package com.shopsphere.user.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 行为埋点配置（Nacos {@code shopsphere-user.yaml#behavior.*} 下发）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "behavior")
public class BehaviorProperties {

    /** MQ topic exchange 名（契约 §8） */
    private String exchange = "shopsphere.behavior";

    /** MQ routing key（契约 §8） */
    private String routingKey = "user.behavior";

    private Async async = new Async();

    @Data
    public static class Async {
        private int corePoolSize = 4;
        private int maxPoolSize = 8;
        private int queueCapacity = 1000;
        private int keepAliveSeconds = 60;
    }
}
