package com.shopsphere.user.config;

import com.shopsphere.user.messaging.OrderMqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * User 服务消费侧 MQ 拓扑（T3.4，api-contracts §8）。
 *
 * <p>按「消费方拥有队列」模式：User 声明自己消费的 {@code q.points} / {@code q.notify} 及其
 * 到 {@code shopsphere.order} 的绑定。{@code shopsphere.order} exchange 与死信设施
 * （{@code shopsphere.order.dlx} / {@code q.order.dlq}）由 Order 服务主声明，此处幂等重声明
 * （type/durable 一致即安全），消除 User 先于 Order 启动时的依赖。
 *
 * <p>两队列均带 {@code x-dead-letter-exchange}：消费失败 {@code basicNack(requeue=false)}
 * → DLX(fanout) → {@code q.order.dlq}。
 *
 * <p>消费者手动 ack 由 Nacos 配置 {@code spring.rabbitmq.listener.simple.acknowledge-mode=manual}
 * 全局开启；Jackson 反序列化复用 {@code RabbitConfig#mqMessageConverter}（容器工厂自动注入）。
 */
@Configuration
public class OrderEventRabbitConfig {

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(OrderMqConstants.EXCHANGE_ORDER, true, false);
    }

    @Bean
    public FanoutExchange orderDlxExchange() {
        return new FanoutExchange(OrderMqConstants.EXCHANGE_ORDER_DLX, true, false);
    }

    @Bean
    public Queue orderDlq() {
        return QueueBuilder.durable(OrderMqConstants.QUEUE_ORDER_DLQ).build();
    }

    @Bean
    public Binding orderDlqBinding() {
        return BindingBuilder.bind(orderDlq()).to(orderDlxExchange());
    }

    @Bean
    public Queue pointsQueue() {
        return QueueBuilder.durable(OrderMqConstants.QUEUE_POINTS)
                .deadLetterExchange(OrderMqConstants.EXCHANGE_ORDER_DLX)
                .build();
    }

    @Bean
    public Binding pointsBinding() {
        return BindingBuilder.bind(pointsQueue())
                .to(orderExchange())
                .with(OrderMqConstants.RK_ORDER_CREATED);
    }

    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(OrderMqConstants.QUEUE_NOTIFY)
                .deadLetterExchange(OrderMqConstants.EXCHANGE_ORDER_DLX)
                .build();
    }

    @Bean
    public Binding notifyBinding() {
        return BindingBuilder.bind(notifyQueue())
                .to(orderExchange())
                .with(OrderMqConstants.RK_ORDER_CREATED);
    }
}
