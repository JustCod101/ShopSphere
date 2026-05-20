package com.shopsphere.user.config;

import com.shopsphere.common.util.JwtUtil;
import com.shopsphere.user.service.JwtSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.PrivateKey;

/**
 * 启动期一次性加载 PEM 私钥并构造 {@link JwtSigner} Bean。
 *
 * <p><b>密钥来源</b>：{@code jwt.private-key}（Nacos {@code shopsphere-user-${profile}.yaml}
 * 中以 Jasypt {@code ENC(...)} 加密；Spring 装配时由 jasypt-spring-boot-starter 透明解密）。
 *
 * <p><b>本期不做热轮换</b>：私钥变更需重启。后续可仿 Gateway {@code JwtPublicKeyProvider}
 * 改造为 Nacos 监听式热更新。
 */
@Configuration
public class JwtSignerConfig {

    @Bean
    public JwtSigner jwtSigner(
            @Value("${jwt.private-key}") String privateKeyPem,
            @Value("${jwt.expire-seconds:7200}") long expireSeconds) {
        PrivateKey privateKey = JwtUtil.parsePrivateKeyPem(privateKeyPem);
        return new JwtSigner(privateKey, expireSeconds);
    }
}
