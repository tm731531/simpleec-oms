package com.simpleec.channeljob.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.client.PlatformApiClient;
import com.simpleec.channeljob.repository.ChannelRepository;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import com.simpleec.channeljob.entity.ChannelSyncLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for performing health checks on channels and platforms
 * Records results to channel_sync_logs table
 */
@Slf4j
@Service
public class HealthCheckService {

    private static final String HEALTH_CACHE_PREFIX = "channel:health:";
    private static final Duration HEALTH_CACHE_TTL  = Duration.ofMinutes(10);

    private final ChannelRepository        channelRepository;
    private final ChannelSyncLogRepository channelSyncLogRepository;
    private final PlatformApiClient        platformApiClient;
    private final StringRedisTemplate      redisTemplate;
    private final ObjectMapper             objectMapper;

    public HealthCheckService(ChannelRepository channelRepository,
                              ChannelSyncLogRepository channelSyncLogRepository,
                              PlatformApiClient platformApiClient,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper) {
        this.channelRepository        = channelRepository;
        this.channelSyncLogRepository = channelSyncLogRepository;
        this.platformApiClient        = platformApiClient;
        this.redisTemplate            = redisTemplate;
        this.objectMapper             = objectMapper;
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
        String health = httpStatus >= 400 ? "unhealthy" : "healthy";
        try {
            ChannelSyncLog syncLog = new ChannelSyncLog();
            syncLog.setId(UUID.randomUUID().toString());
            syncLog.setChannelId(channelId);
            syncLog.setMerchantId(merchantId);
            syncLog.setSyncType("CHANNEL_HEALTH_CHECK");
            syncLog.setHttpStatus(httpStatus);
            syncLog.setStatus(httpStatus >= 400 ? "failed" : "success");
            syncLog.setHealth(health);
            syncLog.setErrorMessage(errorMessage);
            syncLog.setCreatedAt(LocalDateTime.now());
            channelSyncLogRepository.save(syncLog);
        } catch (Exception e) {
            log.warn("Failed to record health log for channel {}", channelId, e);
        }

        // 寫 Redis cache — 過期自動清除，前端查到 null 顯示 unknown
        try {
            Map<String, Object> cache = new HashMap<>();
            cache.put("health", health);
            cache.put("httpStatus", httpStatus);
            cache.put("checkedAt", LocalDateTime.now().toString());
            cache.put("errorMessage", errorMessage);
            if (platformCode != null) cache.put("platformId", platformCode);
            redisTemplate.opsForValue().set(
                HEALTH_CACHE_PREFIX + channelId,
                objectMapper.writeValueAsString(cache),
                HEALTH_CACHE_TTL
            );
        } catch (Exception e) {
            log.warn("Failed to write health cache for channel {}", channelId, e);
        }
    }

    private void recordPlatformHealthLog(String platformCode, int httpStatus, String errorMessage) {
        try {
            ChannelSyncLog log = new ChannelSyncLog();
            log.setId(UUID.randomUUID().toString());
            log.setChannelId("PLATFORM_CHECK"); // Platform-level check marker
            log.setMerchantId("SYSTEM"); // System-level check
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
