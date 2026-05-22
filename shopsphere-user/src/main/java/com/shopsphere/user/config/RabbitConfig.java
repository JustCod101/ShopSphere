package com.shopsphere.user.config;

import com.shopsphere.user.service.BehaviorProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 生产端接入（T1.4 首次引入）。声明行为埋点 exchange + publisher-confirm/returns 回调。
 *
 * <p><b>不声明队列与 binding</b>：契约 §8 中 {@code q.reco.behavior} 由推荐服务自行声明并绑定到本
 * exchange，符合"消费方拥有队列"模式。User 服务不感知下游存在。
 *
 * <p><b>publisher-confirm/returns 回调</b>：M4 拍板 🟡 轻量等级 — nack/return 仅记 WARN，不补偿、不重试。
 *
 * <p><b>MessageConverter 见 {@link MqConverterConfig}</b>：刻意拆出独立配置，避免
 * {@code rabbitConfig → rabbitTemplate → mqMessageConverter → rabbitConfig} 的自动装配环。
 * Spring Boot 的 {@code RabbitTemplateConfigurer} 已把那个唯一的 {@code MessageConverter}
 * 装到自动配置的 {@code RabbitTemplate} 上，本类无需再 {@code setMessageConverter}。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitConfig {

    private final BehaviorProperties props;
    private final RabbitTemplate rabbitTemplate;

    @Bean
    public TopicExchange behaviorExchange() {
        return ExchangeBuilder.topicExchange(props.getExchange()).durable(true).build();
    }

    @PostConstruct
    void wireRabbitTemplate() {
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
