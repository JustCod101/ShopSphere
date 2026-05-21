package com.shopsphere.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 缓存延迟双删用的 {@link TaskScheduler}（T2.2）。
 *
 * <p>{@code ProductCacheService.invalidate} 第二删走此调度器（延迟 500ms），
 * 避免请求线程 {@code Thread.sleep} 阻塞。daemon 线程，不阻止 JVM 退出。
 */
@Configuration
public class CacheTaskSchedulerConfig {

    @Bean
    public TaskScheduler cacheTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("cache-evict-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(3);
        scheduler.initialize();
        return scheduler;
    }
}
