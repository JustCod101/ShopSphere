package com.shopsphere.user.service;

import com.shopsphere.common.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.PrivateKey;
import java.time.Duration;
import java.util.Map;

/**
 * JWT 签发器。Claims 契约固定为 {@code userId}(Long) / {@code userName}(String)（§10），
 * 与 Gateway JwtAuthFilter（T1.2）一致；JJWT 自带 iat/exp。
 *
 * <p>由 {@code JwtSignerConfig} 在启动期一次性加载私钥构造 Bean；本期不做热轮换
 * （后续可仿 Gateway JwtPublicKeyProvider 扩 Nacos 监听）。
 */
@Slf4j
public class JwtSigner {

    private final PrivateKey privateKey;
    private final long expireSeconds;

    public JwtSigner(PrivateKey privateKey, long expireSeconds) {
        this.privateKey = privateKey;
        this.expireSeconds = expireSeconds;
    }

    /** 签发 token；claims 仅写 userId / userName，禁止写 sub/name（与 Gateway 解析键对齐）。 */
    public String sign(Long userId, String userName) {
        Map<String, Object> claims = Map.of(
                "userId", userId,
                "userName", userName == null ? "" : userName
        );
        return JwtUtil.signWithPrivateKey(privateKey, claims, Duration.ofSeconds(expireSeconds));
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }
}
