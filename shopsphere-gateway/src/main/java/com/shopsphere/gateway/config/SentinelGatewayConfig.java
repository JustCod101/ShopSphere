package com.shopsphere.gateway.config;

import com.alibaba.cloud.sentinel.datasource.converter.JsonConverter;
import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.common.context.HeaderConstant;
import com.shopsphere.common.result.ErrorCode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sentinel 网关流控配置（T1.5）。
 *
 * <h3>设计</h3>
 * <ul>
 *   <li><b>API 分组在代码</b>:路径 → 资源名映射不出代码版本（路径漂移即编译失败）。</li>
 *   <li><b>阈值在 Nacos</b>:{@code shopsphere-gateway-flow-rules.json}（dev/DEFAULT_GROUP），
 *       由 {@code sentinel-datasource-nacos} 热加载到 {@code GatewayRuleManager}。</li>
 *   <li><b>BlockRequestHandler</b>:从 {@code exchange} 属性拿 {@link HeaderConstant#X_TRACE_ID}
 *       （RequestLogFilter 在最外层 WebFilter 已注入），返回 HTTP 200 + {@code Result(1003)}。</li>
 * </ul>
 *
 * <h3>filter 顺序</h3>
 * adapter 自动注册的 {@code SentinelGatewayFilter} 默认 order=-1（adapter 内部常量）。
 * 本工程 GlobalFilter 顺序约定：{@code RequestLogFilter}/{@code InternalAccessRejectFilter}（WebFilter，路由前）→
 * 路由匹配 → GlobalFilter 链。Sentinel 与 {@code JwtAuthFilter}(order=-100) 都在 GlobalFilter 链中；
 * 当前不强制 Sentinel 早于 JWT —— JWT 验签 ms 级 CPU 在 200 QPS 量级洪峰下可承受。
 * 若后续要 Sentinel 先行,在此类增 {@code @Bean SentinelGatewayFilter} 覆盖 order 即可。
 */
@Configuration
public class SentinelGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelGatewayConfig.class);

    /** 路径 → 资源名的代码映射；与 {@code shopsphere-gateway-flow-rules.json} 的 resource 字段强绑定 */
    private static final Map<String, String> API_GROUPS = Map.of(
            "api-user-login",    "/api/user/login",
            "api-user-register", "/api/user/register",
            "api-user-behavior", "/api/user/behavior"
    );

    @PostConstruct
    void initApiDefinitions() {
        Set<ApiDefinition> defs = new HashSet<>();
        API_GROUPS.forEach((name, path) -> {
            Set<ApiPredicateItem> items = new HashSet<>();
            ApiPathPredicateItem item = new ApiPathPredicateItem();
            item.setPattern(path);
            items.add(item);
            defs.add(new ApiDefinition(name).setPredicateItems(items));
        });
        GatewayApiDefinitionManager.loadApiDefinitions(defs);
        log.info("Sentinel API 分组加载完成: {}", API_GROUPS.keySet());

        // 直接 build 一个实例挂到 Sentinel 全局回调,不走 @Bean 工厂方法(避免 CGLib 自引用循环)
        GatewayCallbackManager.setBlockHandler(buildBlockRequestHandler());
        log.info("Sentinel BlockRequestHandler 已挂载（trace-id 透传 + Result(1003)）");
    }

    @Bean
    public BlockRequestHandler traceIdAwareBlockRequestHandler() {
        return buildBlockRequestHandler();
    }

    @Bean("sentinel-json-gw-flow-converter")
    public JsonConverter<GatewayFlowRule> jsonGatewayFlowConverter() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new JsonConverter<>(mapper, GatewayFlowRule.class);
    }

    /** Build 一个 BlockRequestHandler 实例。@PostConstruct 与 @Bean 均通过此 helper 拿到等价实例,
     *  避免 @PostConstruct 内调用同 class 的 @Bean 方法(CGLib 自引用 → Spring Boot 3 默认拒绝). */
    private static BlockRequestHandler buildBlockRequestHandler() {
        return (exchange, t) -> {
            Object attr = exchange.getAttribute(HeaderConstant.X_TRACE_ID);
            String tid = (attr instanceof String s && !s.isBlank()) ? s : null;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", ErrorCode.RATE_LIMITED.getCode());
            body.put("message", ErrorCode.RATE_LIMITED.getMessage());
            body.put("data", null);
            body.put("traceId", tid);
            body.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
            // 业务错误统一 HTTP 200（契约 §2，错误码在 body.code）
            return ServerResponse.status(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body);
        };
    }

    /** 防御性常量引用，确保 adapter 升级 API 改名时编译期暴露 */
    @SuppressWarnings("unused")
    private static final int API_GROUP_MODE = SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME;
}
