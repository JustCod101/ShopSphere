package com.shopsphere.user.service;

import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 防爆破计数 + 锁定逻辑单测（Mockito StringRedisTemplate）。
 * <p>覆盖：未锁定放行；首次失败设 TTL；达阈值设锁定 + 清计数；锁定态抛 2002 + 锁定 message。
 */
class LoginAttemptServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LoginAttemptProperties props;
    private LoginAttemptService svc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        props = new LoginAttemptProperties();
        props.setMaxFail(5);
        props.setWindowSeconds(600);
        props.setLockSeconds(1800);
        props.setKeyPrefix("user:login:fail:");
        props.setLockPrefix("user:login:lock:");

        svc = new LoginAttemptService(redis, props);
    }

    @Test
    void ensureNotLocked_keyAbsent_passesThrough() {
        when(redis.hasKey("user:login:lock:alice")).thenReturn(false);
        assertDoesNotThrow(() -> svc.ensureNotLocked("alice"));
    }

    @Test
    void ensureNotLocked_keyPresent_throwsPasswordWrong_withLockMessage() {
        when(redis.hasKey("user:login:lock:alice")).thenReturn(true);
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.ensureNotLocked("alice"));
        assertEquals(ErrorCode.PASSWORD_WRONG, ex.getErrorCode());
        assertEquals(LoginAttemptService.LOCK_MESSAGE, ex.getMessage(),
                "锁定 message 须与 PASSWORD_WRONG 默认 message 区分（防枚举）");
    }

    @Test
    void recordFail_firstFailure_setsWindowTtl() {
        when(ops.increment("user:login:fail:alice")).thenReturn(1L);
        svc.recordFail("alice");
        verify(redis).expire("user:login:fail:alice", Duration.ofSeconds(600));
        verify(ops, never()).set(eq("user:login:lock:alice"), any(), any(Duration.class));
    }

    @Test
    void recordFail_underThreshold_noLock_noTtlReset() {
        when(ops.increment("user:login:fail:alice")).thenReturn(3L);
        svc.recordFail("alice");
        verify(redis, never()).expire(any(), any(Duration.class));
        verify(ops, never()).set(eq("user:login:lock:alice"), any(), any(Duration.class));
    }

    @Test
    void recordFail_atThreshold_setsLock_clearsCounter() {
        when(ops.increment("user:login:fail:alice")).thenReturn(5L);
        svc.recordFail("alice");
        verify(ops).set("user:login:lock:alice", "1", Duration.ofSeconds(1800));
        verify(redis).delete("user:login:fail:alice");
    }

    @Test
    void recordFail_redisReturnsNull_swallows_logsWarn() {
        when(ops.increment("user:login:fail:alice")).thenReturn(null);
        assertDoesNotThrow(() -> svc.recordFail("alice"));
        verify(redis, never()).expire(any(), any(Duration.class));
    }

    @Test
    void clearOnSuccess_deletesBothKeys() {
        svc.clearOnSuccess("alice");
        verify(redis).delete(java.util.List.of("user:login:fail:alice", "user:login:lock:alice"));
    }
}
