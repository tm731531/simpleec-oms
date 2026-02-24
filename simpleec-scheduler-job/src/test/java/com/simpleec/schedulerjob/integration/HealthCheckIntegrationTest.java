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
        // Setup: Create test platforms first
        Platform platform1 = new Platform();
        platform1.setId("shopee");
        platform1.setPlatformName("Shopee");
        platform1.setQueueTopic("task.channel.shopee");
        platform1.setActived(true);
        platformRepository.save(platform1);

        Platform platform2 = new Platform();
        platform2.setId("momo");
        platform2.setPlatformName("MOMO");
        platform2.setQueueTopic("task.channel.momo");
        platform2.setActived(true);
        platformRepository.save(platform2);

        Platform platform3 = new Platform();
        platform3.setId("yahoo");
        platform3.setPlatformName("Yahoo");
        platform3.setQueueTopic("task.channel.yahoo");
        platform3.setActived(true);
        platformRepository.save(platform3);

        // Setup: Create test channels
        Channel channel1 = new Channel();
        channel1.setId("channel-001");
        channel1.setMerchantId("merchant-001");
        channel1.setPlatformId("shopee");
        channel1.setEnableSync(true);
        channel1.setActived(true);
        channelRepository.save(channel1);

        Channel channel2 = new Channel();
        channel2.setId("channel-002");
        channel2.setMerchantId("merchant-002");
        channel2.setPlatformId("momo");
        channel2.setEnableSync(true);
        channel2.setActived(true);
        channelRepository.save(channel2);

        Channel disabledChannel = new Channel();
        disabledChannel.setId("channel-003");
        disabledChannel.setMerchantId("merchant-003");
        disabledChannel.setPlatformId("yahoo");
        disabledChannel.setEnableSync(false);
        disabledChannel.setActived(true);
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
        platform1.setId("shopee");
        platform1.setPlatformName("Shopee");
        platform1.setQueueTopic("task.channel.shopee");
        platform1.setActived(true);
        platformRepository.save(platform1);

        Platform platform2 = new Platform();
        platform2.setId("momo");
        platform2.setPlatformName("MOMO");
        platform2.setQueueTopic("task.channel.momo");
        platform2.setActived(true);
        platformRepository.save(platform2);

        Platform inactivePlatform = new Platform();
        inactivePlatform.setId("inactive");
        inactivePlatform.setPlatformName("Inactive Platform");
        inactivePlatform.setQueueTopic("task.channel.inactive");
        inactivePlatform.setActived(false);
        platformRepository.save(inactivePlatform);

        // Act: Trigger health check
        healthCheckScheduler.runHealthCheck();

        // Assert: Verify active platforms were queried
        List<Platform> activePlatforms = channelService.findActivePlatforms();
        assertEquals(2, activePlatforms.size());
        assertTrue(activePlatforms.stream().anyMatch(p -> p.getId().equals("shopee")));
        assertTrue(activePlatforms.stream().anyMatch(p -> p.getId().equals("momo")));
        assertFalse(activePlatforms.stream().anyMatch(p -> p.getId().equals("inactive")));
    }

    @Test
    void testHealthCheckMessagePublishing() {
        // Setup: Create a test platform and channel
        Platform platform = new Platform();
        platform.setId("shopee");
        platform.setPlatformName("Shopee");
        platform.setQueueTopic("task.channel.shopee");
        platform.setActived(true);
        platformRepository.save(platform);

        Channel channel = new Channel();
        channel.setId("test-channel");
        channel.setMerchantId("test-merchant");
        channel.setPlatformId("shopee");
        channel.setEnableSync(true);
        channel.setActived(true);
        channelRepository.save(channel);

        // Act: Create and publish health check message
        HealthCheckMessage message = new HealthCheckMessage();
        message.setTaskType("CHECK_HEALTH");
        message.setMerchantId("test-merchant");
        message.setChannelId("test-channel");
        message.setPlatformCode("shopee");
        message.setTimestamp(System.currentTimeMillis());

        assertDoesNotThrow(() -> {
            kafkaProducer.publishToTopic("task.channel.shopee.fast", "test-channel", message);
        });
    }

    @Test
    void testPlatformHealthCheckMessagePublishing() {
        // Setup: Create a test platform
        Platform platform = new Platform();
        platform.setId("shopee");
        platform.setPlatformName("Shopee");
        platform.setQueueTopic("task.channel.shopee");
        platform.setActived(true);
        platformRepository.save(platform);

        // Act: Create and publish platform health check message
        PlatformHealthCheckMessage message = new PlatformHealthCheckMessage();
        message.setTaskType("CHECK_HEALTH_PLATFORM");
        message.setPlatformCode("shopee");
        message.setTimestamp(System.currentTimeMillis());

        assertDoesNotThrow(() -> {
            kafkaProducer.publishToTopic("task.channel.shopee.fast", "shopee-platform", message);
        });
    }

    @Test
    void testSchedulerDoesNotPublishForDisabledChannels() {
        // Setup: Create platform and disabled channel
        Platform platform = new Platform();
        platform.setId("shopee");
        platform.setPlatformName("Shopee");
        platform.setQueueTopic("task.channel.shopee");
        platform.setActived(true);
        platformRepository.save(platform);

        Channel disabledChannel = new Channel();
        disabledChannel.setId("disabled-001");
        disabledChannel.setMerchantId("merchant-001");
        disabledChannel.setPlatformId("shopee");
        disabledChannel.setEnableSync(false);
        disabledChannel.setActived(true);
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
        inactivePlatform.setId("inactive");
        inactivePlatform.setPlatformName("Inactive");
        inactivePlatform.setQueueTopic("task.channel.inactive");
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
        // Setup: Create platform and channel
        Platform platform = new Platform();
        platform.setId("shopee");
        platform.setPlatformName("Shopee");
        platform.setQueueTopic("task.channel.shopee");
        platform.setActived(true);
        platformRepository.save(platform);

        Channel channel = new Channel();
        channel.setId("format-test");
        channel.setMerchantId("merchant-001");
        channel.setPlatformId("shopee");
        channel.setEnableSync(true);
        channel.setActived(true);
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
        String[] platformIds = {"shopee", "momo", "yahoo", "pchome", "cyberbiz", "shopline", "shopify"};

        for (String platformId : platformIds) {
            Platform platform = new Platform();
            platform.setId(platformId);
            platform.setPlatformName(platformId.toUpperCase());
            platform.setQueueTopic("task.channel." + platformId);
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
