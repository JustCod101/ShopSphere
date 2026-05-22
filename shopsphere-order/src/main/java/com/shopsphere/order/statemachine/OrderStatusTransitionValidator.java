package com.shopsphere.order.statemachine;

import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.order.enums.OrderStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机迁移校验器（api-contracts §6.3）。
 *
 * <p>合法迁移：{@code CREATED→PAID}、{@code CREATED→CANCELLED}、{@code PAID→SHIPPED}、
 * {@code PAID→CANCELLED}、{@code SHIPPED→COMPLETED}（SHIPPED 之后不可取消）。
 *
 * <p>所有改订单 {@code status} 的入口（支付 / 取消 / 后续发货、完成）都必须先经
 * {@link #assertCanTransit}，状态流转规则在此单点统一管理，杜绝散落各处的裸 UPDATE。
 * 建单是初始态（无 from），不走本校验。
 */
@Component
public class OrderStatusTransitionValidator {

    private final Map<OrderStatus, Set<OrderStatus>> allowed = new EnumMap<>(OrderStatus.class);

    public OrderStatusTransitionValidator() {
        allowed.put(OrderStatus.CREATED, EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED));
        allowed.put(OrderStatus.PAID, EnumSet.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED));
        allowed.put(OrderStatus.SHIPPED, EnumSet.of(OrderStatus.COMPLETED));
        allowed.put(OrderStatus.COMPLETED, EnumSet.noneOf(OrderStatus.class));
        allowed.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    /** 判断 {@code from → to} 是否为合法迁移。 */
    public boolean canTransit(OrderStatus from, OrderStatus to) {
        return from != null && to != null && allowed.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 断言 {@code from → to} 合法，非法则抛 {@link ErrorCode#ORDER_STATUS_INVALID}（4002）。
     */
    public void assertCanTransit(OrderStatus from, OrderStatus to) {
        if (!canTransit(from, to)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID,
                    "非法状态流转: " + from + "→" + to);
        }
    }
}
