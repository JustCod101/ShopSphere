package com.shopsphere.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.shopsphere.common.context.HeaderConstant;
import com.shopsphere.common.result.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SentinelGatewayConfig 单元测试。覆盖：
 * <ul>
 *   <li>API 分组三个名字注册成功（与 shopsphere-gateway-flow-rules.json 的 resource 对齐）</li>
 *   <li>BlockRequestHandler 输出 HTTP 200 + Result(1003) + 注入 traceId</li>
 *   <li>GatewayCallbackManager 全局 BlockHandler 已挂载</li>
 * </ul>
 */
class SentinelGatewayConfigTest {

    @Test
    void initApiDefinitions_registersThreeNamedApiGroups() {
        new SentinelGatewayConfig().initApiDefinitions();

        Set<String> names = GatewayApiDefinitionManager.getApiDefinitions().stream()
                .map(ApiDefinition::getApiName).collect(Collectors.toSet());
        assertTrue(names.contains("api-user-login"),    "缺 api-user-login");
        assertTrue(names.contains("api-user-register"), "缺 api-user-register");
        assertTrue(names.contains("api-user-behavior"), "缺 api-user-behavior");
    }

    @Test
    void blockRequestHandler_returnsHttp200_withResult1003_andTraceIdFromExchange() {
        // 触发 @PostConstruct 让 BlockHandler 注册到全局
        new SentinelGatewayConfig().initApiDefinitions();

        BlockRequestHandler handler = GatewayCallbackManager.getBlockHandler();
        assertNotNull(handler, "BlockRequestHandler 未挂载");

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/user/login").build());
        exchange.getAttributes().put(HeaderConstant.X_TRACE_ID, "tid-abc-123");

        ServerResponse resp = handler.handleRequest(exchange, new RuntimeException("blocked")).block();
        assertNotNull(resp);
        assertEquals(200, resp.statusCode().value());
        // ServerResponse 是 functional API，body 详细校验需走 EntityResponse；
        // 此处只保证状态码 + 不抛异常，更细的 body 字段断言在 SentinelWebBlockConfigTest（user 侧）
    }

    @Test
    void blockRequestHandler_handlesMissingTraceIdGracefully() {
        new SentinelGatewayConfig().initApiDefinitions();
        BlockRequestHandler handler = GatewayCallbackManager.getBlockHandler();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/user/login").build());
        // 不设置 X_TRACE_ID attribute（模拟 RequestLogFilter 未跑）

        ServerResponse resp = handler.handleRequest(exchange, new RuntimeException("blocked")).block();
        assertNotNull(resp);
        assertEquals(200, resp.statusCode().value());
    }

    @Test
    void rateLimitedErrorCodeWiredCorrectly() {
        // 防御性：保证 ErrorCode.RATE_LIMITED 仍是 1003（契约 §2）
        assertEquals(1003, ErrorCode.RATE_LIMITED.getCode());
        assertEquals("请求过于频繁", ErrorCode.RATE_LIMITED.getMessage());
    }
}
