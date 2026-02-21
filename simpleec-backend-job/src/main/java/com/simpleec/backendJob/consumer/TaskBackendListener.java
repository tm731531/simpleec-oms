package com.simpleec.backendJob.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.backendJob.handler.EventHandler;
import com.simpleec.backendJob.handler.EventHandlerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
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

    /**
     * 消費 task.backend topic 中的事件
     */
    @KafkaListener(topics = "task.backend", groupId = "backend-consumer-group")
    public void consume(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            JsonNode header = event.get("header");

            if (header == null) {
                log.warn("Received message without header: {}", message);
                return;
            }

            String taskType = header.get("taskType").asText();
            String messageId = header.get("messageId").asText();

            log.debug("Received event - messageId: {}, taskType: {}", messageId, taskType);

            // 根據 taskType 查詢相應的事件處理器
            EventHandler handler = handlerRegistry.getHandler(taskType);

            if (handler == null) {
                log.warn("No handler found for taskType: {}, messageId: {}", taskType, messageId);
                return;
            }

            // 調用事件處理器
            try {
                handler.handle(event);
                log.debug("Event processed successfully - messageId: {}, taskType: {}", messageId, taskType);
            } catch (Exception e) {
                log.error("Error handling event - messageId: {}, taskType: {}", messageId, taskType, e);
                // 考慮是否需要重試或發送到 DLT (Dead Letter Topic)
            }

        } catch (Exception e) {
            log.error("Error processing message from task.backend topic", e);
        }
    }
}
