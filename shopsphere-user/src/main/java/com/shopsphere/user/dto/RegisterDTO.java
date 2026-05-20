package com.shopsphere.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求体（契约 §6.1）。校验：
 * <ul>
 *   <li>username 4-20 位，字母数字下划线</li>
 *   <li>password 8-32 位且必须含字母与数字（强度策略）</li>
 *   <li>email 可选；phone 可选（11 位数字或国际格式略宽）</li>
 * </ul>
 */
@Data
public class RegisterDTO {

    @NotBlank
    @Size(min = 4, max = 20)
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名仅允许字母、数字和下划线")
    private String username;

    @NotBlank
    @Size(min = 8, max = 32)
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,32}$", message = "密码须 8-32 位且包含字母和数字")
    private String password;

    @Email
    private String email;

    @Pattern(regexp = "^\\+?[0-9]{6,20}$", message = "手机号格式非法")
    private String phone;
}
