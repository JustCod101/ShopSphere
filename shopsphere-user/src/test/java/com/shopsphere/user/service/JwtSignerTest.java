package com.shopsphere.user.service;

import com.shopsphere.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtSigner 单测：claims 键、过期行为。
 * <p>不引入 Spring，直接用 {@link JwtUtil#generateRsaKeyPair(int)} 临时密钥。
 */
class JwtSignerTest {

    @Test
    void signProducesClaimsWithUserIdAndUserName_NoSubNoName() throws Exception {
        KeyPair kp = JwtUtil.generateRsaKeyPair(2048);
        JwtSigner signer = new JwtSigner(kp.getPrivate(), 3600);

        String token = signer.sign(42L, "alice");
        Jws<Claims> jws = JwtUtil.verifyWithPublicKey(kp.getPublic(), token);
        Claims c = jws.getPayload();

        // 契约：userId Long、userName String；不写 sub/name（与 Gateway JwtAuthFilter 对齐）
        assertEquals(42L, ((Number) c.get("userId")).longValue());
        assertEquals("alice", c.get("userName"));
        assertNull(c.getSubject(), "禁止写标准 sub");
        assertNull(c.get("name"), "禁止写标准 name");
        assertNotNull(c.getIssuedAt());
        assertNotNull(c.getExpiration());
    }

    @Test
    void expiredTokenThrowsExpiredJwtException() throws Exception {
        KeyPair kp = JwtUtil.generateRsaKeyPair(2048);
        JwtSigner signer = new JwtSigner(kp.getPrivate(), 1);
        String token = signer.sign(1L, "x");

        Thread.sleep(1500);
        assertThrows(ExpiredJwtException.class,
                () -> JwtUtil.verifyWithPublicKey(kp.getPublic(), token));
    }

    @Test
    void nullUserNameEncodedAsEmptyString() throws Exception {
        KeyPair kp = JwtUtil.generateRsaKeyPair(2048);
        JwtSigner signer = new JwtSigner(kp.getPrivate(), 3600);
        String token = signer.sign(7L, null);

        Claims c = JwtUtil.verifyWithPublicKey(kp.getPublic(), token).getPayload();
        assertEquals("", c.get("userName"));
    }
}
