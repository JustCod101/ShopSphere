package com.shopsphere.gateway.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shopsphere.common.context.HeaderConstant;
import com.shopsphere.common.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 响应式统一 JSON 错误输出。
 *
 * <p>不调用 {@code Result.fail()}：其 traceId 取自 servlet 链路 MDC，网关为 WebFlux 响应式无 MDC。
 * 这里手工构造与 docs §1.1 <b>字段同构</b> 的对象（code/message/data/traceId/timestamp），
 * traceId 取 {@code exchange} 属性（由 RequestLogFilter 注入；§3 不回写客户端响应头，故不再兜底响应头）。
 *
 * <ul>
 *   <li>{@link #write}：自定义 HTTP 状态（如 {@code /internal} 403 + 1004）。</li>
 *   <li>{@link #writeResult}：业务错误固定 HTTP 200（§1.1，如鉴权失败 1001）。</li>
 * </ul>
 */
public final class ErrorResponseUtil {

    private static final Logger log = LoggerFactory.getLogger(ErrorResponseUtil.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // §1.1：timestamp 为 ISO-8601 带偏移字符串，禁止序列化为 epoch 数字
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ErrorResponseUtil() {
    }

    /** 自定义 HTTP 状态的统一错误体（如 {@code /internal} → 403 + 1004）。 */
    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorCode ec) {
        return doWrite(exchange, status, ec);
    }

    /** 业务错误：HTTP 200 + 统一 {@code Result} 体（§1.1，如鉴权失败 → 200 + 1001）。 */
    public static Mono<Void> writeResult(ServerWebExchange exchange, ErrorCode ec) {
        return doWrite(exchange, HttpStatus.OK, ec);
    }

    private static Mono<Void> doWrite(ServerWebExchange exchange, HttpStatus status, ErrorCode ec) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(
                new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ec.getCode());
        body.put("message", ec.getMessage());
        body.put("data", null);
        body.put("traceId", resolveTraceId(exchange));
        body.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC));

        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            // 理论不可达：固定 schema 序列化失败
            log.error("序列化错误响应失败", e);
            bytes = ("{\"code\":" + ec.getCode() + ",\"message\":\"" + ec.getMessage() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private static String resolveTraceId(ServerWebExchange exchange) {
        Object attr = exchange.getAttribute(HeaderConstant.X_TRACE_ID);
        return (attr instanceof String s && !s.isBlank()) ? s : null;
    }
}
