package com.shopsphere.api.order.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * {@link OrderCreatedEvent} 内的订单明细项。
 *
 * <p>order 模块的 {@code OrderItemEntity} 不可跨服务共享，故事件侧另立扁平 DTO；
 * 字段为下单时快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long productId;
    private String productName;
    private BigDecimal price;
    private Integer quantity;
}
