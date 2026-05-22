package com.shopsphere.order.messaging;

import com.shopsphere.order.config.OrderProperties;
import com.shopsphere.order.constant.OrderConstants;
import com.shopsphere.order.entity.LocalMessageEntity;
import com.shopsphere.order.mapper.LocalMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地消息表 outbox 中继投递集成测试（T3.4 生产侧）。
 *
 * <p>{@code @SpringBootTest} 启动 Order 上下文 + MySQL / Redis / RabbitMQ Testcontainers
 * （禁用 Nacos / Seata）。Flyway 自动建 {@code t_local_message}；本测试直插 PENDING 行，
 * 调 {@link LocalMessagePublisher#relay()} 验证 PENDING(0) → SENT(1) → CONFIRMED(2) 状态机，
 * 以及 confirm nack 退避重试 / 重试耗尽转 FAILED(3)。
 *
 * <p>各 case 用不同 id / bizKey 隔离，互不干扰。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "seata.enabled=false")
class LocalMessagePublisherIT {

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

    private static final String TEST_QUEUE = "q.test.order.created";

    /** 唯一消息 id 发号，保证各 case / 各次插入互不干扰。 */
    private static final AtomicLong SEQ = new AtomicLong(1_000_000L);

    @Autowired
    private LocalMessagePublisher localMessagePublisher;
    @Autowired
    private LocalMessageMapper localMessageMapper;
    @Autowired
    private OrderProperties orderProperties;
    @Autowired
    private AmqpAdmin amqpAdmin;

    /** 测试侧自建一个绑定到 shopsphere.order/order.created 的队列，使中继消息可路由（避免 return）。 */
    private void ensureTestQueue() {
        TopicExchange exchange = new TopicExchange(OrderConstants.EXCHANGE_ORDER, true, false);
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        amqpAdmin.declareExchange(exchange);
        amqpAdmin.declareQueue(queue);
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(OrderConstants.RK_ORDER_CREATED);
        amqpAdmin.declareBinding(binding);
    }

    private long insertPending(String bizKey, OffsetDateTime nextRetryAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        LocalMessageEntity msg = LocalMessageEntity.builder()
                .id(SEQ.getAndIncrement())
                .bizKey(bizKey)
                .exchange(OrderConstants.EXCHANGE_ORDER)
                .routingKey(OrderConstants.RK_ORDER_CREATED)
                .payload("{\"orderId\":1,\"orderNo\":\"" + bizKey + "\",\"userId\":7,\"totalAmount\":99.00}")
                .status(OrderConstants.LOCAL_MSG_PENDING)
                .retryCount(0)
                .nextRetryAt(nextRetryAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        localMessageMapper.insert(msg);
        return msg.getId();
    }

    private int statusOf(long id) {
        LocalMessageEntity row = localMessageMapper.selectById(id);
        assertNotNull(row, "消息行应存在 id=" + id);
        return row.getStatus();
    }

    // ---- case 1：PENDING → relay → SENT → (broker confirm) → CONFIRMED ----

    @Test
    void case1_relay_pendingBecomesSentThenConfirmed() {
        ensureTestQueue();
        long id = insertPending("SO-IT-001", null);
        assertEquals(OrderConstants.LOCAL_MSG_PENDING, statusOf(id), "初始 PENDING");

        localMessagePublisher.relay();

        // relay() 同步把行置 SENT(1)
        assertEquals(OrderConstants.LOCAL_MSG_SENT, statusOf(id), "relay 后即 SENT");

        // broker publisher-confirm 异步到达后 markConfirmed → CONFIRMED(2)
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(OrderConstants.LOCAL_MSG_CONFIRMED, statusOf(id),
                        "broker confirm 后应转 CONFIRMED"));
    }

    // ---- case 2：已 CONFIRMED 的行不会被 relay 重新投递 ----

    @Test
    void case2_alreadyConfirmedRow_notReSent() {
        long id = insertPending("SO-IT-002", null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // 直接置为 CONFIRMED：模拟此前已成功投递确认
        localMessageMapper.markSent(id, now);
        localMessageMapper.markConfirmed(id, now);
        assertEquals(OrderConstants.LOCAL_MSG_CONFIRMED, statusOf(id));

        localMessagePublisher.relay();

        assertEquals(OrderConstants.LOCAL_MSG_CONFIRMED, statusOf(id),
                "selectPendingBatch 只取 status=0，CONFIRMED 行不应被动");
    }

    // ---- case 3：next_retry_at 在未来的行未到期，不被 relay 投递 ----

    @Test
    void case3_notDueRow_notSent() {
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        long id = insertPending("SO-IT-003", future);
        assertEquals(OrderConstants.LOCAL_MSG_PENDING, statusOf(id));

        localMessagePublisher.relay();

        assertEquals(OrderConstants.LOCAL_MSG_PENDING, statusOf(id),
                "next_retry_at 在未来，本轮不投递，留 PENDING");
    }

    // ---- case 4：confirm nack → markRetry 回 PENDING、retry_count+1、next_retry_at 在未来 ----

    @Test
    void case4_onConfirmNack_marksRetryBackToPending() {
        long id = insertPending("SO-IT-004", null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // 把行推进到 SENT —— nack 处理基于已 SENT 的行
        localMessageMapper.markSent(id, now);
        assertEquals(OrderConstants.LOCAL_MSG_SENT, statusOf(id));

        // 直接模拟 broker nack（真实 nack 难以稳定触发）
        localMessagePublisher.onConfirm(new CorrelationData(String.valueOf(id)), false, "simulated nack");

        LocalMessageEntity row = localMessageMapper.selectById(id);
        assertEquals(OrderConstants.LOCAL_MSG_PENDING, row.getStatus(), "nack 后退避重试，回 PENDING");
        assertEquals(1, row.getRetryCount(), "retry_count 累加到 1");
        assertNotNull(row.getNextRetryAt(), "应设置 next_retry_at");
        assertTrue(row.getNextRetryAt().isAfter(now), "next_retry_at 须在未来（指数退避）");
    }

    // ---- case 5：连续 nack 至达到 max-retry → 转 FAILED(3) ----

    @Test
    void case5_onConfirmNack_exhaustsRetry_marksFailed() {
        long id = insertPending("SO-IT-005", null);
        localMessageMapper.markSent(id, OffsetDateTime.now(ZoneOffset.UTC));

        int maxRetry = orderProperties.getOutbox().getMaxRetry();
        // 触发 maxRetry 次 nack：第 maxRetry 次时 retryCount 达上限 → FAILED
        for (int i = 0; i < maxRetry; i++) {
            localMessagePublisher.onConfirm(new CorrelationData(String.valueOf(id)), false, "nack " + i);
        }

        LocalMessageEntity row = localMessageMapper.selectById(id);
        assertEquals(OrderConstants.LOCAL_MSG_FAILED, row.getStatus(),
                "重试耗尽（>= max-retry）应转 FAILED");
        assertEquals(maxRetry, row.getRetryCount(), "retry_count 等于 max-retry");
    }

    // ---- case 6：onConfirm 收到 ack → SENT(1) → CONFIRMED(2) ----

    @Test
    void case6_onConfirmAck_marksConfirmed() {
        long id = insertPending("SO-IT-006", null);
        localMessageMapper.markSent(id, OffsetDateTime.now(ZoneOffset.UTC));
        assertEquals(OrderConstants.LOCAL_MSG_SENT, statusOf(id));

        localMessagePublisher.onConfirm(new CorrelationData(String.valueOf(id)), true, null);

        assertEquals(OrderConstants.LOCAL_MSG_CONFIRMED, statusOf(id), "ack → CONFIRMED");
    }

    // ---- case 7：onConfirm 收到非法 CorrelationData → 安全忽略，不抛异常 ----

    @Test
    void case7_onConfirmWithNullOrBadCorrelation_isIgnored() {
        // null correlation
        localMessagePublisher.onConfirm(null, true, null);
        // 非法 id（非数字）
        localMessagePublisher.onConfirm(new CorrelationData("not-a-number"), true, null);
        // 不存在的 id 的 nack
        localMessagePublisher.onConfirm(new CorrelationData("999999999"), false, "ghost");
        // 上述调用均不应抛异常即视为通过
    }

    // ---- case 8：边界 —— relay 在无 PENDING 行时空跑不报错 ----

    @Test
    void case8_relayWithNoPending_isNoop() {
        // 多次 relay 不应抛异常（空批 / 锁正常释放）
        localMessagePublisher.relay();
        localMessagePublisher.relay();
    }
}
