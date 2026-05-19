package com.shopsphere.common;

import com.shopsphere.common.context.HeaderConstant;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.PageResult;
import com.shopsphere.common.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证 Result/PageResult/ErrorCode 与 docs/api-contracts.md §1/§2 契约一致。
 * 纯单测，无 Spring 上下文（common 无 web 运行时强依赖）。
 */
class ResultAndErrorCodeTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void ok_shouldBeSuccessWithUtcOffsetDateTime() {
        Result<String> r = Result.ok("hi");
        assertEquals(0, r.getCode());
        assertEquals("hi", r.getData());
        assertNotNull(r.getTimestamp());
        // M3：timestamp 为 OffsetDateTime 且为 UTC 偏移
        assertEquals(ZoneOffset.UTC, r.getTimestamp().getOffset());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertEquals(now.getOffset(), r.getTimestamp().getOffset());
    }

    @Test
    void result_shouldFillTraceIdFromMdc() {
        MDC.put(HeaderConstant.MDC_TRACE_ID, "trace-abc-123");
        Result<Void> r = Result.fail(ErrorCode.UNAUTHORIZED);
        assertEquals("trace-abc-123", r.getTraceId());
        assertEquals(1001, r.getCode());
        assertNull(r.getData());
    }

    @Test
    void fail_withCustomMessage() {
        Result<Void> r = Result.fail(ErrorCode.STOCK_NOT_ENOUGH, "仅剩 1 件");
        assertEquals(3002, r.getCode());
        assertEquals("仅剩 1 件", r.getMessage());
    }

    @Test
    void pageResult_of() {
        PageResult<String> p = PageResult.of(List.of("a", "b"), 2L, 1L, 20L);
        assertEquals(2, p.getRecords().size());
        assertEquals(2L, p.getTotal());
        assertEquals(1L, p.getPage());
        assertEquals(20L, p.getSize());
    }

    /**
     * 错误码号段逐一对齐 api-contracts §2。
     * 注：5xxx（COLD_START/MODEL_NOT_READY）仅作监控埋点，禁止写入 Result.code（C1）。
     */
    @Test
    void errorCode_numbersMatchContract() {
        assertEquals(0, ErrorCode.SUCCESS.getCode());
        assertEquals(1000, ErrorCode.PARAM_INVALID.getCode());
        assertEquals(1001, ErrorCode.UNAUTHORIZED.getCode());
        assertEquals(1003, ErrorCode.RATE_LIMITED.getCode());
        assertEquals(1004, ErrorCode.ROUTE_NOT_FOUND.getCode());
        assertEquals(1500, ErrorCode.SERVER_ERROR.getCode());
        assertEquals(2001, ErrorCode.USERNAME_EXISTS.getCode());
        assertEquals(2002, ErrorCode.PASSWORD_WRONG.getCode());
        assertEquals(2003, ErrorCode.USER_NOT_FOUND.getCode());
        assertEquals(3001, ErrorCode.PRODUCT_NOT_FOUND.getCode());
        assertEquals(3002, ErrorCode.STOCK_NOT_ENOUGH.getCode());
        assertEquals(3003, ErrorCode.STOCK_PREDEDUCT_FAIL.getCode());
        assertEquals(4001, ErrorCode.ORDER_NOT_FOUND.getCode());
        assertEquals(4002, ErrorCode.ORDER_STATUS_INVALID.getCode());
        assertEquals(4003, ErrorCode.GLOBAL_TX_ROLLBACK.getCode());
        assertEquals(5001, ErrorCode.COLD_START.getCode());
        assertEquals(5002, ErrorCode.MODEL_NOT_READY.getCode());
    }
}
