package com.shopsphere.user.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopsphere.api.order.event.OrderCreatedEvent;
import com.shopsphere.api.order.event.OrderItemPayload;
import com.shopsphere.user.entity.PointsLogEntity;
import com.shopsphere.user.entity.UserPointsEntity;
import com.shopsphere.user.mapper.PointsLogMapper;
import com.shopsphere.user.mapper.UserPointsMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 积分消费者集成测试（T3.4 消费侧）。
 *
 * <p>{@code @SpringBootTest} 启动 User 上下文 + MySQL / Redis / RabbitMQ Testcontainers
 * （禁用 Nacos / Seata / Sentinel）。Flyway 自动建 {@code t_user_points} / {@code t_points_log}；
 * 测试向 {@code shopsphere.order}/{@code order.created} 投递 {@link OrderCreatedEvent}，
 * 验证 {@link PointsConsumer} 正常发放积分与重复投递幂等（{@code t_points_log.uk_order}）。
 *
 * <p>{@link TestJwtKeys} 提供离线 {@code jwt.private-key} 占位。
 * 各 case 用不同 orderId / userId 隔离，互不干扰。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "seata.enabled=false")
class PointsConsumerIT {

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

    /** 唯一 orderId / userId 发号，保证各 case / 各次发布互不干扰。 */
    private static final AtomicLong ORDER_SEQ = new AtomicLong(5_100_000L);
    private static final AtomicLong USER_SEQ = new AtomicLong(7_100_000L);

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private UserPointsMapper userPointsMapper;
    @Autowired
    private PointsLogMapper pointsLogMapper;

    private OrderCreatedEvent event(long orderId, long userId, BigDecimal totalAmount) {
        return OrderCreatedEvent.builder()
                .orderId(orderId)
                .orderNo("SO" + orderId)
                .userId(userId)
                .totalAmount(totalAmount)
                .ts(OffsetDateTime.now(ZoneOffset.UTC))
                .items(List.of(new OrderItemPayload(2001L, "测试商品", new BigDecimal("99.50"), 2)))
                .build();
    }

    /** 经 Jackson2JsonMessageConverter（mqMessageConverter）序列化为 JSON 投递。 */
    private void publish(OrderCreatedEvent event) {
        rabbitTemplate.convertAndSend(
                OrderMqConstants.EXCHANGE_ORDER, OrderMqConstants.RK_ORDER_CREATED, event);
    }

    private long pointsLogCount(long orderId) {
        return pointsLogMapper.selectCount(new LambdaQueryWrapper<PointsLogEntity>()
                .eq(PointsLogEntity::getOrderId, orderId));
    }

    private Long userPoints(long userId) {
        UserPointsEntity row = userPointsMapper.selectById(userId);
        return row == null ? null : row.getPoints();
    }

    // ---- case 1：正常 —— 投递事件 → 积分按 floor(totalAmount) 累加，1 条流水 ----

    @Test
    void case1_normal_awardsPointsAndWritesSingleLog() {
        long orderId = ORDER_SEQ.getAndIncrement();
        long userId = USER_SEQ.getAndIncrement();
        // floor(199.99) = 199
        publish(event(orderId, userId, new BigDecimal("199.99")));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertEquals(1L, pointsLogCount(orderId),
                        "应恰有 1 条积分流水"));

        assertEquals(199L, userPoints(userId), "积分 = floor(199.99)");
        PointsLogEntity log = pointsLogMapper.selectOne(new LambdaQueryWrapper<PointsLogEntity>()
                .eq(PointsLogEntity::getOrderId, orderId));
        assertNotNull(log);
        assertEquals(199, log.getPoints(), "流水记录的发放积分 = 199");
        assertEquals(userId, log.getUserId());
    }

    // ---- case 2：幂等 —— 同一事件投递两次，只发放一次 ----

    @Test
    void case2_duplicateDelivery_isIdempotent() {
        long orderId = ORDER_SEQ.getAndIncrement();
        long userId = USER_SEQ.getAndIncrement();
        OrderCreatedEvent ev = event(orderId, userId, new BigDecimal("50.00"));

        publish(ev);
        // 等首次发放落库
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertEquals(1L, pointsLogCount(orderId)));
        assertEquals(50L, userPoints(userId), "首次发放 50");

        // 重复投递同一事件 —— uk_order 唯一约束 → DuplicateKeyException → 幂等 ack
        publish(ev);

        // 给消费者足够时间处理重复消息后，断言状态未变
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> {
                    assertEquals(1L, pointsLogCount(orderId), "重复投递后仍只 1 条流水");
                    assertEquals(50L, userPoints(userId), "积分不被重复累加");
                });
    }

    // ---- case 3：边界 —— totalAmount 含小数，按 floor 取整 ----

    @Test
    void case3_fractionalAmount_flooredToInteger() {
        long orderId = ORDER_SEQ.getAndIncrement();
        long userId = USER_SEQ.getAndIncrement();
        // floor(0.99) = 0
        publish(event(orderId, userId, new BigDecimal("0.99")));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertEquals(1L, pointsLogCount(orderId)));

        assertEquals(0L, userPoints(userId), "floor(0.99) = 0 积分");
        PointsLogEntity log = pointsLogMapper.selectOne(new LambdaQueryWrapper<PointsLogEntity>()
                .eq(PointsLogEntity::getOrderId, orderId));
        assertEquals(0, log.getPoints());
    }

    // ---- case 4：同一用户多笔订单 —— 积分累加，各订单各一条流水 ----

    @Test
    void case4_multipleOrdersSameUser_pointsAccumulate() {
        long userId = USER_SEQ.getAndIncrement();
        long order1 = ORDER_SEQ.getAndIncrement();
        long order2 = ORDER_SEQ.getAndIncrement();

        publish(event(order1, userId, new BigDecimal("30.00")));
        publish(event(order2, userId, new BigDecimal("70.00")));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    assertEquals(1L, pointsLogCount(order1));
                    assertEquals(1L, pointsLogCount(order2));
                });

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertEquals(100L, userPoints(userId),
                        "两笔订单积分累加 30 + 70"));
    }
}
