package com.simpleec.api.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
}
