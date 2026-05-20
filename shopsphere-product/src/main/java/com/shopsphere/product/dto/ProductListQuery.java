package com.shopsphere.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * /api/product/list 查询参数（契约 §1.2 + §6.2）。
 * <p>page/size 在 Service 层做截断：page&lt;1 → 1；size&gt;100 → 100；空 → 默认 1/20。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductListQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long categoryId;
    private String keyword;
    private Long page;
    private Long size;
}
