package com.simpleec.channeljob.service;

import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.entity.ChannelMessage;
import com.simpleec.channeljob.repository.ChannelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for channel operations
 * NOTE: Disabled - requires JPA which is disabled in Kafka consumer job
 */
@Slf4j
//@Service
public class ChannelService {

    private final ChannelRepository channelRepository;

    public ChannelService(ChannelRepository channelRepository) {
        this.channelRepository = channelRepository;
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
}
