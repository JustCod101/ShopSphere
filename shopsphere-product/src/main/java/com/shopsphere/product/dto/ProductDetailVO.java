package com.shopsphere.product.dto;

import com.shopsphere.product.entity.ProductEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 商品详情 VO（契约 §6.2 GET /api/product/{id}）。
 *
 * <p><b>stock 字段语义：可售量</b> = {@code t_product_stock.stock - locked_stock}
 * （契约 §4.3：{@code locked_stock} 为 TCC-Try 预留态，不可售）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private Long categoryId;
    private BigDecimal price;
    private String mainImage;
    private String description;

    /** 可售量 = stock - locked_stock（契约 §4.3） */
    private Integer stock;

    private Integer status;
    private OffsetDateTime createdAt;

    public static ProductDetailVO from(ProductEntity e, int sellableStock) {
        return ProductDetailVO.builder()
                .id(e.getId())
                .name(e.getName())
                .categoryId(e.getCategoryId())
                .price(e.getPrice())
                .mainImage(e.getMainImage())
                .description(e.getDescription())
                .stock(sellableStock)
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
