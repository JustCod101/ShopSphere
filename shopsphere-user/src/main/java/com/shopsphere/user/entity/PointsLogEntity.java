package com.shopsphere.user.entity;

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
 * t_points_log 实体（积分发放流水，T3.4）。
 *
 * <p>{@code orderId} 上有 {@code uk_order} 唯一约束 —— 消费 {@code order.created} 的幂等键：
 * 重复投递时 {@code insert} 抛 {@code DuplicateKeyException}，整事务回滚，积分不重复累加。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_points_log")
public class PointsLogEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    @TableField("user_id")
    private Long userId;

    private Integer points;

    @TableField("created_at")
    private OffsetDateTime createdAt;
}
