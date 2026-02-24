package com.simpleec.schedulerjob.scheduler;

import com.simpleec.schedulerjob.service.ChannelService;
import com.simpleec.schedulerjob.kafka.KafkaProducer;
import com.simpleec.schedulerjob.entity.Channel;
import com.simpleec.schedulerjob.entity.Platform;
import com.simpleec.schedulerjob.dto.HealthCheckMessage;
import com.simpleec.schedulerjob.dto.PlatformHealthCheckMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduler that runs health checks every 5 minutes
 * Publishes CHECK_HEALTH tasks for channels and platforms
 */
@Slf4j
@Component
public class HealthCheckScheduler {

    private final ChannelService channelService;
    private final KafkaProducer kafkaProducer;

    public HealthCheckScheduler(ChannelService channelService, KafkaProducer kafkaProducer) {
        this.channelService = channelService;
        this.kafkaProducer = kafkaProducer;
    }

    /**
     * Run every 5 minutes to publish health check tasks
     * Sends CHECK_HEALTH task for each enabled channel
     * Sends CHECK_HEALTH_PLATFORM task for each active platform
     */
    @Scheduled(fixedRate = 300000) // 5 minutes = 300000 ms
    public void runHealthCheck() {
        log.info("Starting health check cycle...");

        try {
            publishChannelHealthChecks();
            publishPlatformHealthChecks();
            log.info("Health check cycle completed");
        } catch (Exception e) {
            log.error("Error in health check cycle", e);
        }
    }

    /**
     * Publish health check tasks for all enabled channels
     */
    private void publishChannelHealthChecks() {
        // Get all enabled channels
        List<Channel> enabledChannels = channelService.findEnabledChannels();

        log.info("Publishing health checks for {} enabled channels", enabledChannels.size());

        for (Channel channel : enabledChannels) {
            try {
                // Query Platform to get the correct kafka topic
                Platform platform = channelService.findPlatformById(channel.getPlatformId());
                if (platform == null || platform.getQueueTopic() == null) {
                    log.warn("Platform {} not found or has no queue_topic configured", channel.getPlatformId());
                    continue;
                }

                // Publish CHECK_HEALTH task to {queueTopic}.fast topic
                String topic = platform.getQueueTopic() + ".fast";

                HealthCheckMessage message = new HealthCheckMessage();
                message.setTaskType("CHECK_HEALTH");
                message.setMerchantId(channel.getMerchantId());
                message.setChannelId(channel.getId());
                message.setPlatformCode(channel.getPlatformId());
                message.setTimestamp(System.currentTimeMillis());

                kafkaProducer.publishToTopic(topic, channel.getId(), message);
                log.debug("Published health check for channel {} to topic {}", channel.getId(), topic);

            } catch (Exception e) {
                log.error("Error publishing health check for channel {}", channel.getId(), e);
            }
        }
    }

    /**
     * Publish health check tasks for all active platforms
     */
    private void publishPlatformHealthChecks() {
        // Get all active platforms
        List<Platform> activePlatforms = channelService.findActivePlatforms();

        log.info("Publishing platform health checks for {} platforms", activePlatforms.size());

        for (Platform platform : activePlatforms) {
            try {
                // Check if platform has queue_topic configured
                if (platform.getQueueTopic() == null) {
                    log.warn("Platform {} has no queue_topic configured", platform.getId());
                    continue;
                }

                // Publish CHECK_HEALTH_PLATFORM task to {queueTopic}.fast topic
                String topic = platform.getQueueTopic() + ".fast";

                PlatformHealthCheckMessage message = new PlatformHealthCheckMessage();
                message.setTaskType("CHECK_HEALTH_PLATFORM");
                message.setPlatformCode(platform.getId());
                message.setTimestamp(System.currentTimeMillis());

                kafkaProducer.publishToTopic(topic, platform.getId(), message);
                log.debug("Published platform health check for {} to topic {}", platform.getId(), topic);

            } catch (Exception e) {
                log.error("Error publishing platform health check for {}", platform.getId(), e);
            }
        }
    }
}
