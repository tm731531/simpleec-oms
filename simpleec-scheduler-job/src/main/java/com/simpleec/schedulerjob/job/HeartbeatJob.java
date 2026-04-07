package com.simpleec.schedulerjob.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.util.DateUtil;
import com.simpleec.common.util.NanoIdUtil;

import java.time.Instant;

/**
 * HeartbeatJob - 系統心臟
 *
 * 每秒向 scheduler topic 發送時間戳信號
 * 所有時間驅動的任務都由此信號觸發
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatJob {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 每秒執行一次
     * cron 的秒級精度：每 1 秒一次
     */
    @Scheduled(fixedRate = 1000)
    public void sendHeartbeat() {
        try {
            long now = System.currentTimeMillis();
            Instant instant = Instant.ofEpochMilli(now);

            // 構建 Heartbeat 消息
            ObjectNode message = objectMapper.createObjectNode();

            // Header
            ObjectNode header = objectMapper.createObjectNode();
            header.put("messageId", NanoIdUtil.generate());
            header.put("requestId", "req_" + NanoIdUtil.generate());
            header.put("taskType", TaskTypeEnum.HEARTBEAT.getCode());
            header.put("timestamp", DateUtil.now());
            header.put("source", "scheduler");
            header.put("version", 1);
            header.put("isRollback", false);

            // Body (分鐘位用於路由決策；timestamp 已在 header 中)
            ObjectNode body = objectMapper.createObjectNode();
            body.put("timestamp", now);  // 加入 timestamp 供 SchedulerConsumer 使用
            body.put("minute", instant.getEpochSecond() / 60);
            body.put("minuteOfHour", instant.atZone(java.time.ZoneId.of("UTC")).getMinute());
            body.put("secondOfMinute", instant.atZone(java.time.ZoneId.of("UTC")).getSecond());

            message.set("header", header);
            message.set("body", body);

            // 發送到 scheduler topic（給 SchedulerConsumer 消費以派發排程任務）
            log.info("Sending heartbeat message to topic: {}", TopicConstants.SCHEDULER);
            try {
                // 發送到 scheduler topic
                var future = kafkaTemplate.send(TopicConstants.SCHEDULER, header.get("messageId").asText(), message);
                var result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                log.debug("Heartbeat sent to '{}' - partition {}, offset {}",
                    TopicConstants.SCHEDULER,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

                log.info("Heartbeat sent successfully");
            } catch (Exception e) {
                log.error("Failed to send heartbeat to Kafka", e);
            }

            // 每 10 秒記錄一次日誌，避免日誌過多
            if (instant.getEpochSecond() % 10 == 0) {
                log.debug("Heartbeat sent: minute={}, secondOfMinute={}",
                    instant.atZone(java.time.ZoneId.of("UTC")).getMinute(),
                    instant.atZone(java.time.ZoneId.of("UTC")).getSecond());
            }

        } catch (Exception e) {
            log.error("Failed to send heartbeat", e);
        }
    }
}
