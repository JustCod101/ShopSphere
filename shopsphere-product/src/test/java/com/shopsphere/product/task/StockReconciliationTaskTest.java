package com.shopsphere.product.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shopsphere.product.entity.ProductStockEntity;
import com.shopsphere.product.mapper.ProductStockMapper;
import com.shopsphere.product.service.StockRedisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StockReconciliationTask 单测（Mockito）—— 用 Logback {@link ListAppender} 断言 ERROR 日志。
 */
class StockReconciliationTaskTest {

    private ProductStockMapper stockMapper;
    private StockRedisService stockRedisService;
    private StockReconciliationTask task;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        stockMapper = mock(ProductStockMapper.class);
        stockRedisService = mock(StockRedisService.class);
        task = new StockReconciliationTask(stockMapper, stockRedisService);

        logger = (Logger) LoggerFactory.getLogger(StockReconciliationTask.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private static ProductStockEntity stock(long productId, int stock, int locked) {
        return ProductStockEntity.builder()
                .productId(productId).stock(stock).lockedStock(locked).version(0)
                .build();
    }

    private long errorCount() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }

    private boolean errorContains(String fragment) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .anyMatch(e -> e.getFormattedMessage().contains(fragment));
    }

    @Test
    void reconcile_allMatch_noErrorLogged() {
        when(stockMapper.selectList(null))
                .thenReturn(List.of(stock(2001L, 100, 20), stock(2002L, 50, 0)));
        // T3.3：stock 列即可售库存池，Redis 直接镜像 stock（与 locked_stock 无关）
        when(stockRedisService.getAvailable(2001L)).thenReturn(100L);
        when(stockRedisService.getAvailable(2002L)).thenReturn(50L);

        task.reconcile();

        assertEquals(0, errorCount(), "全部匹配不应有 ERROR");
    }

    @Test
    void reconcile_mismatch_logsErrorWithThreeValues() {
        when(stockMapper.selectList(null)).thenReturn(List.of(stock(2001L, 100, 0)));
        when(stockRedisService.getAvailable(2001L)).thenReturn(5L);    // DB=100, Redis=5

        task.reconcile();

        assertTrue(errorContains("MISMATCH"), "差异应打 ERROR");
        assertTrue(errorContains("productId=2001"));
        assertTrue(errorContains("redis=5"));
        assertTrue(errorContains("db=100"));
    }

    @Test
    void reconcile_redisKeyMissing_logsErrorAsMissing() {
        when(stockMapper.selectList(null)).thenReturn(List.of(stock(2001L, 100, 0)));
        when(stockRedisService.getAvailable(2001L))
                .thenReturn(StockRedisService.RESULT_KEY_MISSING);

        task.reconcile();

        assertTrue(errorContains("MISMATCH"));
        assertTrue(errorContains("redis=MISSING"), "缺失计数器应标记 MISSING");
    }
}
