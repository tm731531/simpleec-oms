package com.simpleec.channeljob.controller;

import com.simpleec.channeljob.entity.ChannelSyncLog;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import com.simpleec.channeljob.service.HealthCheckService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API endpoints for health check status queries
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
public class HealthCheckController {

    private final HealthCheckService healthCheckService;
    private final ChannelSyncLogRepository channelSyncLogRepository;

    public HealthCheckController(HealthCheckService healthCheckService,
                               ChannelSyncLogRepository channelSyncLogRepository) {
        this.healthCheckService = healthCheckService;
        this.channelSyncLogRepository = channelSyncLogRepository;
    }

    /**
     * Get health status for a specific channel
     */
    @GetMapping("/channel/{channelId}")
    public ResponseEntity<Map<String, Object>> getChannelHealth(@PathVariable String channelId) {
        log.info("Querying health status for channel {}", channelId);

        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck(channelId);

        Map<String, Object> response = new HashMap<>();
        response.put("httpStatus", result.getHttpStatus());
        response.put("health", result.getHealth());
        response.put("errorMessage", result.getErrorMessage());

        return ResponseEntity.ok(response);
    }

    /**
     * Get health status for a specific platform
     */
    @GetMapping("/platform/{platformCode}")
    public ResponseEntity<Map<String, Object>> getPlatformHealth(@PathVariable String platformCode) {
        log.info("Querying health status for platform {}", platformCode);

        HealthCheckService.HealthCheckResult result = healthCheckService.performPlatformHealthCheck(platformCode);

        Map<String, Object> response = new HashMap<>();
        response.put("httpStatus", result.getHttpStatus());
        response.put("health", result.getHealth());
        response.put("errorMessage", result.getErrorMessage());

        return ResponseEntity.ok(response);
    }

    /**
     * Get recent health check history for a channel
     */
    @GetMapping("/channel/{channelId}/history")
    public ResponseEntity<Map<String, Object>> getChannelHealthHistory(
            @PathVariable String channelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Fetching health history for channel {} (page: {}, size: {})", channelId, page, size);

        Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size,
            org.springframework.data.domain.Sort.by("createdAt").descending());

        Page<ChannelSyncLog> logs = channelSyncLogRepository.findByChannelId(channelId, pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("channelId", channelId);
        response.put("totalRecords", logs.getTotalElements());
        response.put("page", page);
        response.put("size", size);
        response.put("logs", logs.getContent());

        return ResponseEntity.ok(response);
    }

    /**
     * Get recent health check history for a platform
     */
    @GetMapping("/platform/{platformCode}/history")
    public ResponseEntity<Map<String, Object>> getPlatformHealthHistory(
            @PathVariable String platformCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Fetching health history for platform {} (page: {}, size: {})", platformCode, page, size);

        Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size,
            org.springframework.data.domain.Sort.by("createdAt").descending());

        Page<ChannelSyncLog> logs = channelSyncLogRepository.findByChannelIdIsNullOrderByCreatedAtDesc(pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("platformCode", platformCode);
        response.put("totalRecords", logs.getTotalElements());
        response.put("page", page);
        response.put("size", size);
        response.put("logs", logs.getContent());

        return ResponseEntity.ok(response);
    }

    /**
     * Get aggregate health summary
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getHealthSummary() {
        log.info("Fetching health summary");

        // Get total checks
        long totalChecks = channelSyncLogRepository.count();

        // Get recent checks (last 100)
        List<ChannelSyncLog> recentLogs = channelSyncLogRepository.findTop100ByOrderByCreatedAtDesc();

        long healthyCount = recentLogs.stream()
            .filter(log -> log.getHttpStatus() < 400)
            .count();

        long unhealthyCount = recentLogs.size() - healthyCount;

        Map<String, Object> response = new HashMap<>();
        response.put("totalChecks", totalChecks);
        response.put("recentChecks", recentLogs.size());
        response.put("healthyCount", healthyCount);
        response.put("unhealthyCount", unhealthyCount);
        response.put("healthPercentage", recentLogs.isEmpty() ? 0 : (healthyCount * 100.0 / recentLogs.size()));

        return ResponseEntity.ok(response);
    }
}
