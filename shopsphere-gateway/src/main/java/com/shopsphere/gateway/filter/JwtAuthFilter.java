package com.shopsphere.gateway.filter;

import com.shopsphere.common.context.HeaderConstant;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.util.JwtUtil;
import com.shopsphere.gateway.config.GatewayFilterOrders;
import com.shopsphere.gateway.config.WhitelistProperties;
import com.shopsphere.gateway.security.JwtPublicKeyProvider;
import com.shopsphere.gateway.util.ErrorResponseUtil;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;

/**
 * JWT 鉴权全局过滤器（§3 / §3.1 / §10）。
 *
 * <p>{@link GlobalFilter}，order={@link GatewayFilterOrders#JWT_AUTH}=-100：早于路由转发
 * （RewritePath/NettyRouting），晚于 WebFilter 链（{@code RequestLogFilter} 已剥离入站三头并生成
 * traceId；{@code InternalAccessRejectFilter} 已拦掉 {@code /internal}）。
 *
 * <p>流程：白名单（Ant，{@code /api/v1/}→{@code /api/} 归一）命中 → 放行且不注入 X-User-*；
 * 未命中 → 强制 RS256 验签，成功注入 {@code X-User-Id}/{@code X-User-Name}，
 * 失败（缺失/非法/过期/无公钥）→ {@code Result(1001)} HTTP 200。
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER = "Bearer ";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final WhitelistProperties whitelistProperties;
    private final JwtPublicKeyProvider publicKeyProvider;

    public JwtAuthFilter(WhitelistProperties whitelistProperties,
                         JwtPublicKeyProvider publicKeyProvider) {
        this.whitelistProperties = whitelistProperties;
        this.publicKeyProvider = publicKeyProvider;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String tid = exchange.getAttribute(HeaderConstant.X_TRACE_ID);
        String path = request.getPath().value();

        // CORS 预检 OPTIONS 不带 Authorization：放行（globalcors 通常已在网关 handler 映射阶段
        // 短路预检，此处为机制无关的防御纵深，避免预检被判 1001 致浏览器跨域失败）。不注入 X-User-*。
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(exchange);
        }

        // 白名单：放行且不注入 X-User-*（§3.1）
        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        // 受保护路径：强制 RS256 验签
        String token = bearerToken(request.getHeaders());
        if (token == null) {
            return unauthorized(exchange, tid, "缺少 Bearer Token", path);
        }
        PublicKey key = publicKeyProvider.publicKey();
        if (key == null) {
            return unauthorized(exchange, tid, "网关公钥未就绪", path);
        }
        final long userId;
        final String userName;
        try {
            Claims claims = JwtUtil.verifyWithPublicKey(key, token).getPayload();
            Number uid = claims.get("userId", Number.class);
            if (uid == null) {
                return unauthorized(exchange, tid, "Token 缺少 userId", path);
            }
            userId = uid.longValue();
            userName = claims.get("userName", String.class);
        } catch (Exception e) {
            return unauthorized(exchange, tid, "Token 验签失败/过期: " + e.getClass().getSimpleName(), path);
        }

        ServerHttpRequest mutated = request.mutate()
                .headers(h -> {
                    h.set(HeaderConstant.X_USER_ID, Long.toString(userId));
                    if (StringUtils.hasText(userName)) {
                        h.set(HeaderConstant.X_USER_NAME,
                                URLEncoder.encode(userName, StandardCharsets.UTF_8));
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isWhitelisted(String path) {
        String normalized = path.startsWith("/api/v1/") ? "/api/" + path.substring("/api/v1/".length()) : path;
        for (String pattern : whitelistProperties.getWhitelist()) {
            if (MATCHER.match(pattern, normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String bearerToken(HttpHeaders headers) {
        String h = headers.getFirst(HttpHeaders.AUTHORIZATION);
        return (h != null && h.startsWith(BEARER)) ? h.substring(BEARER.length()).trim() : null;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String tid, String reason, String path) {
        log.info("gw auth reject traceId={} path={} reason={}", tid, path, reason);
        return ErrorResponseUtil.writeResult(exchange, ErrorCode.UNAUTHORIZED);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrders.JWT_AUTH;
    }
}
