package com.oneec.app.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // ===== Exchanges =====
    public static final String EXCHANGE_CHANNEL_ACTION = "channel.action";
    public static final String EXCHANGE_DLX = "channel.action.dlx";
    public static final String EXCHANGE_RETRY = "channel.action.retry";

    // ===== Queues =====
    public static final String QUEUE_CHANNEL_ACTION = "channel.action.queue";
    public static final String QUEUE_DLQ = "channel.action.dlq";
    public static final String QUEUE_RETRY = "channel.action.retry.queue";

    // ===== Message Converter =====
    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    // ===== Main Exchange & Queue =====
    @Bean
    public TopicExchange channelActionExchange() {
        return new TopicExchange(EXCHANGE_CHANNEL_ACTION, true, false);
    }

    @Bean
    public Queue channelActionQueue() {
        return QueueBuilder.durable(QUEUE_CHANNEL_ACTION)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dead")
                .build();
    }

    @Bean
    public Binding channelActionBinding() {
        // bind all channel.action messages: routing key = {channel}.{action}
        return BindingBuilder.bind(channelActionQueue())
                .to(channelActionExchange())
                .with("#");
    }

    // ===== Dead Letter Exchange & Queue =====
    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(EXCHANGE_DLX, true, false);
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue())
                .to(dlxExchange())
                .with("dead");
    }

    // ===== Retry Exchange & Queue (TTL 30s → back to main) =====
    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(EXCHANGE_RETRY, true, false);
    }

    @Bean
    public Queue retryQueue() {
        return QueueBuilder.durable(QUEUE_RETRY)
                .withArgument("x-message-ttl", 30000) // 30 seconds
                .withArgument("x-dead-letter-exchange", EXCHANGE_CHANNEL_ACTION)
                .withArgument("x-dead-letter-routing-key", "retry")
                .build();
    }

    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(retryQueue())
                .to(retryExchange())
                .with("retry");
    }
}
