package com.shopsphere.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LoginAttemptService 多次失败叠加场景补强（契约 §10）。
 * <p>已有 {@link LoginAttemptServiceTest} 覆盖各单点状态；本类聚焦"序列调用"语义：
 * <ul>
 *   <li>首次失败设 TTL（且仅设一次，滚动窗口不重置）</li>
 *   <li>达阈值（count==5）设 lock + delete failKey</li>
 *   <li>超过阈值（count==6）依然 idempotent 锁定，不抛异常</li>
 * </ul>
 * 模拟 INCR 序列返回 1,2,3,4,5,6 — 验证状态机调用次数。
 */
class LoginAttemptServiceConcurrencyTest {

    private static final String USER = "alice";
    private static final String FAIL_KEY = "user:login:fail:alice";
    private static final String LOCK_KEY = "user:login:lock:alice";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LoginAttemptService svc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        LoginAttemptProperties props = new LoginAttemptProperties();
        props.setMaxFail(5);
        props.setWindowSeconds(600);
        props.setLockSeconds(1800);
        props.setKeyPrefix("user:login:fail:");
        props.setLockPrefix("user:login:lock:");

        svc = new LoginAttemptService(redis, props);
    }

    @Test
    void recordFail_sixTimes_expireOnce_lockSetOnCount5AndCount6_idempotent() {
        // INCR 序列：1,2,3,4,5,6
        when(ops.increment(FAIL_KEY)).thenReturn(1L, 2L, 3L, 4L, 5L, 6L);

        for (int i = 0; i < 6; i++) {
            final int round = i + 1;
            // 序列中任一回合都不应抛异常（idempotent lock）
            assertDoesNotThrow(() -> svc.recordFail(USER),
                    "recordFail 第 " + round + " 次不应抛异常");
        }

        // 仅在首次失败（count==1）设置滚动窗口 TTL；后续 INCR 不重置 TTL
        verify(redis, times(1)).expire(eq(FAIL_KEY), eq(Duration.ofSeconds(600)));

        // count==5 与 count==6 时各 set 一次锁定 key（>= 1 + idempotent 语义）
        verify(ops, atLeast(1))
                .set(eq(LOCK_KEY), eq("1"), eq(Duration.ofSeconds(1800)));
        verify(ops, times(2))
                .set(eq(LOCK_KEY), eq("1"), eq(Duration.ofSeconds(1800)));

        // 达阈值后清失败计数，同样调用 2 次（count=5 + count=6 都进入清理分支）
        verify(redis, times(2)).delete(FAIL_KEY);

        // 阈值前不会动 lockKey
        verify(ops, never()).set(eq(LOCK_KEY), any(), eq(Duration.ZERO));
    }
}
