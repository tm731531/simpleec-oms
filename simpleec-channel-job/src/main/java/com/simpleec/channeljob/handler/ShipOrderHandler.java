package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SHIP_ORDER handler — pushes shipment info (tracking number, carrier) back to the platform.
 *
 * Current status: stub. Logs receipt only.
 *
 * TODO: For each platform, call the corresponding shipment confirmation API:
 *   - Shopee:    POST /api/v2/logistics/ship_order
 *   - Cyberbiz:  PUT  /v1/orders/{id}/fulfill
 *   - Easystore: POST /orders/{id}/fulfillments
 *   - Momo/Yahoo/PChome: TBD per platform contract
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipOrderHandler {

    public void handleShipOrder(String platformCode, String channelId,
                                String merchantId, JsonNode body) {
        // body fields published by UserOrderController.publishShipOrderEvent:
        //   orderId (OMS internal), channelOrderId, trackingNumber, carrier
        String channelOrderId = body.path("channelOrderId").asText(body.path("orderId").asText("unknown"));
        String trackingNumber = body.path("trackingNumber").asText("");
        String carrier = body.path("carrier").asText("");

        log.warn("SHIP_ORDER not yet implemented: platform={}, channel={}, orderId={}, tracking={}, carrier={}",
                platformCode, channelId, channelOrderId, trackingNumber, carrier);

        // TODO: resolve platform adapter and call shipment confirmation API
    }
}
