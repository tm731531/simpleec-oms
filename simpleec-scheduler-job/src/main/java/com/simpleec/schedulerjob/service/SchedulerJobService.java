package com.simpleec.schedulerjob.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.util.DateUtil;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

/**
 * SchedulerJobService - 排程工作協調器
 *
 * 監聽 scheduler.heartbeat 主題
 * 根據心跳信號的分鐘位和秒位，定期觸發任務
 * 每 5 分鐘（當 minute % 5 == 0）發送 FETCH_ORDERS 和 FETCH_RETURNS 到所有平台的 .slow 主題
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerJobService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // 記錄上次觸發的分鐘，避免同一分鐘內重複觸發
    private volatile int lastTriggeredMinute = -1;

    /**
     * 監聽心跳信號並根據時間決定是否觸發排程任務
     */
    @KafkaListener(
        topics = TopicConstants.SCHEDULER_HEARTBEAT,
        groupId = "scheduler-consumer-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void processHeartbeat(String message) {
        try {
            Map<String, Object> heartbeat = objectMapper.readValue(message, Map.class);
            Map<String, Object> body = (Map<String, Object>) heartbeat.get("body");

            if (body == null) return;

            int minute = ((Number) body.get("minute")).intValue();
            int minuteOfHour = ((Number) body.get("minuteOfHour")).intValue();
            int secondOfMinute = ((Number) body.get("secondOfMinute")).intValue();

            // 每 5 分鐘觸發一次（當 minute % 5 == 0）
            // 使用 secondOfMinute == 0 確保只在分鐘開始時觸發一次
            if (minute % 5 == 0 && secondOfMinute == 0 && minute != lastTriggeredMinute) {
                lastTriggeredMinute = minute;
                log.info("Scheduler triggered at minute {}", minuteOfHour);
                triggerPeriodicTasks();
            }
        } catch (Exception e) {
            log.error("Error processing heartbeat", e);
        }
    }

    /**
     * 觸發週期性任務：
     * - FETCH_ORDERS 到所有平台的 .slow 主題
     * - FETCH_RETURNS 到所有平台的 .slow 主題
     *
     * 當前支援的平台（來自 PLATFORM_MAPPING.md）：
     * - shopee, cyberbiz, pchome, momo, shopline, yahoo, easystore
     */
    private void triggerPeriodicTasks() {
        // 平台列表（來自 PLATFORM_MAPPING.md）
        String[] platforms = {"shopee", "cyberbiz", "pchome", "momo", "shopline", "yahoo", "easystore"};

        for (String platform : platforms) {
            // 發送 FETCH_ORDERS 任務
            publishTaskToSlowTopic(platform, "FETCH_ORDERS");

            // 發送 FETCH_RETURNS 任務
            publishTaskToSlowTopic(platform, "FETCH_RETURNS");
        }

        log.info("Periodic tasks triggered: FETCH_ORDERS and FETCH_RETURNS for {} platforms", platforms.length);
    }

    /**
     * 發布任務到平台的 .slow 主題
     */
    private void publishTaskToSlowTopic(String platform, String taskType) {
        try {
            String topic = TopicConstants.platformSlowTopic(platform);
            String taskId = NanoIdUtil.generate();

            // 構建任務訊息
            ObjectNode message = objectMapper.createObjectNode();

            // Header
            ObjectNode header = objectMapper.createObjectNode();
            header.put("messageId", NanoIdUtil.generate());
            header.put("taskType", taskType);
            header.put("timestamp", DateUtil.now());
            header.put("platform", platform);
            header.put("version", "1.0");

            // Body（針對 FETCH_ORDERS 和 FETCH_RETURNS 的最小訊息體）
            ObjectNode body = objectMapper.createObjectNode();
            body.put("platform", platform);
            body.put("triggeredAt", System.currentTimeMillis());

            message.set("header", header);
            message.set("body", body);

            // 發送到主題
            kafkaTemplate.send(topic, taskId, message);
            log.debug("Published {} to topic {} with taskId {}", taskType, topic, taskId);

        } catch (Exception e) {
            log.error("Failed to publish task {} to platform {}", taskType, platform, e);
        }
    }
}
