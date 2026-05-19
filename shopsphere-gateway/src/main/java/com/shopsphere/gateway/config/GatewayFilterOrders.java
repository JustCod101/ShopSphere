package com.shopsphere.gateway.config;

import org.springframework.core.Ordered;

/**
 * 全局过滤器执行顺序契约（order 越小越先执行 / 越靠外层）。
 *
 * <p>两条独立链：
 * <ul>
 *   <li><b>WebFilter 链</b>（包裹整个网关 handler，先于任何 GlobalFilter）：
 *       {@link #REQUEST_LOG} → {@link #INTERNAL_REJECT}。</li>
 *   <li><b>GlobalFilter 链</b>（网关 handler 内部，路由匹配后执行）：{@link #JWT_AUTH}=-100，
 *       早于路由转发(RewritePath/NettyRouting)。</li>
 * </ul>
 * 因 WebFilter 整体先于 GlobalFilter，{@code /internal/**} 由 {@code InternalAccessRejectFilter}
 * 在 {@code JwtAuthFilter} 之前 403/1004，鉴权对其不可达——拒绝优先级天然高于 JWT（§4.1）。
 *
 * <p>CORS 由 Nacos {@code spring.cloud.gateway.globalcors} 处理（预检在网关 handler 映射阶段
 * 短路，先于 GlobalFilter 链），不再有独立 {@code CorsWebFilter}。
 */
public final class GatewayFilterOrders {

    private GatewayFilterOrders() {
    }

    /** 请求日志：最外层，包裹全链路计时（task「最低 order」）。 */
    public static final int REQUEST_LOG = Ordered.HIGHEST_PRECEDENCE;

    /** {@code /internal/**} 显式拒绝：高优先级，必须先于 JWT。 */
    public static final int INTERNAL_REJECT = Ordered.HIGHEST_PRECEDENCE + 10;

    /**
     * JWT 鉴权过滤器（GlobalFilter）。order=-100：早于路由转发（RewritePath/NettyRouting），
     * 晚于 WebFilter 链（含 {@link #INTERNAL_REJECT}），故 {@code /internal} 在鉴权前已被拒绝。
     */
    public static final int JWT_AUTH = -100;
}
