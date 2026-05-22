package com.shopsphere.order.messaging;

import com.shopsphere.order.config.OrderProperties;
import com.shopsphere.order.entity.LocalMessageEntity;
import com.shopsphere.order.mapper.LocalMessageMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地消息表 outbox 中继投递器（T3.4，C3 / 🔴 强可靠，api-contracts §8）。
 *
 * <p>T3.2 把 {@code order.created} / {@code order.payment.timeout} 与建单同本地事务写入
 * {@code t_local_message}（PENDING）；本类负责把 PENDING 行可靠投递到 RabbitMQ：
 *
 * <ol>
 *   <li>{@link #relay()} 每 5 秒扫描 PENDING 且到期的行，{@code FOR UPDATE SKIP LOCKED}
 *       取批、逐条 {@code send}、置 SENT —— 多实例靠行锁互斥，外加 Redisson 锁降低争用。</li>
 *   <li>{@link #onConfirm} 处理 broker publisher-confirm：ack → CONFIRMED；
 *       nack → 指数退避重试，超 {@code maxRetry} 转 FAILED 并 ERROR 告警。</li>
 * </ol>
 *
 * <p>投递的是 {@code t_local_message.payload} 中已序列化好的 JSON 字节，故走 {@code send()}
 * 而非 {@code convertAndSend()}，不二次序列化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessagePublisher {

    private static final String OUTBOX_LOCK_KEY = "lock:order:outbox";

    private final RabbitTemplate rabbitTemplate;
    private final LocalMessageMapper localMessageMapper;
    private final RedissonClient redissonClient;
    private final OrderProperties orderProperties;

    @PostConstruct
    void wireCallbacks() {
        rabbitTemplate.setConfirmCallback(this::onConfirm);
        rabbitTemplate.setReturnsCallback(ret ->
                log.warn("outbox 消息不可路由 exchange={} rk={} replyText={}",
                        ret.getExchange(), ret.getRoutingKey(), ret.getReplyText()));
    }

    /**
     * 扫描并投递 PENDING 消息。{@code @Transactional}：本方法即批次的本地事务，
     * {@code FOR UPDATE} 行锁持有至提交，confirm 回调的 markConfirmed 会阻塞到提交后执行，
     * 杜绝「回调先于 markSent」竞态。
     */
    @Scheduled(fixedDelayString = "${order.outbox.scan-interval-ms:5000}")
    @Transactional
    public void relay() {
        OrderProperties.Outbox cfg = orderProperties.getOutbox();
        RLock lock = redissonClient.getLock(OUTBOX_LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(cfg.getLockWaitMs(), cfg.getLockLeaseMs(), TimeUnit.MILLISECONDS);
            if (!locked) {
                // 另一实例正在投递，本轮跳过
                return;
            }
            dispatchBatch(cfg.getBatchSize());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("outbox 中继获取分布式锁被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void dispatchBatch(int batchSize) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<LocalMessageEntity> batch = localMessageMapper.selectPendingBatch(now, batchSize);
        if (batch.isEmpty()) {
            return;
        }
        int sent = 0;
        for (LocalMessageEntity msg : batch) {
            try {
                rabbitTemplate.send(msg.getExchange(), msg.getRoutingKey(),
                        toMessage(msg.getPayload()), new CorrelationData(String.valueOf(msg.getId())));
                localMessageMapper.markSent(msg.getId(), now);
                sent++;
            } catch (AmqpException e) {
                // broker 不可达：剩余行留 PENDING，下一轮重试；停止本批
                log.warn("outbox 投递失败 id={} : {}，本批中止", msg.getId(), e.getMessage());
                break;
            }
        }
        log.debug("outbox 中继投递 {} / {} 条", sent, batch.size());
    }

    private Message toMessage(String payloadJson) {
        return MessageBuilder.withBody(payloadJson.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .build();
    }

    /**
     * publisher-confirm 回调（broker IO 线程，异步）。{@code correlationData.id} = {@code t_local_message.id}。
     * 各次 DB 更新各自独立提交（非事务上下文，{@code SqlSessionTemplate} 自动 commit）。
     */
    void onConfirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null || correlationData.getId() == null) {
            log.warn("outbox confirm 回调缺少 CorrelationData，无法定位消息 ack={}", ack);
            return;
        }
        long id;
        try {
            id = Long.parseLong(correlationData.getId());
        } catch (NumberFormatException e) {
            log.warn("outbox confirm 回调 CorrelationData.id 非法: {}", correlationData.getId());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (ack) {
            localMessageMapper.markConfirmed(id, now);
            return;
        }
        handleNack(id, cause, now);
    }

    private void handleNack(long id, String cause, OffsetDateTime now) {
        LocalMessageEntity msg = localMessageMapper.selectById(id);
        if (msg == null) {
            log.warn("outbox confirm nack 但消息不存在 id={}", id);
            return;
        }
        int retryCount = (msg.getRetryCount() == null ? 0 : msg.getRetryCount()) + 1;
        if (retryCount >= orderProperties.getOutbox().getMaxRetry()) {
            localMessageMapper.markFailed(id, retryCount, now);
            log.error("outbox 消息重试耗尽，转 FAILED id={} bizKey={} retryCount={} cause={}",
                    id, msg.getBizKey(), retryCount, cause);
            return;
        }
        OffsetDateTime nextRetryAt = now.plusSeconds(1L << retryCount);
        localMessageMapper.markRetry(id, retryCount, nextRetryAt, now);
        log.warn("outbox 消息 confirm nack，退避重试 id={} retryCount={} nextRetryAt={} cause={}",
                id, retryCount, nextRetryAt, cause);
    }
}
