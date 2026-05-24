package com.shopsphere.e2e.support;

import com.shopsphere.e2e.E2eConfig;
import redis.clients.jedis.Jedis;

/**
 * 轻量 Jedis 包装。E2E 仅在 case e/g 直读 stock:product:{id} 做断言。
 */
public final class RedisClient implements AutoCloseable {

    private final Jedis jedis;

    public RedisClient() {
        E2eConfig c = E2eConfig.get();
        this.jedis = new Jedis(c.redisHost(), c.redisPort());
    }

    /** 取 Long;不存在返 null。 */
    public Long getLong(String key) {
        String v = jedis.get(key);
        return v == null ? null : Long.parseLong(v);
    }

    public boolean exists(String key) {
        return jedis.exists(key);
    }

    @Override public void close() { jedis.close(); }
}
