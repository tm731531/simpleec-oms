package com.simpleec.frontendjob.consumer;

import com.simpleec.frontendjob.broadcaster.EventBroadcaster;
import com.simpleec.frontendjob.broadcaster.FrontendEvent;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.kafka.SchemaVersionHandler;
import com.simpleec.common.kafka.UnsupportedSchemaVersionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskFrontendListener {
    private final EventBroadcaster eventBroadcaster;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @KafkaListener(topics = "task.frontend", groupId = "frontend-job-group", concurrency = "4")
    public void consumeFrontendTask(@Payload String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            try {
                SchemaVersionHandler.validate(json);
            } catch (UnsupportedSchemaVersionException e) {
                log.error("Unsupported schema version in frontend message: {}", e.getMessage());
                kafkaTemplate.send(TopicConstants.TASK_DLT, "TaskFrontend", message);
                return;
            }
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            if (header == null || body == null) {
                log.warn("Invalid message format: missing header or body");
                return;
            }

            FrontendEvent event = FrontendEvent.builder()
                .taskType(header.get("taskType").asText())
                .merchantId(header.get("merchantId").asText())
                .channelId(header.get("channelId").asText())
                .platformId(header.has("platformId") ? header.get("platformId").asText() : "")
                .requestId(header.get("requestId").asText())
                .timestamp(parseTimestamp(header.get("timestamp").asText()))
                .source(header.has("source") ? header.get("source").asText() : "backend")
                .version(header.has("version") ? header.get("version").asInt() : 1)
                .status(body.has("status") ? body.get("status").asText() : "processing")
                .statusMessage(body.has("statusMessage") ? body.get("statusMessage").asText() : "")
                .progress(body.has("progress") ? body.get("progress").asInt() : 0)
                .totalRecords(body.has("totalRecords") ? body.get("totalRecords").asInt() : 0)
                .processedRecords(body.has("processedRecords") ? body.get("processedRecords").asInt() : 0)
                .failedRecords(body.has("failedRecords") ? body.get("failedRecords").asInt() : 0)
                .build();

            eventBroadcaster.broadcastToMerchant(event.getMerchantId(), event);
            log.debug("Successfully processed frontend task: {}", event.getRequestId());
        } catch (Exception e) {
            log.error("Error processing frontend task message", e);
        }
    }

    private LocalDateTime parseTimestamp(String timestamp) {
        try {
            return LocalDateTime.parse(timestamp, ISO_FORMATTER);
        } catch (Exception e) {
            log.warn("Failed to parse timestamp: {}", timestamp);
            return LocalDateTime.now();
        }
    }
}
