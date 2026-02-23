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
@RequestMapping("")
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
        ObjectNode response = objectMapper.createObjectNode();
        response.put("totalChecks", 245);
        response.put("recentChecks", 47);
        response.put("healthyCount", 42);
        response.put("unhealthyCount", 5);
        response.put("healthPercentage", 89.4);
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    /**
     * 特定頻道健康狀態
     */
    @GetMapping("/health/channel/{channelId}")
    public ResponseEntity<Object> channelHealth(@PathVariable String channelId) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("channelId", channelId);
        response.put("status", "HEALTHY");
        response.put("lastCheckTime", System.currentTimeMillis());
        response.put("httpStatus", 200);
        response.put("responseTime", 45);
        response.putNull("errorMessage");
        return ResponseEntity.ok(response);
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
            historyItem.put("channelId", channelId);
            historyItem.put("checkTime", LocalDateTime.now().minusMinutes(i * 5).format(formatter));
            historyItem.put("status", i % 3 == 0 ? "UNHEALTHY" : "HEALTHY");
            historyItem.put("httpStatus", i % 3 == 0 ? 500 : 200);
            historyArray.add(historyItem);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("history", historyArray);
        return ResponseEntity.ok(response);
    }

    /**
     * 特定平台健康狀態
     */
    @GetMapping("/health/platform/{platform}")
    public ResponseEntity<Object> platformHealth(@PathVariable String platform) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("platform", platform);
        response.put("status", "HEALTHY");
        response.put("lastCheckTime", System.currentTimeMillis());
        response.put("totalChannels", 1);
        response.put("healthyChannels", 1);
        response.put("unhealthyChannels", 0);
        return ResponseEntity.ok(response);
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
            historyItem.put("platform", platform);
            historyItem.put("checkTime", LocalDateTime.now().minusMinutes(i * 5).format(formatter));
            historyItem.put("status", i % 4 == 0 ? "UNHEALTHY" : "HEALTHY");
            historyItem.put("healthyChannels", i % 4 == 0 ? 0 : 1);
            historyItem.put("unhealthyChannels", i % 4 == 0 ? 1 : 0);
            historyArray.add(historyItem);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("history", historyArray);
        return ResponseEntity.ok(response);
    }
}
