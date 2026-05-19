package com.shopsphere.gateway.filter;

import com.shopsphere.common.context.HeaderConstant;
import com.shopsphere.gateway.config.GatewayFilterOrders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
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
 * <p>职责（§3 安全铁律）：
 * <ol>
 *   <li><b>剥离</b>外部传入的 {@code X-User-Id}/{@code X-User-Name}/{@code X-Trace-Id}（防伪造，
 *       最早 WebFilter，覆盖含 {@code /internal} 在内的所有路径）；</li>
 *   <li>由网关<b>重新生成</b> {@code X-Trace-Id}（UUID 去横线 32 位 hex，忽略入站值），
 *       写入下游请求头 + exchange 属性，<b>不</b>回写客户端响应头（仅内部链路）；</li>
 *   <li>请求结束后单行记录 traceId / method / path / status / 耗时。</li>
 * </ol>
 */
@Component
public class RequestLogFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // §3：网关独占 traceId，始终新生成，忽略任何入站值
        final String tid = UUID.randomUUID().toString().replace("-", "");

        // 剥离外部伪造的上下文头，再注入网关生成的 traceId；不回写客户端响应头
        ServerHttpRequest mutated = request.mutate()
                .headers(h -> {
                    h.remove(HeaderConstant.X_USER_ID);
                    h.remove(HeaderConstant.X_USER_NAME);
                    h.remove(HeaderConstant.X_TRACE_ID);
                    h.set(HeaderConstant.X_TRACE_ID, tid);
                })
                .build();
        exchange.getAttributes().put(HeaderConstant.X_TRACE_ID, tid);
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();

        String method = request.getMethod().name();
        String path = request.getURI().getRawPath();
        long startNanos = System.nanoTime();

        boolean skipLog = path.startsWith("/actuator");
        return chain.filter(mutatedExchange).doFinally(signal -> {
            if (skipLog) {
                return; // 探针/管理端降噪（管理端通常已独立端口，此为同端口回退兜底）
            }
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
