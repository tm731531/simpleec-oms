package com.simpleec.schedulerjob.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.enums.PlatformEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.common.util.DateUtil;

import java.time.Instant;

/**
 * SchedulerConsumer - 時間驅動的任務派發器
 *
 * 讀取 HeartbeatJob 發送的時間戳
 * 根據分鐘位置的模運算，決策派發什麼任務到各 Topic
 *
 * 派發規則：
 *   :00, :05, :10... (% 5 == 0) → FETCH_ORDERS 到各平台的 .slow topic
 *   :01, :06, :11... (% 5 == 1) → ORDER_REPORT 到 task.backend
 *   :02, :07, :12... (% 5 == 2) → INVENTORY_REPORT 到 task.backend
 *   :03, :08, :13... (% 5 == 3) → SALES_REPORT 到 task.backend
 *   :04, :09, :14... (% 5 == 4) → RETURN_REPORT 到 task.backend
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerConsumer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 消費 scheduler topic 中的 Heartbeat
     */
    @KafkaListener(topics = "scheduler", groupId = "scheduler-consumer-group")
    public void consume(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode body = json.get("body");

            long timestamp = body.get("timestamp").asLong();
            int minuteOfHour = body.get("minuteOfHour").asInt();

            // 根據分鐘位做派發決策
            int mod5 = minuteOfHour % 5;
            int mod10 = minuteOfHour % 10;

            if (mod5 == 0) {
                // :00, :05, :10, :15 ...
                // 派發 FETCH_ORDERS 到各平台的 .slow topic
                dispatchFetchOrders(timestamp);
            }

            if (mod5 == 1) {
                // :01, :06, :11 ...
                // 派發 ORDER_REPORT
                dispatchReport(TaskTypeEnum.ORDER_REPORT, timestamp);
            }

            if (mod5 == 2) {
                // :02, :07, :12 ...
                // 派發 INVENTORY_REPORT
                dispatchReport(TaskTypeEnum.INVENTORY_REPORT, timestamp);
            }

            if (mod5 == 3) {
                // :03, :08, :13 ...
                // 派發 SALES_REPORT
                dispatchReport(TaskTypeEnum.SALES_REPORT, timestamp);
            }

            if (mod5 == 4) {
                // :04, :09, :14 ...
                // 派發 RETURN_REPORT
                dispatchReport(TaskTypeEnum.RETURN_REPORT, timestamp);
            }

            if (mod10 == 5) {
                // :05, :15, :25 ... 每 10 分鐘
                // Kafka 健康檢查
                dispatchReport(TaskTypeEnum.KAFKA_HEALTH_CHECK, timestamp);
            }

            // 每日報表（每小時 :00 和 :30）
            if (minuteOfHour == 0 || minuteOfHour == 30) {
                dispatchReport(TaskTypeEnum.DAILY_REPORT, timestamp);
            }

        } catch (Exception e) {
            log.error("Error processing scheduler message", e);
        }
    }

    /**
     * 派發 FETCH_ORDERS 到各平台的 .slow topic
     */
    private void dispatchFetchOrders(long timestamp) {
        log.info("Dispatching FETCH_ORDERS at {}", DateUtil.toIsoString(timestamp));

        // 對所有已配置的平台派發 FETCH_ORDERS
        for (PlatformEnum platform : PlatformEnum.values()) {
            try {
                String topic = TopicConstants.platformSlowTopic(platform.getCode());

                // 構建 FETCH_ORDERS 消息
                ObjectNode message = objectMapper.createObjectNode();

                ObjectNode header = objectMapper.createObjectNode();
                header.put("messageId", NanoIdUtil.generate());
                header.put("taskType", TaskTypeEnum.FETCH_ORDERS.getCode());
                header.put("channelId", platform.getCode() + "_001");  // 簡化：假設只有一個實例
                header.put("timestamp", DateUtil.toIsoString(timestamp));
                header.put("version", "1.0");

                ObjectNode body = objectMapper.createObjectNode();
                body.put("timeRange", String.format("Last 5 minutes"));
                body.put("merchantId", "MERCHANT_001");  // 簡化

                message.set("header", header);
                message.set("body", body);

                kafkaTemplate.send(topic, header.get("messageId").asText(), message);
                log.debug("Sent FETCH_ORDERS to {} topic", topic);

            } catch (Exception e) {
                log.error("Error dispatching FETCH_ORDERS for platform {}", platform.getCode(), e);
            }
        }
    }

    /**
     * 派發後端報表任務
     */
    private void dispatchReport(TaskTypeEnum reportType, long timestamp) {
        try {
            log.info("Dispatching {} at {}", reportType.getCode(), DateUtil.toIsoString(timestamp));

            ObjectNode message = objectMapper.createObjectNode();

            ObjectNode header = objectMapper.createObjectNode();
            header.put("messageId", NanoIdUtil.generate());
            header.put("taskType", reportType.getCode());
            header.put("timestamp", DateUtil.toIsoString(timestamp));
            header.put("version", "1.0");

            ObjectNode body = objectMapper.createObjectNode();
            body.put("timeRange", "Last 5 minutes");
            body.put("merchantId", "MERCHANT_001");

            message.set("header", header);
            message.set("body", body);

            kafkaTemplate.send(TopicConstants.TASK_BACKEND, header.get("messageId").asText(), message);
            log.debug("Sent {} to task.backend topic", reportType.getCode());

        } catch (Exception e) {
            log.error("Error dispatching report {}", reportType.getCode(), e);
        }
    }
}
