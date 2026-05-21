package com.shopsphere.api.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 库存 TCC-Try 命令（api-contracts §4.3）。
 * <p>幂等键 = {@code (orderId, productId)};{@code xid} 仅作 TCC 事务关联日志,不作幂等键（S3）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockTccDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String xid;
    private Long orderId;
    private List<StockItem> items;
}
