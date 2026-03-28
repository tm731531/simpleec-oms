package com.simpleec.retryjob.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.retryjob.consumer.RetryJobConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Polls the Redis sorted set for due retry messages every 10 seconds.
 *
 * Messages are stored by {@link RetryJobConsumer} with score = scheduled epoch-millis.
 * This job picks up messages whose score <= now, removes them atomically,
 * and re-publishes to the target Kafka topic based on taskType.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrySchedulerJob {

    private static final int BATCH_SIZE = 50;

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final RetryJobConsumer retryJobConsumer;

    @Scheduled(fixedDelay = 10_000)
    public void processScheduledRetries() {
        try {
            long now = System.currentTimeMillis();
            Set<String> dueMessages = redisTemplate.opsForZSet()
                    .rangeByScore(RetryJobConsumer.RETRY_SCHEDULED_KEY, 0, now, 0, BATCH_SIZE);

            if (dueMessages == null || dueMessages.isEmpty()) {
                return;
            }

            int processed = 0;
            for (String msgJson : dueMessages) {
                // Atomic remove — only the winner processes this message
                Long removed = redisTemplate.opsForZSet()
                        .remove(RetryJobConsumer.RETRY_SCHEDULED_KEY, msgJson);
                if (removed == null || removed == 0) {
                    continue;
                }

                try {
                    JsonNode msg = objectMapper.readTree(msgJson);
                    String taskType = msg.path("header").path("taskType").asText("");
                    String platformId = msg.path("header").path("platformId").asText("");
                    String taskId = msg.path("header").path("messageId").asText("unknown");
                    String targetTopic = retryJobConsumer.routeTaskToTopic(taskType, platformId);

                    // If the router resolved to DLT, wrap in DLT format before sending
                    String outJson;
                    if (TopicConstants.TASK_DLT.equals(targetTopic)) {
                        ObjectNode dltMessage = objectMapper.createObjectNode();
                        dltMessage.set("originalMessage", msg);
                        ObjectNode dltInfo = objectMapper.createObjectNode();
                        dltInfo.put("taskId", taskId);
                        dltInfo.put("taskType", taskType);
                        dltInfo.put("finalRetryCount", msg.path("body").path("errorInfo").path("retryCount").asInt(0));
                        dltInfo.put("lastError", "Unknown routing in RetrySchedulerJob");
                        dltInfo.put("sentToDLTAt", java.time.Instant.now().toString());
                        dltInfo.put("status", "AWAITING_MANUAL_REVIEW");
                        dltMessage.set("dltInfo", dltInfo);
                        outJson = objectMapper.writeValueAsString(dltMessage);
                    } else {
                        outJson = msgJson;
                    }

                    // Re-publish the message (with header/body intact, including originalHeader/originalBody)
                    kafkaTemplate.send(targetTopic, taskId, outJson);
                    processed++;

                    log.info("Retry fired for task {} -> topic {}", taskId, targetTopic);
                } catch (Exception e) {
                    log.error("Failed to process scheduled retry message, re-adding to sorted set", e);
                    // Re-add with a 30-second delay to avoid tight failure loops
                    redisTemplate.opsForZSet().add(
                            RetryJobConsumer.RETRY_SCHEDULED_KEY,
                            msgJson,
                            now + 30_000);
                }
            }

            if (processed > 0) {
                log.info("Processed {} scheduled retries", processed);
            }
        } catch (Exception e) {
            log.error("Error in retry scheduler poll", e);
        }
    }
}
