package com.shopsphere.e2e.support;

import java.io.File;
import java.util.List;

/**
 * 测试失败时调用：dump 各容器日志到 target/e2e-logs/{service}.log。
 * 用 docker compose CLI 直接调用，无需 docker SDK 依赖。
 */
public final class LogDumper {

    private static final List<String> SERVICES = List.of(
            "shopsphere-gateway", "shopsphere-user", "shopsphere-product",
            "shopsphere-order", "shopsphere-recommendation",
            "nacos", "mysql", "rabbitmq");

    private LogDumper() {}

    public static void dumpAll() {
        File dir = new File("target/e2e-logs");
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("[LogDumper] cannot create " + dir);
            return;
        }
        for (String svc : SERVICES) {
            File out = new File(dir, svc + ".log");
            try {
                Process p = new ProcessBuilder("docker", "compose", "logs", "--no-color", "--tail=500", svc)
                        .redirectErrorStream(true)
                        .redirectOutput(out)
                        .start();
                p.waitFor();
            } catch (Exception e) {
                System.err.println("[LogDumper] dump " + svc + " failed: " + e.getMessage());
            }
        }
        System.err.println("[LogDumper] dumped to " + dir.getAbsolutePath());
    }
}
