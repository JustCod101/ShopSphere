package com.shopsphere.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 订单服务业务配置（Nacos {@code shopsphere-order.yaml#order.*} 下发）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "order")
public class OrderProperties {

    private Payment payment = new Payment();

    private Outbox outbox = new Outbox();

    @Data
    public static class Payment {
        /** 未支付超时窗口（分钟），默认 30（api-contracts §6.3 / S4）。 */
        private int timeoutMinutes = 30;
    }

    /**
     * 本地消息表 outbox 中继任务配置（T3.4）。
     * {@code scanIntervalMs} 由 {@code @Scheduled(fixedDelayString)} 直接读取，此处仅作文档化默认值。
     */
    @Data
    public static class Outbox {
        /** 扫描周期（毫秒），默认 5000。 */
        private long scanIntervalMs = 5000;
        /** 单次扫描最多投递条数，默认 100。 */
        private int batchSize = 100;
        /** confirm nack 最大重试次数，超过转 FAILED，默认 5。 */
        private int maxRetry = 5;
        /** Redisson 锁获取等待（毫秒），默认 0（抢不到立即跳过本轮）。 */
        private long lockWaitMs = 0;
        /** Redisson 锁租约（毫秒），默认 10000（远大于单轮扫描耗时，到期自动释放防死锁）。 */
        private long lockLeaseMs = 10000;
    }
}
