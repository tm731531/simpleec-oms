package com.simpleec.api.service;

import com.simpleec.common.constants.TopicConstants;
import com.simpleec.core.dto.SyncStatus;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.entity.SellPack;
import com.simpleec.core.repository.ChannelRepository;
import com.simpleec.core.repository.PlatformRepository;
import com.simpleec.core.repository.SellPackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SellPack 同步服務 - 發布 Kafka 事件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellPackSyncService {
    private final SellPackRepository sellPackRepository;
    private final ChannelRepository channelRepository;
    private final PlatformRepository platformRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SellPack syncUpdate(SellPack sellPack, String operation,
                                Object oldValue, Object newValue,
                                String taskType) {
        SyncStatus syncStatus = SyncStatus.builder()
            .status("pending")
            .operation(operation)
            .oldValue(oldValue)
            .newValue(newValue)
            .startTime(Instant.now().toString())
            .retryCount(0)
            .build();
        sellPack.setSyncStatus(syncStatus);
        SellPack saved = sellPackRepository.save(sellPack);

        try {
            Optional<Channel> channelOpt = channelRepository.findById(sellPack.getChannelId());
            if (channelOpt.isPresent()) {
                Optional<Platform> platformOpt = platformRepository.findById(channelOpt.get().getPlatformId());
                if (platformOpt.isPresent()) {
                    Platform platform = platformOpt.get();
                    String topic = TopicConstants.platformFastTopic(
                        platform.getPlatformName().toLowerCase());

                    Map<String, Object> header = new HashMap<>();
                    header.put("taskType", taskType);
                    header.put("merchantId", sellPack.getMerchantId());
                    header.put("platformId", platform.getPlatformName().toLowerCase());
                    header.put("channelId", sellPack.getChannelId());
                    header.put("requestId", UUID.randomUUID().toString());
                    header.put("timestamp", Instant.now().toString());
                    header.put("source", "api");
                    header.put("version", 1);
                    header.put("isRollback", false);

                    Map<String, Object> body = new HashMap<>();
                    body.put("sellPackId", sellPack.getId());
                    body.put("operation", operation);
                    body.put("oldValue", oldValue);
                    body.put("newValue", newValue);

                    Map<String, Object> message = new HashMap<>();
                    message.put("header", header);
                    message.put("body", body);

                    kafkaTemplate.send(topic, sellPack.getId(), message);
                    log.info("Published {} event to topic {} for sellpack {}", taskType, topic, sellPack.getId());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to publish Kafka event for sellpack {}: {}", sellPack.getId(), e.getMessage());
        }

        return saved;
    }
}
