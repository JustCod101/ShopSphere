package com.shopsphere.product.task;

import com.shopsphere.product.entity.ProductStockEntity;
import com.shopsphere.product.mapper.ProductStockMapper;
import com.shopsphere.product.service.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存对账任务（T2.3）。
 *
 * <p>每 5 分钟比对 Redis {@code stock:product:{id}} 与 DB 可售库存池 {@code stock}
 * （T3.3：stock 即可售量，与 Redis 同步语义）。差异打 ERROR 日志(含 productId / redis / db
 * 三值),<b>不自动修复</b> —— 库存漂移涉及资金与超卖风险,须人工介入定位根因。
 *
 * <p>间隔可配 {@code product.stock.reconcile-interval-ms}(默认 300000)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReconciliationTask {

    private final ProductStockMapper stockMapper;
    private final StockRedisService stockRedisService;

    @Scheduled(fixedDelayString = "${product.stock.reconcile-interval-ms:300000}")
    public void reconcile() {
        List<ProductStockEntity> all = stockMapper.selectList(null);
        int mismatch = 0;
        for (ProductStockEntity s : all) {
            // stock 列即可售库存池（契约 §4.3）；与 Redis 直接比对
            long db = s.getStock();
            long redis = stockRedisService.getAvailable(s.getProductId());
            if (redis != db) {
                mismatch++;
                log.error("stock reconcile MISMATCH: productId={}, redis={}, db={}",
                        s.getProductId(),
                        redis == StockRedisService.RESULT_KEY_MISSING ? "MISSING" : redis,
                        db);
            }
        }
        if (mismatch == 0) {
            log.info("stock reconcile ok: {} products checked", all.size());
        } else {
            log.error("stock reconcile finished: {}/{} mismatches need manual intervention",
                    mismatch, all.size());
        }
    }
}
