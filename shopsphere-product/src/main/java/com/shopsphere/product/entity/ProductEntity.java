package com.shopsphere.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * t_product 实体。
 * <ul>
 *   <li>价格 {@link BigDecimal}（DECIMAL(10,2)），禁止 double（CLAUDE.md / 契约 §6.2）。</li>
 *   <li>时间字段 {@link OffsetDateTime}（UTC，契约 §1.1），由
 *       {@code MybatisPlusMetaObjectHandler} 在 INSERT/UPDATE 自动填充。</li>
 *   <li>{@code status} 由 MP 全局逻辑删除接管（1=上架 / 0=删除）。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_product")
public class ProductEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    @TableField("category_id")
    private Long categoryId;

    private BigDecimal price;

    @TableField("main_image")
    private String mainImage;

    private String description;

    /** 1=上架 0=逻辑删除 */
    private Integer status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
