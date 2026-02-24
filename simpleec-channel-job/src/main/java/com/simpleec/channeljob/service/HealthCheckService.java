package com.simpleec.channeljob.service;

import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.client.PlatformApiClient;
import com.simpleec.channeljob.repository.ChannelRepository;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import com.simpleec.channeljob.entity.ChannelSyncLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Service for performing health checks on channels and platforms
 * NOTE: Disabled - requires JPA which is disabled in Kafka consumer job
 */
@Slf4j
//@Service
public class HealthCheckService {

    private final ChannelRepository channelRepository;
    private final ChannelSyncLogRepository channelSyncLogRepository;
    private final PlatformApiClient platformApiClient;

    public HealthCheckService(ChannelRepository channelRepository,
                              ChannelSyncLogRepository channelSyncLogRepository,
                              PlatformApiClient platformApiClient) {
        this.channelRepository = channelRepository;
        this.channelSyncLogRepository = channelSyncLogRepository;
        this.platformApiClient = platformApiClient;
    }

    /**
     * Perform health check for specific channel
     * Returns HTTP status and health status
     */
    public HealthCheckResult performChannelHealthCheck(String channelId) {
        try {
            Channel channel = channelRepository.findById(channelId)
                .orElse(null);

            if (channel == null) {
                log.warn("Channel {} not found", channelId);
                recordHealthLog(channelId, null, null, 404, "Channel not found");
                return new HealthCheckResult(404, "unhealthy", "Channel not found");
            }

            // Call platform API with channel's token
            int httpStatus = platformApiClient.healthCheck(
                channel.getPlatformId(),
                channel.getToken()
            );

            String errorMessage = httpStatus >= 400
                ? getPlatformErrorMessage(httpStatus, channel.getPlatformId())
                : null;

            recordHealthLog(
                channelId,
                channel.getMerchantId(),
                channel.getPlatformId(),
                httpStatus,
                errorMessage
            );

            return new HealthCheckResult(
                httpStatus,
                httpStatus >= 400 ? "unhealthy" : "healthy",
                errorMessage
            );

        } catch (Exception e) {
            log.error("Error performing health check for channel {}", channelId, e);
            recordHealthLog(channelId, null, null, 500, e.getMessage());
            return new HealthCheckResult(500, "unhealthy", e.getMessage());
        }
    }

    /**
     * Perform health check for entire platform (no auth needed)
     */
    public HealthCheckResult performPlatformHealthCheck(String platformCode) {
        try {
            int httpStatus = platformApiClient.platformHealthCheck(platformCode);

            String errorMessage = httpStatus >= 400
                ? getPlatformErrorMessage(httpStatus, platformCode)
                : null;

            recordPlatformHealthLog(platformCode, httpStatus, errorMessage);

            return new HealthCheckResult(
                httpStatus,
                httpStatus >= 400 ? "unhealthy" : "healthy",
                errorMessage
            );

        } catch (Exception e) {
            log.error("Error performing platform health check for {}", platformCode, e);
            recordPlatformHealthLog(platformCode, 500, e.getMessage());
            return new HealthCheckResult(500, "unhealthy", e.getMessage());
        }
    }

    private void recordHealthLog(String channelId, String merchantId, String platformCode,
                                 int httpStatus, String errorMessage) {
        try {
            ChannelSyncLog log = new ChannelSyncLog();
            log.setId(UUID.randomUUID().toString());
            log.setChannelId(channelId);
            log.setMerchantId(merchantId);
            log.setHttpStatus(httpStatus);
            log.setStatus(httpStatus >= 400 ? "failed" : "success");
            log.setErrorMessage(errorMessage);
            log.setCreatedAt(LocalDateTime.now());

            channelSyncLogRepository.save(log);
        } catch (Exception e) {
            log.warn("Failed to record health log for channel {}", channelId, e);
        }
    }

    private void recordPlatformHealthLog(String platformCode, int httpStatus, String errorMessage) {
        try {
            ChannelSyncLog log = new ChannelSyncLog();
            log.setId(UUID.randomUUID().toString());
            log.setChannelId(null); // No specific channel for platform check
            log.setHttpStatus(httpStatus);
            log.setStatus(httpStatus >= 400 ? "failed" : "success");
            log.setErrorMessage(errorMessage);
            log.setCreatedAt(LocalDateTime.now());

            channelSyncLogRepository.save(log);
        } catch (Exception e) {
            log.warn("Failed to record platform health log for {}", platformCode, e);
        }
    }

    private String getPlatformErrorMessage(int httpStatus, String platformCode) {
        return switch (httpStatus) {
            case 401 -> "Unauthorized: Token invalid or expired";
            case 403 -> "Forbidden: Insufficient permissions";
            case 500 -> "Platform service error";
            case 503 -> "Platform service unavailable";
            default -> "HTTP " + httpStatus;
        };
    }

    /**
     * Health check result DTO
     */
    public static class HealthCheckResult {
        public final int httpStatus;
        public final String health;
        public final String errorMessage;

        public HealthCheckResult(int httpStatus, String health, String errorMessage) {
            this.httpStatus = httpStatus;
            this.health = health;
            this.errorMessage = errorMessage;
        }

        public int getHttpStatus() { return httpStatus; }
        public String getHealth() { return health; }
        public String getErrorMessage() { return errorMessage; }
    }
}
