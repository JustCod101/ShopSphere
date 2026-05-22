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
    void run_loadsStockPoolForEveryProduct() {
        // T3.3：stock 列即可售库存池，Redis 直接镜像 stock（locked_stock 不参与）
        when(stockMapper.selectList(null)).thenReturn(List.of(
                stock(2001L, 95, 5),       // 可售 95
                stock(2002L, 50, 0)));     // 可售 50

        runner.run();

        verify(stockRedisService).initStock(2001L, 95L);
        verify(stockRedisService).initStock(2002L, 50L);
    }
}
