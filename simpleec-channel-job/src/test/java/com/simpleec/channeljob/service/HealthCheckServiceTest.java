package com.simpleec.channeljob.service;

import com.simpleec.channeljob.client.PlatformApiClient;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.entity.ChannelSyncLog;
import com.simpleec.channeljob.repository.ChannelRepository;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthCheckServiceTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private ChannelSyncLogRepository channelSyncLogRepository;

    @Mock
    private PlatformApiClient platformApiClient;

    @InjectMocks
    private HealthCheckService healthCheckService;

    private Channel testChannel;

    @BeforeEach
    void setUp() {
        testChannel = new Channel();
        testChannel.setId("channel-001");
        testChannel.setMerchantId("merchant-001");
        testChannel.setPlatformCode("shopee");
        testChannel.setToken("valid-token");
    }

    @Test
    void testPerformChannelHealthCheck_Success() {
        // Arrange
        when(channelRepository.findById("channel-001")).thenReturn(Optional.of(testChannel));
        when(platformApiClient.healthCheck("shopee", "valid-token")).thenReturn(200);

        // Act
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("channel-001");

        // Assert
        assertEquals(200, result.getHttpStatus());
        assertEquals("healthy", result.getHealth());
        assertNull(result.getErrorMessage());

        // Verify log was recorded
        verify(channelSyncLogRepository, times(1)).save(any(ChannelSyncLog.class));
    }

    @Test
    void testPerformChannelHealthCheck_TokenExpired() {
        // Arrange
        when(channelRepository.findById("channel-001")).thenReturn(Optional.of(testChannel));
        when(platformApiClient.healthCheck("shopee", "valid-token")).thenReturn(401);

        // Act
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("channel-001");

        // Assert
        assertEquals(401, result.getHttpStatus());
        assertEquals("unhealthy", result.getHealth());
        assertTrue(result.getErrorMessage().contains("Token invalid or expired"));

        // Verify log was recorded
        ArgumentCaptor<ChannelSyncLog> captor = ArgumentCaptor.forClass(ChannelSyncLog.class);
        verify(channelSyncLogRepository, times(1)).save(captor.capture());
        assertEquals(401, captor.getValue().getHttpStatus());
    }

    @Test
    void testPerformChannelHealthCheck_Forbidden() {
        // Arrange
        when(channelRepository.findById("channel-001")).thenReturn(Optional.of(testChannel));
        when(platformApiClient.healthCheck("shopee", "valid-token")).thenReturn(403);

        // Act
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("channel-001");

        // Assert
        assertEquals(403, result.getHttpStatus());
        assertEquals("unhealthy", result.getHealth());
        assertTrue(result.getErrorMessage().contains("Insufficient permissions"));
    }

    @Test
    void testPerformChannelHealthCheck_PlatformError() {
        // Arrange
        when(channelRepository.findById("channel-001")).thenReturn(Optional.of(testChannel));
        when(platformApiClient.healthCheck("shopee", "valid-token")).thenReturn(500);

        // Act
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("channel-001");

        // Assert
        assertEquals(500, result.getHttpStatus());
        assertEquals("unhealthy", result.getHealth());
        assertTrue(result.getErrorMessage().contains("Platform service error"));
    }

    @Test
    void testPerformChannelHealthCheck_ServiceUnavailable() {
        // Arrange
        when(channelRepository.findById("channel-001")).thenReturn(Optional.of(testChannel));
        when(platformApiClient.healthCheck("shopee", "valid-token")).thenReturn(503);

        // Act
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("channel-001");

        // Assert
        assertEquals(503, result.getHttpStatus());
        assertEquals("unhealthy", result.getHealth());
        assertTrue(result.getErrorMessage().contains("Platform service unavailable"));
    }

    @Test
    void testPerformChannelHealthCheck_ChannelNotFound() {
        // Arrange
        when(channelRepository.findById("channel-001")).thenReturn(Optional.empty());

        // Act
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("channel-001");

        // Assert
        assertEquals(404, result.getHttpStatus());
        assertEquals("unhealthy", result.getHealth());
        assertTrue(result.getErrorMessage().contains("Channel not found"));

        // Verify log was recorded
        ArgumentCaptor<ChannelSyncLog> captor = ArgumentCaptor.forClass(ChannelSyncLog.class);
        verify(channelSyncLogRepository, times(1)).save(captor.capture());
        assertEquals(404, captor.getValue().getHttpStatus());
    }

    @Test
    void testPerformChannelHealthCheck_ApiException() {
        // Arrange
        when(channelRepository.findById("channel-001")).thenReturn(Optional.of(testChannel));
        when(platformApiClient.healthCheck("shopee", "valid-token"))
            .thenThrow(new RuntimeException("API connection failed"));

        // Act
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("channel-001");

        // Assert
        assertEquals(500, result.getHttpStatus());
        assertEquals("unhealthy", result.getHealth());
        assertTrue(result.getErrorMessage().contains("API connection failed"));

        // Verify log was recorded
        verify(channelSyncLogRepository, times(1)).save(any(ChannelSyncLog.class));
    }

    @Test
    void testPerformPlatformHealthCheck_Success() {
        // Arrange
        when(platformApiClient.platformHealthCheck("shopee")).thenReturn(200);

        // Act
        HealthCheckService.HealthCheckResult result = healthCheckService.performPlatformHealthCheck("shopee");

        // Assert
        assertEquals(200, result.getHttpStatus());
        assertEquals("healthy", result.getHealth());
        assertNull(result.getErrorMessage());

        // Verify log was recorded
        verify(channelSyncLogRepository, times(1)).save(any(ChannelSyncLog.class));
    }

    @Test
    void testPerformPlatformHealthCheck_Unhealthy() {
        // Arrange
        when(platformApiClient.platformHealthCheck("shopee")).thenReturn(503);

        // Act
        HealthCheckService.HealthCheckResult result = healthCheckService.performPlatformHealthCheck("shopee");

        // Assert
        assertEquals(503, result.getHttpStatus());
        assertEquals("unhealthy", result.getHealth());
        assertTrue(result.getErrorMessage().contains("Platform service unavailable"));

        // Verify log was recorded
        ArgumentCaptor<ChannelSyncLog> captor = ArgumentCaptor.forClass(ChannelSyncLog.class);
        verify(channelSyncLogRepository, times(1)).save(captor.capture());
        assertNull(captor.getValue().getChannelId());
    }

    @Test
    void testPerformPlatformHealthCheck_Exception() {
        // Arrange
        when(platformApiClient.platformHealthCheck("shopee"))
            .thenThrow(new RuntimeException("Connection timeout"));

        // Act
        HealthCheckService.HealthCheckResult result = healthCheckService.performPlatformHealthCheck("shopee");

        // Assert
        assertEquals(500, result.getHttpStatus());
        assertEquals("unhealthy", result.getHealth());
        assertTrue(result.getErrorMessage().contains("Connection timeout"));
    }
}
