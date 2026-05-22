package com.shopsphere.user.messaging;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * 订单事件消费者公共支撑（T3.4）—— 手动 ack/nack 与有界重试决策。
 *
 * <p>{@code q.points} / {@code q.notify} 两个消费者共用：消费失败时基于 Redis 计数器做
 * 「重试 N 次后转 DLX」的有界决策，避免无限 requeue 失控。
 * {@code basicAck}/{@code basicNack} 的 {@link IOException} 在此内部吞掉并记日志
 * —— 信道异常时消息不会被 ack，断连后由 broker 重投。
 */
@Slf4j
@Component
public class MqConsumerSupport {

    /** 单条消息最多处理次数，达到即转 DLX。 */
    public static final int MAX_ATTEMPTS = 3;

    private static final Duration RETRY_KEY_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public MqConsumerSupport(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 确认消费成功。 */
    public void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("basicAck 失败 deliveryTag={} : {}", deliveryTag, e.getMessage());
        }
    }

    /** requeue 重投（用于未达上限的可重试失败）。 */
    public void requeue(Channel channel, long deliveryTag) {
        nack(channel, deliveryTag, true);
    }

    /** 拒绝且不 requeue —— 经队列的 {@code x-dead-letter-exchange} 转入 {@code q.order.dlq}。 */
    public void deadLetter(Channel channel, long deliveryTag) {
        nack(channel, deliveryTag, false);
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException e) {
            log.error("basicNack(requeue={}) 失败 deliveryTag={} : {}", requeue, deliveryTag, e.getMessage());
        }
    }

    /**
     * 可重试失败的处理决策：累加 Redis 计数，未达 {@link #MAX_ATTEMPTS} 则 requeue 重试，
     * 否则转 DLX。Redis 不可用（计数返回 null）时直接转 DLX —— 宁可入死信，不让 retry 失控。
     *
     * @param scope   计数命名空间（如 {@code points} / {@code notify}）
     * @param bizId   业务幂等 ID（如 orderId）
     * @return {@code true} 已转 DLX（调用方应记 ERROR 告警）；{@code false} 已 requeue 重试
     */
    public boolean retryOrDeadLetter(Channel channel, long deliveryTag, String scope, long bizId) {
        String key = "mq:retry:" + scope + ":" + bizId;
        Long attempts = redis.opsForValue().increment(key);
        if (attempts == null) {
            log.error("重试计数 Redis 不可用 key={}，直接转 DLX", key);
            deadLetter(channel, deliveryTag);
            return true;
        }
        if (attempts == 1L) {
            redis.expire(key, RETRY_KEY_TTL);
        }
        if (attempts >= MAX_ATTEMPTS) {
            deadLetter(channel, deliveryTag);
            return true;
        }
        requeue(channel, deliveryTag);
        return false;
    }
}
