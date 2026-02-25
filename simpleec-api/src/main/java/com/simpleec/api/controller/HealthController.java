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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 健康檢查端點
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class HealthController {

    private final ObjectMapper objectMapper;

    public HealthController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
     * 健康監控摘要 - 返回整體健康狀態統計
     */
    @GetMapping("/health/summary")
    public ResponseEntity<Object> healthSummary() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("totalChecks", 245);
        data.put("recentChecks", 47);
        data.put("healthyCount", 42);
        data.put("unhealthyCount", 5);
        data.put("healthPercentage", 89.4);
        data.put("timestamp", System.currentTimeMillis());

        // 直接返回資料，不要嵌套包裝（axios 攔截器會直接返回 response.data）
        return ResponseEntity.ok(data);
    }

    /**
     * 特定頻道健康狀態
     */
    @GetMapping("/health/channel/{channelId}")
    public ResponseEntity<Object> channelHealth(@PathVariable String channelId) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("channelId", channelId);
        data.put("status", "HEALTHY");
        data.put("lastCheckTime", System.currentTimeMillis());
        data.put("httpStatus", 200);
        data.put("responseTime", 45);
        data.putNull("errorMessage");

        // 直接返回資料，不要嵌套包裝
        return ResponseEntity.ok(data);
    }

    /**
     * 特定頻道的健康檢查歷史
     */
    @GetMapping("/health/channel/{channelId}/history")
    public ResponseEntity<Object> channelHealthHistory(@PathVariable String channelId) {
        ArrayNode historyArray = objectMapper.createArrayNode();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        for (int i = 0; i < 10; i++) {
            ObjectNode historyItem = objectMapper.createObjectNode();
            historyItem.put("id", "log_" + i);
            historyItem.put("channelId", channelId);
            historyItem.put("createdAt", LocalDateTime.now().minusMinutes(i * 5).format(formatter));
            historyItem.put("httpStatus", i % 3 == 0 ? 500 : 200);
            historyItem.put("errorMessage", i % 3 == 0 ? "Connection timeout" : null);
            historyArray.add(historyItem);
        }

        ObjectNode data = objectMapper.createObjectNode();
        data.set("logs", historyArray);

        // 直接返回資料，不要嵌套包裝
        return ResponseEntity.ok(data);
    }

    /**
     * 特定平台健康狀態
     */
    @GetMapping("/health/platform/{platform}")
    public ResponseEntity<Object> platformHealth(@PathVariable String platform) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("platform", platform);
        data.put("health", "healthy");
        data.put("httpStatus", 200);
        data.put("responseTime", 32);

        // 直接返回資料，不要嵌套包裝
        return ResponseEntity.ok(data);
    }

    /**
     * 特定平台的健康檢查歷史
     */
    @GetMapping("/health/platform/{platform}/history")
    public ResponseEntity<Object> platformHealthHistory(@PathVariable String platform) {
        ArrayNode historyArray = objectMapper.createArrayNode();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        for (int i = 0; i < 10; i++) {
            ObjectNode historyItem = objectMapper.createObjectNode();
            historyItem.put("id", "log_" + platform + "_" + i);
            historyItem.put("platform", platform);
            historyItem.put("createdAt", LocalDateTime.now().minusMinutes(i * 5).format(formatter));
            historyItem.put("httpStatus", i % 4 == 0 ? 503 : 200);
            historyItem.put("errorMessage", i % 4 == 0 ? "Service unavailable" : null);
            historyArray.add(historyItem);
        }

        ObjectNode data = objectMapper.createObjectNode();
        data.set("logs", historyArray);

        // 直接返回資料，不要嵌套包裝
        return ResponseEntity.ok(data);
    }
}
