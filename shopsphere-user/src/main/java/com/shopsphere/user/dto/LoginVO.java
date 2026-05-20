package com.shopsphere.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应（契约 §6.1）：{ token, expiresIn(秒) }。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    private String token;
    /** token 有效期，单位秒（与 jwt.expire-seconds 一致） */
    private long expiresIn;
}
