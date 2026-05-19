package com.shopsphere.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具，RS256 非对称（增强项决策：私钥仅 User 签发，公钥下发 Gateway 校验）。
 * <p>common 不持有任何密钥，密钥以 PEM 字符串由调用方注入。JJWT 0.12.x API。
 */
public final class JwtUtil {

    private JwtUtil() {
    }

    /** 用私钥签发 token；ttl 为有效期 */
    public static String signWithPrivateKey(PrivateKey privateKey, Map<String, Object> claims, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /** 用公钥验签并解析；签名/过期非法时抛 JJWT 异常 */
    public static Jws<Claims> verifyWithPublicKey(PublicKey publicKey, String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token);
    }

    /** 解析 PKCS#8 PEM 私钥 */
    public static PrivateKey parsePrivateKeyPem(String pem) {
        byte[] der = pemBody(pem);
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 RSA 私钥 PEM", e);
        }
    }

    /** 解析 X.509 PEM 公钥 */
    public static PublicKey parsePublicKeyPem(String pem) {
        byte[] der = pemBody(pem);
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 RSA 公钥 PEM", e);
        }
    }

    /** 生成 RSA 密钥对，仅 CLI/初始化用（默认 2048） */
    public static KeyPair generateRsaKeyPair(int keySize) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(keySize);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("生成 RSA 密钥对失败", e);
        }
    }

    private static byte[] pemBody(String pem) {
        String body = pem.replaceAll("-----BEGIN[^-]+-----", "")
                .replaceAll("-----END[^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
