package com.shopsphere.api.product;

import com.shopsphere.api.product.dto.StockTccCmd;
import com.shopsphere.api.product.dto.StockTccConfirmCmd;
import com.shopsphere.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Product 库存 TCC 内部接口（api-contracts §4.3，S1/S2/S3 重设计）。
 * <p>Try 预留 → Confirm 真实出库（支付成功）→ Cancel 释放并回补 Redis。
 * 服务间 Nacos 直连，不经 Gateway（C2）。
 */
@FeignClient(name = "shopsphere-product", path = "/internal/product/stock",
        fallback = ProductFeignFallback.class)
public interface ProductFeignClient {

    @PostMapping("/try")
    Result<Void> tryStock(@RequestBody StockTccCmd cmd);

    @PostMapping("/confirm")
    Result<Void> confirmStock(@RequestBody StockTccConfirmCmd cmd);

    @PostMapping("/cancel")
    Result<Void> cancelStock(@RequestBody StockTccConfirmCmd cmd);
}
