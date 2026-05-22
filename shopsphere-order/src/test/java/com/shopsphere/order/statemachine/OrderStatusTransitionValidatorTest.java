package com.shopsphere.order.statemachine;

import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.order.enums.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单状态机校验器单测 —— 覆盖全部 5×5=25 个有序迁移组合。
 * 合法 5 个：CREATED→PAID/CANCELLED、PAID→SHIPPED/CANCELLED、SHIPPED→COMPLETED；其余 20 个非法。
 */
class OrderStatusTransitionValidatorTest {

    private final OrderStatusTransitionValidator validator = new OrderStatusTransitionValidator();

    /** 合法迁移的权威集合（"FROM→TO" 字符串）。 */
    private static final Set<String> LEGAL = Set.of(
            "CREATED→PAID", "CREATED→CANCELLED",
            "PAID→SHIPPED", "PAID→CANCELLED",
            "SHIPPED→COMPLETED");

    private static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0}→{1}")
    @MethodSource("allPairs")
    void everyPair_matchesLegalTable(OrderStatus from, OrderStatus to) {
        boolean legal = LEGAL.contains(from + "→" + to);
        assertEquals(legal, validator.canTransit(from, to),
                "canTransit 与合法表不一致: " + from + "→" + to);

        if (legal) {
            validator.assertCanTransit(from, to);   // 不抛异常
        } else {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> validator.assertCanTransit(from, to));
            assertEquals(ErrorCode.ORDER_STATUS_INVALID, ex.getErrorCode(),
                    "非法迁移须抛 4002: " + from + "→" + to);
        }
    }

    @Test
    void legalTableHasExactlyFiveTransitions() {
        long legalCount = allPairs()
                .filter(a -> validator.canTransit((OrderStatus) a.get()[0], (OrderStatus) a.get()[1]))
                .count();
        assertEquals(5, legalCount, "合法迁移应恰为 5 个");
    }

    @Test
    void nullArguments_areNotTransitable() {
        assertFalse(validator.canTransit(null, OrderStatus.PAID));
        assertFalse(validator.canTransit(OrderStatus.CREATED, null));
    }

    @Test
    void terminalStates_haveNoOutgoingTransition() {
        for (OrderStatus to : OrderStatus.values()) {
            assertFalse(validator.canTransit(OrderStatus.COMPLETED, to), "COMPLETED 是终态");
            assertFalse(validator.canTransit(OrderStatus.CANCELLED, to), "CANCELLED 是终态");
        }
    }
}
