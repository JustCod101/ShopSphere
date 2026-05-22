package com.shopsphere.user.service;

import com.shopsphere.api.order.event.OrderCreatedEvent;

/**
 * 积分发放服务（T3.4）—— 消费 MQ {@code order.created} 后给下单用户发放积分。
 */
public interface PointsService {

    /**
     * 按订单发放积分。幂等：{@code t_points_log.order_id} 唯一约束保证同一订单只发一次；
     * 重复调用抛 {@code DuplicateKeyException}（由调用方按「已处理」处理）。
     */
    void award(OrderCreatedEvent event);
}
