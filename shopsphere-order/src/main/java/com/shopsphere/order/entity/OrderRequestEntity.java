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
import java.time.OffsetDateTime;

/**
 * t_order_request 实体（下单幂等表，S5）。唯一键 {@code (user_id, request_id)}；
 * 同一 {@code (userId, X-Request-Id)} 仅生成一单，TTL 24h 由清理任务回收。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order_request")
public class OrderRequestEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("request_id")
    private String requestId;

    @TableField("order_id")
    private Long orderId;

    @TableField("created_at")
    private OffsetDateTime createdAt;
}
