package com.shopsphere.product.service;

import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Redis 库存原子操作（T2.3）—— TCC 库存分支的 Redis 子动作（契约 §4.3）。
 *
 * <h3>键</h3>
 * {@code stock:product:{id}} —— 当前可售库存,纯数字字符串。<b>常驻无 TTL</b>:
 * 启动由 {@code StockWarmupRunner} 从 DB 预热,之后只由本类 Lua 维护。
 *
 * <h3>原子性</h3>
 * 预扣/回补走 Lua 脚本(GET+判断+DECRBY 单脚本原子),禁止 Java 端 GET+SET 组合。
 *
 * <h3>预扣返回码</h3>
 * <ul>
 *   <li>{@code >= 0} —— 成功,值为扣减后剩余库存</li>
 *   <li>{@code -1} {@link #RESULT_INSUFFICIENT} —— 库存不足,未扣减</li>
 *   <li>{@code -2} {@link #RESULT_KEY_MISSING} —— key 不存在(不自动初始化,避免雪崩重建错误库存)</li>
 * </ul>
 *
 * <p>本类只交付库存 Redis 子动作;TCC 编排(DB 条件更新 + {@code t_stock_tcc_log}
 * 幂等/空回滚/防悬挂)是 T2.4。
 */
@Slf4j
@Service
public class StockRedisService {

    public static final String STOCK_KEY_PREFIX = "stock:product:";
    /** 预扣:库存不足 */
    public static final long RESULT_INSUFFICIENT = -1L;
    /** 预扣 / 读取:key 不存在 */
    public static final long RESULT_KEY_MISSING = -2L;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> stockPreDeductScript;
    private final RedisScript<Long> stockRestoreScript;

    // 显式构造器：两个 RedisScript<Long> 同类型，须按 Bean 名消歧。本工程不继承
    // spring-boot-starter-parent、未开 -parameters，Spring 6.1 已移除 LocalVariableTable
    // 参数名发现，无法靠参数名注入 —— 故在构造器参数上显式 @Qualifier。
    public StockRedisService(StringRedisTemplate stringRedisTemplate,
                             @Qualifier("stockPreDeductScript") RedisScript<Long> stockPreDeductScript,
                             @Qualifier("stockRestoreScript") RedisScript<Long> stockRestoreScript) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.stockPreDeductScript = stockPreDeductScript;
        this.stockRestoreScript = stockRestoreScript;
    }

    /** 启动 SCRIPT LOAD 预加载两段脚本,首次业务调用即可走 EVALSHA。 */
    @PostConstruct
    void preloadScripts() {
        try {
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
                scriptLoad(connection, stockPreDeductScript);
                scriptLoad(connection, stockRestoreScript);
                return null;
            });
            log.info("stock Lua scripts preloaded (SCRIPT LOAD)");
        } catch (RuntimeException e) {
            // 预加载失败不阻断启动:execute 仍会 EVAL 兜底
            log.warn("stock Lua script preload failed, will fall back to EVAL on first call", e);
        }
    }

    private void scriptLoad(RedisConnection connection, RedisScript<Long> script) {
        connection.scriptingCommands()
                .scriptLoad(script.getScriptAsString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 原子预扣库存。
     *
     * @return {@code >=0} 扣减后剩余 / {@link #RESULT_INSUFFICIENT} 不足 / {@link #RESULT_KEY_MISSING} 缺失
     */
    public long preDeduct(Long productId, int qty) {
        requirePositive(qty);
        Long result = stringRedisTemplate.execute(stockPreDeductScript,
                List.of(stockKey(productId)), String.valueOf(qty));
        return result == null ? RESULT_KEY_MISSING : result;
    }

    /**
     * 原子回补库存(TCC-Cancel)。key 不存在时 SET 兜底。
     *
     * @return 回补后的库存值
     */
    public long restore(Long productId, int qty) {
        requirePositive(qty);
        Long result = stringRedisTemplate.execute(stockRestoreScript,
                List.of(stockKey(productId)), String.valueOf(qty));
        if (result == null) {
            log.error("stock restore returned null, productId={}, qty={}", productId, qty);
            throw new BusinessException(ErrorCode.SERVER_ERROR);
        }
        return result;
    }

    /**
     * 读当前可售库存。
     *
     * @return 库存值 / {@link #RESULT_KEY_MISSING} key 不存在或值损坏
     */
    public long getAvailable(Long productId) {
        String value = stringRedisTemplate.opsForValue().get(stockKey(productId));
        if (value == null) {
            return RESULT_KEY_MISSING;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.error("corrupt stock value '{}' for productId={}", value, productId);
            return RESULT_KEY_MISSING;
        }
    }

    /**
     * 初始化 / 重同步库存（启动预热用）。SET 覆盖,无 TTL —— stock key 是常驻计数器。
     */
    public void initStock(Long productId, long available) {
        stringRedisTemplate.opsForValue().set(stockKey(productId), Long.toString(available));
    }

    public String stockKey(Long productId) {
        return STOCK_KEY_PREFIX + productId;
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
    }
}
