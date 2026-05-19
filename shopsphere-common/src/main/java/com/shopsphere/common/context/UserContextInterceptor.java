package com.shopsphere.common.context;

import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * 从 Gateway 透传头还原用户上下文 + MDC，并兜底鉴权（api-contracts §3）。
 * <p>禁止业务代码直接读 header；JWT 校验在 Gateway，此处仅信任头并兜底未认证。
 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // X-Trace-Id 由 Gateway 生成；服务间直连/缺失时本地兜底生成 32 位 hex
        String traceId = request.getHeader(HeaderConstant.X_TRACE_ID);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(HeaderConstant.MDC_TRACE_ID, traceId);

        Long userId = parseUserId(request.getHeader(HeaderConstant.X_USER_ID));
        String userName = request.getHeader(HeaderConstant.X_USER_NAME);

        UserContextHolder.set(UserContext.builder()
                .userId(userId)
                .userName(userName)
                .traceId(traceId)
                .build());

        // 非 @PublicApi 接口且无 X-User-Id → 未认证
        if (userId == null && handler instanceof HandlerMethod hm && !isPublic(hm)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContextHolder.clear();
        MDC.remove(HeaderConstant.MDC_TRACE_ID);
    }

    private boolean isPublic(HandlerMethod hm) {
        return hm.hasMethodAnnotation(PublicApi.class)
                || hm.getBeanType().isAnnotationPresent(PublicApi.class);
    }

    private Long parseUserId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
