package com.shopsphere.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一错误码，号段严格对齐 docs/api-contracts.md §2。
 * <p>格式 {服务段}{三位序号}，0 保留为成功。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, "ok"),

    // ---- 通用 1xxx ----
    PARAM_INVALID(1000, "参数校验失败"),
    UNAUTHORIZED(1001, "未认证"),
    RATE_LIMITED(1003, "请求过于频繁"),
    /** 仅 Gateway 路由层使用，业务服务禁止返回（业务"不存在"用服务段码，§2/M5） */
    ROUTE_NOT_FOUND(1004, "资源不存在"),
    SERVER_ERROR(1500, "系统内部错误"),

    // ---- User 2xxx ----
    USERNAME_EXISTS(2001, "用户名已存在"),
    PASSWORD_WRONG(2002, "密码错误"),
    USER_NOT_FOUND(2003, "用户不存在"),

    // ---- Product 3xxx ----
    PRODUCT_NOT_FOUND(3001, "商品不存在"),
    STOCK_NOT_ENOUGH(3002, "库存不足"),
    STOCK_PREDEDUCT_FAIL(3003, "库存预扣失败"),

    // ---- Order 4xxx ----
    ORDER_NOT_FOUND(4001, "订单不存在"),
    ORDER_STATUS_INVALID(4002, "订单状态非法"),
    GLOBAL_TX_ROLLBACK(4003, "全局事务回滚"),

    // ---- Recommendation 5xxx ----
    // 注意：5xxx 仅作监控埋点/日志维度，禁止写入 Result.code（C1 拍板）。
    COLD_START(5001, "冷启动"),
    MODEL_NOT_READY(5002, "模型未就绪");

    private final int code;
    private final String message;
}
