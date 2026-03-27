package com.simpleec.retryjob.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.kafka.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Retry Job Consumer — error-type-aware retry with Redis-based delayed scheduling.
 *
 * Consumes task.failed topic and applies the following retry contract:
 * <ul>
 *   <li>CLIENT_ERROR_4XX  -> DLT immediately (not retryable)</li>
 *   <li>SERVER_ERROR_5XX  -> retry max 3 times with backoff, then DLT</li>
 *   <li>NETWORK_ERROR     -> retry max 5 times with backoff, then DLT</li>
 *   <li>FORMAT_ERROR      -> DLT immediately (not retryable)</li>
 *   <li>Unknown errorType -> DLT immediately</li>
 *   <li>Unknown taskType  -> DLT immediately</li>
 * </ul>
 *
 * Backoff schedule: 1 min, 5 min, 30 min, 60 min, 120 min
 * Delayed retries are stored in a Redis sorted set (score = scheduled timestamp)
 * and polled by {@link RetrySchedulerJob}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryJobConsumer {

    public static final String RETRY_SCHEDULED_KEY = "oms:retry:scheduled";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @KafkaListener(topics = "task.failed", groupId = "retry-job-group", concurrency = "2")
    public void consumeFailedTask(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            if (header == null || body == null) {
                log.error("Malformed failed-task message (missing header or body), sending to DLT");
                sendToDlt(json, 0, "Malformed message: missing header or body", "UNKNOWN");
                return;
            }

            String taskId = header.path("messageId").asText("unknown");
            String taskType = header.path("taskType").asText("");

            // --- Check for unknown taskType: route to DLT immediately ---
            if (!isKnownTaskType(taskType)) {
                log.warn("Unknown taskType '{}' for task {}, routing to DLT", taskType, taskId);
                sendToDlt(json, 0, "Unknown taskType: " + taskType, taskType);
                return;
            }

            // --- Extract errorInfo from body ---
            JsonNode errorInfo = body.path("errorInfo");
            String errorTypeStr = errorInfo.path("errorType").asText("");
            int retryCount = errorInfo.path("retryCount").asInt(0);
            String errorMessage = errorInfo.path("errorMessage").asText("unknown error");

            ErrorType errorType = ErrorType.fromString(errorTypeStr);

            log.info("Processing failed task {} - type: {}, errorType: {}, retryCount: {}",
                    taskId, taskType, errorTypeStr, retryCount);

            // --- Non-retryable errors: route to DLT immediately ---
            if (errorType == null || !errorType.isRetryable()) {
                log.warn("Non-retryable error (errorType={}) for task {}, routing to DLT",
                        errorTypeStr.isEmpty() ? "UNKNOWN" : errorTypeStr, taskId);
                sendToDlt(json, retryCount, errorMessage, taskType);
                return;
            }

            // --- Check retry limit ---
            int maxRetries = errorType.getMaxRetries();
            if (retryCount >= maxRetries) {
                log.error("Task {} exceeded max retries ({}/{}) for errorType={}, sending to DLT",
                        taskId, retryCount, maxRetries, errorType);
                sendToDlt(json, retryCount, errorMessage, taskType);
                return;
            }

            // --- Schedule delayed retry via Redis sorted set ---
            long delayMs = calculateBackoffDelay(retryCount);
            scheduleRetry(json, retryCount + 1, delayMs, taskId);

        } catch (Exception e) {
            log.error("Error processing failed task, sending raw message to DLT", e);
            try {
                kafkaTemplate.send(TopicConstants.TASK_DLT, message);
            } catch (Exception ex) {
                log.error("Failed to send to DLT as fallback", ex);
            }
        }
    }

    /**
     * Backoff schedule:
     *   retry 0 -> 1 minute
     *   retry 1 -> 5 minutes
     *   retry 2 -> 30 minutes
     *   retry 3 -> 60 minutes
     *   retry 4 -> 120 minutes
     */
    long calculateBackoffDelay(int retryCount) {
        return switch (retryCount) {
            case 0 -> 60_000L;         // 1 minute
            case 1 -> 300_000L;        // 5 minutes
            case 2 -> 1_800_000L;      // 30 minutes
            case 3 -> 3_600_000L;      // 60 minutes
            case 4 -> 7_200_000L;      // 120 minutes
            default -> 7_200_000L;     // cap at 120 minutes
        };
    }

    /**
     * Schedule a delayed retry by adding the message to a Redis sorted set.
     * The score is the epoch-millis timestamp at which the retry should fire.
     * The message body.errorInfo.retryCount is incremented before storing.
     * The body.originalHeader and body.originalBody are preserved.
     */
    private void scheduleRetry(JsonNode message, int newRetryCount, long delayMs, String taskId) throws Exception {
        ObjectNode updatedMessage = message.deepCopy();

        // Update retryCount inside body.errorInfo — ensure body and errorInfo exist as ObjectNodes
        JsonNode bodyNode = updatedMessage.path("body");
        ObjectNode body = bodyNode.isObject() ? (ObjectNode) bodyNode : updatedMessage.putObject("body");
        JsonNode errorInfoNode = body.path("errorInfo");
        ObjectNode errorInfo = errorInfoNode.isObject() ? (ObjectNode) errorInfoNode : body.putObject("errorInfo");
        errorInfo.put("retryCount", newRetryCount);
        errorInfo.put("lastRetryTime", Instant.now().toString());

        long scheduledAt = System.currentTimeMillis() + delayMs;
        String messageJson = objectMapper.writeValueAsString(updatedMessage);

        redisTemplate.opsForZSet().add(RETRY_SCHEDULED_KEY, messageJson, scheduledAt);

        log.info("Scheduled retry for task {} at {} (attempt {}, delay {}ms)",
                taskId, Instant.ofEpochMilli(scheduledAt), newRetryCount, delayMs);
    }

    /**
     * Check if taskType is a known TaskTypeEnum value.
     */
    private boolean isKnownTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return false;
        }
        try {
            TaskTypeEnum.fromCode(taskType);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Route a taskType to its target Kafka topic for retry re-publishing.
     * Channel-specific tasks require platformId to resolve the platform slow/fast topic.
     * Unknown task types route to DLT.
     *
     * @param taskType   the task type from the message header
     * @param platformId the platform from the message header (e.g. "shopee", "momo"),
     *                   or null/blank for non-channel tasks
     */
    public String routeTaskToTopic(String taskType, String platformId) {
        boolean hasPlatform = platformId != null && !platformId.isBlank();

        return switch (taskType) {
            // Channel slow tasks → retry to the same platform slow topic
            case "FETCH_ORDERS", "FETCH_ORDER_DETAIL", "FETCH_RETURNS", "FETCH_RETURN_DETAIL" -> {
                if (!hasPlatform) {
                    log.warn("routeTaskToTopic: {} has no platformId, routing to DLT", taskType);
                    yield TopicConstants.TASK_DLT;
                }
                yield TopicConstants.platformSlowTopic(platformId.toLowerCase());
            }
            // SYNC_PACK: channel job uses platformId → slow topic; backend job has no platformId → task.backend
            case "SYNC_PACK" -> hasPlatform
                    ? TopicConstants.platformSlowTopic(platformId.toLowerCase())
                    : TopicConstants.TASK_BACKEND;
            // Channel fast tasks → retry to the same platform fast topic
            case "SHIP_ORDER", "UPDATE_INVENTORY", "UPDATE_PRICE", "APPROVE_RETURN", "REJECT_RETURN" -> {
                if (!hasPlatform) {
                    log.warn("routeTaskToTopic: {} has no platformId, routing to DLT", taskType);
                    yield TopicConstants.TASK_DLT;
                }
                yield TopicConstants.platformFastTopic(platformId.toLowerCase());
            }
            case "ORDER_UPSERT", "ORDER_STATUS_CHANGE", "CANCEL_ORDER_INTERNAL" -> TopicConstants.ORDER_PROCESS;
            case "RETURN_UPSERT", "APPROVE_RETURN_INTERNAL" -> TopicConstants.RETURN_PROCESS;
            case "SYNC_PRODUCT", "ORDER_REPORT", "INVENTORY_REPORT", "SALES_REPORT",
                 "RETURN_REPORT", "DAILY_REPORT", "KAFKA_HEALTH_CHECK", "HEARTBEAT" -> TopicConstants.TASK_BACKEND;
            default -> TopicConstants.TASK_DLT;
        };
    }

    /**
     * Send a message to the Dead Letter Topic (task.dlt) with DLT metadata.
     */
    private void sendToDlt(JsonNode message, int retryCount, String lastError, String taskType) throws Exception {
        ObjectNode dltMessage = objectMapper.createObjectNode();

        // Preserve the entire original message
        dltMessage.set("originalMessage", message);

        // Add DLT metadata
        ObjectNode dltInfo = objectMapper.createObjectNode();
        dltInfo.put("taskId", message.path("header").path("messageId").asText("unknown"));
        dltInfo.put("taskType", taskType);
        dltInfo.put("finalRetryCount", retryCount);
        dltInfo.put("lastError", lastError);
        dltInfo.put("sentToDLTAt", Instant.now().toString());
        dltInfo.put("status", "AWAITING_MANUAL_REVIEW");

        dltMessage.set("dltInfo", dltInfo);

        String messageStr = objectMapper.writeValueAsString(dltMessage);
        String key = message.path("header").path("messageId").asText("unknown");
        kafkaTemplate.send(TopicConstants.TASK_DLT, key, messageStr);

        log.error("Task {} (type={}) sent to DLT after {} retries. Error: {}",
                key, taskType, retryCount, lastError);
    }
}
