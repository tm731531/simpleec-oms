package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * UPDATE_INVENTORY handler — pushes stock level changes back to the platform.
 *
 * Translation layer: reads sellPackId from Kafka body, looks up
 * channelProductId + channelSpecId from sell_pack table, then calls
 * the ChannelAdapter interface (no hardcoded platform cast).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateInventoryHandler {

    private final ChannelService channelService;
    private final ChannelAdapter cyberbizAdapter;

    public void handleUpdateInventory(String platformCode, String channelId,
                                      String merchantId, JsonNode body) {
        String sellPackId = body.path("sellPackId").asText("");
        int quantity      = body.path("newValue").asInt(body.path("quantity").asInt(0));

        if (sellPackId.isBlank()) {
            log.warn("UPDATE_INVENTORY skipped: missing sellPackId (platform={} channel={})",
                    platformCode, channelId);
            return;
        }

        // Translation: OMS sellPackId → platform-specific channelProductId + channelSpecId
        Map<String, String> ids = channelService.getSellPackChannelIds(sellPackId);
        if (ids == null) {
            log.error("UPDATE_INVENTORY: sell_pack not found: {} (platform={} channel={})",
                    sellPackId, platformCode, channelId);
            return;
        }
        String channelProductId = ids.get("channelProductId");
        String channelSpecId    = ids.get("channelSpecId");

        if (channelProductId.isBlank() || channelSpecId.isBlank()) {
            log.warn("UPDATE_INVENTORY skipped: sell_pack {} missing channelProductId or channelSpecId " +
                     "(platform={} channel={})", sellPackId, platformCode, channelId);
            return;
        }

        Channel channel = channelService.getChannel(channelId);
        if (channel == null) {
            log.error("UPDATE_INVENTORY: channel not found: {}", channelId);
            return;
        }

        try {
            // TODO: resolve adapter by platformCode when multi-platform adapter registry is ready
            cyberbizAdapter.setCredentials(channel.getToken(), channel.getToken2());
            cyberbizAdapter.updateVariantInventory(channelProductId, channelSpecId, quantity);
            log.info("UPDATE_INVENTORY success: platform={} channel={} sellPackId={} qty={}",
                    platformCode, channelId, sellPackId, quantity);
        } catch (Exception e) {
            log.error("UPDATE_INVENTORY failed: platform={} channel={} sellPackId={}",
                    platformCode, channelId, sellPackId, e);
            throw new RuntimeException("UPDATE_INVENTORY failed for sellPackId=" + sellPackId, e);
        }
    }
}
