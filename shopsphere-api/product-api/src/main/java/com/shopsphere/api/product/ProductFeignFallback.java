package com.shopsphere.api.product;

import com.shopsphere.api.product.dto.StockTccCmd;
import com.shopsphere.api.product.dto.StockTccConfirmCmd;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.Result;
import org.springframework.stereotype.Component;

/**
 * Sentinel 降级兜底。Try 失败按预扣失败处理；Confirm/Cancel 降级返回失败，
 * 由发起方/补偿任务重试（TCC 二阶段幂等，api-contracts §4.3）。
 */
@Component
public class ProductFeignFallback implements ProductFeignClient {

    @Override
    public Result<Void> tryStock(StockTccCmd cmd) {
        return Result.fail(ErrorCode.STOCK_PREDEDUCT_FAIL, "商品服务不可用，库存预扣降级");
    }

    @Override
    public Result<Void> confirmStock(StockTccConfirmCmd cmd) {
        return Result.fail(ErrorCode.STOCK_PREDEDUCT_FAIL, "商品服务不可用，Confirm 降级待重试");
    }

    @Override
    public Result<Void> cancelStock(StockTccConfirmCmd cmd) {
        return Result.fail(ErrorCode.STOCK_PREDEDUCT_FAIL, "商品服务不可用，Cancel 降级待重试");
    }
}
