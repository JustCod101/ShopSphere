package com.shopsphere.gateway.security;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.shopsphere.common.util.JwtUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.PublicKey;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JWT 验签公钥提供者（§10：RS256 非对称，公钥经 Nacos 下发，轮换零重启）。
 *
 * <p>{@code shopsphere-jwt-public-key.pem} 是裸 PEM 文本而非 yaml/properties，无法用
 * {@code @ConfigurationProperties}/{@code @RefreshScope} 绑定；故直接用 Nacos {@link ConfigService}
 * 启动拉取 + {@link Listener} 监听变更，{@code volatile} 原子替换 {@link #publicKey()}。
 *
 * <p><b>fail-closed</b>：拉取失败 / PEM 非法 / 未下发 → 公钥为 {@code null}，
 * {@code JwtAuthFilter} 对受保护路径一律 {@code 1001}，杜绝"未配公钥即裸奔"。
 *
 * <p><b>自愈（MI-2）</b>：启动时 Nacos 不可达不阻断网关；getConfig/监听注册失败则后台定时退避重试，
 * Nacos 恢复后自动补拉公钥并补注册监听器，无需人工重启；二者均就绪后停止重试。
 */
@Component
public class JwtPublicKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtPublicKeyProvider.class);

    private final NacosConfigManager nacosConfigManager;
    private final String dataId;
    private final String group;
    private final long timeoutMs;

    private volatile PublicKey publicKey;
    private volatile boolean listenerRegistered = false;

    private static final long RETRY_PERIOD_SEC = 30;

    /** 单例：Nacos 每次推送都会调 Listener#getExecutor()，须复用同一池避免线程泄漏（S5）。 */
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jwt-pubkey-refresh");
        t.setDaemon(true);
        return t;
    });

    /** 启动期 Nacos 不可达时的后台重试（MI-2 自愈）；二者就绪后关闭。 */
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jwt-pubkey-retry");
        t.setDaemon(true);
        return t;
    });

    /** 复用单一 Listener 实例，避免重复注册多份。 */
    private final Listener configListener = new Listener() {
        @Override
        public Executor getExecutor() {
            return refreshExecutor;
        }

        @Override
        public void receiveConfigInfo(String configInfo) {
            apply(configInfo, "热更新");
        }
    };

    public JwtPublicKeyProvider(
            NacosConfigManager nacosConfigManager,
            @Value("${jwt.public-key.data-id:shopsphere-jwt-public-key.pem}") String dataId,
            @Value("${jwt.public-key.group:DEFAULT_GROUP}") String group,
            @Value("${jwt.public-key.timeout-ms:5000}") long timeoutMs) {
        this.nacosConfigManager = nacosConfigManager;
        this.dataId = dataId;
        this.group = group;
        this.timeoutMs = timeoutMs;
    }

    @PostConstruct
    public void init() {
        // fail-closed：Nacos 不可达不得阻断网关启动；publicKey 保持 null → 受保护路径一律 1001
        attempt("启动加载");
        if (!listenerRegistered) {
            log.warn("JWT 公钥监听未就绪，后台每 {}s 重试，Nacos 恢复后自愈", RETRY_PERIOD_SEC);
            retryScheduler.scheduleWithFixedDelay(() -> attempt("重试"),
                    RETRY_PERIOD_SEC, RETRY_PERIOD_SEC, TimeUnit.SECONDS);
        }
    }

    /** 拉取公钥 + 注册监听器（各自容错）；两者就绪后停止后台重试。幂等可重复调用。 */
    private synchronized void attempt(String scene) {
        ConfigService configService = nacosConfigManager.getConfigService();
        if (publicKey == null) {
            try {
                apply(configService.getConfig(dataId, group, timeoutMs), scene);
            } catch (Exception e) {
                log.error("JWT 公钥{}失败：Nacos 拉取异常，受保护路径将全部 1001", scene, e);
            }
        }
        if (!listenerRegistered) {
            try {
                configService.addListener(dataId, group, configListener);
                listenerRegistered = true;
                log.info("JWT 公钥监听器注册成功");
            } catch (Exception e) {
                log.error("JWT 公钥监听器注册失败（将继续重试）", e);
            }
        }
        if (listenerRegistered && publicKey != null) {
            retryScheduler.shutdown();
        }
    }

    @PreDestroy
    public void shutdown() {
        retryScheduler.shutdownNow();
        refreshExecutor.shutdownNow();
    }

    /** 当前验签公钥；{@code null} 表示未就绪（受保护请求应判 1001）。 */
    public PublicKey publicKey() {
        return publicKey;
    }

    private void apply(String pem, String scene) {
        if (!StringUtils.hasText(pem)) {
            this.publicKey = null;
            log.error("JWT 公钥{}失败：Nacos dataId={} group={} 内容为空，受保护路径将全部 1001",
                    scene, dataId, group);
            return;
        }
        try {
            this.publicKey = JwtUtil.parsePublicKeyPem(pem);
            log.info("JWT 公钥{}成功 (dataId={} group={})", scene, dataId, group);
        } catch (RuntimeException e) {
            this.publicKey = null;
            log.error("JWT 公钥{}失败：PEM 非法，受保护路径将全部 1001", scene, e);
        }
    }
}
