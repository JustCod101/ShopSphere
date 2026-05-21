package com.shopsphere.product.service;

import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.product.dto.ProductDetailVO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductCacheService 单测（纯 Mockito，不连真 Redis）。
 *
 * <p>覆盖 cache-aside 全分支：命中（静态+stock）/ 空值标记 / 防击穿抢锁成功 / 抢锁失败重试 /
 * 重试耗尽降级 / stock key 单独 miss 补写 / 延迟双删。
 */
class ProductCacheServiceTest {

    private static final long ID = 2001L;
    private static final String DETAIL_KEY = "product:detail:2001";
    private static final String STOCK_KEY = "stock:product:2001";

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> stringValueOps = mock(ValueOperations.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ProductCacheService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        service = new ProductCacheService(redisTemplate, stringRedisTemplate,
                redissonClient, taskScheduler, meterRegistry);
        service.initCounters();
    }

    private static ProductDetailVO staticVo() {
        return ProductDetailVO.builder()
                .id(ID).name("深入理解 Java 虚拟机").categoryId(1005L)
                .price(new BigDecimal("89.00")).mainImage("img").description("desc")
                .status(1).createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private static ProductDetailVO fullVo(int stock) {
        ProductDetailVO vo = staticVo();
        vo.setStock(stock);
        return vo;
    }

    @SuppressWarnings("unchecked")
    private Function<Long, ProductDetailVO> loaderReturning(ProductDetailVO vo) {
        Function<Long, ProductDetailVO> loader = mock(Function.class);
        when(loader.apply(anyLong())).thenReturn(vo);
        return loader;
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    // ---------------------- 缓存命中 ----------------------

    @Test
    void cacheHit_assemblesStaticPlusStockFromStockKey() {
        when(valueOps.get(DETAIL_KEY)).thenReturn(staticVo());
        when(stringValueOps.get(STOCK_KEY)).thenReturn("55");
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(999));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(ID, vo.getId());
        assertEquals(55, vo.getStock(), "stock 取自 stock key，非详情缓存");
        verify(loader, never()).apply(any());
        assertEquals(1.0, counter("product.detail.cache.hit"));
    }

    @Test
    void cacheHit_nullMarker_throwsProductNotFound() {
        when(valueOps.get(DETAIL_KEY)).thenReturn(ProductCacheService.NULL_MARKER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getOrLoadDetail(ID, loaderReturning(null)));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());
        assertEquals(1.0, counter("product.detail.cache.null_hit"));
    }

    @Test
    void cacheHit_stockKeyMiss_fallsBackToDbLoaderAndBackfills() {
        when(valueOps.get(DETAIL_KEY)).thenReturn(staticVo());
        when(stringValueOps.get(STOCK_KEY)).thenReturn(null);
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(33));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(33, vo.getStock());
        verify(loader).apply(ID);
        // 回查后补写 stock key
        verify(stringValueOps).set(eq(STOCK_KEY), eq("33"), any(Duration.class));
    }

    @Test
    void cacheHit_corruptStockValue_fallsBackToDbLoader() {
        when(valueOps.get(DETAIL_KEY)).thenReturn(staticVo());
        when(stringValueOps.get(STOCK_KEY)).thenReturn("not-a-number");
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(44));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(44, vo.getStock(), "脏数据忽略，回查 DB");
        verify(stringValueOps).set(eq(STOCK_KEY), eq("44"), any(Duration.class));
    }

    // ---------------------- miss + 防击穿抢锁 ----------------------

    @Test
    void miss_lockAcquired_loadsDbAndCachesBothKeys() throws InterruptedException {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null);                 // 入口 + 二次校验都 miss
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stringValueOps.get(STOCK_KEY)).thenReturn("100");
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(100));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(100, vo.getStock());
        verify(valueOps).set(eq(DETAIL_KEY), any(ProductDetailVO.class), any(Duration.class));
        verify(stringValueOps).set(eq(STOCK_KEY), eq("100"), any(Duration.class));
        verify(lock).unlock();
        assertEquals(1.0, counter("product.detail.cache.miss"));
    }

    @Test
    void miss_lockAcquired_dbReturnsNull_cachesNullMarkerAndThrows() throws InterruptedException {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getOrLoadDetail(ID, loaderReturning(null)));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());

        // 空值标记 TTL = 120s
        ArgumentCaptor<Duration> ttlCap = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(DETAIL_KEY), eq(ProductCacheService.NULL_MARKER), ttlCap.capture());
        assertEquals(120, ttlCap.getValue().getSeconds());
        verify(lock).unlock();
    }

    @Test
    void miss_lockAcquired_recheckHit_skipsDbLoad() throws InterruptedException {
        // 入口 miss，等锁期间别的线程已回填 → 二次校验命中
        when(valueOps.get(DETAIL_KEY)).thenReturn(null).thenReturn(staticVo());
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stringValueOps.get(STOCK_KEY)).thenReturn("66");
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(999));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(66, vo.getStock());
        verify(loader, never()).apply(any());      // 二次校验命中，不查 DB
        verify(lock).unlock();
    }

    // ---------------------- miss + 抢锁失败 ----------------------

    @Test
    void miss_lockNotAcquired_retrySucceeds() throws InterruptedException {
        // 入口 miss → 抢锁失败 → 重试第 1 次仍 miss → 第 2 次命中
        when(valueOps.get(DETAIL_KEY))
                .thenReturn(null).thenReturn(null).thenReturn(staticVo());
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        when(stringValueOps.get(STOCK_KEY)).thenReturn("12");

        ProductDetailVO vo = service.getOrLoadDetail(ID, loaderReturning(fullVo(999)));

        assertEquals(12, vo.getStock(), "重试读到缓存");
        assertEquals(0.0, counter("product.detail.cache.db_fallback"));
    }

    @Test
    void miss_lockNotAcquired_retryExhausted_fallsBackToDb() throws InterruptedException {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null);                 // 入口 + 3 次重试都 miss
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(88));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(88, vo.getStock(), "重试耗尽，降级直查 DB");
        verify(loader).apply(ID);
        assertEquals(1.0, counter("product.detail.cache.db_fallback"));
        // 降级路径不回填缓存
        verify(valueOps, never()).set(eq(DETAIL_KEY), any(), any(Duration.class));
    }

    @Test
    void miss_lockNotAcquired_retryExhausted_dbNull_throws() throws InterruptedException {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getOrLoadDetail(ID, loaderReturning(null)));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());
    }

    // ---------------------- 延迟双删 ----------------------

    @Test
    void invalidate_deletesBothKeysSyncAndSchedulesSecondDelete() {
        service.invalidate(ID);

        // 同步删一次
        verify(redisTemplate).delete(DETAIL_KEY);
        verify(stringRedisTemplate).delete(STOCK_KEY);
        assertEquals(1.0, counter("product.detail.cache.invalidate"));

        // 调度第二删
        ArgumentCaptor<Runnable> taskCap = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> whenCap = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(taskCap.capture(), whenCap.capture());
        assertTrue(whenCap.getValue().isAfter(Instant.now().plusMillis(200)),
                "第二删延迟应在 ~500ms 后");

        // 执行调度任务 → 再删一次（累计 2 次）
        taskCap.getValue().run();
        verify(redisTemplate, times(2)).delete(DETAIL_KEY);
        verify(stringRedisTemplate, times(2)).delete(STOCK_KEY);
    }
}
