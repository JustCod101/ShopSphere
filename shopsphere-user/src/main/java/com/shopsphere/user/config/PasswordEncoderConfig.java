package com.shopsphere.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCryptPasswordEncoder Bean。
 * <p>strength=10（默认），单次哈希 ~80ms 量级，适配登录吞吐；调高可正比变慢。
 * 仅引入 spring-security-crypto 子模块（约束：不引入完整 spring-security）。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
