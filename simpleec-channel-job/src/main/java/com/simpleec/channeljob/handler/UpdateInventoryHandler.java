package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * UPDATE_INVENTORY handler — pushes stock level changes back to the platform,
 * then snapshots the result into sell_pack_inventory.
 *
 * Multi-location (capabilities.multiLocation=true, e.g. Shopify):
 *   - Look up the single sync-target channel_location for this channel.
 *   - Pass platformLocationId to the adapter call.
 *   - Snapshot with that location's NanoID as channel_location_id.
 *
 * No-location (multiLocation=false, e.g. Shopee, Cyberbiz):
 *   - Push directly; snapshot with channel_location_id = NULL.
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
        int newValue      = body.path("newValue").asInt(body.path("quantity").asInt(0));

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

        // Read capabilities — drives multi-location branching, not platformCode string
        JsonNode capabilities = channelService.getPlatformCapabilities(channelId);
        boolean multiLocation = capabilities.path("multiLocation").asBoolean(false);

        if (multiLocation) {
            handleMultiLocation(platformCode, channelId, sellPackId,
                    channelProductId, channelSpecId, newValue, channel);
        } else {
            handleSingleLocation(platformCode, channelId, sellPackId,
                    channelProductId, channelSpecId, newValue, channel);
        }
    }

    /**
     * Push to the single sync-target location (multi-location platforms, e.g. Shopify).
     * Not yet implemented for any live platform — logs a warning until a
     * multi-location adapter is available.
     */
    private void handleMultiLocation(String platformCode, String channelId, String sellPackId,
                                      String channelProductId, String channelSpecId,
                                      int newValue, Channel channel) {
        Optional<Map<String, String>> syncTarget = channelService.getSyncTargetLocation(channelId);
        if (syncTarget.isEmpty()) {
            log.error("UPDATE_INVENTORY: multiLocation=true but no sync-target location configured " +
                      "(platform={} channel={} sellPackId={})", platformCode, channelId, sellPackId);
            return;
        }
        String locationNanoId       = syncTarget.get().get("id");
        String platformLocationId   = syncTarget.get().get("platformLocationId");

        // TODO: use multi-location-aware adapter method when Shopify adapter is implemented
        // For now, adapters that support multi-location should override updateVariantInventoryAtLocation()
        log.warn("UPDATE_INVENTORY multi-location not yet fully implemented for platform={} channel={}; " +
                 "locationId={} sellPackId={} qty={}",
                 platformCode, channelId, platformLocationId, sellPackId, newValue);

        // Snapshot the intended quantity even before the adapter call so the UI reflects intent
        channelService.upsertInventorySnapshot(sellPackId, locationNanoId, newValue);
    }

    /**
     * Push to the single platform-level inventory (no-location platforms, e.g. Shopee, Cyberbiz).
     */
    private void handleSingleLocation(String platformCode, String channelId, String sellPackId,
                                       String channelProductId, String channelSpecId,
                                       int newValue, Channel channel) {
        try {
            cyberbizAdapter.setCredentials(channel.getToken(), channel.getToken2());
            cyberbizAdapter.updateVariantInventory(channelProductId, channelSpecId, newValue);
            log.info("UPDATE_INVENTORY success: platform={} channel={} sellPackId={} qty={}",
                    platformCode, channelId, sellPackId, newValue);

            // Snapshot after confirmed push
            channelService.upsertInventorySnapshot(sellPackId, null, newValue);

        } catch (Exception e) {
            log.error("UPDATE_INVENTORY failed: platform={} channel={} sellPackId={}",
                    platformCode, channelId, sellPackId, e);
            throw new RuntimeException("UPDATE_INVENTORY failed for sellPackId=" + sellPackId, e);
        }
    }
}
