package com.shopsphere.user.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.common.context.HeaderConstant;
import com.shopsphere.common.result.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SentinelWebBlockConfig 单测：验证 BlockExceptionHandler 写出 Result(1003) + traceId 兜底。
 */
class SentinelWebBlockConfigTest {

    // 与生产链一致：注册 JSR-310 等所有 Jackson 模块，保证 OffsetDateTime 可序列化
    private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private final BlockExceptionHandler handler =
            new SentinelWebBlockConfig(om).sentinelBlockExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private BlockException newBlockExceptionFor(String resource) {
        FlowRule rule = new FlowRule(resource).setCount(20).as(FlowRule.class);
        return new FlowException("flow-blocked", rule);
    }

    @Test
    void handle_writes200_withCode1003_andMessageFromErrorCode() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/user/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.handle(req, resp, newBlockExceptionFor("/api/user/login"));

        assertEquals(200, resp.getStatus());
        assertTrue(resp.getContentType().startsWith("application/json"));
        JsonNode body = om.readTree(resp.getContentAsString());
        assertEquals(ErrorCode.RATE_LIMITED.getCode(), body.get("code").asInt());
        assertEquals(ErrorCode.RATE_LIMITED.getMessage(), body.get("message").asText());
        assertTrue(body.get("data").isNull());
        // timestamp 序列化格式由 application.yml 的 spring.jackson.date-format 控制；
        // 单测 ObjectMapper 是默认配置（findAndRegisterModules），只校验存在 + ISO 形态
        assertNotNull(body.get("timestamp"));
        assertFalse(body.get("timestamp").asText().isBlank());
    }

    @Test
    void handle_takesTraceIdFromMdc_whenPresent() throws Exception {
        MDC.put(HeaderConstant.MDC_TRACE_ID, "tid-from-mdc");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/user/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.handle(req, resp, newBlockExceptionFor("/api/user/login"));

        JsonNode body = om.readTree(resp.getContentAsString());
        assertEquals("tid-from-mdc", body.get("traceId").asText());
    }

    @Test
    void handle_fallbacksToRequestHeader_whenMdcAbsent() throws Exception {
        // 模拟 SentinelWebInterceptor 抢在 UserContextInterceptor 之前的场景
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/user/login");
        req.addHeader(HeaderConstant.X_TRACE_ID, "tid-from-header");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.handle(req, resp, newBlockExceptionFor("/api/user/login"));

        JsonNode body = om.readTree(resp.getContentAsString());
        assertEquals("tid-from-header", body.get("traceId").asText());
    }

    @Test
    void handle_traceIdNullWhenNoSource() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/user/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.handle(req, resp, newBlockExceptionFor("/api/user/login"));

        JsonNode body = om.readTree(resp.getContentAsString());
        assertTrue(body.get("traceId").isNull(),
                "MDC 与 header 都没有时 traceId 须为 null,不能编造");
    }
}
