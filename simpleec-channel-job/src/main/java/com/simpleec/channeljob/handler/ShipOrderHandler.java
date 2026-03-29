package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channel.adapter.CyberbizAdapter;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SHIP_ORDER handler — pushes shipment info (tracking number, carrier) back to the platform.
 *
 * Cyberbiz: POST /v1/orders/{order_id}/fulfillments/custom_shipping
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipOrderHandler {

    private final ChannelService channelService;
    private final ChannelAdapter cyberbizAdapter;

    public void handleShipOrder(String platformCode, String channelId,
                                String merchantId, JsonNode body) {
        String channelOrderId = body.path("channelOrderId").asText(body.path("orderId").asText("unknown"));
        String trackingNumber = body.path("trackingNumber").asText("");
        String carrier        = body.path("carrier").asText("other");

        if ("cyberbiz".equalsIgnoreCase(platformCode)) {
            Channel channel = channelService.getChannel(channelId);
            if (channel == null) {
                log.error("SHIP_ORDER: channel not found: {}", channelId);
                return;
            }
            try {
                CyberbizAdapter adapter = (CyberbizAdapter) cyberbizAdapter;
                adapter.setCredentials(channel.getToken(), channel.getToken2());
                // line_item_ids not included in SHIP_ORDER event — pass empty string (fulfills all items)
                adapter.shipOrder(channelOrderId, Map.of(
                        "trackingNumber", trackingNumber,
                        "carrier", carrier.isBlank() ? "other" : carrier,
                        "lineItemIds", "",
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
            log.warn("SHIP_ORDER not yet implemented: platform={}, channel={}, orderId={}, tracking={}, carrier={}",
                    platformCode, channelId, channelOrderId, trackingNumber, carrier);
        }
    }
}
