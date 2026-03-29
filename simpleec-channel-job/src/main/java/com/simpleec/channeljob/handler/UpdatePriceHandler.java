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
 * UPDATE_PRICE handler — pushes price changes back to the platform.
 *
 * Cyberbiz: PUT /v1/products/{channelProductId}/product_variants/{channelSpecId}
 *           form-data: price={price}
 *
 * Event body fields (published by SellPackSyncService):
 *   channelProductId — Cyberbiz product ID
 *   channelSpecId    — Cyberbiz variant ID
 *   newValue         — new selling price (string/number)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdatePriceHandler {

    private final ChannelService channelService;
    private final ChannelAdapter cyberbizAdapter;

    public void handleUpdatePrice(String platformCode, String channelId,
                                  String merchantId, JsonNode body) {
        String channelProductId = body.path("channelProductId").asText("");
        String channelSpecId    = body.path("channelSpecId").asText("");
        String newPrice         = body.path("newValue").asText(body.path("price").asText("0"));

        if ("cyberbiz".equalsIgnoreCase(platformCode)) {
            if (channelProductId.isBlank() || channelSpecId.isBlank()) {
                log.warn("UPDATE_PRICE skipped: missing channelProductId or channelSpecId " +
                         "(platform={} channel={})", platformCode, channelId);
                return;
            }
            Channel channel = channelService.getChannel(channelId);
            if (channel == null) {
                log.error("UPDATE_PRICE: channel not found: {}", channelId);
                return;
            }
            try {
                CyberbizAdapter adapter = (CyberbizAdapter) cyberbizAdapter;
                adapter.setCredentials(channel.getToken(), channel.getToken2());
                adapter.updateVariantPrice(channelProductId, channelSpecId, newPrice);
                log.info("UPDATE_PRICE success: platform={} channel={} productId={} variantId={} price={}",
                        platformCode, channelId, channelProductId, channelSpecId, newPrice);
            } catch (Exception e) {
                log.error("UPDATE_PRICE failed: platform={} channel={} productId={} variantId={}",
                        platformCode, channelId, channelProductId, channelSpecId, e);
                throw new RuntimeException("UPDATE_PRICE failed for variant=" + channelSpecId, e);
            }
        } else {
            log.warn("UPDATE_PRICE not yet implemented: platform={}, channel={}, productId={}, variantId={}, price={}",
                    platformCode, channelId, channelProductId, channelSpecId, newPrice);
        }
    }
}
