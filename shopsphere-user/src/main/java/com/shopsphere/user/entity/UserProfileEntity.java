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
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * t_user_profile 实体。user_id 为主键（一对一 t_user）。
 * <p>birthday 用 LocalDate（无时区，符合"日期"语义；契约 §1.1 允许）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user_profile")
public class UserProfileEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    private String nickname;
    private String avatar;

    /** 0=未知 1=男 2=女 */
    private Integer gender;
    private LocalDate birthday;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;
}
