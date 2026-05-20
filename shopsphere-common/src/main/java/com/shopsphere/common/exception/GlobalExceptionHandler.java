package com.shopsphere.common.exception;

import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理，对齐 docs/api-contracts.md §1.1。
 * <p>业务错误一律 HTTP 200，错误码在 Result.code；仅未捕获异常用 5xx。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常 code={} msg={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe != null
                ? fe.getField() + ": " + fe.getDefaultMessage()
                : ErrorCode.PARAM_INVALID.getMessage();
        log.warn("参数校验失败 {}", msg);
        return Result.fail(ErrorCode.PARAM_INVALID, msg);
    }

    /**
     * 请求体反序列化失败（JSON 语法错、枚举非法值如 actionType="unknown" 等）→ 1000。
     * <p>不暴露完整异常细节，避免泄漏 Jackson 内部路径；message 只含根因摘要。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        Throwable root = e.getMostSpecificCause();
        String msg = root != null && root.getMessage() != null
                ? "请求体解析失败：" + root.getMessage().split("\\R", 2)[0]
                : ErrorCode.PARAM_INVALID.getMessage();
        log.warn("请求体不可读 {}", msg);
        return Result.fail(ErrorCode.PARAM_INVALID, msg);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleOther(Exception e) {
        // log.error 自动带 MDC traceId（日志 pattern 由各服务配置）
        log.error("系统内部错误", e);
        return Result.fail(ErrorCode.SERVER_ERROR);
    }
}
