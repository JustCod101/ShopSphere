package com.shopsphere.product.dto;

import com.shopsphere.product.entity.ProductEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 列表项 VO（契约 §6.2 /api/product/list 元素）。
 * <p>不含 description / stock / createdAt（列表场景轻量，需要时打详情）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private Long categoryId;
    private BigDecimal price;
    private String mainImage;
    private Integer status;

    public static ProductVO from(ProductEntity e) {
        return ProductVO.builder()
                .id(e.getId())
                .name(e.getName())
                .categoryId(e.getCategoryId())
                .price(e.getPrice())
                .mainImage(e.getMainImage())
                .status(e.getStatus())
                .build();
    }
}
