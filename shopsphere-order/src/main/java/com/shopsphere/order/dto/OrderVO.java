package com.shopsphere.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 订单视图（api-contracts §6.3）。{@code /pay} 等接口返回订单当前状态。
 * <p>{@code status} 为状态名字符串（见 {@code OrderStatus}）；时间字段 UTC OffsetDateTime。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderVO {

    private Long orderId;
    private String orderNo;
    private String status;
    private BigDecimal totalAmount;
    private OffsetDateTime payExpireAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime createdAt;
}
