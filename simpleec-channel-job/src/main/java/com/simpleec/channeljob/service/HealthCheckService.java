package com.simpleec.channeljob.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.client.PlatformApiClient;
import com.simpleec.channeljob.repository.ChannelRepository;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import com.simpleec.channeljob.entity.ChannelSyncLog;
import com.simpleec.common.util.NanoIdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for performing health checks on channels and platforms
 * Records results to channel_sync_logs table
 */
@Slf4j
@Service
public class HealthCheckService {

    private static final String HEALTH_CACHE_PREFIX    = "channel:health:";
    private static final String PLATFORM_CACHE_PREFIX  = "platform:health:";
    private static final Duration HEALTH_CACHE_TTL     = Duration.ofMinutes(10);

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
                recordHealthLog(channelId, null, 404, "Channel not found");
                return new HealthCheckResult(404, "unhealthy", "Channel not found");
            }

            // Call platform API with channel's token
            int httpStatus = platformApiClient.healthCheck(
                channel.getPlatformId(),
                channel.getToken(),
                channel.getToken2()
            );

            String health       = channelHealth(httpStatus);
            String errorMessage = healthError(httpStatus, channel.getPlatformId());

            recordHealthLog(channelId, channel.getPlatformId(), httpStatus, errorMessage);

            return new HealthCheckResult(httpStatus, health, errorMessage);

        } catch (Exception e) {
            log.error("Error performing health check for channel {}", channelId, e);
            recordHealthLog(channelId, null, 500, e.getMessage());
            return new HealthCheckResult(500, "unhealthy", e.getMessage());
        }
    }

    /**
     * Perform health check for entire platform (no auth needed)
     */
    public HealthCheckResult performPlatformHealthCheck(String platformCode) {
        try {
            int httpStatus = platformApiClient.platformHealthCheck(platformCode);

            // Platform ping: any HTTP response (even 4xx) means API is reachable → healthy
            // Only 5xx / 0 (unreachable / unknown) → unhealthy or unknown
            String health       = platformHealth(httpStatus);
            // Platform ping: 4xx is expected (no auth). Only record error for 5xx or unreachable (0).
            String errorMessage = (httpStatus == 0 || httpStatus >= 500) ? healthError(httpStatus, platformCode) : null;

            recordPlatformHealthLog(platformCode, httpStatus, errorMessage);
            return new HealthCheckResult(httpStatus, health, errorMessage);

        } catch (Exception e) {
            log.error("Error performing platform health check for {}", platformCode, e);
            recordPlatformHealthLog(platformCode, 500, e.getMessage());
            return new HealthCheckResult(500, "unhealthy", e.getMessage());
        }
    }

    /**
     * Channel health: 2xx = healthy, 401/403 = unhealthy (bad token), 0 = unknown (not configured)
     */
    private static String channelHealth(int httpStatus) {
        if (httpStatus == 0)                        return "unknown";
        if (httpStatus == 401 || httpStatus == 403) return "unhealthy";
        if (httpStatus >= 500)                      return "unhealthy";
        if (httpStatus >= 200 && httpStatus < 400)  return "healthy";
        return "unhealthy";
    }

    /**
     * Platform health: ping an API endpoint (no auth).
     * 2xx/3xx = healthy (endpoint responds normally)
     * 401/403 = healthy (endpoint exists, auth required — expected when pinging without credentials)
     * 404 = unhealthy (URL is wrong / endpoint does not exist — ping URL misconfigured)
     * 5xx/0 = unhealthy (server error or unreachable)
     */
    private static String platformHealth(int httpStatus) {
        if (httpStatus == 0)                        return "unknown";
        if (httpStatus >= 500)                      return "unhealthy";
        if (httpStatus == 404)                      return "unhealthy"; // wrong URL
        if (httpStatus == 401 || httpStatus == 403) return "healthy";   // endpoint exists, auth needed
        if (httpStatus >= 200 && httpStatus < 400)  return "healthy";
        return "unhealthy";
    }

    private static String healthError(int httpStatus, String context) {
        return switch (httpStatus) {
            case 0   -> null; // unknown — not an error, just not checked
            case 401 -> "Unauthorized: Token invalid or expired";
            case 403 -> "Forbidden: Insufficient permissions";
            case 404 -> "Platform endpoint not found (wrong URL)";
            case 500 -> "Platform service error";
            case 503 -> "Platform service unavailable";
            default  -> httpStatus >= 400 ? "HTTP " + httpStatus : null;
        };
    }

    private void recordHealthLog(String channelId, String platformId,
                                 int httpStatus, String errorMessage) {
        String health = channelHealth(httpStatus);
        try {
            ChannelSyncLog syncLog = new ChannelSyncLog();
            syncLog.setId(NanoIdUtil.generate());
            syncLog.setChannelId(channelId);
            syncLog.setPlatformId(platformId);
            syncLog.setSyncType("CHANNEL_HEALTH_CHECK");
            syncLog.setHttpStatus(httpStatus);
            syncLog.setStatus("unknown".equals(health) ? "skipped" : (httpStatus >= 400 ? "failed" : "success"));
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
            if (platformId != null) cache.put("platformId", platformId);
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
        String health = platformHealth(httpStatus);
        try {
            ChannelSyncLog syncLog = new ChannelSyncLog();
            syncLog.setId(NanoIdUtil.generate());
            syncLog.setChannelId(null);  // 平台檢查，無 channel_id
            syncLog.setPlatformId(platformCode);
            syncLog.setSyncType("PLATFORM_HEALTH_CHECK");
            syncLog.setHttpStatus(httpStatus);
            syncLog.setStatus("unknown".equals(health) ? "skipped" : (httpStatus >= 500 ? "failed" : "success"));
            syncLog.setHealth(health);
            syncLog.setErrorMessage(errorMessage);
            syncLog.setCreatedAt(LocalDateTime.now());
            channelSyncLogRepository.save(syncLog);
        } catch (Exception e) {
            log.warn("Failed to record platform health log for {}", platformCode, e);
        }

        // 寫 Redis cache — platform:health:{platformCode}，供前端顯示平台整體狀態
        try {
            Map<String, Object> cache = new HashMap<>();
            cache.put("health",       health);
            cache.put("httpStatus",   httpStatus);
            cache.put("checkedAt",    LocalDateTime.now().toString());
            cache.put("errorMessage", errorMessage);
            redisTemplate.opsForValue().set(
                PLATFORM_CACHE_PREFIX + platformCode,
                objectMapper.writeValueAsString(cache),
                HEALTH_CACHE_TTL
            );
        } catch (Exception e) {
            log.warn("Failed to write platform health cache for {}", platformCode, e);
        }
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
