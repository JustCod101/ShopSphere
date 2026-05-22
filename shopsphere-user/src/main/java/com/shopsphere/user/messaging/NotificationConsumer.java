package com.shopsphere.user.messaging;

import com.rabbitmq.client.Channel;
import com.shopsphere.api.order.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 通知消费者（T3.4，Part C）—— 监听 {@code q.notify}，消费 {@code order.created}。
 *
 * <p>本期仅打 INFO 日志占位（真实通知渠道后续扩展）。手动 ack。
 * 幂等：Redis {@code SET notify:sent:{orderNo} NX EX 86400} —— 已存在即跳过。
 * Redis 不可用时 fail-open（仍打日志 + ack，重复通知无害，本期只打日志）。
 * 不可恢复异常转 DLX。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private static final Duration IDEMPOTENT_TTL = Duration.ofDays(1);

    private final StringRedisTemplate redis;
    private final MqConsumerSupport support;

    @RabbitListener(queues = OrderMqConstants.QUEUE_NOTIFY)
    public void onOrderCreated(OrderCreatedEvent event, Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        if (event == null || event.getOrderNo() == null) {
            log.error("q.notify 收到非法事件（orderNo 缺失），转 DLX");
            support.deadLetter(channel, deliveryTag);
            return;
        }
        String orderNo = event.getOrderNo();
        try {
            Boolean first = redis.opsForValue()
                    .setIfAbsent("notify:sent:" + orderNo, "1", IDEMPOTENT_TTL);
            if (Boolean.FALSE.equals(first)) {
                log.info("通知重复投递，幂等跳过 orderNo={}", orderNo);
                support.ack(channel, deliveryTag);
                return;
            }
            if (first == null) {
                // Redis 不可用：fail-open（本期通知仅日志，重复无害）
                log.warn("通知幂等检查 Redis 不可用，fail-open 继续 orderNo={}", orderNo);
            }
            // TODO: 接入真实通知渠道（短信 / 站内信 / 推送）
            log.info("TODO: 发送通知 userId={} orderNo={}", event.getUserId(), orderNo);
            support.ack(channel, deliveryTag);
        } catch (Exception e) {
            // 通知本期无可重试业务，异常一律视为不可恢复 → DLX
            log.error("通知处理遇异常，转 DLX orderNo={}", orderNo, e);
            support.deadLetter(channel, deliveryTag);
        }
    }
}
