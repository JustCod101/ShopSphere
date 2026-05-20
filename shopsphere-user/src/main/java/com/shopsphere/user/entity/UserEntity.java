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
 * t_user 实体。时间字段统一 OffsetDateTime（UTC，契约 §1.1）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user")
public class UserEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 雪花 ID，MyBatis-Plus 内置生成 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    @TableField("password_hash")
    private String passwordHash;

    private String email;
    private String phone;

    /** 1=正常 0=禁用 */
    private Integer status;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;
}
