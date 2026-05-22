package com.shopsphere.common.result;

import com.shopsphere.common.context.HeaderConstant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 统一响应包装，对齐 docs/api-contracts.md §1.1。
 * <p>业务错误一律 HTTP 200，错误信息在 code 中体现；Controller 不得手工拼错误体，
 * 失败统一由 BusinessException + GlobalExceptionHandler 转换。
 *
 * @param <T> 业务数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 0 = 成功；非 0 见 {@link ErrorCode} */
    private int code;
    /** 面向调用方的可读信息，不含堆栈 */
    private String message;
    /** 成功时为业务数据，失败时为 null */
    private T data;
    /** 链路追踪 ID，由 Gateway 生成并透传，取自 MDC */
    private String traceId;
    /** UTC OffsetDateTime，ISO-8601 带偏移（M3：禁止 Date/LocalDateTime） */
    private OffsetDateTime timestamp;

    /** 是否成功（code == 0）。跨服务 Feign 调用方据此判定下游结果。 */
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> fail(ErrorCode ec) {
        return build(ec.getCode(), ec.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode ec, String customMsg) {
        return build(ec.getCode(), customMsg, null);
    }

    private static <T> Result<T> build(int code, String message, T data) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.data = data;
        r.traceId = MDC.get(HeaderConstant.MDC_TRACE_ID);
        r.timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        return r;
    }
}
