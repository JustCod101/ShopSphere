package com.shopsphere.common.context;

/**
 * Gateway → 下游 透传头与 MDC key 约定，对齐 docs/api-contracts.md §3（已拍板，三头固定）。
 */
public final class HeaderConstant {

    private HeaderConstant() {
    }

    /** 用户 ID（long），未登录接口不带 */
    public static final String X_USER_ID = "X-User-Id";
    /** 用户名（URL-encoded，可选） */
    public static final String X_USER_NAME = "X-User-Name";
    /** 链路 ID，全链路透传 */
    public static final String X_TRACE_ID = "X-Trace-Id";

    /** 日志 MDC 中链路 ID 的 key（与 Result.traceId 同源） */
    public static final String MDC_TRACE_ID = "traceId";
}
