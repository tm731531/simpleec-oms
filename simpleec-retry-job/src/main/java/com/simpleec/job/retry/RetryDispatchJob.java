package com.simpleec.job.retry;

import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import com.simpleec.core.observability.TaskMdcHelper;
import com.simpleec.job.retry.service.FailedTaskLogService;
import com.simpleec.job.retry.service.RetryPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class RetryDispatchJob {

    private final TaskProducer taskProducer;
    private final RetryPolicyService retryPolicy;
    private final FailedTaskLogService failedTaskLogService;

    @KafkaListener(
        topics = "task.failed",
        groupId = "retry-dispatch-job",
        concurrency = "${job.retry-dispatch.concurrency:2}")
    @SuppressWarnings("unchecked")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        TaskMdcHelper.set(msg);
        try {
            Map<String, Object> payload = msg.getPayload();
            String originalTopic = payload != null ? (String) payload.get("originalTopic") : null;
            String originalKey = payload != null ? (String) payload.get("originalKey") : null;
            String originalAction = payload != null ? (String) payload.get("originalAction") : null;
            int retryCount = msg.getRetryCount();

            // IRON RULE: fast topic NEVER retries
            if (originalTopic != null && originalTopic.endsWith(".fast")) {
                log.info("Fast topic {} never retries (iron rule)", originalTopic);
                failedTaskLogService.save(msg, "NOT_RETRYABLE_FAST");
                ack.acknowledge();
                return;
            }

            int maxRetry = retryPolicy.getMaxRetry(originalAction);
            if (retryCount >= maxRetry) {
                log.warn("Action {} exceeded max retry ({}>={})", originalAction, retryCount, maxRetry);
                failedTaskLogService.save(msg, "MAX_RETRY_EXCEEDED");
                ack.acknowledge();
                return;
            }

            if (!retryPolicy.isRetryable(originalAction)) {
                log.info("Action {} not retryable", originalAction);
                failedTaskLogService.save(msg, "NOT_RETRYABLE");
                ack.acknowledge();
                return;
            }

            Duration delay = retryPolicy.getDelay(retryCount);
            if (delay.toMillis() > 0) {
                Thread.sleep(delay.toMillis());
            }

            // Rebuild original message with retryCount+1
            Map<String, Object> originalPayload = payload.containsKey("originalPayload")
                ? (Map<String, Object>) payload.get("originalPayload")
                : null;

            TaskMessage retryMsg = TaskMessage.builder()
                .messageId(msg.getMessageId())
                .taskType(payload.containsKey("originalTaskType") ? (String) payload.get("originalTaskType") : "channel_action")
                .taskAction(originalAction)
                .sourceJobType("retry-dispatch-job")
                .merchantId(msg.getMerchantId())
                .ownerType(msg.getOwnerType())
                .ownerId(msg.getOwnerId())
                .payload(originalPayload)
                .createdAt(msg.getCreatedAt())
                .retryCount(retryCount + 1)
                .build();

            taskProducer.send(originalTopic, originalKey, retryMsg);
            log.info("Retry dispatched: action={}, topic={}, attempt={}/{}",
                originalAction, originalTopic, retryCount + 1, maxRetry);

            ack.acknowledge();

        } catch (Exception e) {
            log.error("RetryDispatch itself failed: {}", e.getMessage());
            ack.acknowledge();
        } finally {
            TaskMdcHelper.clear();
        }
    }
}
