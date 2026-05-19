package com.shopsphere.gateway.filter;

import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.gateway.config.GatewayFilterOrders;
import com.shopsphere.gateway.util.ErrorResponseUtil;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * {@code /internal/**} 显式拒绝（docs §4.1：防外部直调内部 Feign 接口）。
 *
 * <p>实现为 WebFlux {@link WebFilter}：在路由解析前、JWT 鉴权前对所有请求生效
 * （order={@link GatewayFilterOrders#INTERNAL_REJECT}，仅次于 RequestLogFilter）。
 * GlobalFilter 仅在路由匹配后才跑，对无路由的 {@code /internal/**} 会先 404，故不可用。
 *
 * <p>命中即短路返回 HTTP 403 + 统一 {@code Result}（code={@code 1004}），不进入后续链。
 */
@Component
public class InternalAccessRejectFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/internal") || path.startsWith("/internal/")) {
            return ErrorResponseUtil.write(exchange, HttpStatus.FORBIDDEN, ErrorCode.ROUTE_NOT_FOUND);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrders.INTERNAL_REJECT;
    }
}
