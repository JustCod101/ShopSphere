package com.shopsphere.common.context;

/**
 * ThreadLocal 持有当前线程的 {@link UserContext}。
 * <p>业务代码统一从此读取，禁止直接读 header（api-contracts §3）。
 * 必须在请求结束时 {@link #clear()}（由 UserContextInterceptor.afterCompletion 调用）。
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserContext> TL = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserContext ctx) {
        TL.set(ctx);
    }

    public static UserContext get() {
        return TL.get();
    }

    public static Long getUserId() {
        UserContext c = TL.get();
        return c == null ? null : c.getUserId();
    }

    public static String getTraceId() {
        UserContext c = TL.get();
        return c == null ? null : c.getTraceId();
    }

    public static void clear() {
        TL.remove();
    }
}
