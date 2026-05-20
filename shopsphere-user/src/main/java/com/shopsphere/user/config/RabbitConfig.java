package com.shopsphere.user.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.user.service.BehaviorProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 接入（T1.4 首次引入）。仅声明生产端需要的 exchange + 通用 converter + 回调。
 *
 * <p><b>不声明队列与 binding</b>：契约 §8 中 {@code q.reco.behavior} 由推荐服务自行声明并绑定到本
 * exchange，符合"消费方拥有队列"模式。User 服务不感知下游存在。
 *
 * <p><b>publisher-confirm/returns 回调</b>：M4 拍板 🟡 轻量等级 — nack/return 仅记 WARN，不补偿、不重试。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitConfig {

    private final BehaviorProperties props;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Bean
    public TopicExchange behaviorExchange() {
        return ExchangeBuilder.topicExchange(props.getExchange()).durable(true).build();
    }

    /**
     * 复用主 ObjectMapper，确保 OffsetDateTime 按 ISO-8601 UTC 序列化（与 HTTP 响应同源；契约 §1.1）。
     */
    @Bean
    public MessageConverter mqMessageConverter() {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @PostConstruct
    void wireRabbitTemplate() {
        rabbitTemplate.setMessageConverter(mqMessageConverter());
        // confirm: 收到 broker ack 时回调；ack=false 表示 broker 拒收（exchange 不存在等）
        rabbitTemplate.setConfirmCallback((cd, ack, cause) -> {
            if (!ack) {
                log.warn("MQ confirm NACK eventId={} cause={}",
                        cd != null ? cd.getId() : null, cause);
            }
        });
        // returns: mandatory=true 且无队列绑定时回调（消费侧 q.reco.behavior 未上线时此处告警）
        rabbitTemplate.setReturnsCallback(ret ->
                log.warn("MQ unroutable exchange={} rk={} replyText={} body={}B",
                        ret.getExchange(), ret.getRoutingKey(),
                        ret.getReplyText(), ret.getMessage().getBody().length));
    }
}
