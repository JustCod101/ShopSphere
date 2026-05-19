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
 * traceId 优先取 {@code exchange} 属性（由 RequestLogFilter 注入），兜底取响应头。
 */
public final class ErrorResponseUtil {

    private static final Logger log = LoggerFactory.getLogger(ErrorResponseUtil.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // §1.1：timestamp 为 ISO-8601 带偏移字符串，禁止序列化为 epoch 数字
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ErrorResponseUtil() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorCode ec) {
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
        if (attr instanceof String s && !s.isBlank()) {
            return s;
        }
        return exchange.getResponse().getHeaders().getFirst(HeaderConstant.X_TRACE_ID);
    }
}
