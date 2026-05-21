package com.shopsphere.product.controller;

import com.shopsphere.api.product.dto.ProductDetailDTO;
import com.shopsphere.api.product.dto.StockItem;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.Result;
import com.shopsphere.product.service.ProductService;
import com.shopsphere.product.service.StockTccService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InternalProductController（T2.4 骨架）单测：4 端点委托正确;失败由 service 抛
 * BusinessException,经全局异常处理转 Result（此处直接断言异常）。
 */
class InternalProductControllerTest {

    private ProductService productService;
    private StockTccService stockTccService;
    private InternalProductController controller;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        stockTccService = mock(StockTccService.class);
        controller = new InternalProductController(productService, stockTccService);
    }

    @Test
    void getDetail_delegatesToServiceAndReturnsDTO() {
        ProductDetailDTO dto = ProductDetailDTO.builder()
                .id(2001L).name("MacBook").price(new BigDecimal("14999.00")).stock(80).build();
        when(productService.getDetailForInternal(2001L)).thenReturn(dto);

        Result<ProductDetailDTO> r = controller.getDetail(2001L);

        assertEquals(0, r.getCode());
        assertEquals(2001L, r.getData().getId());
        assertEquals(80, r.getData().getStock());
        verify(productService).getDetailForInternal(2001L);
    }

    @Test
    void stockTry_delegatesAndReturnsOk() {
        StockTccDTO dto = new StockTccDTO("x1", 9001L,
                List.of(new StockItem(2001L, 3)));

        Result<Void> r = controller.stockTry(dto);

        assertEquals(0, r.getCode());
        verify(stockTccService).tryStock(dto);
    }

    @Test
    void stockTry_propagatesStockNotEnough() {
        StockTccDTO dto = new StockTccDTO("x1", 9001L,
                List.of(new StockItem(2001L, 999999)));
        doThrow(new BusinessException(ErrorCode.STOCK_NOT_ENOUGH))
                .when(stockTccService).tryStock(any());

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.stockTry(dto));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH, ex.getErrorCode());
        assertEquals(3002, ex.getErrorCode().getCode());
    }

    @Test
    void stockConfirm_delegatesAndReturnsOk() {
        StockTccActionDTO dto = new StockTccActionDTO("x1", 9001L);

        Result<Void> r = controller.stockConfirm(dto);

        assertEquals(0, r.getCode());
        verify(stockTccService).confirmStock(dto);
    }

    @Test
    void stockCancel_delegatesAndReturnsOk() {
        StockTccActionDTO dto = new StockTccActionDTO("x2", 9002L);

        Result<Void> r = controller.stockCancel(dto);

        assertEquals(0, r.getCode());
        verify(stockTccService).cancelStock(dto);
    }
}
