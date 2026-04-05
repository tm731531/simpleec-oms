package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.entity.OrderRef;
import com.simpleec.channeljob.repository.OrderRefRepository;
import com.simpleec.channeljob.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SHIP_ORDER handler — pushes shipment info (tracking number, carrier) back to the platform.
 *
 * Translation layer: reads orderId (OMS NanoID) from body, looks up channel_order_id from DB.
 *
 * Cyberbiz: POST /v1/orders/{order_id}/fulfillments/custom_shipping
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipOrderHandler {

    private final ChannelService channelService;
    private final ChannelAdapter cyberbizAdapter;
    private final OrderRefRepository orderRefRepository;

    public void handleShipOrder(String platformCode, String channelId,
                                String merchantId, JsonNode body) {
        String orderId = body.path("orderId").asText("unknown");

        // Translation: OMS orderId → platform channelOrderId
        OrderRef orderRef = orderRefRepository.findById(orderId).orElse(null);
        if (orderRef == null) {
            log.error("SHIP_ORDER: order not found in DB: {}", orderId);
            return;
        }
        String channelOrderId = orderRef.getChannelOrderId();
        if (channelOrderId == null || channelOrderId.isBlank()) {
            log.error("SHIP_ORDER: channelOrderId is missing for orderId={}", orderId);
            return;
        }
        String trackingNumber = body.path("trackingNumber").asText("");
        String carrier        = body.path("carrier").asText("other");

        // Extract lineItems (v2 event — partial shipment support)
        // v1: lineItems absent → pass empty string (Cyberbiz fulfills all items)
        // v2: lineItems present → pass specific channel_item_ids
        String lineItemIds = "";
        if (body.has("lineItems") && body.get("lineItems").isArray()) {
            List<String> ids = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : body.get("lineItems")) {
                String cid = item.path("channelItemId").asText(
                    item.path("channel_item_id").asText(""));
                if (!cid.isBlank()) ids.add(cid);
            }
            lineItemIds = String.join(",", ids);
        }

        JsonNode capabilities = channelService.getPlatformCapabilities(channelId);
        if (capabilities.path("supportsShipment").asBoolean(false)) {
            Channel channel = channelService.getChannel(channelId);
            if (channel == null) {
                log.error("SHIP_ORDER: channel not found: {}", channelId);
                return;
            }
            try {
                cyberbizAdapter.setCredentials(channel.getToken(), channel.getToken2());
                cyberbizAdapter.shipOrder(channelOrderId, Map.of(
                        "trackingNumber", trackingNumber,
                        "carrier", carrier.isBlank() ? "other" : carrier,
                        "lineItemIds", lineItemIds,
                        "notifyCustomer", "false"
                ));
                log.info("SHIP_ORDER success: platform={} channel={} orderId={} tracking={}",
                        platformCode, channelId, channelOrderId, trackingNumber);
            } catch (Exception e) {
                log.error("SHIP_ORDER failed: platform={} channel={} orderId={} tracking={}",
                        platformCode, channelId, channelOrderId, trackingNumber, e);
                throw new RuntimeException("SHIP_ORDER failed for orderId=" + channelOrderId, e);
            }
        } else {
            log.warn("SHIP_ORDER not supported for channel={} (supportsShipment=false): orderId={}, tracking={}, carrier={}",
                    channelId, channelOrderId, trackingNumber, carrier);
        }
    }
}
