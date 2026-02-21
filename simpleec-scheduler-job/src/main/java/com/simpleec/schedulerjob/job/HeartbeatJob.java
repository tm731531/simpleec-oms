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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.Duration;

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
    private final StringRedisTemplate redisTemplate;

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
            header.put("taskType", TaskTypeEnum.HEARTBEAT.getCode());
            header.put("timestamp", DateUtil.now());
            header.put("version", "1.0");

            // Body (時間戳和分鐘位用於路由決策)
            ObjectNode body = objectMapper.createObjectNode();
            body.put("timestamp", now);
            body.put("minute", instant.getEpochSecond() / 60);
            body.put("minuteOfHour", instant.atZone(java.time.ZoneId.of("UTC")).getMinute());
            body.put("secondOfMinute", instant.atZone(java.time.ZoneId.of("UTC")).getSecond());

            message.set("header", header);
            message.set("body", body);

            // 發送到 scheduler.heartbeat topic
            kafkaTemplate.send(TopicConstants.SCHEDULER_HEARTBEAT, header.get("messageId").asText(), message);

            // 寫入 Redis 用於監控（設置 2 秒過期時間，允許檢測到心跳停止）
            String jobId = "scheduler-job-" + System.getenv("HOSTNAME");
            String heartbeatKey = "heartbeat:" + jobId;
            redisTemplate.opsForValue().set(heartbeatKey, DateUtil.now(), Duration.ofSeconds(2));

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
