package com.shopsphere.api.order.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code order.created} 事件 payload —— {@code shopsphere.order} topic exchange，
 * routingKey {@code order.created}（api-contracts §8，🔴 强可靠）。
 *
 * <p>由 Order 服务在下单成功后经本地消息表（C3）中继投递；消费者（User 积分 / 通知，
 * Reco 行为）以 {@code orderId} 为幂等键去重。
 *
 * <p>放在 {@code order-api} 共享模块：生产端（Order）与消费端（User）引用同一类型，
 * 杜绝 payload 字段漂移。{@code orderNo} 较 §8 表所列字段为额外补充 —— 通知消费者需要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    /** 下单时间（UTC） */
    private OffsetDateTime ts;
    private List<OrderItemPayload> items;
}
