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
 * <p>T2.3 后 stock 由 {@link StockRedisService} 常驻计数器提供，本类不再读写 stock key；
 * 故 stock 相关用例改为 mock {@code stockRedisService.getAvailable}，{@code invalidate} 只删详情 key。
 */
class ProductCacheServiceTest {

    private static final long ID = 2001L;
    private static final String DETAIL_KEY = "product:detail:2001";

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final StockRedisService stockRedisService = mock(StockRedisService.class);

    private ProductCacheService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        service = new ProductCacheService(redisTemplate, redissonClient,
                taskScheduler, meterRegistry, stockRedisService);
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
    void cacheHit_assemblesStaticPlusStockFromCounter() {
        when(valueOps.get(DETAIL_KEY)).thenReturn(staticVo());
        when(stockRedisService.getAvailable(ID)).thenReturn(55L);
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(999));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(ID, vo.getId());
        assertEquals(55, vo.getStock(), "stock 取自 StockRedisService 常驻计数器");
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
    void cacheHit_stockCounterMissing_fallsBackToDbSellable() {
        when(valueOps.get(DETAIL_KEY)).thenReturn(staticVo());
        when(stockRedisService.getAvailable(ID))
                .thenReturn(StockRedisService.RESULT_KEY_MISSING);
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(33));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(33, vo.getStock(), "计数器缺失 → 用 DB sellable 兜底展示");
        verify(loader).apply(ID);
        // 缓存层不回填 stock 计数器（库存计数器不由缓存层重建）
        verify(stockRedisService, never()).initStock(anyLong(), anyLong());
    }

    // ---------------------- miss + 防击穿抢锁 ----------------------

    @Test
    void miss_lockAcquired_loadsDbAndCachesDetailOnly() throws InterruptedException {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stockRedisService.getAvailable(ID)).thenReturn(100L);
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(100));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(100, vo.getStock());
        // 只缓存详情 key；stock 计数器不由缓存层写
        verify(valueOps).set(eq(DETAIL_KEY), any(ProductDetailVO.class), any(Duration.class));
        verify(stockRedisService, never()).initStock(anyLong(), anyLong());
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

        ArgumentCaptor<Duration> ttlCap = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(DETAIL_KEY), eq(ProductCacheService.NULL_MARKER), ttlCap.capture());
        assertEquals(120, ttlCap.getValue().getSeconds());
        verify(lock).unlock();
    }

    @Test
    void miss_lockAcquired_recheckHit_skipsDbLoad() throws InterruptedException {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null).thenReturn(staticVo());
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stockRedisService.getAvailable(ID)).thenReturn(66L);
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(999));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(66, vo.getStock());
        verify(loader, never()).apply(any());
        verify(lock).unlock();
    }

    // ---------------------- miss + 抢锁失败 ----------------------

    @Test
    void miss_lockNotAcquired_retrySucceeds() throws InterruptedException {
        when(valueOps.get(DETAIL_KEY))
                .thenReturn(null).thenReturn(null).thenReturn(staticVo());
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        when(stockRedisService.getAvailable(ID)).thenReturn(12L);

        ProductDetailVO vo = service.getOrLoadDetail(ID, loaderReturning(fullVo(999)));

        assertEquals(12, vo.getStock(), "重试读到缓存");
        assertEquals(0.0, counter("product.detail.cache.db_fallback"));
    }

    @Test
    void miss_lockNotAcquired_retryExhausted_fallsBackToDb() throws InterruptedException {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        Function<Long, ProductDetailVO> loader = loaderReturning(fullVo(88));

        ProductDetailVO vo = service.getOrLoadDetail(ID, loader);

        assertEquals(88, vo.getStock(), "重试耗尽，降级直查 DB");
        verify(loader).apply(ID);
        assertEquals(1.0, counter("product.detail.cache.db_fallback"));
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

    // ---------------------- 延迟双删（只删详情 key） ----------------------

    @Test
    void invalidate_deletesDetailKeyOnly_syncAndScheduledSecondDelete() {
        service.invalidate(ID);

        // 同步删详情 key 一次
        verify(redisTemplate).delete(DETAIL_KEY);
        assertEquals(1.0, counter("product.detail.cache.invalidate"));

        // 调度第二删
        ArgumentCaptor<Runnable> taskCap = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> whenCap = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(taskCap.capture(), whenCap.capture());
        assertTrue(whenCap.getValue().isAfter(Instant.now().plusMillis(200)),
                "第二删延迟应在 ~500ms 后");

        // 执行调度任务 → 详情 key 再删一次（累计 2 次）
        taskCap.getValue().run();
        verify(redisTemplate, times(2)).delete(DETAIL_KEY);

        // stock key 是常驻计数器，invalidate 不应触碰
        verify(stockRedisService, never()).initStock(anyLong(), anyLong());
    }
}
