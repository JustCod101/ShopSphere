package com.shopsphere.product.service;

import com.shopsphere.product.config.RedisScriptConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StockRedisService 集成测试 —— 用 Testcontainers 真 Redis（embedded-redis 已弃维护）。
 *
 * <p><b>运行需 Docker</b>。覆盖 preDeduct / restore / getAvailable 各分支，
 * 并含并发安全验证 {@link #concurrentPreDeduct_neverOversells}。
 */
@Testcontainers
class StockRedisServiceTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static StringRedisTemplate redisTemplate;

    private StockRedisService service;

    @BeforeAll
    static void initTemplate() {
        RedisConfiguration cfg = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        factory.start();
        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.execute((RedisCallback<Object>) conn -> {
            conn.serverCommands().flushDb();
            return null;
        });
        RedisScriptConfig scriptConfig = new RedisScriptConfig();
        service = new StockRedisService(redisTemplate,
                scriptConfig.stockPreDeductScript(), scriptConfig.stockRestoreScript());
        service.preloadScripts();
    }

    // ---------------------- preDeduct ----------------------

    @Test
    void preDeduct_success_returnsRemaining() {
        service.initStock(1L, 100);
        assertEquals(70L, service.preDeduct(1L, 30), "扣 30 → 剩 70");
        assertEquals(70L, service.getAvailable(1L));
    }

    @Test
    void preDeduct_exactStock_succeedsToZero() {
        service.initStock(1L, 5);
        assertEquals(0L, service.preDeduct(1L, 5), "扣完恰好归零");
        assertEquals(0L, service.getAvailable(1L));
    }

    @Test
    void preDeduct_insufficient_returnsMinus1_andDoesNotMutate() {
        service.initStock(1L, 10);
        assertEquals(StockRedisService.RESULT_INSUFFICIENT, service.preDeduct(1L, 50));
        assertEquals(10L, service.getAvailable(1L), "不足时不得扣减");
    }

    @Test
    void preDeduct_keyMissing_returnsMinus2() {
        assertEquals(StockRedisService.RESULT_KEY_MISSING, service.preDeduct(999L, 1),
                "key 不存在 → -2，不自动初始化");
        assertEquals(StockRedisService.RESULT_KEY_MISSING, service.getAvailable(999L));
    }

    @Test
    void preDeduct_invalidQty_throws() {
        service.initStock(1L, 100);
        assertThrows(IllegalArgumentException.class, () -> service.preDeduct(1L, 0));
        assertThrows(IllegalArgumentException.class, () -> service.preDeduct(1L, -3));
    }

    // ---------------------- restore ----------------------

    @Test
    void restore_existingKey_incrByAndReturnsNewValue() {
        service.initStock(1L, 20);
        assertEquals(28L, service.restore(1L, 8), "回补 8 → 28");
        assertEquals(28L, service.getAvailable(1L));
    }

    @Test
    void restore_missingKey_setsAndReturnsQty() {
        assertEquals(15L, service.restore(777L, 15), "key 缺失 → SET 兜底 = 回补量");
        assertEquals(15L, service.getAvailable(777L));
    }

    // ---------------------- getAvailable ----------------------

    @Test
    void getAvailable_missing_returnsMinus2() {
        assertEquals(StockRedisService.RESULT_KEY_MISSING, service.getAvailable(888L));
    }

    @Test
    void preDeductThenRestore_roundTrip() {
        service.initStock(1L, 100);
        assertEquals(60L, service.preDeduct(1L, 40));
        assertEquals(100L, service.restore(1L, 40), "扣 40 再补 40 → 回到 100");
    }

    // ---------------------- 并发安全验证 ----------------------

    /**
     * 多线程并发 preDeduct：1000 次扣减尝试压 100 库存。
     * 验证成功扣减总数<b>正好等于</b>初始库存（既不超卖也不少卖），最终库存归零。
     * 这是 Lua 脚本原子性（GET+判断+DECRBY 不可分割）的端到端证明。
     */
    @Test
    void concurrentPreDeduct_neverOversells() throws Exception {
        long initial = 100;
        service.initStock(1L, initial);

        int threads = 50;
        int attemptsPerThread = 20;            // 1000 次尝试 >> 100 库存
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger successCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < attemptsPerThread; i++) {
                        if (service.preDeduct(1L, 1) >= 0) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "并发任务应在 30s 内完成");
        pool.shutdown();

        assertEquals(initial, successCount.get(),
                "成功扣减次数必须正好 = 初始库存：不超卖（≤100）也不少卖（≥100）");
        assertEquals(0L, service.getAvailable(1L), "最终库存归零");
    }
}
