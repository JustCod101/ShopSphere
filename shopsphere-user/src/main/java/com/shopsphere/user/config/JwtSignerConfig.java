package com.shopsphere.user.config;

import com.shopsphere.common.util.JwtUtil;
import com.shopsphere.user.service.JwtSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;

/**
 * 启动期一次性加载 PEM 私钥并构造 {@link JwtSigner} Bean。
 *
 * <p><b>密钥来源</b>：优先 {@code jwt.private-key}（Nacos {@code shopsphere-user-${profile}.yaml}
 * 中以 Jasypt {@code ENC(...)} 加密）；本地容器启动可用 {@code jwt.private-key-path}
 * 从 gitignored 挂载文件读取，避免把私钥写入仓库或 Compose 文件。
 *
 * <p><b>本期不做热轮换</b>：私钥变更需重启。后续可仿 Gateway {@code JwtPublicKeyProvider}
 * 改造为 Nacos 监听式热更新。
 */
@Configuration
public class JwtSignerConfig {

    @Bean
    public JwtSigner jwtSigner(
            @Value("${jwt.private-key:}") String privateKeyPem,
            @Value("${jwt.private-key-path:}") String privateKeyPath,
            @Value("${jwt.expire-seconds:7200}") long expireSeconds) {
        PrivateKey privateKey = JwtUtil.parsePrivateKeyPem(resolvePrivateKey(privateKeyPem, privateKeyPath));
        return new JwtSigner(privateKey, expireSeconds);
    }

    private String resolvePrivateKey(String privateKeyPem, String privateKeyPath) {
        if (StringUtils.hasText(privateKeyPem)) {
            return privateKeyPem;
        }
        if (!StringUtils.hasText(privateKeyPath)) {
            throw new IllegalStateException("缺少 jwt.private-key 或 jwt.private-key-path");
        }
        try {
            return Files.readString(Path.of(privateKeyPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 RSA 私钥文件失败: " + privateKeyPath, e);
        }
    }
}
