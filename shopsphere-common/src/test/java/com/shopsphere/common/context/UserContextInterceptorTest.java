package com.shopsphere.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证 X-User-Name 与 Gateway 编码对称解码（M1）：
 * Gateway 侧 {@code URLEncoder.encode(name, UTF-8)} ↔ 此处 {@code URLDecoder.decode}。
 */
class UserContextInterceptorTest {

    private final UserContextInterceptor interceptor = new UserContextInterceptor();

    @AfterEach
    void clear() {
        UserContextHolder.clear();
    }

    private String roundTrip(String userName) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderConstant.X_USER_ID, "10");
        req.addHeader(HeaderConstant.X_USER_NAME,
                URLEncoder.encode(userName, StandardCharsets.UTF_8));
        // handler 非 HandlerMethod → 跳过 @PublicApi 鉴权兜底分支
        interceptor.preHandle(req, new MockHttpServletResponse(), new Object());
        String decoded = UserContextHolder.get().getUserName();
        interceptor.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);
        return decoded;
    }

    @Test
    void decodesSpaceAndCjkSymmetrically() {
        assertEquals("张 三", roundTrip("张 三"));
        assertEquals("a b+c", roundTrip("a b+c"));
        assertEquals("plain", roundTrip("plain"));
    }

    @Test
    void absentUserNameStaysNull() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HeaderConstant.X_USER_ID, "10");
        interceptor.preHandle(req, new MockHttpServletResponse(), new Object());
        assertNull(UserContextHolder.get().getUserName());
        interceptor.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);
    }
}
