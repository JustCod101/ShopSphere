package com.shopsphere.order.constant;

/**
 * 订单服务常量。MQ exchange / routing key 对齐 api-contracts §8。
 */
public final class OrderConstants {

    private OrderConstants() {
    }

    /** 订单号前缀（orderNo = SO + 雪花 orderId） */
    public static final String ORDER_NO_PREFIX = "SO";

    /** 下单幂等头（S5，api-contracts §6.3） */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 订单 MQ topic exchange（§8） */
    public static final String EXCHANGE_ORDER = "shopsphere.order";
    /** 订单死信交换机（fanout，§8 死信统一汇聚） */
    public static final String EXCHANGE_ORDER_DLX = "shopsphere.order.dlx";
    /** routing key：下单成功事件（§8，🔴 强可靠） */
    public static final String RK_ORDER_CREATED = "order.created";
    /** routing key：未支付超时事件（§8，🔴 强可靠·延迟） */
    public static final String RK_ORDER_TIMEOUT = "order.payment.timeout";

    /** 订单死信队列（所有订单队列的死信汇聚至此） */
    public static final String QUEUE_ORDER_DLQ = "q.order.dlq";
    /** Order 自消费的未支付超时队列（由等待队列 TTL 死信投入，T3.5 OrderTimeoutConsumer 消费） */
    public static final String QUEUE_ORDER_TIMEOUT = "q.order.timeout";
    /** 未支付超时等待队列：带 TTL、无消费者，到期死信进 {@link #QUEUE_ORDER_TIMEOUT} 实现延迟 */
    public static final String QUEUE_ORDER_TIMEOUT_WAIT = "q.order.timeout.wait";

    /** t_local_message.status：待投递 */
    public static final int LOCAL_MSG_PENDING = 0;
    /** t_local_message.status：已投递（待 broker confirm） */
    public static final int LOCAL_MSG_SENT = 1;
    /** t_local_message.status：broker 已确认 */
    public static final int LOCAL_MSG_CONFIRMED = 2;
    /** t_local_message.status：重试耗尽，投递失败 */
    public static final int LOCAL_MSG_FAILED = 3;
}
