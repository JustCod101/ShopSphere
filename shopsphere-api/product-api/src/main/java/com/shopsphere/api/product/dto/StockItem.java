package com.shopsphere.api.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单商品库存数量项（api-contracts §4.3）。{@link StockTccDTO} 的元素。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long productId;
    private Integer quantity;
}
