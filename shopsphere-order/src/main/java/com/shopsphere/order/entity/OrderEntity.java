package com.shopsphere.order.entity;

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
 * t_order 实体（订单主表）。金额 {@link BigDecimal}；时间字段 {@link OffsetDateTime} UTC（契约 §1.1）。
 *
 * <p>{@code id} 在下单时由 {@code IdWorker} 预生成（须先于落库拿到 orderId 传给库存 TCC），
 * 插入前已显式赋值，{@code ASSIGN_ID} 仅在 id 为 null 时兜底。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order")
public class OrderEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private Long userId;

    @TableField("address_id")
    private Long addressId;

    @TableField("total_amount")
    private BigDecimal totalAmount;

    /** 0=CREATED 1=PAID 2=SHIPPED 3=COMPLETED 4=CANCELLED（见 {@code OrderStatus}） */
    private Integer status;

    private String remark;

    @TableField("pay_expire_at")
    private OffsetDateTime payExpireAt;

    @TableField("paid_at")
    private OffsetDateTime paidAt;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;
}
