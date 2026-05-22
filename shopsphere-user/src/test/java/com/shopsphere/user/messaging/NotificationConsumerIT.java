package com.shopsphere.user.messaging;

import com.shopsphere.api.order.event.OrderCreatedEvent;
import com.shopsphere.api.order.event.OrderItemPayload;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知消费者集成测试（T3.4 消费侧）。
 *
 * <p>{@code @SpringBootTest} 启动 User 上下文 + MySQL / Redis / RabbitMQ Testcontainers
 * （禁用 Nacos / Seata / Sentinel）。{@link NotificationConsumer} 本期仅打日志，
 * 可观测副作用是 Redis 幂等键 {@code notify:sent:{orderNo}} —— 测试以该键为断言锚点：
 * 首投后键存在；重复投递键仍单一、无异常。
 *
 * <p>{@link TestJwtKeys} 提供离线 {@code jwt.private-key} 占位。
 * 各 case 用不同 orderNo 隔离，互不干扰。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "seata.enabled=false")
class NotificationConsumerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36"))
            .withDatabaseName("shopsphere_user");

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
        // 生产由 Nacos（Jasypt 加密）下发；离线测试用进程内生成的 RSA 私钥占位。
        r.add("jwt.private-key", () -> TestJwtKeys.PRIVATE_KEY_PEM);
    }

    private static final AtomicLong ORDER_SEQ = new AtomicLong(5_200_000L);

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private StringRedisTemplate redis;

    private OrderCreatedEvent event(long orderId, String orderNo) {
        return OrderCreatedEvent.builder()
                .orderId(orderId)
                .orderNo(orderNo)
                .userId(7_200_000L + orderId)
                .totalAmount(new BigDecimal("88.00"))
                .ts(OffsetDateTime.now(ZoneOffset.UTC))
                .items(List.of(new OrderItemPayload(2002L, "测试商品", new BigDecimal("44.00"), 2)))
                .build();
    }

    private void publish(OrderCreatedEvent event) {
        rabbitTemplate.convertAndSend(
                OrderMqConstants.EXCHANGE_ORDER, OrderMqConstants.RK_ORDER_CREATED, event);
    }

    private boolean idempotentKeyExists(String orderNo) {
        return Boolean.TRUE.equals(redis.hasKey("notify:sent:" + orderNo));
    }

    // ---- case 1：正常 —— 投递事件 → Redis 幂等键被建立 ----

    @Test
    void case1_normal_setsIdempotencyKey() {
        long orderId = ORDER_SEQ.getAndIncrement();
        String orderNo = "SO-NT-" + orderId;

        publish(event(orderId, orderNo));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertTrue(idempotentKeyExists(orderNo),
                        "首次通知后应建立 notify:sent:{orderNo} 幂等键"));

        assertEquals("1", redis.opsForValue().get("notify:sent:" + orderNo));
    }

    // ---- case 2：幂等 —— 重复投递不报错，幂等键仍单一 ----

    @Test
    void case2_duplicateDelivery_idempotentNoError() {
        long orderId = ORDER_SEQ.getAndIncrement();
        String orderNo = "SO-NT-" + orderId;
        OrderCreatedEvent ev = event(orderId, orderNo);

        publish(ev);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertTrue(idempotentKeyExists(orderNo)));

        // 重复投递：消费者命中已存在的键 → 幂等跳过，无异常
        publish(ev);

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> {
                    assertTrue(idempotentKeyExists(orderNo), "重复投递后幂等键仍存在");
                    assertEquals("1", redis.opsForValue().get("notify:sent:" + orderNo),
                            "键值保持 1，未被覆盖/重置");
                });
    }

    // ---- case 3：不同订单 —— 各自独立的幂等键 ----

    @Test
    void case3_distinctOrders_haveDistinctKeys() {
        long order1 = ORDER_SEQ.getAndIncrement();
        long order2 = ORDER_SEQ.getAndIncrement();
        String no1 = "SO-NT-" + order1;
        String no2 = "SO-NT-" + order2;

        publish(event(order1, no1));
        publish(event(order2, no2));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    assertTrue(idempotentKeyExists(no1), "订单1 幂等键存在");
                    assertTrue(idempotentKeyExists(no2), "订单2 幂等键存在");
                });
    }
}
