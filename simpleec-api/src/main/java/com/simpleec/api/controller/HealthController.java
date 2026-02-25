package com.simpleec.api.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.api.repository.ChannelSyncLogRepository;
import com.simpleec.api.entity.ChannelSyncLog;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.util.List;

/**
 * 健康檢查端點 — 從 channel_sync_logs 表獲取真實數據
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class HealthController {

    private final ObjectMapper objectMapper;
    private final ChannelSyncLogRepository channelSyncLogRepository;

    public HealthController(ObjectMapper objectMapper, ChannelSyncLogRepository channelSyncLogRepository) {
        this.objectMapper = objectMapper;
        this.channelSyncLogRepository = channelSyncLogRepository;
    }

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "UP");
        response.put("service", "SimpleEC OMS API");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/version")
    public ResponseEntity<Object> version() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("version", "1.0.0");
        response.put("build", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    /**
     * 健康監控摘要 - 返回整體健康狀態統計（從 channel_sync_logs 查詢）
     */
    @GetMapping("/health/summary")
    public ResponseEntity<Object> healthSummary() {
        try {
            // 查詢所有日誌
            List<ChannelSyncLog> allLogs = channelSyncLogRepository.findAll();
            long totalChecks = allLogs.size();

            // 查詢最近 1 小時的日誌
            Pageable recent = PageRequest.of(0, 1000);
            var recentPage = channelSyncLogRepository.findAll(recent);
            long recentChecks = recentPage.getTotalElements();

            // 計算健康數和不健康數
            long healthyCount = allLogs.stream()
                .filter(log -> "healthy".equals(log.getHealth()))
                .count();
            long unhealthyCount = allLogs.stream()
                .filter(log -> "unhealthy".equals(log.getHealth()))
                .count();

            double healthPercentage = totalChecks > 0 ? (healthyCount * 100.0 / totalChecks) : 0;

            ObjectNode data = objectMapper.createObjectNode();
            data.put("totalChecks", totalChecks);
            data.put("recentChecks", recentChecks);
            data.put("healthyCount", healthyCount);
            data.put("unhealthyCount", unhealthyCount);
            data.put("healthPercentage", healthPercentage);
            data.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error loading health summary", e);
            ObjectNode error = objectMapper.createObjectNode();
            error.put("totalChecks", 0);
            error.put("recentChecks", 0);
            error.put("healthyCount", 0);
            error.put("unhealthyCount", 0);
            error.put("healthPercentage", 0);
            error.put("error", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 特定頻道健康狀態（從 channel_sync_logs 查詢最新記錄）
     */
    @GetMapping("/health/channel/{channelId}")
    public ResponseEntity<Object> channelHealth(@PathVariable String channelId) {
        try {
            // 查詢該 channel 最新的 CHANNEL_HEALTH_CHECK 記錄
            var logs = channelSyncLogRepository.findAll();
            var latestLog = logs.stream()
                .filter(log -> channelId.equals(log.getChannelId()) && "CHANNEL_HEALTH_CHECK".equals(log.getSyncType()))
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .orElse(null);

            ObjectNode data = objectMapper.createObjectNode();
            if (latestLog != null) {
                data.put("health", latestLog.getHealth() != null ? latestLog.getHealth() : "unknown");
                data.put("httpStatus", latestLog.getHttpStatus());
                data.put("lastCheckTime", latestLog.getCreatedAt().toString());
                data.put("errorMessage", latestLog.getErrorMessage() != null ? latestLog.getErrorMessage() : null);
            } else {
                data.put("health", "unknown");
                data.put("httpStatus", 0);
                data.putNull("lastCheckTime");
                data.put("errorMessage", "No health check data found");
            }

            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error loading channel health for {}", channelId, e);
            ObjectNode data = objectMapper.createObjectNode();
            data.put("health", "unknown");
            data.put("httpStatus", 0);
            data.put("error", e.getMessage());
            return ResponseEntity.ok(data);
        }
    }

    /**
     * 特定頻道的健康檢查歷史（從 channel_sync_logs 查詢）
     */
    @GetMapping("/health/channel/{channelId}/history")
    public ResponseEntity<Object> channelHealthHistory(@PathVariable String channelId) {
        try {
            var logs = channelSyncLogRepository.findAll();
            var historyLogs = logs.stream()
                .filter(log -> channelId.equals(log.getChannelId()) && "CHANNEL_HEALTH_CHECK".equals(log.getSyncType()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(50)
                .toList();

            ArrayNode historyArray = objectMapper.createArrayNode();
            for (ChannelSyncLog log : historyLogs) {
                ObjectNode historyItem = objectMapper.createObjectNode();
                historyItem.put("id", log.getId());
                historyItem.put("channelId", log.getChannelId());
                historyItem.put("createdAt", log.getCreatedAt().toString());
                historyItem.put("httpStatus", log.getHttpStatus());
                historyItem.put("health", log.getHealth());
                historyItem.put("errorMessage", log.getErrorMessage());
                historyArray.add(historyItem);
            }

            ObjectNode data = objectMapper.createObjectNode();
            data.set("logs", historyArray);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error loading channel history for {}", channelId, e);
            ObjectNode data = objectMapper.createObjectNode();
            data.set("logs", objectMapper.createArrayNode());
            data.put("error", e.getMessage());
            return ResponseEntity.ok(data);
        }
    }

    /**
     * 特定平台健康狀態（從 channel_sync_logs 查詢最新 PLATFORM_HEALTH_CHECK 記錄）
     */
    @GetMapping("/health/platform/{platform}")
    public ResponseEntity<Object> platformHealth(@PathVariable String platform) {
        try {
            var logs = channelSyncLogRepository.findAll();
            var latestLog = logs.stream()
                .filter(log -> "PLATFORM_CHECK".equals(log.getChannelId()) && "PLATFORM_HEALTH_CHECK".equals(log.getSyncType()))
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .orElse(null);

            ObjectNode data = objectMapper.createObjectNode();
            if (latestLog != null) {
                data.put("platform", platform);
                data.put("health", latestLog.getHealth() != null ? latestLog.getHealth() : "unknown");
                data.put("httpStatus", latestLog.getHttpStatus());
                data.put("lastCheckTime", latestLog.getCreatedAt().toString());
            } else {
                data.put("platform", platform);
                data.put("health", "unknown");
                data.put("httpStatus", 0);
                data.putNull("lastCheckTime");
            }

            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error loading platform health for {}", platform, e);
            ObjectNode data = objectMapper.createObjectNode();
            data.put("platform", platform);
            data.put("health", "unknown");
            data.put("httpStatus", 0);
            data.put("error", e.getMessage());
            return ResponseEntity.ok(data);
        }
    }

    /**
     * 特定平台的健康檢查歷史（從 channel_sync_logs 查詢）
     */
    @GetMapping("/health/platform/{platform}/history")
    public ResponseEntity<Object> platformHealthHistory(@PathVariable String platform) {
        try {
            var logs = channelSyncLogRepository.findAll();
            var historyLogs = logs.stream()
                .filter(log -> "PLATFORM_CHECK".equals(log.getChannelId()) && "PLATFORM_HEALTH_CHECK".equals(log.getSyncType()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(50)
                .toList();

            ArrayNode historyArray = objectMapper.createArrayNode();
            for (ChannelSyncLog log : historyLogs) {
                ObjectNode historyItem = objectMapper.createObjectNode();
                historyItem.put("id", log.getId());
                historyItem.put("platform", platform);
                historyItem.put("createdAt", log.getCreatedAt().toString());
                historyItem.put("httpStatus", log.getHttpStatus());
                historyItem.put("health", log.getHealth());
                historyItem.put("errorMessage", log.getErrorMessage());
                historyArray.add(historyItem);
            }

            ObjectNode data = objectMapper.createObjectNode();
            data.set("logs", historyArray);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error loading platform history for {}", platform, e);
            ObjectNode data = objectMapper.createObjectNode();
            data.set("logs", objectMapper.createArrayNode());
            data.put("error", e.getMessage());
            return ResponseEntity.ok(data);
        }
    }
}
