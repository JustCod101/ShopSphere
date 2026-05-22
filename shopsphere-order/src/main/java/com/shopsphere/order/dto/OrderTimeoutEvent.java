package com.shopsphere.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * {@code order.payment.timeout} 消息体（api-contracts §8）。
 *
 * <p>下单时写入 {@code t_local_message}，由 outbox 投递到 {@code shopsphere.order} topic；
 * 经 {@code q.order.timeout.wait}（TTL 30min）死信延迟后进入 {@code q.order.timeout}，
 * 由 {@code OrderTimeoutConsumer} 消费触发未支付超时自动取消（S4）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimeoutEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;
    /** 支付截止时间（UTC） */
    private OffsetDateTime payExpireAt;
}
