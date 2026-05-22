package com.shopsphere.order.messaging;

import com.rabbitmq.client.Channel;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.order.constant.OrderConstants;
import com.shopsphere.order.dto.OrderTimeoutEvent;
import com.shopsphere.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未支付超时自动取消消费者（T3.5，S4，api-contracts §6.3）。
 *
 * <p>消费 {@code q.order.timeout}（消息经 {@code q.order.timeout.wait} 的 TTL 延迟而来）。
 * 手动 ack，委派给 {@link OrderService#cancel}（{@code operatorUserId=null} 即系统超时取消，
 * 内部仅取消仍为 {@code CREATED} 的订单）：
 * <ul>
 *   <li>订单不存在 / 已支付 / 已取消 → {@code cancel} 抛 {@code 4001/4002} → 视作「丢弃」幂等 ack；</li>
 *   <li>取消成功 → ack；</li>
 *   <li>库存回补失败等可重试异常 → {@code nack(requeue=false)} 转 DLX。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer {

    private final OrderService orderService;

    @RabbitListener(queues = OrderConstants.QUEUE_ORDER_TIMEOUT)
    public void onTimeout(OrderTimeoutEvent event, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        if (event == null || event.getOrderId() == null) {
            log.error("q.order.timeout 收到非法事件（orderId 缺失），转 DLX");
            deadLetter(channel, deliveryTag);
            return;
        }
        Long orderId = event.getOrderId();
        try {
            orderService.cancel(orderId, null, "system-timeout");
            log.info("未支付超时自动取消 orderId={}", orderId);
            ack(channel, deliveryTag);
        } catch (BusinessException e) {
            // 4001 订单不存在 / 4002 已支付或状态不可取消 —— 即「丢弃」语义，幂等 ack
            if (e.getErrorCode() == ErrorCode.ORDER_NOT_FOUND
                    || e.getErrorCode() == ErrorCode.ORDER_STATUS_INVALID) {
                log.info("超时取消跳过 orderId={} : {}", orderId, e.getMessage());
                ack(channel, deliveryTag);
            } else {
                // 其他业务异常（库存回补失败等）—— 可重试，转 DLX
                log.error("超时取消失败，转 DLX orderId={}", orderId, e);
                deadLetter(channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("超时取消遇不可预期异常，转 DLX orderId={}", orderId, e);
            deadLetter(channel, deliveryTag);
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("basicAck 失败 deliveryTag={} : {}", deliveryTag, e.getMessage());
        }
    }

    private void deadLetter(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            log.error("basicNack 失败 deliveryTag={} : {}", deliveryTag, e.getMessage());
        }
    }
}
