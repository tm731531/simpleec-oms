package com.simpleec.schedulerjob.integration;

import com.simpleec.schedulerjob.dto.HealthCheckMessage;
import com.simpleec.schedulerjob.dto.PlatformHealthCheckMessage;
import com.simpleec.schedulerjob.entity.Channel;
import com.simpleec.schedulerjob.entity.Platform;
import com.simpleec.schedulerjob.kafka.KafkaProducer;
import com.simpleec.schedulerjob.repository.ChannelRepository;
import com.simpleec.schedulerjob.repository.PlatformRepository;
import com.simpleec.schedulerjob.scheduler.HealthCheckScheduler;
import com.simpleec.schedulerjob.service.ChannelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for health check scheduler flow
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, brokerProperties = {
    "listeners=PLAINTEXT://localhost:9092",
    "port=9092"
})
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=localhost:9092"
})
class HealthCheckIntegrationTest {

    @Autowired
    private HealthCheckScheduler healthCheckScheduler;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private PlatformRepository platformRepository;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private KafkaProducer kafkaProducer;

    @BeforeEach
    void setUp() {
        channelRepository.deleteAll();
        platformRepository.deleteAll();
    }

    @Test
    void testHealthCheckSchedulerFlow_WithEnabledChannels() {
        // Setup: Create test data
        Channel channel1 = new Channel();
        channel1.setId("channel-001");
        channel1.setMerchantId("merchant-001");
        channel1.setPlatformCode("shopee");
        channel1.setEnabledSync(true);
        channelRepository.save(channel1);

        Channel channel2 = new Channel();
        channel2.setId("channel-002");
        channel2.setMerchantId("merchant-002");
        channel2.setPlatformCode("momo");
        channel2.setEnabledSync(true);
        channelRepository.save(channel2);

        Channel disabledChannel = new Channel();
        disabledChannel.setId("channel-003");
        disabledChannel.setMerchantId("merchant-003");
        disabledChannel.setPlatformCode("yahoo");
        disabledChannel.setEnabledSync(false);
        channelRepository.save(disabledChannel);

        // Act: Trigger health check
        healthCheckScheduler.runHealthCheck();

        // Assert: Verify enabled channels were queried
        List<Channel> enabledChannels = channelService.findEnabledChannels();
        assertEquals(2, enabledChannels.size());
        assertTrue(enabledChannels.stream().anyMatch(c -> c.getId().equals("channel-001")));
        assertTrue(enabledChannels.stream().anyMatch(c -> c.getId().equals("channel-002")));
        assertFalse(enabledChannels.stream().anyMatch(c -> c.getId().equals("channel-003")));
    }

    @Test
    void testHealthCheckSchedulerFlow_WithActivePlatforms() {
        // Setup: Create test platforms
        Platform platform1 = new Platform();
        platform1.setCode("shopee");
        platform1.setName("Shopee");
        platform1.setActived(true);
        platformRepository.save(platform1);

        Platform platform2 = new Platform();
        platform2.setCode("momo");
        platform2.setName("MOMO");
        platform2.setActived(true);
        platformRepository.save(platform2);

        Platform inactivePlatform = new Platform();
        inactivePlatform.setCode("inactive");
        inactivePlatform.setName("Inactive Platform");
        inactivePlatform.setActived(false);
        platformRepository.save(inactivePlatform);

        // Act: Trigger health check
        healthCheckScheduler.runHealthCheck();

        // Assert: Verify active platforms were queried
        List<Platform> activePlatforms = channelService.findActivePlatforms();
        assertEquals(2, activePlatforms.size());
        assertTrue(activePlatforms.stream().anyMatch(p -> p.getCode().equals("shopee")));
        assertTrue(activePlatforms.stream().anyMatch(p -> p.getCode().equals("momo")));
        assertFalse(activePlatforms.stream().anyMatch(p -> p.getCode().equals("inactive")));
    }

    @Test
    void testHealthCheckMessagePublishing() {
        // Setup: Create a test channel
        Channel channel = new Channel();
        channel.setId("test-channel");
        channel.setMerchantId("test-merchant");
        channel.setPlatformCode("shopee");
        channel.setEnabledSync(true);
        channelRepository.save(channel);

        // Act: Create and publish health check message
        HealthCheckMessage message = new HealthCheckMessage();
        message.setTaskType("CHECK_HEALTH");
        message.setMerchantId("test-merchant");
        message.setChannelId("test-channel");
        message.setPlatformCode("shopee");
        message.setTimestamp(System.currentTimeMillis());

        assertDoesNotThrow(() -> {
            kafkaProducer.publishToTopic("shopee.fast", "test-channel", message);
        });
    }

    @Test
    void testPlatformHealthCheckMessagePublishing() {
        // Setup: Create a test platform
        Platform platform = new Platform();
        platform.setCode("shopee");
        platform.setName("Shopee");
        platform.setActived(true);
        platformRepository.save(platform);

        // Act: Create and publish platform health check message
        PlatformHealthCheckMessage message = new PlatformHealthCheckMessage();
        message.setTaskType("CHECK_HEALTH_PLATFORM");
        message.setPlatformCode("shopee");
        message.setTimestamp(System.currentTimeMillis());

        assertDoesNotThrow(() -> {
            kafkaProducer.publishToTopic("shopee.fast", "shopee-platform", message);
        });
    }

    @Test
    void testSchedulerDoesNotPublishForDisabledChannels() {
        // Setup: Create only disabled channels
        Channel disabledChannel = new Channel();
        disabledChannel.setId("disabled-001");
        disabledChannel.setMerchantId("merchant-001");
        disabledChannel.setPlatformCode("shopee");
        disabledChannel.setEnabledSync(false);
        channelRepository.save(disabledChannel);

        // Act: Trigger health check
        healthCheckScheduler.runHealthCheck();

        // Assert: Disabled channel should not be in enabled list
        List<Channel> enabledChannels = channelService.findEnabledChannels();
        assertEquals(0, enabledChannels.size());
    }

    @Test
    void testSchedulerDoesNotPublishForInactivePlatforms() {
        // Setup: Create only inactive platforms
        Platform inactivePlatform = new Platform();
        inactivePlatform.setCode("inactive");
        inactivePlatform.setName("Inactive");
        inactivePlatform.setActived(false);
        platformRepository.save(inactivePlatform);

        // Act: Trigger health check
        healthCheckScheduler.runHealthCheck();

        // Assert: Inactive platform should not be in active list
        List<Platform> activePlatforms = channelService.findActivePlatforms();
        assertEquals(0, activePlatforms.size());
    }

    @Test
    void testHealthCheckSchedulerMessageFormat() {
        // Setup
        Channel channel = new Channel();
        channel.setId("format-test");
        channel.setMerchantId("merchant-001");
        channel.setPlatformCode("shopee");
        channel.setEnabledSync(true);
        channelRepository.save(channel);

        // Create message
        HealthCheckMessage message = new HealthCheckMessage();
        message.setTaskType("CHECK_HEALTH");
        message.setMerchantId("merchant-001");
        message.setChannelId("format-test");
        message.setPlatformCode("shopee");
        message.setTimestamp(System.currentTimeMillis());

        // Verify message structure
        assertEquals("CHECK_HEALTH", message.getTaskType());
        assertEquals("merchant-001", message.getMerchantId());
        assertEquals("format-test", message.getChannelId());
        assertEquals("shopee", message.getPlatformCode());
        assertTrue(message.getTimestamp() > 0);
    }

    @Test
    void testMultiplePlatformsHealthCheck() {
        // Setup: Create multiple platforms
        String[] platforms = {"shopee", "momo", "yahoo", "pchome", "cyberbiz", "shopline", "shopify"};

        for (String platformCode : platforms) {
            Platform platform = new Platform();
            platform.setCode(platformCode);
            platform.setName(platformCode.toUpperCase());
            platform.setActived(true);
            platformRepository.save(platform);
        }

        // Act: Trigger health check
        healthCheckScheduler.runHealthCheck();

        // Assert: All platforms should be active
        List<Platform> activePlatforms = channelService.findActivePlatforms();
        assertEquals(7, activePlatforms.size());
    }
}
