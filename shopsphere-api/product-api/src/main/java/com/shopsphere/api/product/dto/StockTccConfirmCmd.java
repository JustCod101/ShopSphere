package com.shopsphere.api.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 库存 TCC-Confirm / Cancel 命令（api-contracts §4.3）。
 * <p>幂等键 = (orderId, productId)，按 orderId 维度对该订单全部预留项确认/取消。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockTccConfirmCmd implements Serializable {

    private static final long serialVersionUID = 1L;

    private String xid;
    private Long orderId;
}
