package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channel.registry.ChannelAdapterRegistry;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * UPDATE_PRICE handler — pushes price changes back to the platform.
 *
 * Translation layer: reads sellPackId from Kafka body, looks up
 * channelProductId + channelSpecId from sell_pack table, then calls
 * the ChannelAdapter interface (no hardcoded platform cast).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdatePriceHandler {

    private final ChannelService channelService;
    private final ChannelAdapterRegistry adapterRegistry;

    public void handleUpdatePrice(String platformCode, String channelId,
                                  String merchantId, JsonNode body) {
        String sellPackId = body.path("sellPackId").asText("");
        String newPrice   = body.path("newValue").asText(body.path("price").asText("0"));

        if (sellPackId.isBlank()) {
            log.warn("UPDATE_PRICE skipped: missing sellPackId (platform={} channel={})",
                    platformCode, channelId);
            return;
        }

        // Translation: OMS sellPackId → platform-specific channelProductId + channelSpecId
        Map<String, String> ids = channelService.getSellPackChannelIds(sellPackId);
        if (ids == null) {
            log.error("UPDATE_PRICE: sell_pack not found: {} (platform={} channel={})",
                    sellPackId, platformCode, channelId);
            return;
        }
        String channelProductId = ids.get("channelProductId");
        String channelSpecId    = ids.get("channelSpecId");

        if (channelProductId.isBlank() || channelSpecId.isBlank()) {
            log.warn("UPDATE_PRICE skipped: sell_pack {} missing channelProductId or channelSpecId " +
                     "(platform={} channel={})", sellPackId, platformCode, channelId);
            return;
        }

        Channel channel = channelService.getChannel(channelId);
        if (channel == null) {
            log.error("UPDATE_PRICE: channel not found: {}", channelId);
            return;
        }

        try {
            ChannelAdapter adapter = adapterRegistry.getAdapter(platformCode);
            adapter.setCredentials(channelId, channel.getToken(), channel.getToken2());
            adapter.updateVariantPrice(channelProductId, channelSpecId, newPrice);
            log.info("UPDATE_PRICE success: platform={} channel={} sellPackId={} price={}",
                    platformCode, channelId, sellPackId, newPrice);
        } catch (Exception e) {
            log.error("UPDATE_PRICE failed: platform={} channel={} sellPackId={}",
                    platformCode, channelId, sellPackId, e);
            throw new RuntimeException("UPDATE_PRICE failed for sellPackId=" + sellPackId, e);
        }
    }
}
