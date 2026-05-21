package com.shopsphere.api.product;

import com.shopsphere.api.product.dto.ProductDetailDTO;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Product Feign 降级兜底（CLAUDE.md：Feign 必须有 fallback）。
 *
 * <p><b>不吞错</b>：4 个方法全部返回 {@code code != 0} 的失败 Result,由调用方决策 ——
 * 绝不返回 ok 掩盖故障。
 * <ul>
 *   <li>{@code stockTry} → {@code STOCK_PREDEDUCT_FAIL(3003)},按预扣失败处理</li>
 *   <li>{@code stockConfirm}/{@code stockCancel} → {@code SERVER_ERROR(1500)},系统级失败,
 *       调用方(TCC 发起方/补偿任务)须重试(二阶段幂等保证重试安全)</li>
 *   <li>{@code getDetail} → {@code PRODUCT_NOT_FOUND(3001)}</li>
 * </ul>
 * <p>ERROR 日志即 Sentinel 熔断告警信号,运维须对该日志配告警。
 * <p>生效需消费方引入 {@code spring-cloud-starter-alibaba-sentinel} 且 {@code feign.sentinel.enabled=true}。
 */
@Slf4j
@Component
public class ProductFeignFallback implements ProductFeignClient {

    @Override
    public Result<ProductDetailDTO> getDetail(Long id) {
        log.error("[Feign fallback] getDetail 降级 productId={} —— 商品服务不可用", id);
        return Result.fail(ErrorCode.PRODUCT_NOT_FOUND, "商品服务不可用，降级");
    }

    @Override
    public Result<Void> stockTry(StockTccDTO dto) {
        log.error("[Feign fallback] stockTry 降级 orderId={} xid={} —— 按库存预扣失败处理",
                dto == null ? null : dto.getOrderId(), dto == null ? null : dto.getXid());
        return Result.fail(ErrorCode.STOCK_PREDEDUCT_FAIL, "商品服务不可用，库存预扣降级");
    }

    @Override
    public Result<Void> stockConfirm(StockTccActionDTO dto) {
        log.error("[Feign fallback] stockConfirm 降级 orderId={} xid={} —— 调用方须重试，Sentinel 熔断告警",
                dto == null ? null : dto.getOrderId(), dto == null ? null : dto.getXid());
        return Result.fail(ErrorCode.SERVER_ERROR, "商品服务不可用，Confirm 降级，调用方须重试");
    }

    @Override
    public Result<Void> stockCancel(StockTccActionDTO dto) {
        log.error("[Feign fallback] stockCancel 降级 orderId={} xid={} —— 调用方须重试，Sentinel 熔断告警",
                dto == null ? null : dto.getOrderId(), dto == null ? null : dto.getXid());
        return Result.fail(ErrorCode.SERVER_ERROR, "商品服务不可用，Cancel 降级，调用方须重试");
    }
}
