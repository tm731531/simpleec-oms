package com.simpleec.retryjob.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.core.entity.FailedTaskLog;
import com.simpleec.core.repository.FailedTaskLogRepository;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Dead Letter Topic (DLT) 消費者
 *
 * 消費 task.dlt topic - 處理達到最大重試次數的失敗消息
 * 需要人工檢查和手動干預
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DltConsumer {

    private final ObjectMapper objectMapper;
    private final FailedTaskLogRepository failedTaskLogRepository;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 消費 task.dlt topic
     * 這些消息已經過多次重試都失敗了，需要人工檢查
     */
    @KafkaListener(topics = "task.dlt", groupId = "dlt-consumer-group", concurrency = "1")
    public void consumeDltMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode dltInfo = json.get("dltInfo");
            JsonNode originalMessage = json.get("originalMessage");

            String taskId = dltInfo.get("taskId").asText();
            String taskType = dltInfo.get("taskType").asText();
            String lastError = dltInfo.get("lastError").asText();
            int retryCount = dltInfo.get("finalRetryCount").asInt();

            log.error("=== DEAD LETTER QUEUE (DLT) ===");
            log.error("Task ID: {}", taskId);
            log.error("Task Type: {}", taskType);
            log.error("Final Retry Count: {}", retryCount);
            log.error("Last Error: {}", lastError);
            log.error("Received at DLT: {}", LocalDateTime.now().format(FORMATTER));
            log.error("Original Message: {}", objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(originalMessage));
            log.error("================================");

            // 記錄到數據庫或存儲系統供管理員檢查
            recordDltMessage(taskId, taskType, lastError, originalMessage);

            // 發送告警通知（可選）
            sendAlertNotification(taskId, taskType, lastError);

        } catch (Exception e) {
            log.error("Error processing DLT message", e);
        }
    }

    /**
     * 記錄 DLT 消息到 failed_task_logs 表，供管理員查詢和人工處理
     */
    private void recordDltMessage(String taskId, String taskType, String error, JsonNode originalMessage) {
        try {
            String merchantId = originalMessage.path("header").path("merchantId").asText(null);
            String originalTopic = originalMessage.path("header").path("originalTopic").asText(null);
            String payloadJson = objectMapper.writeValueAsString(originalMessage);

            FailedTaskLog log_ = FailedTaskLog.builder()
                    .id(NanoIdUtil.generate(20))
                    .messageId(taskId)
                    .taskType(taskType)
                    .taskAction("DLT_RECEIVED")
                    .sourceJobType("retry-job")
                    .merchantId(merchantId)
                    .originalTopic(originalTopic)
                    .errorMessage(error)
                    .reason("DLT")
                    .retryCount(0)
                    .payload(payloadJson)
                    .build();

            failedTaskLogRepository.save(log_);
            log.info("DLT message persisted to failed_task_logs: {}", taskId);

        } catch (Exception e) {
            log.error("Failed to persist DLT message to DB for taskId={}", taskId, e);
        }
    }

    /**
     * 發送告警通知給管理員
     * 可選：郵件、Slack、釘釘等通知
     */
    private void sendAlertNotification(String taskId, String taskType, String error) {
        try {
            String alertMessage = String.format(
                "⚠️ DLT Alert:\nTask: %s (Type: %s)\nError: %s\nTime: %s\nAction: Manual Review Required",
                taskId, taskType, error, LocalDateTime.now().format(FORMATTER)
            );

            // TODO: 發送通知
            // - 郵件：adminNotificationService.sendEmail(...)
            // - Slack：slackService.sendMessage(...)
            // - 釘釘：dingTalkService.sendMessage(...)

            log.warn("Alert notification would be sent: {}", alertMessage);

        } catch (Exception e) {
            log.error("Failed to send alert notification", e);
        }
    }
}
