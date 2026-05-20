package com.shopsphere.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.Map;

/**
 * 行为埋点请求体（POST /api/user/behavior）。userId 不在 body，从 X-User-Id 由拦截器解析。
 */
@Data
public class BehaviorRequestDTO {

    @NotNull
    @Positive
    private Long itemId;

    @NotNull
    private ActionType actionType;

    /** 可选扩展上下文（来源页、关键词等）；以 JSON 列原样落库。 */
    private Map<String, Object> extra;
}
