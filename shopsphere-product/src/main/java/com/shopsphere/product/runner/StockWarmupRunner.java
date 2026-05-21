package com.shopsphere.product.runner;

import com.shopsphere.product.entity.ProductStockEntity;
import com.shopsphere.product.mapper.ProductStockMapper;
import com.shopsphere.product.service.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动库存预热（T2.3）。
 *
 * <p>从 {@code t_product_stock} 全量读出,把可售量 {@code stock - locked_stock} SET 进
 * Redis {@code stock:product:{id}}。<b>SET 覆盖</b>:每次启动强制以 DB 为准重同步
 * （契约 §4.3 下 TCC-Try 同步改 DB+Redis,DB 是权威值）。
 *
 * <p>负值（{@code locked_stock > stock}，数据异常）兜底为 0。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWarmupRunner implements CommandLineRunner {

    private final ProductStockMapper stockMapper;
    private final StockRedisService stockRedisService;

    @Override
    public void run(String... args) {
        List<ProductStockEntity> all = stockMapper.selectList(null);
        for (ProductStockEntity s : all) {
            long available = Math.max(0L, (long) s.getStock() - s.getLockedStock());
            stockRedisService.initStock(s.getProductId(), available);
        }
        log.info("stock warmup done: {} products loaded into Redis", all.size());
    }
}
