package com.shopsphere.api.order;

import org.springframework.stereotype.Component;

/**
 * Sentinel 降级兜底占位（CLAUDE.md：Feign 必须有 fallback）。
 * Phase 3 随 {@link OrderFeignClient} 方法补充对应降级实现。
 */
@Component
public class OrderFeignFallback implements OrderFeignClient {
    // 预留，Phase 3 按需补充
}
