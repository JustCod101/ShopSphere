package com.shopsphere.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Gateway 公开路径白名单（docs §3.1，载体 Nacos {@code shopsphere-gateway.yaml} 的 {@code security.whitelist}）。
 *
 * <p>T1.1 仅完成<b>配置绑定 + 热更新验证</b>，<b>不实现鉴权</b>。JWT/白名单匹配过滤器在 T1.2/T1.3 落地，
 * 届时直接注入本 bean。
 *
 * <p>热更新机制：{@code @ConfigurationProperties} bean 在 Nacos 变更触发的 {@code EnvironmentChangeEvent}
 * 时由 Spring Cloud {@code ConfigurationPropertiesRebinder} 自动重绑（无需 {@code @RefreshScope}——后者是惰性
 * 重建，无访问者则不触发）。{@link #onRefreshed} 监听刷新完成事件，提供可观测的生效日志，并作为 T1.2 重载钩子。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security")
public class WhitelistProperties {

    private static final Logger log = LoggerFactory.getLogger(WhitelistProperties.class);

    private List<String> whitelist = new ArrayList<>();

    @PostConstruct
    public void logLoaded() {
        log.info("Gateway 白名单加载生效，共 {} 条: {}", whitelist.size(), whitelist);
    }

    /** Nacos 配置刷新完成后（重绑已发生）打印当前白名单，验证 §3.1 热更新载体可用。 */
    @EventListener(RefreshScopeRefreshedEvent.class)
    public void onRefreshed(RefreshScopeRefreshedEvent event) {
        log.info("Gateway 白名单热更新，共 {} 条: {}", whitelist.size(), whitelist);
    }
}
