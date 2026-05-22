package com.shopsphere.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 下单响应（api-contracts §6.3）：{ orderId, status, totalAmount, payExpireAt }。
 * <p>{@code status} 为状态名字符串（见 {@code OrderStatus}）；{@code payExpireAt} 为 UTC OffsetDateTime。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateVO {

    private Long orderId;
    private String status;
    private BigDecimal totalAmount;
    private OffsetDateTime payExpireAt;
}
