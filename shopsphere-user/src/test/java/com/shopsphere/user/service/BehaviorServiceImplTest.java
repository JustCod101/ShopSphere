package com.shopsphere.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.user.dto.ActionType;
import com.shopsphere.user.dto.BehaviorRequestDTO;
import com.shopsphere.user.entity.UserBehaviorEntity;
import com.shopsphere.user.mapper.UserBehaviorMapper;
import com.shopsphere.user.messaging.BehaviorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BehaviorServiceImpl 单测：落库 + 发布 Spring 事件（不直接 MQ 调用，由 AFTER_COMMIT listener 接管）。
 * 覆盖：
 * <ul>
 *   <li>成功：insert 调 1 次 + ApplicationEventPublisher.publishEvent 调 1 次 + eventId 32 hex</li>
 *   <li>extra=null/空 → 落库 extra 为 null</li>
 *   <li>insert 抛 DataAccessException → BusinessException(SERVER_ERROR)，不发事件</li>
 * </ul>
 */
class BehaviorServiceImplTest {

    private UserBehaviorMapper behaviorMapper;
    private ApplicationEventPublisher applicationEventPublisher;
    private BehaviorServiceImpl svc;

    @BeforeEach
    void setUp() {
        behaviorMapper = mock(UserBehaviorMapper.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        svc = new BehaviorServiceImpl(behaviorMapper, applicationEventPublisher, new ObjectMapper());
    }

    private BehaviorRequestDTO req(ActionType t, Map<String, Object> extra) {
        BehaviorRequestDTO r = new BehaviorRequestDTO();
        r.setItemId(1001L);
        r.setActionType(t);
        r.setExtra(extra);
        return r;
    }

    @Test
    void record_success_writesEntity_andPublishesEvent_withConsistentEventId() {
        ArgumentCaptor<UserBehaviorEntity> entityCap = ArgumentCaptor.forClass(UserBehaviorEntity.class);
        ArgumentCaptor<BehaviorEvent> evCap = ArgumentCaptor.forClass(BehaviorEvent.class);

        svc.record(100L, req(ActionType.VIEW, Map.of("src", "home")));

        verify(behaviorMapper).insert(entityCap.capture());
        verify(applicationEventPublisher).publishEvent(evCap.capture());

        UserBehaviorEntity e = entityCap.getValue();
        BehaviorEvent ev = evCap.getValue();

        // eventId 一致 + 32 位 hex
        assertEquals(e.getEventId(), ev.getEventId());
        assertEquals(32, e.getEventId().length());
        assertTrue(e.getEventId().matches("^[0-9a-f]{32}$"));

        // 落库字段
        assertEquals(100L, e.getUserId());
        assertEquals(1001L, e.getItemId());
        assertEquals("view", e.getActionType());
        assertTrue(e.getExtra().contains("\"src\":\"home\""));
        assertNotNull(e.getTs());
        assertNotNull(e.getCreatedAt());

        // 事件 payload
        assertEquals(100L, ev.getUserId());
        assertEquals(1001L, ev.getItemId());
        assertEquals(ActionType.VIEW, ev.getActionType());
        assertEquals(Map.of("src", "home"), ev.getExtra());
    }

    @Test
    void record_nullExtra_storedAsNull() {
        svc.record(100L, req(ActionType.CART, null));

        ArgumentCaptor<UserBehaviorEntity> cap = ArgumentCaptor.forClass(UserBehaviorEntity.class);
        verify(behaviorMapper).insert(cap.capture());
        assertNull(cap.getValue().getExtra());
        assertEquals("cart", cap.getValue().getActionType());
    }

    @Test
    void record_emptyExtra_storedAsNull() {
        svc.record(100L, req(ActionType.ORDER, Map.of()));

        ArgumentCaptor<UserBehaviorEntity> cap = ArgumentCaptor.forClass(UserBehaviorEntity.class);
        verify(behaviorMapper).insert(cap.capture());
        assertNull(cap.getValue().getExtra());
    }

    @Test
    void record_insertFails_throwsServerError_noEventPublished() {
        DataAccessException dbErr = new QueryTimeoutException("simulated");
        doThrow(dbErr).when(behaviorMapper).insert(any(UserBehaviorEntity.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.record(100L, req(ActionType.VIEW, null)));
        assertEquals(ErrorCode.SERVER_ERROR, ex.getErrorCode());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }
}
