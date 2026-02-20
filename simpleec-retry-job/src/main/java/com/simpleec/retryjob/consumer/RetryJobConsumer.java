package com.simpleec.retryjob.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Retry Job Consumer
 *
 * 消費 task.failed topic 中失敗的消息
 * 實現指數退避重試策略：
 * - 第 1 次重試：1 分鐘後
 * - 第 2 次重試：5 分鐘後
 * - 第 3 次重試：30 分鐘後
 * - 第 4 次重試後：發送到 task.dlt (死信隊列)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryJobConsumer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 消費 task.failed topic
     * 失敗消息來自各個 Consumer 的錯誤處理器
     */
    @KafkaListener(topics = "task.failed", groupId = "retry-job-group", concurrency = "2")
    public void consumeFailedTask(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            String taskId = header.get("messageId").asText();
            String taskType = header.get("taskType").asText();
            int retryCount = body.get("retryCount").asInt(0);
            String lastError = body.get("lastError").asText();

            log.info("Processing failed task {} - type: {}, retry count: {}",
                taskId, taskType, retryCount);

            // 檢查是否超過最大重試次數
            if (retryCount >= 4) {
                log.error("Task {} exceeded max retries (4), sending to DLT", taskId);
                sendToDLT(json, retryCount, lastError);
                return;
            }

            // 計算延遲時間（指數退避）
            long delayMs = calculateBackoffDelay(retryCount);

            // 更新重試計數
            ObjectNode updatedBody = (ObjectNode) body;
            updatedBody.put("retryCount", retryCount + 1);
            updatedBody.put("lastRetryTime", Instant.now().toString());

            ((ObjectNode) json).set("body", updatedBody);

            log.info("Scheduling retry for task {} with {} ms delay (attempt {})",
                taskId, delayMs, retryCount + 1);

            // TODO: 在延遲時間後重新發送到原始 topic
            // 目前簡化實現：直接重新發送
            retryTask(json, taskType);

        } catch (Exception e) {
            log.error("Error processing failed task", e);
        }
    }

    /**
     * 計算指數退避延遲時間
     *
     * @param retryCount 當前重試次數 (0-3)
     * @return 延遲毫秒數
     */
    private long calculateBackoffDelay(int retryCount) {
        return switch (retryCount) {
            case 0 -> 60_000L;        // 第 1 次：1 分鐘
            case 1 -> 300_000L;       // 第 2 次：5 分鐘
            case 2 -> 1_800_000L;     // 第 3 次：30 分鐘
            default -> 5_000L;        // 默認：5 秒
        };
    }

    /**
     * 重試失敗的任務
     * 將消息重新發送到原始 topic（或對應的處理 topic）
     */
    private void retryTask(JsonNode message, String taskType) throws Exception {
        String messageStr = objectMapper.writeValueAsString(message);

        // 根據 taskType 路由到對應的 topic
        String targetTopic = routeTaskToTopic(taskType);

        kafkaTemplate.send(targetTopic, message.get("header").get("messageId").asText(), messageStr);
        log.info("Task requeued to topic: {}", targetTopic);
    }

    /**
     * 根據 taskType 決定路由的 topic
     */
    private String routeTaskToTopic(String taskType) {
        return switch (taskType) {
            case "FETCH_ORDERS" -> "order.process";
            case "FETCH_ORDER_DETAIL" -> "order.process";
            case "ORDER_UPSERT" -> TopicConstants.ORDER_PROCESS;
            case "RETURN_UPSERT" -> TopicConstants.RETURN_PROCESS;
            case "SYNC_PRODUCT" -> TopicConstants.TASK_BACKEND;
            case "SYNC_PACK" -> TopicConstants.TASK_BACKEND;
            case "ORDER_REPORT", "INVENTORY_REPORT", "SALES_REPORT", "RETURN_REPORT" -> TopicConstants.TASK_BACKEND;
            default -> TopicConstants.TASK_FAILED;
        };
    }

    /**
     * 發送到死信隊列 (DLT)
     * 標記消息已達到最大重試次數，需要人工檢查
     */
    private void sendToDLT(JsonNode message, int retryCount, String lastError) throws Exception {
        ObjectNode dltMessage = objectMapper.createObjectNode();

        // 複製原始消息
        dltMessage.set("originalMessage", message);

        // 新增 DLT 信息
        ObjectNode dltInfo = objectMapper.createObjectNode();
        dltInfo.put("taskId", message.get("header").get("messageId").asText());
        dltInfo.put("taskType", message.get("header").get("taskType").asText());
        dltInfo.put("finalRetryCount", retryCount);
        dltInfo.put("lastError", lastError);
        dltInfo.put("sentToDLTAt", Instant.now().toString());
        dltInfo.put("status", "AWAITING_MANUAL_REVIEW");

        dltMessage.set("dltInfo", dltInfo);

        String messageStr = objectMapper.writeValueAsString(dltMessage);
        kafkaTemplate.send(TopicConstants.TASK_DLT, message.get("header").get("messageId").asText(), messageStr);

        log.error("Task {} sent to DLT for manual review. Error: {}",
            message.get("header").get("messageId").asText(), lastError);
    }
}
