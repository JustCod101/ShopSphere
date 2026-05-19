package com.shopsphere.gateway.filter;

import com.shopsphere.common.context.HeaderConstant;
import com.shopsphere.gateway.config.GatewayFilterOrders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 全局请求日志（最外层）。
 *
 * <p>实现为 WebFlux {@link WebFilter} 而非 GatewayFilter/GlobalFilter：后者仅在路由匹配后才执行，
 * 对 {@code /internal/**} 等无路由请求（404）不生效；WebFilter 在路由前对<b>所有</b>请求生效。
 *
 * <p>职责：确保每个请求带 {@code X-Trace-Id}（缺失则生成），透传至下游与响应头，并写入 exchange 属性
 * 供 {@code ErrorResponseUtil} 使用；请求结束后单行记录 traceId / method / path / status / 耗时。
 */
@Component
public class RequestLogFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String traceId = request.getHeaders().getFirst(HeaderConstant.X_TRACE_ID);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        final String tid = traceId;

        // 透传给下游 + 写回响应头 + 暴露到 exchange 属性
        ServerHttpRequest mutated = request.mutate()
                .header(HeaderConstant.X_TRACE_ID, tid)
                .build();
        exchange.getResponse().getHeaders().set(HeaderConstant.X_TRACE_ID, tid);
        exchange.getAttributes().put(HeaderConstant.X_TRACE_ID, tid);
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();

        String method = request.getMethod().name();
        String path = request.getURI().getRawPath();
        long startNanos = System.nanoTime();

        return chain.filter(mutatedExchange).doFinally(signal -> {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            HttpStatusCode status = mutatedExchange.getResponse().getStatusCode();
            log.info("gw req traceId={} method={} path={} status={} costMs={}",
                    tid, method, path, status == null ? "-" : status.value(), costMs);
        });
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrders.REQUEST_LOG;
    }
}
