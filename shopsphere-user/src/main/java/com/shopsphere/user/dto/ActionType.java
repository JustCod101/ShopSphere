package com.shopsphere.user.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 行为类型枚举（契约 §8 / §6.1）。
 * <ul>
 *   <li>{@code @JsonValue}：序列化为小写 view/cart/order，与 DB t_user_behavior.action_type 列、MQ payload 三者完全一致</li>
 *   <li>{@code @JsonCreator}：反序列化大小写宽容；非法值抛 {@link IllegalArgumentException}
 *       → Spring MVC 包装为 {@link org.springframework.http.converter.HttpMessageNotReadableException}
 *       → common {@code GlobalExceptionHandler#handleUnreadable} 转 {@code Result(1000)}</li>
 * </ul>
 */
public enum ActionType {
    VIEW, CART, ORDER;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static ActionType fromJson(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("actionType 不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("actionType 必须是 view/cart/order 之一，实际=" + raw);
        }
    }
}
