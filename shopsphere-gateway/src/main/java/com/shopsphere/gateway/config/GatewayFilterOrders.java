package com.shopsphere.gateway.config;

import org.springframework.core.Ordered;

/**
 * 全局过滤器执行顺序契约（order 越小越先执行 / 越靠外层）。
 *
 * <p>关键约束（CLAUDE.md / docs §4.1）：{@code /internal/**} 的拒绝必须先于任何 JWT 鉴权过滤器生效，
 * 故 {@link #INTERNAL_REJECT} 严格小于 {@link #JWT_AUTH}。
 */
public final class GatewayFilterOrders {

    private GatewayFilterOrders() {
    }

    /** 请求日志：最外层，包裹全链路计时（task「最低 order」）。 */
    public static final int REQUEST_LOG = Ordered.HIGHEST_PRECEDENCE;

    /** {@code /internal/**} 显式拒绝：高优先级，必须先于 JWT。 */
    public static final int INTERNAL_REJECT = Ordered.HIGHEST_PRECEDENCE + 10;

    /**
     * JWT 鉴权过滤器顺序占位（T1.1 不实现，T1.2/T1.3 落地）。
     * 保证晚于 {@link #INTERNAL_REJECT}，使内部接口拒绝先于鉴权生效。
     */
    public static final int JWT_AUTH = Ordered.HIGHEST_PRECEDENCE + 100;
}
