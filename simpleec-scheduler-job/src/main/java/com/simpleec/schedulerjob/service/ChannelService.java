package com.simpleec.schedulerjob.service;

import com.simpleec.schedulerjob.entity.Channel;
import com.simpleec.schedulerjob.entity.Platform;
import com.simpleec.schedulerjob.repository.ChannelRepository;
import com.simpleec.schedulerjob.repository.PlatformRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for channel and platform operations
 */
@Slf4j
@Service
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final PlatformRepository platformRepository;

    public ChannelService(ChannelRepository channelRepository,
                         PlatformRepository platformRepository) {
        this.channelRepository = channelRepository;
        this.platformRepository = platformRepository;
    }

    /**
     * Get all active channels for health checking.
     * Health check applies to all actived channels regardless of enableSync,
     * so we can detect dead tokens/credentials even on non-syncing channels.
     */
    public List<Channel> findEnabledChannels() {
        return channelRepository.findByActivedTrue();
    }

    /**
     * Get all active platforms
     */
    public List<Platform> findActivePlatforms() {
        return platformRepository.findAllActive();
    }

    /**
     * Find platform by ID
     */
    public Platform findPlatformById(String platformId) {
        return platformRepository.findById(platformId).orElse(null);
    }
}
