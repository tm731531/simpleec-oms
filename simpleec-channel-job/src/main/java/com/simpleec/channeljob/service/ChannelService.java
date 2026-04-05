package com.simpleec.channeljob.service;

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

    public ChannelService(ChannelRepository channelRepository, JdbcTemplate jdbcTemplate) {
        this.channelRepository = channelRepository;
        this.jdbcTemplate = jdbcTemplate;
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
