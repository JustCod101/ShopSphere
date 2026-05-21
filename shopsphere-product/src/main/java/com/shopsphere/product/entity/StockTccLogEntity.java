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
import java.time.OffsetDateTime;

/**
 * t_stock_tcc_log 实体 —— 库存 TCC 幂等/空回滚日志（契约 §4.3）。
 *
 * <p>幂等键 {@code (orderId, productId, phase)}(DB 唯一索引 uk_order_product_phase)。
 * <p>无 {@code status} 列 → 不参与 MP 全局逻辑删除;{@code createdAt} 由
 * {@code MybatisPlusMetaObjectHandler} 自动填充(UTC)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_stock_tcc_log")
public class StockTccLogEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    @TableField("product_id")
    private Long productId;

    /** TRY / CONFIRM / CANCEL */
    private String phase;

    /** 1=成功 0=空回滚标记 */
    private Integer state;

    /** 该 (order,product) 扣减/回补数量 */
    private Integer quantity;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
