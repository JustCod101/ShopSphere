package com.shopsphere.user.cli;

import com.shopsphere.common.util.JwtUtil;

import java.security.KeyPair;
import java.util.Base64;

/**
 * 一次性密钥对生成 CLI（仅初始化用，不入服务进程）。
 *
 * <h3>用法</h3>
 * <pre>
 *   mvn -q -pl shopsphere-user exec:java \
 *       -Dexec.mainClass=com.shopsphere.user.cli.KeyGenCli
 * </pre>
 *
 * <h3>输出</h3>
 * <ul>
 *   <li>私钥 PKCS#8 PEM → 用 Jasypt 加密后贴 Nacos {@code shopsphere-user-dev.yaml#jwt.private-key}</li>
 *   <li>公钥 X.509 PEM → 直接贴 Nacos {@code shopsphere-jwt-public-key.pem}（裸文本）</li>
 * </ul>
 *
 * <p>本工具**禁止落盘到仓库**；生成后将私钥放 {@code data/jwt/jwt-private-key.pem}（已 gitignore）
 * 用于本地一次性 Jasypt 加密，加密完毕即可删除明文。
 */
public final class KeyGenCli {

    private KeyGenCli() {
    }

    public static void main(String[] args) {
        int keySize = 2048;
        if (args.length > 0) {
            keySize = Integer.parseInt(args[0]);
        }
        KeyPair kp = JwtUtil.generateRsaKeyPair(keySize);
        Base64.Encoder mime = Base64.getMimeEncoder(64, "\n".getBytes());

        System.out.println("-----BEGIN PRIVATE KEY-----");
        System.out.println(mime.encodeToString(kp.getPrivate().getEncoded()));
        System.out.println("-----END PRIVATE KEY-----");
        System.out.println();
        System.out.println("-----BEGIN PUBLIC KEY-----");
        System.out.println(mime.encodeToString(kp.getPublic().getEncoded()));
        System.out.println("-----END PUBLIC KEY-----");
    }
}
