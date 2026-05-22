package com.shopsphere.order.enums;

import lombok.Getter;

/**
 * 订单状态机（api-contracts §6.3）：CREATED → PAID → SHIPPED → COMPLETED，
 * 旁支 CREATED/PAID → CANCELLED（SHIPPED 后不可取消）。
 */
@Getter
public enum OrderStatus {

    CREATED(0),
    PAID(1),
    SHIPPED(2),
    COMPLETED(3),
    CANCELLED(4);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public static OrderStatus of(int code) {
        for (OrderStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知订单状态码: " + code);
    }
}
