package com.shopsphere.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 订单详情视图（{@code GET /api/order/{id}}，api-contracts §6.3）。
 * <p>含订单主体快照与明细 {@code items}；{@code status} 为状态名字符串（见 {@code OrderStatus}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDetailVO {

    private Long orderId;
    private String orderNo;
    private String status;
    private BigDecimal totalAmount;
    private Long addressId;
    private String remark;
    private OffsetDateTime payExpireAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<OrderItemVO> items;
}
