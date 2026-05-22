package com.shopsphere.user.messaging;

import com.shopsphere.common.util.JwtUtil;

import java.security.KeyPair;
import java.util.Base64;

/**
 * 测试专用：进程内生成一对 RSA 密钥，把私钥导出为 PKCS#8 PEM 供
 * {@code @DynamicPropertySource} 注入 {@code jwt.private-key}。
 *
 * <p>生产中 {@code jwt.private-key} 由 Nacos（Jasypt 加密）下发；离线 {@code @SpringBootTest}
 * 无 Nacos，故本类提供一个等效的、合法的 PKCS#8 私钥占位 —— 仅为让 {@code JwtSignerConfig}
 * 能构造 {@code JwtSigner}，本 MQ 链路测试不实际签发/校验 token。
 */
final class TestJwtKeys {

    private TestJwtKeys() {
    }

    /** 进程级唯一密钥对，避免重复生成开销。 */
    static final String PRIVATE_KEY_PEM = exportPrivateKeyPem();

    private static String exportPrivateKeyPem() {
        KeyPair keyPair = JwtUtil.generateRsaKeyPair(2048);
        // PrivateKey.getEncoded() 即 PKCS#8 DER；包装为标准 PEM 头尾。
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
    }
}
