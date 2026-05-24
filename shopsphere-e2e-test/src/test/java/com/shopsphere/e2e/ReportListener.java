package com.shopsphere.e2e;

import com.shopsphere.e2e.support.LogDumper;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit5 TestExecutionListener：收集每个 @Test 的 PASS/FAIL/SKIP + 时长，suite 结束写 docs/e2e-report.md。
 * 通过 META-INF/services/org.junit.platform.launcher.TestExecutionListener 由 SPI 自动注册。
 *
 * <p>失败时同步 LogDumper.dumpAll() 一次（按 testPlanExecutionFinished 兜底）。
 *
 * <p>报告路径：相对 e2e 模块 cwd 写 ../docs/e2e-report.md（mvn 在模块 root 起，故 ../docs 指向项目 root）。
 * 若 ../docs 不存在则降级写到 target/e2e-report.md。
 */
public final class ReportListener implements TestExecutionListener {

    private final Map<String, Long> startNanos = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private boolean anyFailed = false;
    private Instant suiteStarted;

    record Row(String displayName, String status, long durationMs, String failure) {}

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        suiteStarted = Instant.now();
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        if (id.isTest()) {
            startNanos.put(id.getUniqueId(), System.nanoTime());
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!id.isTest()) return;
        long startNs = startNanos.getOrDefault(id.getUniqueId(), System.nanoTime());
        long ms = (System.nanoTime() - startNs) / 1_000_000L;
        String status = switch (result.getStatus()) {
            case SUCCESSFUL -> "PASS";
            case ABORTED   -> "SKIP";
            case FAILED    -> { anyFailed = true; yield "FAIL"; }
        };
        String failure = result.getThrowable().map(Throwable::toString).orElse("");
        // 用 [classSimple] methodName 作为可读名
        String name = displayOf(id);
        rows.add(new Row(name, status, ms, failure));
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (!id.isTest()) return;
        rows.add(new Row(displayOf(id), "SKIP", 0L, reason));
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        writeReport();
        if (anyFailed) {
            try { LogDumper.dumpAll(); } catch (Throwable t) { /* best-effort */ }
        }
    }

    private String displayOf(TestIdentifier id) {
        String src = id.getSource().map(Object::toString).orElse("");
        // src 形如 MethodSource[className = 'com.shopsphere.e2e.A_AuthFlowTest', methodName = 'foo', ...]
        String cls = extract(src, "className = '", "'");
        String mtd = extract(src, "methodName = '", "'");
        if (cls != null && mtd != null) {
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            return simple + "#" + mtd;
        }
        return id.getDisplayName();
    }

    private String extract(String src, String l, String r) {
        int s = src.indexOf(l);
        if (s < 0) return null;
        s += l.length();
        int e = src.indexOf(r, s);
        return e < 0 ? null : src.substring(s, e);
    }

    private void writeReport() {
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        sb.append("# ShopSphere E2E 报告\n\n");
        sb.append("> 由 `ReportListener` 在 ").append(LocalDateTime.now(ZoneOffset.UTC).atOffset(ZoneOffset.UTC).format(fmt))
                .append(" 自动生成。前次内容已被覆盖。\n\n");
        sb.append("- Suite 开始: ").append(suiteStarted.atOffset(ZoneOffset.UTC).format(fmt)).append("\n");
        sb.append("- 总用例数: ").append(rows.size()).append("\n");
        long pass = rows.stream().filter(r -> r.status.equals("PASS")).count();
        long fail = rows.stream().filter(r -> r.status.equals("FAIL")).count();
        long skip = rows.stream().filter(r -> r.status.equals("SKIP")).count();
        sb.append("- **PASS = ").append(pass).append(" / FAIL = ").append(fail).append(" / SKIP = ").append(skip).append("**\n\n");

        sb.append("| 用例 | 状态 | 耗时(ms) | 备注 |\n");
        sb.append("|---|---|---:|---|\n");
        for (Row r : rows) {
            String fail2 = r.failure.replace("\n", " ").replace("|", "\\|");
            if (fail2.length() > 160) fail2 = fail2.substring(0, 157) + "...";
            sb.append("| ").append(r.displayName)
                    .append(" | ").append(r.status)
                    .append(" | ").append(r.durationMs)
                    .append(" | ").append(fail2.isEmpty() ? "" : fail2)
                    .append(" |\n");
        }
        sb.append("\n");
        if (anyFailed) {
            sb.append("> 失败日志已 dump 到 `target/e2e-logs/`。\n");
        }

        Path target = Path.of("..", "docs", "e2e-report.md").normalize();
        if (!Files.exists(target.getParent())) {
            target = Path.of("target", "e2e-report.md");
            try { Files.createDirectories(target.getParent()); } catch (IOException ignored) {}
        }
        try {
            Files.writeString(target, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.err.println("[ReportListener] wrote " + target.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[ReportListener] write failed: " + e.getMessage());
        }
    }
}
