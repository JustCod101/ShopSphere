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
 * t_user_points 实体（用户积分余额，T3.4）。
 *
 * <p>主键为 {@code user_id}（业务键，非雪花），故 {@code IdType.INPUT}。积分累加由
 * {@code UserPointsMapper#addPoints} 的 {@code INSERT ... ON DUPLICATE KEY UPDATE} upsert 完成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user_points")
public class UserPointsEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    private Long points;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;
}
