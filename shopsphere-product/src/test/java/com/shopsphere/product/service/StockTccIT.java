package com.shopsphere.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopsphere.api.product.dto.StockItem;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.product.entity.ProductStockEntity;
import com.shopsphere.product.entity.StockTccLogEntity;
import com.shopsphere.product.mapper.ProductStockMapper;
import com.shopsphere.product.mapper.StockTccLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 库存 TCC 失败演练集成测试（T3.3 Part C）。
 *
 * <p>{@code @SpringBootTest} 启动 Product 上下文 + MySQL & Redis Testcontainers（禁用 Nacos/Seata）。
 * Flyway 自动建表并灌种子（商品 2001-2020，{@code stock=100}）；{@code StockWarmupRunner} 预热 Redis。
 * 各 case 用不同 productId / orderId 隔离，直接调 {@link StockTccService} 验证 8 个场景。
 *
 * <p>{@code stock} 列 = 可售库存池；Try {@code stock-=q,locked+=q}、Confirm {@code locked-=q}、
 * Cancel {@code stock+=q,locked-=q}（契约 §4.3）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "seata.enabled=false")
class StockTccIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36"))
            .withDatabaseName("shopsphere_product");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private StockTccService stockTccService;
    @Autowired
    private ProductStockMapper productStockMapper;
    @Autowired
    private StockTccLogMapper tccLogMapper;
    @Autowired
    private StockRedisService stockRedisService;

    // ---- helpers ----

    private ProductStockEntity stockOf(long productId) {
        return productStockMapper.selectById(productId);
    }

    private long logCount(long orderId, long productId, String phase) {
        return tccLogMapper.selectCount(new LambdaQueryWrapper<StockTccLogEntity>()
                .eq(StockTccLogEntity::getOrderId, orderId)
                .eq(StockTccLogEntity::getProductId, productId)
                .eq(StockTccLogEntity::getPhase, phase));
    }

    private StockTccLogEntity logRow(long orderId, long productId, String phase) {
        return tccLogMapper.selectOne(new LambdaQueryWrapper<StockTccLogEntity>()
                .eq(StockTccLogEntity::getOrderId, orderId)
                .eq(StockTccLogEntity::getProductId, productId)
                .eq(StockTccLogEntity::getPhase, phase));
    }

    private static StockTccDTO tryDto(long orderId, long productId, int qty) {
        return new StockTccDTO("xid-" + orderId, orderId, List.of(new StockItem(productId, qty)));
    }

    // ---- case 1：正常 Try ----

    @Test
    void case1_try_deductsDbAndRedisAndLogsTry() {
        long order = 90001L, product = 2001L;
        stockTccService.tryStock(tryDto(order, product, 5));

        ProductStockEntity s = stockOf(product);
        assertEquals(95, s.getStock(), "可售池 100-5");
        assertEquals(5, s.getLockedStock(), "预留 0+5");
        assertEquals(95L, stockRedisService.getAvailable(product), "Redis 镜像 stock");
        assertEquals(1, logCount(order, product, "TRY"));
    }

    // ---- case 2：多商品部分失败 → 已 Try 商品回补，DB+Redis 一致 ----

    @Test
    void case2_partialFailure_compensatesEarlierItem() {
        long order = 90002L, ok = 2002L, bad = 2003L;
        StockTccDTO dto = new StockTccDTO("xid-" + order, order,
                List.of(new StockItem(ok, 5), new StockItem(bad, 999999)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> stockTccService.tryStock(dto));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH, ex.getErrorCode());

        ProductStockEntity sOk = stockOf(ok);
        assertEquals(100, sOk.getStock(), "先成功的商品 DB 回滚");
        assertEquals(0, sOk.getLockedStock());
        assertEquals(100L, stockRedisService.getAvailable(ok), "先成功的商品 Redis 回补");
        assertEquals(0, logCount(order, ok, "TRY"), "TRY 日志随事务回滚");
    }

    // ---- case 3：Try → Confirm ----

    @Test
    void case3_confirm_releasesLockedKeepsStock() {
        long order = 90003L, product = 2004L;
        stockTccService.tryStock(tryDto(order, product, 7));
        stockTccService.confirmStock(new StockTccActionDTO("xid-" + order, order));

        ProductStockEntity s = stockOf(product);
        assertEquals(93, s.getStock(), "Confirm 不动可售池");
        assertEquals(0, s.getLockedStock(), "预留 7-7 出库");
        assertEquals(93L, stockRedisService.getAvailable(product), "Confirm 不动 Redis");
        assertEquals(1, logCount(order, product, "CONFIRM"));
    }

    // ---- case 4：Try → Cancel ----

    @Test
    void case4_cancel_restoresStockAndRedis() {
        long order = 90004L, product = 2005L;
        stockTccService.tryStock(tryDto(order, product, 8));
        stockTccService.cancelStock(new StockTccActionDTO("xid-" + order, order));

        ProductStockEntity s = stockOf(product);
        assertEquals(100, s.getStock(), "可售池回补 92+8");
        assertEquals(0, s.getLockedStock(), "预留释放");
        assertEquals(100L, stockRedisService.getAvailable(product), "Redis 显式回补");
        assertEquals(1, logCount(order, product, "CANCEL"));
    }

    // ---- case 5：空回滚（Cancel 先于 Try）----

    @Test
    void case5_emptyRollback_marksOrderLevelWithoutTouchingStock() {
        long order = 90005L;
        stockTccService.cancelStock(new StockTccActionDTO("xid-" + order, order));

        StockTccLogEntity marker = logRow(order, 0L, "CANCEL");
        assertEquals(0, marker.getState(), "订单级空回滚标记 state=0");
        assertEquals(1, logCount(order, 0L, "CANCEL"));
    }

    // ---- case 6：防悬挂（空回滚后 Try 到达 → 拒绝）----

    @Test
    void case6_antiHang_rejectsTryAfterEmptyRollback() {
        long order = 90006L, product = 2006L;
        stockTccService.cancelStock(new StockTccActionDTO("xid-" + order, order));   // 空回滚

        BusinessException ex = assertThrows(BusinessException.class,
                () -> stockTccService.tryStock(tryDto(order, product, 3)));
        assertEquals(ErrorCode.STOCK_PREDEDUCT_FAIL, ex.getErrorCode(), "防悬挂返回 3003");

        ProductStockEntity s = stockOf(product);
        assertEquals(100, s.getStock(), "被拒后库存不动");
        assertEquals(0, s.getLockedStock());
        assertEquals(100L, stockRedisService.getAvailable(product));
        assertEquals(0, logCount(order, product, "TRY"));
    }

    // ---- case 7：Confirm 幂等（重复调用只生效一次）----

    @Test
    void case7_confirmIdempotent_secondCallNoop() {
        long order = 90007L, product = 2007L;
        stockTccService.tryStock(tryDto(order, product, 6));
        StockTccActionDTO action = new StockTccActionDTO("xid-" + order, order);
        stockTccService.confirmStock(action);
        stockTccService.confirmStock(action);   // 第二次 —— 幂等 no-op

        ProductStockEntity s = stockOf(product);
        assertEquals(94, s.getStock());
        assertEquals(0, s.getLockedStock(), "locked 未被重复扣减");
        assertEquals(1, logCount(order, product, "CONFIRM"), "CONFIRM 日志仅一行");
    }

    // ---- case 8：已支付订单取消（Try → Confirm → Cancel）→ 逆向补偿回补已出库库存（T3.5）----

    @Test
    void case8_cancelAfterConfirm_reverseCompensatesPaidStock() {
        long order = 90008L, product = 2008L;
        int qty = 9;

        // Try：可售池 100-9=91，预留 0+9=9
        stockTccService.tryStock(tryDto(order, product, qty));
        // Confirm（订单已支付）：真实出库，预留 9-9=0，可售池仍 91
        stockTccService.confirmStock(new StockTccActionDTO("xid-" + order, order));
        ProductStockEntity afterConfirm = stockOf(product);
        assertEquals(91, afterConfirm.getStock(), "Confirm 后可售池 100-9");
        assertEquals(0, afterConfirm.getLockedStock(), "Confirm 后预留归零");

        // Cancel：检测到 CONFIRM 日志 → 走 cancelConfirmed，把已出库数量退回可售池
        stockTccService.cancelStock(new StockTccActionDTO("xid-" + order, order));

        ProductStockEntity s = stockOf(product);
        assertEquals(100, s.getStock(), "PAID 取消逆向补偿：可售池 91+9 回到原始 100");
        assertEquals(0, s.getLockedStock(), "locked 不被 cancelConfirmed 触碰，仍为 0");
        assertEquals(100L, stockRedisService.getAvailable(product), "Redis 镜像回补到 100");
        assertEquals(1, logCount(order, product, "CANCEL"), "CANCEL 日志恰一行");
    }
}
