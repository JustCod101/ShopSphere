package com.shopsphere.order.service;

import com.shopsphere.common.result.PageResult;
import com.shopsphere.order.dto.CreateOrderDTO;
import com.shopsphere.order.dto.OrderCreateVO;
import com.shopsphere.order.dto.OrderDetailVO;
import com.shopsphere.order.dto.OrderVO;

public interface OrderService {

    /**
     * 下单幂等预检（S5）：命中 {@code t_order_request} → 返回首单结果；未命中 → {@code null}。
     */
    OrderCreateVO findExistingOrder(Long userId, String requestId);

    /**
     * 下单：{@code @GlobalTransactional} 发起方。校验商品 → 本地建单 + outbox → 库存 TCC-Try。
     * <p>仅做库存预留（订单 {@code CREATED}）；支付成功才 TCC-Confirm（见 {@link #pay}）。
     */
    OrderCreateVO createOrder(Long userId, String requestId, CreateOrderDTO dto);

    /**
     * 支付：{@code CREATED → PAID}，触发库存 TCC-Confirm。{@code @GlobalTransactional} 发起方。
     * <p>订单不存在/非本人 → {@code 4001}；状态非 {@code CREATED} → {@code 4002}。
     */
    OrderVO pay(Long userId, Long orderId);

    /**
     * 订单详情（含明细）。订单不存在或不属于 {@code userId} → {@code 4001}（不暴露存在性）。
     */
    OrderDetailVO getDetail(Long userId, Long orderId);

    /**
     * 当前用户的订单分页列表。强制按 {@code userId} 过滤（防越权）。
     *
     * @param status 可选状态名筛选（{@code OrderStatus} 枚举名）；非法名 → {@code 1000}
     */
    PageResult<OrderVO> listOrders(Long userId, String status, int page, int size);

    /**
     * 取消订单：{@code CREATED/PAID → CANCELLED}，回补库存。{@code @GlobalTransactional} 发起方。
     * 人工取消与超时取消共用：{@code operatorUserId} 为 {@code null} 即系统超时取消（不校验归属，
     * 且仅取消仍为 {@code CREATED} 的订单）。
     */
    OrderVO cancel(Long orderId, Long operatorUserId, String reason);
}
