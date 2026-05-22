package com.shopsphere.order.controller;

import com.shopsphere.common.context.UserContextHolder;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.PageResult;
import com.shopsphere.common.result.Result;
import com.shopsphere.order.constant.OrderConstants;
import com.shopsphere.order.dto.CreateOrderDTO;
import com.shopsphere.order.dto.OrderCreateVO;
import com.shopsphere.order.dto.OrderDetailVO;
import com.shopsphere.order.dto.OrderVO;
import com.shopsphere.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单对外接口（api-contracts §6.3）。需登录（无 {@code @PublicApi}，由 common
 * {@code UserContextInterceptor} 校验 {@code X-User-Id}）。
 * <p>Controller 仅做参数校验与编排；业务逻辑、Mapper 访问均在 Service 层（CLAUDE.md）。
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 下单：仅库存 TCC-Try 预留，订单 {@code CREATED}。
     * <p>幂等（S5）：必填 {@code X-Request-Id}；同 {@code (userId, X-Request-Id)} 重复请求返回首单结果。
     */
    @PostMapping("/create")
    public Result<OrderCreateVO> create(
            @RequestHeader(value = OrderConstants.HEADER_REQUEST_ID, required = false) String requestId,
            @Valid @RequestBody CreateOrderDTO dto) {
        if (!StringUtils.hasText(requestId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少必填请求头 X-Request-Id");
        }
        Long userId = UserContextHolder.getUserId();
        OrderCreateVO existing = orderService.findExistingOrder(userId, requestId);
        if (existing != null) {
            return Result.ok(existing);
        }
        return Result.ok(orderService.createOrder(userId, requestId, dto));
    }

    /**
     * 支付：{@code CREATED → PAID}，触发库存 TCC-Confirm。
     * <p>订单不存在/非本人 → {@code 4001}；状态非 {@code CREATED} → {@code 4002}。
     */
    @PostMapping("/{id}/pay")
    public Result<OrderVO> pay(@PathVariable("id") Long id) {
        Long userId = UserContextHolder.getUserId();
        return Result.ok(orderService.pay(userId, id));
    }

    /**
     * 订单详情（含明细）。订单不存在或非本人 → {@code 4001}（不暴露存在性）。
     */
    @GetMapping("/{id}")
    public Result<OrderDetailVO> detail(@PathVariable("id") Long id) {
        Long userId = UserContextHolder.getUserId();
        return Result.ok(orderService.getDetail(userId, id));
    }

    /**
     * 当前用户的订单分页列表，强制按 {@code userId} 过滤（防越权）。
     * <p>{@code status} 可选（{@code OrderStatus} 枚举名）；{@code page} 默认 1、{@code size} 默认 20。
     */
    @GetMapping("/list")
    public Result<PageResult<OrderVO>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Long userId = UserContextHolder.getUserId();
        return Result.ok(orderService.listOrders(userId, status, page, size));
    }

    /**
     * 取消订单：{@code CREATED/PAID → CANCELLED}，回补库存。
     * <p>订单不存在/非本人 → {@code 4001}；{@code SHIPPED} 之后不可取消 → {@code 4002}。
     */
    @PostMapping("/{id}/cancel")
    public Result<OrderVO> cancel(@PathVariable("id") Long id) {
        Long userId = UserContextHolder.getUserId();
        return Result.ok(orderService.cancel(id, userId, "user"));
    }
}
