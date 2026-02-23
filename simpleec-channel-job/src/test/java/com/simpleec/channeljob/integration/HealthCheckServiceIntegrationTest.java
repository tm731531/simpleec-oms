package com.simpleec.channeljob.integration;

import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.entity.ChannelSyncLog;
import com.simpleec.channeljob.repository.ChannelRepository;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import com.simpleec.channeljob.service.HealthCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for health check service and logging
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class HealthCheckServiceIntegrationTest {

    @Autowired
    private HealthCheckService healthCheckService;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelSyncLogRepository channelSyncLogRepository;

    @BeforeEach
    void setUp() {
        channelSyncLogRepository.deleteAll();
        channelRepository.deleteAll();
    }

    @Test
    void testChannelHealthCheckWithValidChannel() {
        // Setup: Create test channel
        Channel channel = new Channel();
        channel.setId("test-001");
        channel.setMerchantId("merchant-001");
        channel.setPlatformCode("shopee");
        channel.setToken("valid-token");
        channelRepository.save(channel);

        // Act: Perform health check
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("test-001");

        // Assert: Verify result
        assertNotNull(result);
        assertTrue(result.getHttpStatus() > 0);

        // Verify log was created
        Page<ChannelSyncLog> logs = channelSyncLogRepository.findByChannelId("test-001", PageRequest.of(0, 10));
        assertEquals(1, logs.getTotalElements());

        ChannelSyncLog log = logs.getContent().get(0);
        assertEquals("test-001", log.getChannelId());
        assertEquals("merchant-001", log.getMerchantId());
        assertEquals(result.getHttpStatus(), log.getHttpStatus());
    }

    @Test
    void testChannelHealthCheckWithNonexistentChannel() {
        // Act: Perform health check on non-existent channel
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("nonexistent");

        // Assert: Should return 404
        assertEquals(404, result.getHttpStatus());
        assertEquals("unhealthy", result.getHealth());
        assertTrue(result.getErrorMessage().contains("Channel not found"));

        // Verify log was still created
        Page<ChannelSyncLog> logs = channelSyncLogRepository.findByChannelId("nonexistent", PageRequest.of(0, 10));
        assertEquals(1, logs.getTotalElements());
        assertEquals(404, logs.getContent().get(0).getHttpStatus());
    }

    @Test
    void testPlatformHealthCheckLogging() {
        // Act: Perform platform health check
        HealthCheckService.HealthCheckResult result = healthCheckService.performPlatformHealthCheck("shopee");

        // Assert: Verify result
        assertNotNull(result);
        assertTrue(result.getHttpStatus() > 0);

        // Verify log was created (platform logs have null channelId)
        Page<ChannelSyncLog> logs = channelSyncLogRepository.findByChannelIdIsNullOrderByCreatedAtDesc(PageRequest.of(0, 10));
        assertTrue(logs.getTotalElements() > 0);
        assertNull(logs.getContent().get(0).getChannelId());
    }

    @Test
    void testHealthCheckHistoryPagination() {
        // Setup: Create multiple health check logs
        Channel channel = new Channel();
        channel.setId("test-001");
        channel.setMerchantId("merchant-001");
        channel.setPlatformCode("shopee");
        channel.setToken("token");
        channelRepository.save(channel);

        // Create multiple health checks
        for (int i = 0; i < 15; i++) {
            HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("test-001");
            assertNotNull(result);
        }

        // Act: Query with pagination
        Page<ChannelSyncLog> page1 = channelSyncLogRepository.findByChannelId("test-001", PageRequest.of(0, 10));
        Page<ChannelSyncLog> page2 = channelSyncLogRepository.findByChannelId("test-001", PageRequest.of(1, 10));

        // Assert: Verify pagination
        assertEquals(15, page1.getTotalElements());
        assertEquals(10, page1.getContent().size());
        assertEquals(5, page2.getContent().size());
    }

    @Test
    void testHealthCheckLogRecordsTimestamp() {
        // Setup: Create test channel
        Channel channel = new Channel();
        channel.setId("test-001");
        channel.setMerchantId("merchant-001");
        channel.setPlatformCode("shopee");
        channel.setToken("token");
        channelRepository.save(channel);

        LocalDateTime before = LocalDateTime.now();

        // Act: Perform health check
        healthCheckService.performChannelHealthCheck("test-001");

        LocalDateTime after = LocalDateTime.now();

        // Assert: Verify timestamp is within range
        Page<ChannelSyncLog> logs = channelSyncLogRepository.findByChannelId("test-001", PageRequest.of(0, 10));
        ChannelSyncLog log = logs.getContent().get(0);

        assertTrue(log.getCreatedAt().isAfter(before) || log.getCreatedAt().isEqual(before));
        assertTrue(log.getCreatedAt().isBefore(after) || log.getCreatedAt().isEqual(after));
    }

    @Test
    void testHealthCheckSummaryMetrics() {
        // Setup: Create multiple channels and health checks
        for (int i = 0; i < 5; i++) {
            Channel channel = new Channel();
            channel.setId("channel-" + i);
            channel.setMerchantId("merchant-" + i);
            channel.setPlatformCode("shopee");
            channel.setToken("token-" + i);
            channelRepository.save(channel);

            // Perform health check
            healthCheckService.performChannelHealthCheck("channel-" + i);
        }

        // Act: Get summary metrics
        long totalCount = channelSyncLogRepository.count();
        java.util.List<ChannelSyncLog> recent = channelSyncLogRepository.findTop100ByOrderByCreatedAtDesc();

        // Assert: Verify metrics
        assertEquals(5, totalCount);
        assertEquals(5, recent.size());
    }

    @Test
    void testChannelHealthCheckRecordsErrorMessage() {
        // Setup: Create test channel
        Channel channel = new Channel();
        channel.setId("test-001");
        channel.setMerchantId("merchant-001");
        channel.setPlatformCode("shopee");
        channel.setToken("invalid-token");
        channelRepository.save(channel);

        // Act: Perform health check
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("test-001");

        // Verify error message is logged if present
        if (result.getErrorMessage() != null) {
            Page<ChannelSyncLog> logs = channelSyncLogRepository.findByChannelId("test-001", PageRequest.of(0, 10));
            ChannelSyncLog log = logs.getContent().get(0);
            assertNotNull(log.getErrorMessage());
        }
    }

    @Test
    void testMultipleChannelsMultiplePlatforms() {
        // Setup: Create channels for different platforms
        String[] platforms = {"shopee", "momo", "yahoo"};
        for (int i = 0; i < platforms.length; i++) {
            Channel channel = new Channel();
            channel.setId("channel-" + platforms[i]);
            channel.setMerchantId("merchant-001");
            channel.setPlatformCode(platforms[i]);
            channel.setToken("token-" + i);
            channelRepository.save(channel);
        }

        // Act: Perform health checks on all channels
        for (String platform : platforms) {
            healthCheckService.performChannelHealthCheck("channel-" + platform);
        }

        // Assert: Verify logs for each channel
        assertEquals(3, channelSyncLogRepository.count());
        for (String platform : platforms) {
            Page<ChannelSyncLog> logs = channelSyncLogRepository.findByChannelId("channel-" + platform, PageRequest.of(0, 10));
            assertEquals(1, logs.getTotalElements());
        }
    }

    @Test
    void testHealthCheckTransactionConsistency() {
        // Setup: Create test channel
        Channel channel = new Channel();
        channel.setId("test-001");
        channel.setMerchantId("merchant-001");
        channel.setPlatformCode("shopee");
        channel.setToken("token");
        channelRepository.save(channel);

        // Act: Perform health check (which includes database write)
        HealthCheckService.HealthCheckResult result = healthCheckService.performChannelHealthCheck("test-001");

        // Assert: Verify both result and log exist
        assertNotNull(result);
        Page<ChannelSyncLog> logs = channelSyncLogRepository.findByChannelId("test-001", PageRequest.of(0, 10));
        assertEquals(1, logs.getTotalElements());
        assertEquals(result.getHttpStatus(), logs.getContent().get(0).getHttpStatus());
    }
}
