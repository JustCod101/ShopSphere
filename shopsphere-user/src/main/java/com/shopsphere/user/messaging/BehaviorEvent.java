package com.shopsphere.user.messaging;

import com.shopsphere.user.dto.ActionType;
import com.shopsphere.user.dto.BehaviorRequestDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * MQ payload，发到 {@code shopsphere.behavior} topic exchange routingKey={@code user.behavior}（契约 §8）。
 *
 * <p>字段顺序与 §8 表一致：{@code eventId, userId, itemId, actionType(view/cart/order), ts(UTC)}。
 * {@code extra} 为可选扩展，消费侧（Reco 服务）按需消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** UUID 去横线 32hex；消费侧幂等键 */
    private String eventId;
    private Long userId;
    private Long itemId;
    private ActionType actionType;
    private OffsetDateTime ts;
    /** 可选扩展上下文 */
    private Map<String, Object> extra;

    public static BehaviorEvent of(String eventId, Long userId, BehaviorRequestDTO req, OffsetDateTime ts) {
        return BehaviorEvent.builder()
                .eventId(eventId)
                .userId(userId)
                .itemId(req.getItemId())
                .actionType(req.getActionType())
                .ts(ts)
                .extra(req.getExtra())
                .build();
    }
}
