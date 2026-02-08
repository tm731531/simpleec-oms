package com.oneec.channel.message;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelActionProducer {

    private final RabbitTemplate rabbitTemplate;

    public static final String EXCHANGE_CHANNEL_ACTION = "channel.action";

    /**
     * 發送通路動作到 RabbitMQ
     * routing key 格式: {channelType}.{actionType}
     * 例如: momo.GetOrder, shopee.ModifyPrice
     */
    public void send(ChannelActionMessage message) {
        String routingKey = message.getChannelType().getCode()
                + "." + message.getActionType().getCode();

        log.info("Sending channel action: exchange={}, routingKey={}, messageId={}",
                EXCHANGE_CHANNEL_ACTION, routingKey, message.getMessageId());

        rabbitTemplate.convertAndSend(EXCHANGE_CHANNEL_ACTION, routingKey, message);
    }
}
