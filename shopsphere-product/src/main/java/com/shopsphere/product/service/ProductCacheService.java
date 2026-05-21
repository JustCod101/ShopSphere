package com.shopsphere.product.service;

import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.product.dto.ProductDetailVO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 商品详情缓存外观（T2.2）—— Cache-Aside + 防穿透 / 防击穿 / 防雪崩 + 延迟双删。
 *
 * <h3>键设计</h3>
 * <ul>
 *   <li>{@code product:detail:{id}} —— 静态字段缓存（不含 stock，避免与库存不一致）</li>
 *   <li>{@code stock:product:{id}}  —— 可售量纯数字（T2.3 Lua 接管，本期 miss 路径首写 baseline）</li>
 *   <li>{@code lock:product:detail:{id}} —— 防击穿 Redisson 互斥锁</li>
 * </ul>
 *
 * <h3>三防</h3>
 * <ul>
 *   <li><b>防穿透</b>：DB 查不到 → 缓存 {@value #NULL_MARKER}，TTL {@value #NULL_TTL_SECONDS}s</li>
 *   <li><b>防击穿</b>：缓存 miss → Redisson tryLock（wait 100ms / lease 3s）；
 *       拿不到锁 → 间隔 100ms 重读缓存 3 次 → 仍无则降级直查 DB</li>
 *   <li><b>防雪崩</b>：TTL = 30min + 0~300s 随机抖动，避免大批 key 同刻失效</li>
 * </ul>
 *
 * <h3>失效模式（fail-open）</h3>
 * Redis / Redisson 不可用时由调用方上抛异常，全局兜底；本类不吞异常，命中率埋点亦如实计数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCacheService {

    static final String DETAIL_KEY_PREFIX = "product:detail:";
    static final String STOCK_KEY_PREFIX = "stock:product:";
    static final String LOCK_KEY_PREFIX = "lock:product:detail:";
    /** 防穿透空值标记；与正常 JSON 不会撞（正常值是对象，标记是裸字符串）。 */
    static final String NULL_MARKER = "__NULL__";

    static final Duration BASE_TTL = Duration.ofMinutes(30);
    static final int JITTER_SECONDS = 300;
    static final int NULL_TTL_SECONDS = 120;
    static final long LOCK_WAIT_MS = 100;
    static final long LOCK_LEASE_MS = 3000;
    static final int RETRY_MAX = 3;
    static final long RETRY_SLEEP_MS = 100;
    static final long DOUBLE_DEL_DELAY_MS = 500;

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final TaskScheduler cacheTaskScheduler;
    private final MeterRegistry meterRegistry;

    private Counter hitCounter;
    private Counter missCounter;
    private Counter nullHitCounter;
    private Counter dbFallbackCounter;
    private Counter invalidateCounter;

    @PostConstruct
    void initCounters() {
        Tags tags = Tags.of("service", "shopsphere-product");
        hitCounter = Counter.builder("product.detail.cache.hit")
                .description("详情缓存命中（含 stock 二次读）").tags(tags).register(meterRegistry);
        missCounter = Counter.builder("product.detail.cache.miss")
                .description("详情缓存未命中").tags(tags).register(meterRegistry);
        nullHitCounter = Counter.builder("product.detail.cache.null_hit")
                .description("命中空值标记（防穿透生效）").tags(tags).register(meterRegistry);
        dbFallbackCounter = Counter.builder("product.detail.cache.db_fallback")
                .description("锁竞争失败后降级直查 DB").tags(tags).register(meterRegistry);
        invalidateCounter = Counter.builder("product.detail.cache.invalidate")
                .description("主动失效（双删）次数").tags(tags).register(meterRegistry);
    }

    /**
     * Cache-Aside 入口。
     *
     * @param id       商品 id
     * @param dbLoader DB 加载器，返回 {@code null} 表示商品不存在（触发空值标记 + 3001）
     * @return 完整详情 VO（静态字段来自缓存，stock 来自 stock key 实时读）
     * @throws BusinessException PRODUCT_NOT_FOUND(3001) —— 商品不存在或命中空值标记
     */
    public ProductDetailVO getOrLoadDetail(Long id, Function<Long, ProductDetailVO> dbLoader) {
        Object cached = redisTemplate.opsForValue().get(detailKey(id));
        if (cached != null) {
            return assembleFromCache(id, cached, dbLoader, true);
        }
        missCounter.increment();
        return loadWithBreaker(id, dbLoader);
    }

    /** 防击穿：抢锁查 DB；抢不到则重试读缓存，最终降级直查 DB。 */
    private ProductDetailVO loadWithBreaker(Long id, Function<Long, ProductDetailVO> dbLoader) {
        RLock lock = redissonClient.getLock(lockKey(id));
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVER_ERROR);
        }

        if (acquired) {
            try {
                // 二次校验：等锁期间可能已有线程回填缓存
                Object recheck = redisTemplate.opsForValue().get(detailKey(id));
                if (recheck != null) {
                    return assembleFromCache(id, recheck, dbLoader, false);
                }
                ProductDetailVO loaded = dbLoader.apply(id);
                if (loaded == null) {
                    cacheNullMarker(id);
                    throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
                }
                cacheStatic(id, loaded);
                cacheStock(id, loaded.getStock());
                return loaded;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        // 抢锁失败：间隔重读缓存，等持锁线程回填
        for (int i = 0; i < RETRY_MAX; i++) {
            try {
                Thread.sleep(RETRY_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Object recheck = redisTemplate.opsForValue().get(detailKey(id));
            if (recheck != null) {
                return assembleFromCache(id, recheck, dbLoader, false);
            }
        }

        // 重试耗尽：降级直查 DB，不回填缓存（避免与持锁线程写竞争）
        dbFallbackCounter.increment();
        log.warn("cache breaker fallback to DB, productId={}", id);
        ProductDetailVO loaded = dbLoader.apply(id);
        if (loaded == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return loaded;
    }

    /**
     * 把缓存命中对象组装成完整 VO（补 stock）。
     *
     * @param countAsHit 是否计入 hit 埋点（首次入口命中计；breaker 内二次校验命中不重复计）
     */
    private ProductDetailVO assembleFromCache(Long id, Object cached,
                                             Function<Long, ProductDetailVO> dbLoader,
                                             boolean countAsHit) {
        if (NULL_MARKER.equals(cached)) {
            nullHitCounter.increment();
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        if (countAsHit) {
            hitCounter.increment();
        }
        ProductDetailVO vo = (ProductDetailVO) cached;
        vo.setStock(resolveStock(id, dbLoader));
        return vo;
    }

    /** 读 stock key；miss 则回查 DB 补写（T2.2 baseline 写入路径）。 */
    private Integer resolveStock(Long id, Function<Long, ProductDetailVO> dbLoader) {
        String cached = stringRedisTemplate.opsForValue().get(stockKey(id));
        if (cached != null) {
            try {
                return Integer.parseInt(cached);
            } catch (NumberFormatException e) {
                log.warn("corrupt stock value '{}' for productId={}, reloading from DB", cached, id);
            }
        }
        ProductDetailVO loaded = dbLoader.apply(id);
        if (loaded == null) {
            return 0;
        }
        cacheStock(id, loaded.getStock());
        return loaded.getStock();
    }

    /** 缓存静态字段（stock 置 null，库存独立 key 维护）。 */
    private void cacheStatic(Long id, ProductDetailVO vo) {
        ProductDetailVO staticOnly = ProductDetailVO.builder()
                .id(vo.getId())
                .name(vo.getName())
                .categoryId(vo.getCategoryId())
                .price(vo.getPrice())
                .mainImage(vo.getMainImage())
                .description(vo.getDescription())
                .status(vo.getStatus())
                .createdAt(vo.getCreatedAt())
                .build();
        redisTemplate.opsForValue().set(detailKey(id), staticOnly, randomTtl());
    }

    private void cacheStock(Long id, Integer sellable) {
        if (sellable == null) {
            return;
        }
        stringRedisTemplate.opsForValue().set(stockKey(id), sellable.toString(), randomTtl());
    }

    private void cacheNullMarker(Long id) {
        redisTemplate.opsForValue()
                .set(detailKey(id), NULL_MARKER, Duration.ofSeconds(NULL_TTL_SECONDS));
    }

    /**
     * 主动失效（延迟双删）：同步删一次 → DB 操作期间可能有并发回填 → 500ms 后异步再删一次。
     * <p>T2.2 暂无业务写路径调用此方法；T2.x 后台 update/delete 落地时接入。
     */
    public void invalidate(Long id) {
        String detailKey = detailKey(id);
        String stockKey = stockKey(id);
        redisTemplate.delete(detailKey);
        stringRedisTemplate.delete(stockKey);
        cacheTaskScheduler.schedule(() -> {
            redisTemplate.delete(detailKey);
            stringRedisTemplate.delete(stockKey);
        }, Instant.now().plusMillis(DOUBLE_DEL_DELAY_MS));
        invalidateCounter.increment();
    }

    /** TTL = 基准 30min + 0~300s 随机抖动（防雪崩）。 */
    private Duration randomTtl() {
        int jitter = ThreadLocalRandom.current().nextInt(0, JITTER_SECONDS + 1);
        return BASE_TTL.plusSeconds(jitter);
    }

    private String detailKey(Long id) {
        return DETAIL_KEY_PREFIX + id;
    }

    private String stockKey(Long id) {
        return STOCK_KEY_PREFIX + id;
    }

    private String lockKey(Long id) {
        return LOCK_KEY_PREFIX + id;
    }
}
