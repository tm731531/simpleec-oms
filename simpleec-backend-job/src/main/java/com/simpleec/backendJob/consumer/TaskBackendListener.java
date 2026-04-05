package com.simpleec.backendJob.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.backendJob.handler.EventHandler;
import com.simpleec.backendJob.handler.EventHandlerRegistry;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.kafka.SchemaVersionHandler;
import com.simpleec.common.kafka.TaskMdcHelper;
import com.simpleec.common.kafka.UnsupportedSchemaVersionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 後端任務監聽器
 *
 * 監聽 task.backend topic 中的事件消息
 * 根據 header 中的 taskType 路由到相應的事件處理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskBackendListener {

    private final EventHandlerRegistry handlerRegistry;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 消費 task.backend topic 中的事件
     */
    @KafkaListener(topics = "task.backend", groupId = "backend-consumer-group")
    public void consume(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);

            try {
                SchemaVersionHandler.validate(event);
            } catch (UnsupportedSchemaVersionException e) {
                log.error("Unsupported schema version in task.backend message: {}", e.getMessage());
                kafkaTemplate.send(TopicConstants.TASK_DLT, "TaskBackend", message);
                return;
            }

            TaskMdcHelper.set(event);
            try {
                JsonNode header = event.get("header");

                if (header == null) {
                    log.warn("Received message without header: {}", message);
                    return;
                }

                String taskType = header.path("taskType").asText();
                String messageId = header.path("messageId").asText("unknown");

                log.debug("Received event - messageId: {}, taskType: {}", messageId, taskType);

                // 根據 taskType 查詢相應的事件處理器
                EventHandler handler = handlerRegistry.getHandler(taskType);

                if (handler == null) {
                    log.warn("No handler found for taskType: {}, messageId: {} — routing to task.dlt", taskType, messageId);
                    kafkaTemplate.send(TopicConstants.TASK_DLT, messageId, message);
                    return;
                }

                // 調用事件處理器
                try {
                    handler.handle(event);
                    log.debug("Event processed successfully - messageId: {}, taskType: {}", messageId, taskType);
                } catch (Exception e) {
                    log.error("Error handling event - messageId: {}, taskType: {} — routing to task.failed",
                            messageId, taskType, e);
                    try {
                        kafkaTemplate.send(TopicConstants.TASK_FAILED, messageId, message);
                    } catch (Exception kafkaEx) {
                        log.error("Failed to send to task.failed, routing to task.dlt", kafkaEx);
                        kafkaTemplate.send(TopicConstants.TASK_DLT, messageId, message);
                    }
                }
            } finally {
                TaskMdcHelper.clear();
            }

        } catch (Exception e) {
            log.error("Error processing message from task.backend topic", e);
        }
    }
}
