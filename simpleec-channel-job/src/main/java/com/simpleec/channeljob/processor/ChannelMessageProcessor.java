package com.simpleec.channeljob.processor;

import com.simpleec.channeljob.service.ChannelService;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import com.simpleec.channeljob.entity.ChannelMessage;
import com.simpleec.channeljob.entity.ChannelSyncLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Processes incoming channel messages from Kafka
 * Implements enable_sync gate before processing
 * NOTE: Disabled - requires JPA which is disabled in Kafka consumer job
 */
@Slf4j
//@Component
public class ChannelMessageProcessor {

    private final ChannelService channelService;
    private final ChannelSyncLogRepository channelSyncLogRepository;

    public ChannelMessageProcessor(ChannelService channelService,
                                   ChannelSyncLogRepository channelSyncLogRepository) {
        this.channelService = channelService;
        this.channelSyncLogRepository = channelSyncLogRepository;
    }

    /**
     * Process incoming channel message with enable_sync gate
     *
     * @param message Incoming message from Kafka
     * @return true if processing succeeded, false if skipped or failed
     */
    public boolean processMessage(ChannelMessage message) {
        String channelId = message.getChannelId();
        String merchantId = message.getMerchantId();

        try {
            // [GATE 1: Check enable_sync]
            if (!channelService.isChannelEnabled(channelId)) {
                log.info("Channel {} disabled (enable_sync=false), skipping", channelId);

                // Record skip in logs
                recordSyncLog(merchantId, channelId, 200, "SKIPPED", "Channel disabled");
                return false;
            }

            // Channel enabled - proceed with processing
            log.info("Processing message for channel {}, task: {}", channelId, message.getTaskType());

            boolean success = channelService.fetchFromPlatform(channelId, message);

            if (success) {
                recordSyncLog(merchantId, channelId, 200, "SUCCESS", null);
            } else {
                recordSyncLog(merchantId, channelId, 500, "FAILED", "Processing failed");
            }

            return success;

        } catch (Exception e) {
            log.error("Error processing message for channel {}", channelId, e);
            recordSyncLog(merchantId, channelId, 500, "ERROR", e.getMessage());
            return false;
        }
    }

    private void recordSyncLog(String merchantId, String channelId,
                               int httpStatus, String status, String errorMessage) {
        try {
            ChannelSyncLog log = new ChannelSyncLog();
            log.setId(UUID.randomUUID().toString());
            log.setMerchantId(merchantId);
            log.setChannelId(channelId);
            log.setHttpStatus(httpStatus);
            log.setStatus(status);
            log.setErrorMessage(errorMessage);
            log.setCreatedAt(LocalDateTime.now());

            channelSyncLogRepository.save(log);
        } catch (Exception e) {
            log.warn("Failed to record sync log for channel {}", channelId, e);
        }
    }
}
