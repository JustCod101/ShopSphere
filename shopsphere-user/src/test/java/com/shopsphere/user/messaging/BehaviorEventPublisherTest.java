package com.shopsphere.user.messaging;

import com.shopsphere.user.dto.ActionType;
import com.shopsphere.user.service.BehaviorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BehaviorEventPublisher 单测：RabbitTemplate 调用参数 + AmqpException 吞掉降级（🟡 fail-open）。
 */
class BehaviorEventPublisherTest {

    private RabbitTemplate rabbitTemplate;
    private BehaviorEventPublisher publisher;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        BehaviorProperties props = new BehaviorProperties();
        publisher = new BehaviorEventPublisher(rabbitTemplate, props);
    }

    private BehaviorEvent ev(String eventId) {
        return BehaviorEvent.builder()
                .eventId(eventId).userId(100L).itemId(1001L)
                .actionType(ActionType.VIEW)
                .ts(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    @Test
    void publish_invokesRabbitTemplate_withCorrectExchangeAndRoutingKey_andCorrelationDataIsEventId() {
        BehaviorEvent payload = ev("abcd1234abcd1234abcd1234abcd1234");
        publisher.publish(payload);

        ArgumentCaptor<CorrelationData> cdCap = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(
                eq("shopsphere.behavior"),
                eq("user.behavior"),
                eq(payload),
                cdCap.capture());
        assertEquals("abcd1234abcd1234abcd1234abcd1234", cdCap.getValue().getId(),
                "CorrelationData.id 须 == eventId，便于 ConfirmCallback 定位");
    }

    @Test
    void publish_amqpException_swallowed_doesNotPropagate() {
        doThrow(new AmqpConnectException(new RuntimeException("rabbit down")))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class),
                        any(Object.class), any(CorrelationData.class));
        // 🟡 轻量等级：MQ 故障不应阻断业务，publish() 必须吞异常
        assertDoesNotThrow(() -> publisher.publish(ev("e1")));
    }
}
