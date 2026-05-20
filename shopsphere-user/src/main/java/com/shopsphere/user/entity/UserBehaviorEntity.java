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
 * t_user_behavior 实体。{@code extra} 以序列化 JSON 字符串落库（MySQL JSON 列）；
 * 应用层用 {@code Map<String,Object>} 表达，由 Service 在写入前 {@code ObjectMapper#writeValueAsString} 转换。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user_behavior")
public class UserBehaviorEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("event_id")
    private String eventId;

    @TableField("user_id")
    private Long userId;

    @TableField("item_id")
    private Long itemId;

    @TableField("action_type")
    private String actionType;

    /** JSON 文本（应用层 Map ↔ String，由 Service 序列化） */
    private String extra;

    private OffsetDateTime ts;

    @TableField("created_at")
    private OffsetDateTime createdAt;
}
