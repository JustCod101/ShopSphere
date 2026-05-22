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
 * 启动库存预热（T2.3；T3.3 修正库存语义）。
 *
 * <p>从 {@code t_product_stock} 全量读出,把可售库存池 {@code stock} SET 进
 * Redis {@code stock:product:{id}}。<b>SET 覆盖</b>:每次启动强制以 DB 为准重同步
 * （契约 §4.3 下 TCC 同步改 DB+Redis,DB 是权威值）。
 *
 * <p>{@code stock} 即可售量,Redis 直接镜像之;真实总量 = {@code stock + locked_stock}。
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
            // stock 列即可售库存池（契约 §4.3）；Redis 直接镜像 stock
            stockRedisService.initStock(s.getProductId(), s.getStock());
        }
        log.info("stock warmup done: {} products loaded into Redis", all.size());
    }
}
