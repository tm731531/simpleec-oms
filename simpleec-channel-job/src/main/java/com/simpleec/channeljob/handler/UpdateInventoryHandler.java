package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channel.adapter.CyberbizAdapter;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * UPDATE_INVENTORY handler — pushes stock level changes back to the platform.
 *
 * Cyberbiz: PUT /v1/products/{channelProductId}/product_variants/{channelSpecId}
 *           form-data: inventory_quantity={quantity}
 *
 * Event body fields (published by SellPackSyncService):
 *   channelProductId — Cyberbiz product ID
 *   channelSpecId    — Cyberbiz variant ID
 *   newValue         — new inventory quantity (int)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateInventoryHandler {

    private final ChannelService channelService;
    private final ChannelAdapter cyberbizAdapter;

    public void handleUpdateInventory(String platformCode, String channelId,
                                      String merchantId, JsonNode body) {
        String channelProductId = body.path("channelProductId").asText("");
        String channelSpecId    = body.path("channelSpecId").asText("");
        int quantity            = body.path("newValue").asInt(body.path("quantity").asInt(0));

        if ("cyberbiz".equalsIgnoreCase(platformCode)) {
            if (channelProductId.isBlank() || channelSpecId.isBlank()) {
                log.warn("UPDATE_INVENTORY skipped: missing channelProductId or channelSpecId " +
                         "(platform={} channel={})", platformCode, channelId);
                return;
            }
            Channel channel = channelService.getChannel(channelId);
            if (channel == null) {
                log.error("UPDATE_INVENTORY: channel not found: {}", channelId);
                return;
            }
            try {
                CyberbizAdapter adapter = (CyberbizAdapter) cyberbizAdapter;
                adapter.setCredentials(channel.getToken(), channel.getToken2());
                adapter.updateVariantInventory(channelProductId, channelSpecId, quantity);
                log.info("UPDATE_INVENTORY success: platform={} channel={} productId={} variantId={} qty={}",
                        platformCode, channelId, channelProductId, channelSpecId, quantity);
            } catch (Exception e) {
                log.error("UPDATE_INVENTORY failed: platform={} channel={} productId={} variantId={}",
                        platformCode, channelId, channelProductId, channelSpecId, e);
                throw new RuntimeException("UPDATE_INVENTORY failed for variant=" + channelSpecId, e);
            }
        } else {
            log.warn("UPDATE_INVENTORY not yet implemented: platform={}, channel={}, productId={}, variantId={}, qty={}",
                    platformCode, channelId, channelProductId, channelSpecId, quantity);
        }
    }
}
