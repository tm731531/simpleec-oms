package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 通路健康檢查派發器
 *
 * 每 10 分鐘由 Scheduler 派發 CHANNEL_HEALTH_CHECK 到 task.backend。
 * 此 handler 接收後，對該 merchant 下所有啟用通路：
 *   - 送 CHECK_HEALTH → {platform}.fast（通路層健康檢查）
 *   - 送 CHECK_HEALTH_PLATFORM → {platform}.fast（平台層健康檢查，每平台只送一次）
 *
 * Channel Job 收到後呼叫 HealthCheckService，結果寫入 Redis cache。
 * 前端 /api/user/channels/health-overview 讀 Redis cache 顯示狀態。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelHealthCheckHandler extends AbstractEventHandler {

    private final ChannelRepository channelRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String getTaskType() {
        return "CHANNEL_HEALTH_CHECK";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        List<Channel> channels = channelRepository.findByMerchantIdAndActivedTrue(merchantId);

        if (channels.isEmpty()) {
            log.debug("No active channels for merchant {}", merchantId);
            return;
        }

        Set<String> platformsSent = new HashSet<>();
        int channelCount = 0;

        for (Channel channel : channels) {
            String platformCode = channel.getPlatformId();
            String topic = TopicConstants.platformFastTopic(platformCode);

            // CHECK_HEALTH：通路層
            ObjectNode header = objectMapper.createObjectNode();
            header.put("taskType", "CHECK_HEALTH");
            header.put("merchantId", merchantId);
            header.put("platformId", platformCode);
            header.put("channelId", channel.getId());
            header.put("requestId", UUID.randomUUID().toString());
            header.put("timestamp", Instant.now().toString());
            header.put("source", "backend");
            header.put("version", 1);
            header.put("isRollback", false);

            ObjectNode message = objectMapper.createObjectNode();
            message.set("header", header);
            message.set("body", objectMapper.createObjectNode());
            kafkaTemplate.send(topic, channel.getId(), message);
            channelCount++;

            // CHECK_HEALTH_PLATFORM：平台層（每平台只送一次）
            if (!platformsSent.contains(platformCode)) {
                ObjectNode phHeader = objectMapper.createObjectNode();
                phHeader.put("taskType", "CHECK_HEALTH_PLATFORM");
                phHeader.put("merchantId", merchantId);
                phHeader.put("platformId", platformCode);
                phHeader.put("channelId", "");
                phHeader.put("requestId", UUID.randomUUID().toString());
                phHeader.put("timestamp", Instant.now().toString());
                phHeader.put("source", "backend");
                phHeader.put("version", 1);
                phHeader.put("isRollback", false);

                ObjectNode phMessage = objectMapper.createObjectNode();
                phMessage.set("header", phHeader);
                phMessage.set("body", objectMapper.createObjectNode());
                kafkaTemplate.send(topic, platformCode, phMessage);
                platformsSent.add(platformCode);
            }
        }

        log.info("CHANNEL_HEALTH_CHECK dispatched: merchant={}, channels={}, platforms={}",
            merchantId, channelCount, platformsSent);
    }
}
