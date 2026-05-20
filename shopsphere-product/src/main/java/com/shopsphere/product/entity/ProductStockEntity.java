package com.shopsphere.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * t_product_stock 实体（库存独立表，便于 TCC 事务、热点行行锁，契约 §4.3）。
 *
 * <p><b>无 status 列</b>：不参与 MP 全局逻辑删除（MP 3.5.x 对缺失列自动跳过拼接）。
 * <p><b>主键 = productId</b>：与 {@code t_product.id} 同源（非雪花重新分配）。
 * <p>{@code version} 字段做乐观锁备用，本期不开启 {@code @Version} 业务逻辑（T2.4 TCC 落地时启用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_product_stock")
public class ProductStockEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 与 t_product.id 同源；用 INPUT 由调用方传入，不再生成 */
    @TableId(value = "product_id", type = IdType.INPUT)
    private Long productId;

    private Integer stock;

    @TableField("locked_stock")
    private Integer lockedStock;

    /** 乐观锁占位（T2.4 启用 @Version） */
    @Version
    private Integer version;
}
