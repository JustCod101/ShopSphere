package com.shopsphere.api.product;

import com.shopsphere.api.product.dto.ProductDetailDTO;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;
import com.shopsphere.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Product 服务内部接口（api-contracts §4.2 / §4.3）。
 * <p>服务间 Nacos 直连,不经 Gateway（C2）;{@code /internal/**} 不被 Gateway 路由。
 * <p>库存 TCC：Try 预扣 → Confirm 真实出库 / Cancel 释放回补;幂等键 {@code (orderId, productId)}。
 *
 * <p><b>T2.4 为接口契约 + 骨架</b>;真正的 Seata {@code @TwoPhaseBusinessAction} 与完整
 * 空回滚/防悬挂语义在 T3.3 落地。
 */
@FeignClient(name = "shopsphere-product", path = "/internal/product",
        fallback = ProductFeignFallback.class)
public interface ProductFeignClient {

    /** 商品详情（服务间内部端点,与公开 {@code GET /api/product/{id}} 数据同源）。 */
    @GetMapping("/{id}")
    Result<ProductDetailDTO> getDetail(@PathVariable("id") Long id);

    /** 库存 TCC-Try：按 items 预扣库存,幂等。 */
    @PostMapping("/stock/try")
    Result<Void> stockTry(@RequestBody StockTccDTO dto);

    /** 库存 TCC-Confirm：真实出库,按 orderId 对该订单全部预留项确认,幂等。 */
    @PostMapping("/stock/confirm")
    Result<Void> stockConfirm(@RequestBody StockTccActionDTO dto);

    /** 库存 TCC-Cancel：释放并回补 Redis,按 orderId 对该订单全部预留项取消,幂等。 */
    @PostMapping("/stock/cancel")
    Result<Void> stockCancel(@RequestBody StockTccActionDTO dto);
}
