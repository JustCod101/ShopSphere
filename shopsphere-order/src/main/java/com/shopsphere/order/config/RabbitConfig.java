package com.shopsphere.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.order.constant.OrderConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Order 服务 MQ 拓扑声明（T3.4 + T3.5，api-contracts §8）。
 *
 * <p>按「消费方拥有队列」模式：Order 作为 {@code shopsphere.order} 的生产方，声明 exchange、
 * 死信交换机/队列，以及 <b>自消费</b> 的超时队列；{@code q.points} / {@code q.notify} 由 User
 * 声明，{@code q.reco.behavior} 由 Reco 声明。
 *
 * <h3>未支付超时延迟（T3.5，TTL+DLX，无需延迟插件）</h3>
 * outbox 把 {@code order.payment.timeout} 投到 {@code shopsphere.order} → 路由进
 * {@code q.order.timeout.wait}（带 TTL、无消费者）→ 消息 TTL 到期后经
 * {@code x-dead-letter-routing-key} 死信到默认交换机、按队列名投入 {@code q.order.timeout}
 * → {@code OrderTimeoutConsumer} 消费。
 */
@Configuration
@RequiredArgsConstructor
public class RabbitConfig {

    private final OrderProperties orderProperties;
    private final ObjectMapper objectMapper;

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(OrderConstants.EXCHANGE_ORDER, true, false);
    }

    /** 死信交换机用 fanout：所有订单队列的死信无视 routing key 统一汇入 {@code q.order.dlq}。 */
    @Bean
    public FanoutExchange orderDlxExchange() {
        return new FanoutExchange(OrderConstants.EXCHANGE_ORDER_DLX, true, false);
    }

    @Bean
    public Queue orderDlq() {
        return QueueBuilder.durable(OrderConstants.QUEUE_ORDER_DLQ).build();
    }

    @Bean
    public Binding orderDlqBinding() {
        return BindingBuilder.bind(orderDlq()).to(orderDlxExchange());
    }

    /**
     * 未支付超时等待队列：带 {@code x-message-ttl}（= 支付超时窗口）、<b>无消费者</b>；
     * 消息到期死信到默认交换机（{@code x-dead-letter-exchange=""}），按
     * {@code x-dead-letter-routing-key} 投入 {@code q.order.timeout}。
     */
    @Bean
    public Queue orderTimeoutWaitQueue() {
        long override = orderProperties.getPayment().getQueueTtlMs();
        int ttlMs = override > 0
                ? (int) override
                : orderProperties.getPayment().getTimeoutMinutes() * 60_000;
        return QueueBuilder.durable(OrderConstants.QUEUE_ORDER_TIMEOUT_WAIT)
                .ttl(ttlMs)
                .deadLetterExchange("")
                .deadLetterRoutingKey(OrderConstants.QUEUE_ORDER_TIMEOUT)
                .build();
    }

    @Bean
    public Binding orderTimeoutWaitBinding() {
        return BindingBuilder.bind(orderTimeoutWaitQueue())
                .to(orderExchange())
                .with(OrderConstants.RK_ORDER_TIMEOUT);
    }

    /**
     * 超时消费队列：接收等待队列死信投递（经默认交换机，无需绑定）。
     * 自带 DLX 指向死信交换机 —— 消费者多次失败后转 {@code q.order.dlq}。
     */
    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(OrderConstants.QUEUE_ORDER_TIMEOUT)
                .deadLetterExchange(OrderConstants.EXCHANGE_ORDER_DLX)
                .build();
    }

    /**
     * 消费端 JSON 反序列化器（{@code OrderTimeoutConsumer} 把消息体转 {@code OrderTimeoutEvent}）。
     * 复用主 {@code ObjectMapper}，OffsetDateTime 按 ISO-8601 UTC 解析。
     */
    @Bean
    public MessageConverter mqMessageConverter() {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
