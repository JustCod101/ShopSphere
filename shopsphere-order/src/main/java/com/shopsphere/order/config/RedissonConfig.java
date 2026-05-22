package com.shopsphere.order.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 单点客户端（T3.4）。
 *
 * <p>用途：本地消息表 outbox 中继任务的分布式锁 {@code lock:order:outbox} —— 多实例下
 * 同一时刻仅一个实例扫描投递（{@code FOR UPDATE SKIP LOCKED} 之外的第二层防重）。
 * <p>地址从 {@code spring.data.redis.*} 读，与 Lettuce 连同一个 Redis 实例。
 * <p>单点模式：本期 demo 级；生产改 sentinel/cluster 时调整 {@code useSentinelServers/useClusterServers}。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password) {
        Config config = new Config();
        SingleServerConfig single = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port);
        if (StringUtils.hasText(password)) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }
}
