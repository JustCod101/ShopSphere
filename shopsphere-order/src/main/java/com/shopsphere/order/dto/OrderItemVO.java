package com.shopsphere.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 订单明细项视图（{@link OrderDetailVO} 内嵌）。字段为下单时商品快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemVO {

    private Long productId;
    private String productName;
    private BigDecimal price;
    private Integer quantity;
}
