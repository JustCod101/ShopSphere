package com.shopsphere.common.exception;

import com.shopsphere.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常，统一由 {@link GlobalExceptionHandler} 转换为 Result（HTTP 200）。
 * <p>CLAUDE.md：异常用 BusinessException + 全局 @RestControllerAdvice。
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String customMsg) {
        super(customMsg);
        this.errorCode = errorCode;
    }
}
