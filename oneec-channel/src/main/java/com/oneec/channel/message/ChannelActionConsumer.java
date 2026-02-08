package com.oneec.channel.message;

import com.oneec.channel.adapter.ChannelAdapter;
import com.oneec.channel.adapter.ChannelAdapterFactory;
import com.oneec.common.enums.ActionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelActionConsumer {

    private final ChannelAdapterFactory adapterFactory;

    @RabbitListener(queues = "channel.action.queue")
    public void handleAction(ChannelActionMessage message) {
        log.info("Received action: channel={}, action={}, messageId={}",
                message.getChannelType(), message.getActionType(), message.getMessageId());

        try {
            ChannelAdapter adapter = adapterFactory.getAdapter(message.getChannelType());
            dispatch(adapter, message);
        } catch (Exception e) {
            log.error("Failed to process action: messageId={}, error={}",
                    message.getMessageId(), e.getMessage(), e);
            throw e; // let RabbitMQ handle retry / DLQ
        }
    }

    private void dispatch(ChannelAdapter adapter, ChannelActionMessage message) {
        Long channelId = message.getChannelId();
        var payload = message.getPayload();
        ActionType action = message.getActionType();

        switch (action) {
            case FETCH_ORDERS -> {
                LocalDateTime from = LocalDateTime.parse((String) payload.get("from"));
                LocalDateTime to = LocalDateTime.parse((String) payload.get("to"));
                adapter.fetchOrders(channelId, from, to);
            }
            case MODIFY_PRICE -> {
                String productId = (String) payload.get("channelProductId");
                BigDecimal price = new BigDecimal(payload.get("price").toString());
                adapter.updatePrice(channelId, productId, price);
            }
            case MODIFY_QUANTITY -> {
                String productId = (String) payload.get("channelProductId");
                int qty = ((Number) payload.get("quantity")).intValue();
                adapter.updateQuantity(channelId, productId, qty);
            }
            case START_SELLING -> {
                String productId = (String) payload.get("channelProductId");
                adapter.startSelling(channelId, productId);
            }
            case STOP_SELLING -> {
                String productId = (String) payload.get("channelProductId");
                adapter.stopSelling(channelId, productId);
            }
            case SHIPPING_CONFIRMED -> {
                String orderId = (String) payload.get("channelOrderId");
                String tracking = (String) payload.get("trackingNumber");
                String logistics = (String) payload.get("logisticsCompany");
                adapter.confirmShipment(channelId, orderId, tracking, logistics);
            }
            case ACCEPT_BUYER_CANCELLATION -> {
                String orderId = (String) payload.get("channelOrderId");
                adapter.acceptCancellation(channelId, orderId);
            }
            default -> log.warn("Unhandled action type: {}", action);
        }
    }
}
