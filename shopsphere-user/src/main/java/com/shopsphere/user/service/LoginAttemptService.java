package com.shopsphere.user.service;

import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录防爆破（契约 §10，Phase 1）。基于 Redis 滚动窗口失败计数 + 命中阈值后锁定。
 *
 * <h3>调用顺序铁律（{@link UserServiceImpl#login}）</h3>
 * <pre>
 *   ensureNotLocked(username)         ← 入口先拦锁定态
 *   查 user                           ← 用户不存在亦走 recordFail（防枚举有效账号）
 *   比对密码                          ← 不一致走 recordFail
 *   成功：clearOnSuccess(username)
 * </pre>
 *
 * <h3>错误码契约</h3>
 * <ul>
 *   <li>用户不存在 → 2003 USER_NOT_FOUND（§6.1）</li>
 *   <li>密码错 → 2002 PASSWORD_WRONG（§6.1）</li>
 *   <li>命中锁定 → 2002 + message="账号已临时锁定，请稍后再试"
 *       （与限流复用同号，message 区分；不变更 ErrorCode 枚举）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    /** 锁定时统一对外提示，刻意与 PASSWORD_WRONG 同号同观感，防止账号枚举区分。 */
    static final String LOCK_MESSAGE = "账号已临时锁定，请稍后再试";

    private final StringRedisTemplate redis;
    private final LoginAttemptProperties props;

    /** 锁定态下抛 PASSWORD_WRONG + 自定义 message；否则放行。 */
    public void ensureNotLocked(String username) {
        if (Boolean.TRUE.equals(redis.hasKey(lockKey(username)))) {
            throw new BusinessException(ErrorCode.PASSWORD_WRONG, LOCK_MESSAGE);
        }
    }

    /**
     * 记录一次失败。首次写入时设置窗口 TTL；达到阈值则锁定并清失败计数。
     * <p>"用户不存在"也调本方法（防枚举）。
     *
     * <p><b>Redis 不可用降级策略（fail-open）</b>：{@code INCR} 返回 null 时跳过计数与锁定，
     * 不阻断登录流程。代价是 Redis 故障窗口内防爆破临时失效；选择 open 是出于"业务可用性
     * 高于辅助安全控制"的工程权衡。运维需对 ERROR 日志告警 + Redis 哨兵/集群保高可用。
     * 若产品要 fail-closed（停服优先于无防护），将此处改为抛 {@code BusinessException(SERVER_ERROR)}。
     */
    public void recordFail(String username) {
        String key = failKey(username);
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            log.error("防爆破计数失败：Redis INCR 返回 null（连接异常？）username={} —— 当前请求未计数，建议告警", username);
            return;
        }
        if (count == 1L) {
            // 首次失败：设置窗口 TTL（后续 INCR 不重置，保持滚动窗口语义）
            redis.expire(key, Duration.ofSeconds(props.getWindowSeconds()));
        }
        if (count >= props.getMaxFail()) {
            redis.opsForValue().set(lockKey(username), "1", Duration.ofSeconds(props.getLockSeconds()));
            redis.delete(key);
            log.warn("登录失败达阈值，临时锁定 username={} count={} lockSec={}",
                    username, count, props.getLockSeconds());
        }
    }

    /** 登录成功后清理失败计数与锁定标记。 */
    public void clearOnSuccess(String username) {
        redis.delete(java.util.List.of(failKey(username), lockKey(username)));
    }

    private String failKey(String username) {
        return props.getKeyPrefix() + username;
    }

    private String lockKey(String username) {
        return props.getLockPrefix() + username;
    }
}
