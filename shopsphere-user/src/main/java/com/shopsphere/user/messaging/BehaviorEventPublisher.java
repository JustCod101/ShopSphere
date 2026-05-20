package com.shopsphere.user.messaging;

import com.shopsphere.user.service.BehaviorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 行为事件 MQ 发送器。{@code @Async} 切到 {@code behaviorMqExecutor} 池，避免阻塞 Tomcat 工作线程；
 * RabbitMQ 故障时 {@link AmqpException} 在异步线程内被吞掉（记 WARN），符合 🟡 轻量 fail-open 语义。
 *
 * <p>CorrelationData 用 eventId 作 id，供 {@code RabbitConfig#wireRabbitTemplate} 的 ConfirmCallback
 * 在 broker nack 时定位丢失事件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final BehaviorProperties props;

    @Async("behaviorMqExecutor")
    public void publish(BehaviorEvent ev) {
        try {
            CorrelationData cd = new CorrelationData(ev.getEventId());
            rabbitTemplate.convertAndSend(props.getExchange(), props.getRoutingKey(), ev, cd);
        } catch (AmqpException e) {
            // RabbitMQ 不可达 / 连接拒绝等；轻量等级不补偿，仅告警
            log.warn("MQ 发送失败 eventId={} userId={} actionType={} : {}",
                    ev.getEventId(), ev.getUserId(), ev.getActionType(), e.getMessage());
        }
    }
}
