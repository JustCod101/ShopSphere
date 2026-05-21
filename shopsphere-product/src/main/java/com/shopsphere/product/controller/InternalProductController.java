package com.shopsphere.product.controller;

import com.shopsphere.api.product.ProductFeignClient;
import com.shopsphere.api.product.dto.ProductDetailDTO;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;
import com.shopsphere.common.context.PublicApi;
import com.shopsphere.common.result.Result;
import com.shopsphere.product.service.ProductService;
import com.shopsphere.product.service.StockTccService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部 Feign 端点（契约 §4.2 / §4.3）。直接 {@code implements ProductFeignClient} ——
 * 路径/方法/出入参与 Feign 契约编译期对齐,契约漂移即编译失败。
 *
 * <p><b>路径前缀须类级显式声明</b>:{@code @FeignClient(path="/internal/product")} 仅客户端消费;
 * 服务端 MVC 由类级 {@link RequestMapping} + 接口方法级 {@code @GetMapping/@PostMapping} 组合出
 * {@code /internal/product/**}。与公开 {@code ProductController}({@code /api/product})无冲突。
 *
 * <p><b>鉴权</b>:{@link PublicApi} 跳过 UserContext 鉴权兜底 —— 服务间 Feign 走 Nacos 直连可能
 * 不带 {@code X-User-Id};{@code /internal/**} 不被 Gateway 路由(§4.1,T1.1 已落地)。
 *
 * <p><b>T2.4 骨架</b>:库存 TCC 三接口委托 {@link StockTccService}(幂等表写入 + 直调 Redis);
 * 完整 Seata TCC 语义见 {@code StockTccServiceImpl} 的 T3.3 TODO。
 */
@RestController
@RequestMapping("/internal/product")
@PublicApi
@RequiredArgsConstructor
public class InternalProductController implements ProductFeignClient {

    private final ProductService productService;
    private final StockTccService stockTccService;

    @Override
    public Result<ProductDetailDTO> getDetail(Long id) {
        return Result.ok(productService.getDetailForInternal(id));
    }

    @Override
    public Result<Void> stockTry(StockTccDTO dto) {
        stockTccService.tryStock(dto);
        return Result.ok();
    }

    @Override
    public Result<Void> stockConfirm(StockTccActionDTO dto) {
        stockTccService.confirmStock(dto);
        return Result.ok();
    }

    @Override
    public Result<Void> stockCancel(StockTccActionDTO dto) {
        stockTccService.cancelStock(dto);
        return Result.ok();
    }
}
