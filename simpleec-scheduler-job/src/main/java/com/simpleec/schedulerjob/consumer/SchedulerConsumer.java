package com.simpleec.schedulerjob.consumer;

import com.simpleec.schedulerjob.handler.SchedulerEventHandler;
import com.simpleec.schedulerjob.kafka.KafkaProducer;
import com.simpleec.common.kafka.SchemaVersionHandler;
import com.simpleec.common.kafka.TaskMdcHelper;
import com.simpleec.common.kafka.UnsupportedSchemaVersionException;
import com.simpleec.common.constants.TopicConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * SchedulerConsumer - 時間驅動的任務派發器
 *
 * 標準 Spring Kafka @KafkaListener 消費者
 * 消費 scheduler topic 中的 Heartbeat 訊號
 * 根據分鐘位路由派發各種時間驅動任務
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

    private final SchedulerEventHandler eventHandler;
    private final ObjectMapper objectMapper;
    private final KafkaProducer kafkaProducer;

    /**
     * 消費 scheduler topic 中的 Heartbeat 訊號
     *
     * 使用 Spring Kafka @KafkaListener 注解標準模式
     * 自動處理 consumer group coordination 和 offset 提交
     */
    @KafkaListener(
        topics = "scheduler",
        groupId = "scheduler-dispatcher-group-v4",
        concurrency = "1"
    )
    public void consumeHeartbeat(String message) {
        try {
            log.debug("Processing heartbeat message");

            JsonNode json = objectMapper.readTree(message);

            try {
                SchemaVersionHandler.validate(json);
            } catch (UnsupportedSchemaVersionException e) {
                log.error("Unsupported schema version in scheduler heartbeat: {}", e.getMessage());
                kafkaProducer.publishToTopic(TopicConstants.TASK_DLT, "Scheduler", message);
                return;
            }

            TaskMdcHelper.set(json);
            try {
                JsonNode body = json.get("body");

                long timestamp = body.get("timestamp").asLong();

                // 委派給 handler 處理派發邏輯
                eventHandler.handleHeartbeat(body, timestamp);

            } finally {
                TaskMdcHelper.clear();
            }

        } catch (Exception e) {
            log.error("Error processing scheduler heartbeat", e);
        }
    }
}
