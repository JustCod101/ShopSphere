package com.shopsphere.user.messaging;

/**
 * 订单事件 MQ 拓扑常量（消费侧，对齐 api-contracts §8 / docs/mq-topology.md）。
 *
 * <p>这些名字是跨服务契约：Order 服务声明 {@code shopsphere.order} exchange 与死信设施，
 * User 服务按「消费方拥有队列」声明 {@code q.points} / {@code q.notify} 并绑定。
 */
public final class OrderMqConstants {

    private OrderMqConstants() {
    }

    /** 订单事件 topic exchange */
    public static final String EXCHANGE_ORDER = "shopsphere.order";
    /** 订单死信交换机（fanout） */
    public static final String EXCHANGE_ORDER_DLX = "shopsphere.order.dlx";
    /** routing key：下单成功事件 */
    public static final String RK_ORDER_CREATED = "order.created";

    /** 积分消费队列 */
    public static final String QUEUE_POINTS = "q.points";
    /** 通知消费队列 */
    public static final String QUEUE_NOTIFY = "q.notify";
    /** 订单死信队列 */
    public static final String QUEUE_ORDER_DLQ = "q.order.dlq";
}
