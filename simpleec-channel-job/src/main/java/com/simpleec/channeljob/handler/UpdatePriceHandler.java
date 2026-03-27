package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * UPDATE_PRICE handler — pushes price changes back to the platform.
 *
 * Current status: stub. Logs receipt only.
 *
 * TODO: For each platform, call the corresponding price update API:
 *   - Shopee:    PUT /api/v2/product/update_price
 *   - Cyberbiz:  PUT /v1/variants/{id}  (price, compare_at_price)
 *   - Easystore: PUT /variants/{id}
 *   - Momo/Yahoo/PChome: TBD per platform contract
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdatePriceHandler {

    public void handleUpdatePrice(String platformCode, String channelId,
                                  String merchantId, JsonNode body) {
        String channelItemId = body.path("channelItemId").asText("unknown");
        String newPrice = body.path("price").asText("0");

        log.warn("UPDATE_PRICE not yet implemented: platform={}, channel={}, itemId={}, price={}",
                platformCode, channelId, channelItemId, newPrice);

        // TODO: resolve platform adapter and call price update API
    }
}
