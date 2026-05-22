package com.shopsphere.user.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 消息转换器配置。
 *
 * <p><b>为何独立成类</b>：此 {@code MessageConverter} 是容器内唯一的 converter Bean，Spring Boot
 * 的 {@code RabbitTemplateConfigurer} 会把它装到自动配置的 {@code RabbitTemplate} 上。若把该
 * {@code @Bean} 放在 {@code RabbitConfig}（构造注入 {@code RabbitTemplate}）中，则
 * {@code rabbitConfig → rabbitTemplate → mqMessageConverter → rabbitConfig} 经 {@code @Bean}
 * 工厂方法成环（{@code BeanCurrentlyInCreationException}，构造注入环 {@code allow-circular-references}
 * 也解不开）。本类只依赖 {@code ObjectMapper}，与 {@code RabbitTemplate} 无关，结构上无环。
 *
 * <p>复用主 {@code ObjectMapper}，确保 {@code OffsetDateTime} 按 ISO-8601 UTC 序列化
 * （与 HTTP 响应同源；契约 §1.1）。生产侧行为埋点与消费侧 {@code order.created} 反序列化共用。
 */
@Configuration
@RequiredArgsConstructor
public class MqConverterConfig {

    private final ObjectMapper objectMapper;

    @Bean
    public MessageConverter mqMessageConverter() {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
