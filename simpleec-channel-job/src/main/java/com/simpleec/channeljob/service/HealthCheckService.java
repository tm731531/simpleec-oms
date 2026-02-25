package com.simpleec.channeljob.service;

import com.simpleec.core.entity.Platform;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.client.PlatformApiClient;
import com.simpleec.channeljob.repository.ChannelRepository;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import com.simpleec.channeljob.entity.ChannelSyncLog;
import com.simpleec.core.repository.PlatformRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Service for performing health checks on channels and platforms
 * Records results to channel_sync_logs table
 */
@Slf4j
@Service
public class HealthCheckService {

    private final ChannelRepository channelRepository;
    private final ChannelSyncLogRepository channelSyncLogRepository;
    private final PlatformRepository platformRepository;
    private final PlatformApiClient platformApiClient;

    public HealthCheckService(ChannelRepository channelRepository,
                              ChannelSyncLogRepository channelSyncLogRepository,
                              PlatformRepository platformRepository,
                              PlatformApiClient platformApiClient) {
        this.channelRepository = channelRepository;
        this.channelSyncLogRepository = channelSyncLogRepository;
        this.platformRepository = platformRepository;
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

    /**
     * 記錄通路檢查：需要 platformId + merchantId + channelId
     */
    private void recordHealthLog(String channelId, String merchantId, String platformId,
                                 int httpStatus, String errorMessage) {
        try {
            ChannelSyncLog log = new ChannelSyncLog();
            log.setId(UUID.randomUUID().toString());
            log.setPlatformId(platformId);     // 必填
            log.setMerchantId(merchantId);     // 可選
            log.setChannelId(channelId);       // 可選
            log.setSyncType("CHANNEL_HEALTH_CHECK");
            log.setHttpStatus(httpStatus);
            log.setStatus(httpStatus >= 400 ? "failed" : "success");
            log.setHealth(httpStatus >= 400 ? "unhealthy" : "healthy");
            log.setErrorMessage(errorMessage);
            log.setCreatedAt(LocalDateTime.now());

            channelSyncLogRepository.save(log);
        } catch (Exception e) {
            log.warn("Failed to record health log for channel {}", channelId, e);
        }
    }

    /**
     * 記錄平台檢查：只需要 platformId
     * platformCode 如 "CYBERBIZ"、"SHOPEE" 需要查詢 Platform 表獲得真實的 platformId
     */
    private void recordPlatformHealthLog(String platformCode, int httpStatus, String errorMessage) {
        try {
            // 根據 platformCode（大寫如 "CYBERBIZ"）查詢 Platform 實體
            // Platform 的 platformName 是小寫的（"cyberbiz"）
            Platform platform = platformRepository.findByPlatformName(platformCode.toLowerCase())
                .orElse(null);

            if (platform == null) {
                log.warn("Platform {} not found for health check", platformCode);
                // 如果找不到，直接記錄，但 platformId 為 null（會導致數據庫約束違反）
                // 這種情況下應該告警但不保存
                return;
            }

            ChannelSyncLog log = new ChannelSyncLog();
            log.setId(UUID.randomUUID().toString());
            log.setPlatformId(platform.getId());  // 使用真實的 platformId（NanoID）
            // 平台級檢查不設置 merchantId 和 channelId
            log.setSyncType("PLATFORM_HEALTH_CHECK");
            log.setHttpStatus(httpStatus);
            log.setStatus(httpStatus >= 400 ? "failed" : "success");
            log.setHealth(httpStatus >= 400 ? "unhealthy" : "healthy");
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
