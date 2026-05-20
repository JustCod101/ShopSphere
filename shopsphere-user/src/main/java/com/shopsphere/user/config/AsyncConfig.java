package com.shopsphere.user.config;

import com.shopsphere.user.service.BehaviorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置。专用于行为埋点 MQ 发送（{@code @Async("behaviorMqExecutor")}）。
 *
 * <p><b>拒绝策略 CallerRunsPolicy 反压</b>：队列满 + 池满时退化为调用线程同步执行，
 * 避免任务丢失或 OOM；代价是 Tomcat 工作线程被占用，P99 抖动，监控
 * {@code activeCount/queueSize} 是关键指标。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("behaviorMqExecutor")
    public ThreadPoolTaskExecutor behaviorMqExecutor(BehaviorProperties props) {
        BehaviorProperties.Async a = props.getAsync();
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(a.getCorePoolSize());
        ex.setMaxPoolSize(a.getMaxPoolSize());
        ex.setQueueCapacity(a.getQueueCapacity());
        ex.setKeepAliveSeconds(a.getKeepAliveSeconds());
        ex.setThreadNamePrefix("behavior-mq-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }
}
