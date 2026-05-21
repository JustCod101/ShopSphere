package com.shopsphere.product.feign;

import com.shopsphere.api.product.ProductFeignFallback;
import com.shopsphere.api.product.dto.ProductDetailDTO;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ProductFeignFallback 单测 —— 验证 fallback <b>不吞错</b>：
 * 4 个方法全部返回 {@code code != 0} 的失败 Result,{@code data} 为 null。
 * （测的是 product-api 的类,co-located 在 product 测试源,免给 product-api 加测试依赖。）
 */
class ProductFeignFallbackTest {

    private final ProductFeignFallback fallback = new ProductFeignFallback();

    @Test
    void getDetail_returnsProductNotFound_notOk() {
        Result<ProductDetailDTO> r = fallback.getDetail(2001L);
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND.getCode(), r.getCode());
        assertEquals(3001, r.getCode());
        assertNull(r.getData(), "fallback 不得返回业务数据");
    }

    @Test
    void stockTry_returnsStockPredeductFail_notOk() {
        Result<Void> r = fallback.stockTry(new StockTccDTO("x1", 9001L, null));
        assertEquals(ErrorCode.STOCK_PREDEDUCT_FAIL.getCode(), r.getCode());
        assertEquals(3003, r.getCode());
        assertNotEquals(0, r.getCode(), "fallback 必须返回失败码,不能吞错");
    }

    @Test
    void stockConfirm_returnsServerError_retryable() {
        Result<Void> r = fallback.stockConfirm(new StockTccActionDTO("x1", 9001L));
        assertEquals(ErrorCode.SERVER_ERROR.getCode(), r.getCode());
        assertEquals(1500, r.getCode());
        assertNotEquals(0, r.getCode(), "Confirm 降级必须 fail,让调用方重试");
    }

    @Test
    void stockCancel_returnsServerError_retryable() {
        Result<Void> r = fallback.stockCancel(new StockTccActionDTO("x2", 9002L));
        assertEquals(ErrorCode.SERVER_ERROR.getCode(), r.getCode());
        assertEquals(1500, r.getCode());
        assertNotEquals(0, r.getCode(), "Cancel 降级必须 fail,让调用方重试");
    }

    @Test
    void allFallbacks_handleNullArgGracefully() {
        // Sentinel 极端情况下可能传 null,fallback 不应再抛异常
        assertEquals(3003, fallback.stockTry(null).getCode());
        assertEquals(1500, fallback.stockConfirm(null).getCode());
        assertEquals(1500, fallback.stockCancel(null).getCode());
    }
}
