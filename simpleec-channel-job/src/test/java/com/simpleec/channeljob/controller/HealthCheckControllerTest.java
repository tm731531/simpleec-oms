package com.simpleec.channeljob.controller;

import com.simpleec.channeljob.entity.ChannelSyncLog;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import com.simpleec.channeljob.service.HealthCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthCheckControllerTest {

    @Mock
    private HealthCheckService healthCheckService;

    @Mock
    private ChannelSyncLogRepository channelSyncLogRepository;

    @InjectMocks
    private HealthCheckController healthCheckController;

    @BeforeEach
    void setUp() {
    }

    @Test
    void testGetChannelHealth_Healthy() {
        // Arrange
        HealthCheckService.HealthCheckResult result = new HealthCheckService.HealthCheckResult(200, "healthy", null);
        when(healthCheckService.performChannelHealthCheck("channel-001")).thenReturn(result);

        // Act
        ResponseEntity<Map<String, Object>> response = healthCheckController.getChannelHealth("channel-001");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(200, response.getBody().get("httpStatus"));
        assertEquals("healthy", response.getBody().get("health"));
        assertNull(response.getBody().get("errorMessage"));
    }

    @Test
    void testGetChannelHealth_Unhealthy() {
        // Arrange
        HealthCheckService.HealthCheckResult result = new HealthCheckService.HealthCheckResult(
            401, "unhealthy", "Token invalid or expired");
        when(healthCheckService.performChannelHealthCheck("channel-001")).thenReturn(result);

        // Act
        ResponseEntity<Map<String, Object>> response = healthCheckController.getChannelHealth("channel-001");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(401, response.getBody().get("httpStatus"));
        assertEquals("unhealthy", response.getBody().get("health"));
        assertTrue(((String) response.getBody().get("errorMessage")).contains("Token invalid"));
    }

    @Test
    void testGetPlatformHealth_Healthy() {
        // Arrange
        HealthCheckService.HealthCheckResult result = new HealthCheckService.HealthCheckResult(200, "healthy", null);
        when(healthCheckService.performPlatformHealthCheck("shopee")).thenReturn(result);

        // Act
        ResponseEntity<Map<String, Object>> response = healthCheckController.getPlatformHealth("shopee");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(200, response.getBody().get("httpStatus"));
        assertEquals("healthy", response.getBody().get("health"));
    }

    @Test
    void testGetChannelHealthHistory() {
        // Arrange
        List<ChannelSyncLog> logs = new ArrayList<>();
        ChannelSyncLog log = new ChannelSyncLog();
        log.setId("log-001");
        log.setChannelId("channel-001");
        log.setHttpStatus(200);
        log.setStatus("success");
        log.setCreatedAt(LocalDateTime.now());
        logs.add(log);

        Page<ChannelSyncLog> page = new PageImpl<>(logs);
        when(channelSyncLogRepository.findByChannelId(eq("channel-001"), any(Pageable.class))).thenReturn(page);

        // Act
        ResponseEntity<Map<String, Object>> response = healthCheckController.getChannelHealthHistory("channel-001", 0, 10);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("channel-001", response.getBody().get("channelId"));
        assertEquals(1L, response.getBody().get("totalRecords"));
        assertEquals(0, response.getBody().get("page"));
        assertEquals(10, response.getBody().get("size"));
        assertNotNull(response.getBody().get("logs"));
    }

    @Test
    void testGetHealthSummary() {
        // Arrange
        List<ChannelSyncLog> logs = new ArrayList<>();

        for (int i = 0; i < 7; i++) {
            ChannelSyncLog log = new ChannelSyncLog();
            log.setId("log-" + i);
            log.setHttpStatus(i < 5 ? 200 : 500);  // 5 healthy, 2 unhealthy
            log.setCreatedAt(LocalDateTime.now());
            logs.add(log);
        }

        when(channelSyncLogRepository.count()).thenReturn(100L);
        when(channelSyncLogRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(logs);

        // Act
        ResponseEntity<Map<String, Object>> response = healthCheckController.getHealthSummary();

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(100L, response.getBody().get("totalChecks"));
        assertEquals(7, response.getBody().get("recentChecks"));
        assertEquals(5L, response.getBody().get("healthyCount"));
        assertEquals(2L, response.getBody().get("unhealthyCount"));
        assertEquals(71.42857142857143, response.getBody().get("healthPercentage"));
    }
}
