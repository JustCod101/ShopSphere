package com.shopsphere.product.runner;

import com.shopsphere.product.entity.ProductStockEntity;
import com.shopsphere.product.mapper.ProductStockMapper;
import com.shopsphere.product.service.StockRedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockWarmupRunner 单测（Mockito）—— 验证启动从 DB 全量预热 Redis。
 */
class StockWarmupRunnerTest {

    private ProductStockMapper stockMapper;
    private StockRedisService stockRedisService;
    private StockWarmupRunner runner;

    @BeforeEach
    void setUp() {
        stockMapper = mock(ProductStockMapper.class);
        stockRedisService = mock(StockRedisService.class);
        runner = new StockWarmupRunner(stockMapper, stockRedisService);
    }

    private static ProductStockEntity stock(long productId, int stock, int locked) {
        return ProductStockEntity.builder()
                .productId(productId).stock(stock).lockedStock(locked).version(0)
                .build();
    }

    @Test
    void run_loadsSellableStockForEveryProduct() {
        when(stockMapper.selectList(null)).thenReturn(List.of(
                stock(2001L, 100, 20),     // sellable 80
                stock(2002L, 50, 0)));     // sellable 50

        runner.run();

        verify(stockRedisService).initStock(2001L, 80L);
        verify(stockRedisService).initStock(2002L, 50L);
    }

    @Test
    void run_clampsNegativeSellableToZero() {
        // locked > stock 属数据异常，可售量兜底 0
        when(stockMapper.selectList(null)).thenReturn(List.of(stock(2001L, 5, 10)));

        runner.run();

        verify(stockRedisService).initStock(2001L, 0L);
    }
}
