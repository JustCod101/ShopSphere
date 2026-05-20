package com.shopsphere.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 对外用户视图（/api/user/me 与注册响应）。
 * <p><b>铁律：不得包含 password_hash / passwordHash 字段</b>。
 * 时间字段统一 OffsetDateTime（UTC，契约 §1.1）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private Integer status;
    private OffsetDateTime createdAt;
}
