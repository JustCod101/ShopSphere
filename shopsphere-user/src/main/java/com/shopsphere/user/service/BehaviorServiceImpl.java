package com.shopsphere.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.user.dto.BehaviorRequestDTO;
import com.shopsphere.user.entity.UserBehaviorEntity;
import com.shopsphere.user.mapper.UserBehaviorMapper;
import com.shopsphere.user.messaging.BehaviorEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorServiceImpl implements BehaviorService {

    private final UserBehaviorMapper behaviorMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void record(Long userId, BehaviorRequestDTO req) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime ts = OffsetDateTime.now(ZoneOffset.UTC);

        UserBehaviorEntity e = UserBehaviorEntity.builder()
                .eventId(eventId)
                .userId(userId)
                .itemId(req.getItemId())
                .actionType(req.getActionType().json())
                .extra(serializeExtra(req))
                .ts(ts)
                .createdAt(ts)
                .build();

        try {
            behaviorMapper.insert(e);
        } catch (DataAccessException ex) {
            // 按 T1.4 规格：DB 失败 → SERVER_ERROR（不暴露细节给前端）
            log.error("行为埋点落库失败 userId={} eventId={}", userId, eventId, ex);
            throw new BusinessException(ErrorCode.SERVER_ERROR);
        }

        // 仅发布 Spring 应用事件；真正的 MQ convertAndSend 由 BehaviorEventListener
        // 在 @TransactionalEventListener(AFTER_COMMIT) 阶段触发，确保 DB 已落盘
        applicationEventPublisher.publishEvent(BehaviorEvent.of(eventId, userId, req, ts));
    }

    /** 把 extra Map 序列化为 JSON 字符串落库；null/空 → null。 */
    private String serializeExtra(BehaviorRequestDTO req) {
        if (req.getExtra() == null || req.getExtra().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(req.getExtra());
        } catch (JsonProcessingException e) {
            // extra 来自 @Valid 通过的 Map，理论不会失败；防御性丢弃 extra，继续主流程
            log.warn("行为埋点 extra 序列化失败，丢弃 extra：{}", e.getMessage());
            return null;
        }
    }
}
