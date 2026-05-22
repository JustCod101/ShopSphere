package com.shopsphere.order.messaging;

import com.shopsphere.api.product.ProductFeignClient;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.common.result.Result;
import com.shopsphere.order.constant.OrderConstants;
import com.shopsphere.order.dto.OrderTimeoutEvent;
import com.shopsphere.order.entity.OrderEntity;
import com.shopsphere.order.enums.OrderStatus;
import com.shopsphere.order.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 未支付超时自动取消消费者集成测试（T3.5 Part C）。
 *
 * <p>{@code @SpringBootTest} 启动 Order 上下文 + MySQL / Redis / RabbitMQ Testcontainers
 * （禁用 Nacos / Seata）。{@code ProductFeignClient} 以 {@code @MockBean} 桩入（无 Product 服务），
 * {@code stockCancel} 恒返回成功。
 *
 * <p>测试直接向 {@code q.order.timeout} 投递 {@link OrderTimeoutEvent}（绕过 30min TTL 等待队列），
 * 验证 {@link OrderTimeoutConsumer} 三态：CREATED 订单 → 取消；PAID 订单 → 丢弃不动；
 * 订单不存在 → 丢弃不报错。各 case 用不同 orderId 隔离。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "seata.enabled=false")
class OrderTimeoutConsumerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36"))
            .withDatabaseName("shopsphere_order");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @MockBean
    private ProductFeignClient productFeignClient;

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private OrderMapper orderMapper;

    @BeforeEach
    void setUp() {
        when(productFeignClient.stockCancel(any(StockTccActionDTO.class))).thenReturn(Result.ok());
    }

    private void insertOrder(long orderId, OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        orderMapper.insert(OrderEntity.builder()
                .id(orderId)
                .orderNo("SO" + orderId)
                .userId(7L)
                .addressId(1001L)
                .totalAmount(new BigDecimal("99.00"))
                .status(status.getCode())
                .payExpireAt(now.minusMinutes(1))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private int statusOf(long orderId) {
        OrderEntity row = orderMapper.selectById(orderId);
        return row == null ? -1 : row.getStatus();
    }

    private void publishTimeout(long orderId) {
        // 直投 q.order.timeout（默认交换机按队列名路由），绕过 q.order.timeout.wait 的 TTL 延迟
        rabbitTemplate.convertAndSend("", OrderConstants.QUEUE_ORDER_TIMEOUT,
                new OrderTimeoutEvent(orderId, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
    }

    // ---- case 1：CREATED 订单超时 → 自动取消 ----

    @Test
    void case1_createdOrder_isCancelledOnTimeout() {
        long orderId = 95_000_001L;
        insertOrder(orderId, OrderStatus.CREATED);

        publishTimeout(orderId);

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertEquals(OrderStatus.CANCELLED.getCode(), statusOf(orderId),
                        "CREATED 订单超时应被自动取消"));
        verify(productFeignClient).stockCancel(any(StockTccActionDTO.class));
    }

    // ---- case 2：PAID 订单超时 → 丢弃，状态不变 ----

    @Test
    void case2_paidOrder_isLeftUntouched() {
        long orderId = 95_000_002L;
        insertOrder(orderId, OrderStatus.PAID);

        publishTimeout(orderId);

        // 消费者处理后（markCancelledIfCreated 命中 0 行 → 4002 → ack 丢弃），状态须持续保持 PAID
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertEquals(OrderStatus.PAID.getCode(), statusOf(orderId),
                        "PAID 订单超时不应被取消"));
    }

    // ---- case 3：订单不存在 → 丢弃，不报错、不调用库存回补 ----

    @Test
    void case3_missingOrder_isDiscardedWithoutError() {
        long ghostId = 95_000_003L;
        assertNull(orderMapper.selectById(ghostId), "前置：订单不存在");

        publishTimeout(ghostId);

        // 消费者应正常 ack 丢弃（cancel 抛 4001）；不创建订单、不触发 stockCancel
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertNull(orderMapper.selectById(ghostId)));
        verify(productFeignClient, never()).stockCancel(any(StockTccActionDTO.class));
    }
}
