package com.shopsphere.api.order;

import org.springframework.cloud.openfeign.FeignClient;

/**
 * Order 服务内部接口 —— 预留占位（api-contracts §4.2 标「预留，按需」）。
 * <p>Phase 3 订单链路落地时按需补充方法；当前仅保留契约骨架与命名规范。
 * 服务间 Nacos 直连，不经 Gateway（C2）。
 */
@FeignClient(name = "shopsphere-order", path = "/internal/order",
        fallback = OrderFeignFallback.class)
public interface OrderFeignClient {
    // 预留，Phase 3 按需补充
}
