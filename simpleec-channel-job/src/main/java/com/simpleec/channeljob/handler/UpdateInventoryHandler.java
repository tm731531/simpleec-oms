package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * UPDATE_INVENTORY handler — pushes stock level changes back to the platform.
 *
 * Current status: stub. Logs receipt only.
 *
 * TODO: For each platform, call the corresponding inventory update API:
 *   - Shopee:    PUT /api/v2/product/update_stock
 *   - Cyberbiz:  PUT /v1/variants/{id}  (inventory_quantity)
 *   - Easystore: PUT /variants/{id}
 *   - Momo/Yahoo/PChome: TBD per platform contract
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateInventoryHandler {

    public void handleUpdateInventory(String platformCode, String channelId,
                                      String merchantId, JsonNode body) {
        String channelItemId = body.path("channelItemId").asText("unknown");
        int quantity = body.path("quantity").asInt(0);

        log.warn("UPDATE_INVENTORY not yet implemented: platform={}, channel={}, itemId={}, qty={}",
                platformCode, channelId, channelItemId, quantity);

        // TODO: resolve platform adapter and call inventory update API
    }
}
