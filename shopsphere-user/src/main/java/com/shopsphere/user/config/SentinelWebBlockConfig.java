package com.shopsphere.user.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.common.context.HeaderConstant;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Sentinel Web 限流命中后的统一响应（T1.5）。
 *
 * <p><b>为什么不用 {@code @ExceptionHandler(BlockException.class)}</b>:
 * Sentinel {@code SentinelWebInterceptor.preHandle} 内部 try/catch 后调
 * {@link BlockExceptionHandler#handle}，<b>不会</b>把异常抛回 Spring MVC 异常链 —
 * {@code @RestControllerAdvice} 永远拿不到 {@code BlockException}。
 *
 * <p>本配置注册 Spring Bean {@link BlockExceptionHandler}，SCA 自动装配的
 * {@code SentinelWebInterceptor} 会用它替换默认 handler，直接写 JSON 到 response：
 * <pre>
 *   HTTP 200
 *   { code: 1003, message: "请求过于频繁", data: null, traceId: ..., timestamp: ... }
 * </pre>
 *
 * <p><b>traceId 来源优先级</b>:MDC（{@code UserContextInterceptor} 已写入）→
 * 请求头 {@code X-Trace-Id} 兜底。两者都没有则 {@code null}（不强行编造）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SentinelWebBlockConfig {

    private final ObjectMapper objectMapper;

    @Bean
    public BlockExceptionHandler sentinelBlockExceptionHandler() {
        return (HttpServletRequest req, HttpServletResponse resp, BlockException e) -> {
            String tid = MDC.get(HeaderConstant.MDC_TRACE_ID);
            if (tid == null || tid.isBlank()) {
                // 兜底：UserContextInterceptor 顺序若被 Sentinel 抢先，从请求头回填
                tid = req.getHeader(HeaderConstant.X_TRACE_ID);
            }
            log.warn("Sentinel 限流命中 uri={} ruleResource={} traceId={}",
                    req.getRequestURI(), e.getRule() != null ? e.getRule().getResource() : null, tid);

            resp.setStatus(HttpStatus.OK.value());
            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
            resp.setCharacterEncoding("UTF-8");

            // Result.fail 自动从 MDC 拿 traceId；这里手动覆盖以保证有 fallback
            Result<Void> body = Result.fail(ErrorCode.RATE_LIMITED);
            if (body.getTraceId() == null && tid != null) {
                body.setTraceId(tid);
            }
            resp.getWriter().write(objectMapper.writeValueAsString(body));
        };
    }
}
