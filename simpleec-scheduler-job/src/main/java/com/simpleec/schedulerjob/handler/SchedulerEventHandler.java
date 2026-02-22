package com.simpleec.schedulerjob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.util.DateUtil;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 排程事件處理器 — 根據時間驅動派發各種任務
 *
 * 消費 heartbeat 消息，根據分鐘位做決策派發：
 *   :00, :05, :10... (% 5 == 0) → FETCH_ORDERS（查詢訂單）
 *   :01, :06, :11... (% 5 == 1) → ORDER_REPORT（訂單報表）
 *   :02, :07, :12... (% 5 == 2) → INVENTORY_REPORT（庫存報表）
 *   :03, :08, :13... (% 5 == 3) → SALES_REPORT（銷售報表）
 *   :04, :09, :14... (% 5 == 4) → RETURN_REPORT（退貨報表）
 *   :05, :15, :25... (% 10 == 5) → KAFKA_HEALTH_CHECK（健康檢查）
 *   每小時 :00 和 :30 → DAILY_REPORT（日報表）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerEventHandler {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 處理 heartbeat 訊號並派發排程任務
     */
    public void handleHeartbeat(JsonNode body, long timestamp) {
        try {
            int minuteOfHour = body.get("minuteOfHour").asInt();
            int mod5 = minuteOfHour % 5;
            int mod10 = minuteOfHour % 10;

            // :00, :05, :10, :15 ...
            if (mod5 == 0) {
                dispatchFetchOrders(timestamp);
            }

            // :01, :06, :11 ...
            if (mod5 == 1) {
                dispatchTask(TaskTypeEnum.ORDER_REPORT, timestamp);
            }

            // :02, :07, :12 ...
            if (mod5 == 2) {
                dispatchTask(TaskTypeEnum.INVENTORY_REPORT, timestamp);
            }

            // :03, :08, :13 ...
            if (mod5 == 3) {
                dispatchTask(TaskTypeEnum.SALES_REPORT, timestamp);
            }

            // :04, :09, :14 ...
            if (mod5 == 4) {
                dispatchTask(TaskTypeEnum.RETURN_REPORT, timestamp);
            }

            // :05, :15, :25 ... 每 10 分鐘
            if (mod10 == 5) {
                dispatchTask(TaskTypeEnum.KAFKA_HEALTH_CHECK, timestamp);
            }

            // 每小時 :00 和 :30
            if (minuteOfHour == 0 || minuteOfHour == 30) {
                dispatchTask(TaskTypeEnum.DAILY_REPORT, timestamp);
            }

        } catch (Exception e) {
            log.error("Error handling heartbeat event", e);
        }
    }

    /**
     * 派發 FETCH_ORDERS 到所有平台的 slow topic
     */
    private void dispatchFetchOrders(long timestamp) {
        try {
            log.info("Dispatching FETCH_ORDERS at {}", DateUtil.toIsoString(timestamp));

            // 平台列表（應從資料庫讀取，這裡先用靜態列表）
            List<String> platforms = List.of("cyberbiz", "shopee", "shopify", "pchome", "momo", "shopline", "yahoo");

            for (String platform : platforms) {
                try {
                    String topic = TopicConstants.platformSlowTopic(platform.toLowerCase());
                    ObjectNode message = buildTaskMessage(TaskTypeEnum.FETCH_ORDERS, timestamp);

                    kafkaTemplate.send(topic, message.get("header").get("messageId").asText(), message);
                    log.debug("Sent FETCH_ORDERS to {} topic", topic);
                } catch (Exception e) {
                    log.error("Error dispatching FETCH_ORDERS for platform {}", platform, e);
                }
            }

        } catch (Exception e) {
            log.error("Error in dispatchFetchOrders", e);
        }
    }

    /**
     * 派發報表任務到 task.backend topic
     */
    private void dispatchTask(TaskTypeEnum taskType, long timestamp) {
        try {
            log.info("Dispatching {} at {}", taskType.getCode(), DateUtil.toIsoString(timestamp));

            ObjectNode message = buildTaskMessage(taskType, timestamp);
            kafkaTemplate.send(TopicConstants.TASK_BACKEND,
                message.get("header").get("messageId").asText(), message);

            log.debug("Sent {} to task.backend topic", taskType.getCode());

        } catch (Exception e) {
            log.error("Error dispatching {}", taskType.getCode(), e);
        }
    }

    /**
     * 構建排程任務消息
     */
    private ObjectNode buildTaskMessage(TaskTypeEnum taskType, long timestamp) {
        ObjectNode message = objectMapper.createObjectNode();

        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", NanoIdUtil.generate());
        header.put("taskType", taskType.getCode());
        header.put("timestamp", DateUtil.toIsoString(timestamp));
        header.put("version", "1.0");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("timeRange", "Last 5 minutes");
        body.put("merchantId", "MERCHANT_001");

        message.set("header", header);
        message.set("body", body);

        return message;
    }
}
