package com.shopsphere.user.messaging;

import com.rabbitmq.client.Channel;
import com.shopsphere.api.order.event.OrderCreatedEvent;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.user.service.PointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 积分消费者（T3.4，Part B）—— 监听 {@code q.points}，消费 {@code order.created} 发放积分。
 *
 * <p>手动 ack。幂等由 {@link PointsService#award} 的 {@code t_points_log.order_id} 唯一约束保证：
 * 重复投递 → {@code DuplicateKeyException} → 视作已处理，幂等 ack。
 * 可重试失败经 {@link MqConsumerSupport} 做有界重试，3 次后转 DLX；不可恢复异常直接转 DLX。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsConsumer {

    private final PointsService pointsService;
    private final MqConsumerSupport support;

    @RabbitListener(queues = OrderMqConstants.QUEUE_POINTS)
    public void onOrderCreated(OrderCreatedEvent event, Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        if (event == null || event.getOrderId() == null) {
            log.error("q.points 收到非法事件（orderId 缺失），转 DLX");
            support.deadLetter(channel, deliveryTag);
            return;
        }
        long orderId = event.getOrderId();
        try {
            pointsService.award(event);
            support.ack(channel, deliveryTag);
        } catch (DuplicateKeyException dup) {
            // 重复投递：积分已发放过，幂等跳过
            log.info("积分事件重复投递，幂等 ack orderId={}", orderId);
            support.ack(channel, deliveryTag);
        } catch (BusinessException | DataAccessException e) {
            // 可重试失败（DB 抖动等）
            if (support.retryOrDeadLetter(channel, deliveryTag, "points", orderId)) {
                log.error("积分发放重试耗尽，转 DLX orderId={}", orderId, e);
            } else {
                log.warn("积分发放失败，将重试 orderId={} : {}", orderId, e.getMessage());
            }
        } catch (Exception e) {
            // 不可恢复（毒消息）：不重试，直接 DLX
            log.error("积分发放遇不可恢复异常，转 DLX orderId={}", orderId, e);
            support.deadLetter(channel, deliveryTag);
        }
    }
}
