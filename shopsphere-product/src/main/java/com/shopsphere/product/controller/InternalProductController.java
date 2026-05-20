package com.shopsphere.product.controller;

import com.shopsphere.api.product.ProductFeignClient;
import com.shopsphere.api.product.dto.StockTccCmd;
import com.shopsphere.api.product.dto.StockTccConfirmCmd;
import com.shopsphere.common.context.PublicApi;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部库存 TCC 端点骨架（契约 §4.3 / §6.2）。
 *
 * <p><b>T2.1 本期不实现</b>：三段方法体 throw 1500 + "not implemented (T2.4)"，明确未实现态。
 * 真实业务（Redis Lua 预扣 + DB 条件更新 + t_stock_tcc_log 幂等/空回滚/防悬挂）放 T2.4。
 *
 * <p><b>路径前缀</b>：实现 {@code ProductFeignClient} 的 {@code path="/internal/product/stock"}，
 * 类级 {@link RequestMapping} 须显式声明（{@code @FeignClient.path} 仅客户端消费，服务端 MVC 不识别）。
 *
 * <p><b>鉴权策略</b>：同 user 的 InternalUserController — Gateway 显式拒绝外部 /internal/**，
 * Feign 服务间走 Nacos 直连可能不带 X-User-Id，故 {@link PublicApi} 跳过 UserContext 鉴权兜底。
 */
@RestController
@RequestMapping("/internal/product/stock")
@PublicApi
@RequiredArgsConstructor
public class InternalProductController implements ProductFeignClient {

    @Override
    public Result<Void> tryStock(StockTccCmd cmd) {
        throw new BusinessException(ErrorCode.SERVER_ERROR, "stock/try not implemented (T2.4)");
    }

    @Override
    public Result<Void> confirmStock(StockTccConfirmCmd cmd) {
        throw new BusinessException(ErrorCode.SERVER_ERROR, "stock/confirm not implemented (T2.4)");
    }

    @Override
    public Result<Void> cancelStock(StockTccConfirmCmd cmd) {
        throw new BusinessException(ErrorCode.SERVER_ERROR, "stock/cancel not implemented (T2.4)");
    }
}
