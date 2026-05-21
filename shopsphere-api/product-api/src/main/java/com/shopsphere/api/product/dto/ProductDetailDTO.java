package com.shopsphere.api.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 商品详情跨服务传输对象（Feign {@code ProductFeignClient.getDetail} 返回）。
 *
 * <p>与服务内部 {@code ProductDetailVO} 同形同字段,按 CLAUDE.md「DTO=传输 / VO=返回前端」
 * 拆分(对齐 {@code UserDTO} / {@code UserVO} 模式)。
 * <p>{@code stock} = 可售量(契约 §4.3),时间字段 UTC OffsetDateTime(契约 §1.1)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetailDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private Long categoryId;
    private BigDecimal price;
    private String mainImage;
    private String description;
    private Integer stock;
    private Integer status;
    private OffsetDateTime createdAt;
}
