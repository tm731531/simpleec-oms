package com.simpleec.channeljob.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.entity.ChannelMessage;
import com.simpleec.channeljob.repository.ChannelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

/**
 * Service for channel operations
 * Provides methods to query channel data from the database
 */
@Slf4j
@Service
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChannelService(ChannelRepository channelRepository, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.channelRepository = channelRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Check if channel is enabled for sync
     */
    public boolean isChannelEnabled(String channelId) {
        Channel channel = channelRepository.findById(channelId)
            .orElse(null);

        if (channel == null) {
            log.warn("Channel {} not found", channelId);
            return false;
        }

        return channel.getEnableSync() != null && channel.getEnableSync();
    }

    /**
     * Fetch data from platform (placeholder)
     */
    public boolean fetchFromPlatform(String channelId, ChannelMessage message) {
        log.info("Fetching from platform for channel {} with taskType {}",
            channelId, message.getTaskType());

        // Placeholder - actual implementation would call platform API
        return true;
    }

    /**
     * Get channel token by ID
     */
    public String getChannelToken(String channelId) {
        Channel channel = channelRepository.findById(channelId)
            .orElse(null);

        if (channel == null) {
            log.warn("Channel {} not found", channelId);
            return null;
        }

        return channel.getToken();
    }

    /**
     * Get channel by ID (returns full Channel object with token and token2)
     */
    public Channel getChannel(String channelId) {
        return channelRepository.findById(channelId)
            .orElse(null);
    }

    /**
     * Returns the sync-target channel_location for a multi-location channel,
     * or empty if the channel has no sync-target configured.
     * Result map keys: "id" (NanoID), "platformLocationId"
     */
    public java.util.Optional<java.util.Map<String, String>> getSyncTargetLocation(String channelId) {
        try {
            var rows = jdbcTemplate.queryForList(
                "SELECT id, platform_location_id FROM channel_location " +
                "WHERE channel_id = ? AND is_sync_target = true LIMIT 1",
                channelId);
            if (rows.isEmpty()) return java.util.Optional.empty();
            var row = rows.get(0);
            return java.util.Optional.of(java.util.Map.of(
                "id", row.get("id") != null ? row.get("id").toString() : "",
                "platformLocationId", row.get("platform_location_id") != null ? row.get("platform_location_id").toString() : ""
            ));
        } catch (Exception e) {
            log.warn("Failed to query sync-target location for channel {}: {}", channelId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Upsert sell_pack_inventory snapshot after a successful platform push.
     * channelLocationId may be null for no-location platforms.
     */
    public void upsertInventorySnapshot(String sellPackId, String channelLocationId, int quantity) {
        try {
            String id = com.simpleec.common.util.NanoIdUtil.generate();
            if (channelLocationId == null) {
                jdbcTemplate.update("""
                    INSERT INTO sell_pack_inventory (id, sell_pack_id, channel_location_id, quantity, last_synced_at, created_at, updated_at)
                    VALUES (?, ?, NULL, ?, now(), now(), now())
                    ON CONFLICT (sell_pack_id) WHERE channel_location_id IS NULL
                    DO UPDATE SET quantity = EXCLUDED.quantity, last_synced_at = now(), updated_at = now()
                    """, id, sellPackId, quantity);
            } else {
                jdbcTemplate.update("""
                    INSERT INTO sell_pack_inventory (id, sell_pack_id, channel_location_id, quantity, last_synced_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, now(), now(), now())
                    ON CONFLICT (sell_pack_id, channel_location_id) WHERE channel_location_id IS NOT NULL
                    DO UPDATE SET quantity = EXCLUDED.quantity, last_synced_at = now(), updated_at = now()
                    """, id, sellPackId, channelLocationId, quantity);
            }
            log.debug("Inventory snapshot updated: sellPackId={} location={} qty={}", sellPackId, channelLocationId, quantity);
        } catch (Exception e) {
            log.error("Failed to upsert inventory snapshot for sellPackId={} location={}: {}",
                sellPackId, channelLocationId, e.getMessage());
        }
    }

    /**
     * Read platform capabilities JSONB for a given channelId.
     * Used by handlers to make capability-driven decisions instead of hardcoding platform names.
     *
     * Example: capabilities.path("supportsShipment").asBoolean(false)
     */
    public JsonNode getPlatformCapabilities(String channelId) {
        try {
            var rows = jdbcTemplate.queryForList(
                "SELECT p.capabilities FROM platform p " +
                "JOIN channel c ON c.platform_id = p.id " +
                "WHERE c.id = ?",
                channelId);
            if (rows.isEmpty()) {
                return objectMapper.createObjectNode();
            }
            Object capObj = rows.get(0).get("capabilities");
            return objectMapper.readTree(capObj != null ? capObj.toString() : "{}");
        } catch (Exception e) {
            log.warn("Failed to read platform capabilities for channel {}: {}", channelId, e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /**
     * Look up sell_pack's platform-specific IDs by internal sellPackId.
     * Used by outbound handlers (UPDATE_INVENTORY, UPDATE_PRICE) to translate
     * OMS IDs → platform IDs at the Channel Job layer.
     *
     * @return Map with "channelProductId" and "channelSpecId", or null if not found
     */
    public Map<String, String> getSellPackChannelIds(String sellPackId) {
        var rows = jdbcTemplate.queryForList(
            "SELECT channel_product_id, channel_spec_id FROM sell_pack WHERE id = ?",
            sellPackId);
        if (rows.isEmpty()) {
            log.warn("sell_pack not found: {}", sellPackId);
            return null;
        }
        var row = rows.get(0);
        return Map.of(
            "channelProductId", row.get("channel_product_id") != null ? row.get("channel_product_id").toString() : "",
            "channelSpecId", row.get("channel_spec_id") != null ? row.get("channel_spec_id").toString() : ""
        );
    }
}
