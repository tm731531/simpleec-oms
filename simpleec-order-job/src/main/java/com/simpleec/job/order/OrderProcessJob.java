package com.simpleec.job.order;

import com.simpleec.core.kafka.SchemaVersionHandler;
import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import com.simpleec.core.observability.TaskMdcHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class OrderProcessJob {

    private final TaskProducer taskProducer;
    private final RedisTemplate<String, String> redis;

    @KafkaListener(
        topics = "order.process",
        groupId = "order-process-job",
        concurrency = "${job.order-process.concurrency:4}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        TaskMdcHelper.set(msg);
        try {
            // Schema version gate
            if (!SchemaVersionHandler.isSupported(msg.getSchemaVersion())) {
                log.warn("Unsupported schemaVersion={}, routing to DLT", msg.getSchemaVersion());
                taskProducer.send("task.dlt", null, msg);
                ack.acknowledge();
                return;
            }

            Map<String, Object> payload = msg.getPayload();
            String newHash = payload != null ? (String) payload.get("orderHash") : null;
            String channelOrderId = payload != null ? (String) payload.get("channelOrderId") : null;
            String status = payload != null ? (String) payload.get("orderStatus") : null;

            log.info("OrderProcess: channelOrderId={}, status={}", channelOrderId, status);

            // Update Redis hash for dedup
            if (newHash != null && channelOrderId != null) {
                String hashKey = "order:hash:" + msg.getMerchantId() + ":" + msg.getOwnerId() + ":" + channelOrderId;
                redis.opsForValue().set(hashKey, newHash, Duration.ofDays(7));
            }

            // TODO Phase 4: DB upsert via OrderService

            // Route to task.backend if status changed
            TaskMessage backendMsg = TaskMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .taskType("backend")
                .taskAction("ORDER_STATUS_CHANGED")
                .sourceJobType("order-process-job")
                .merchantId(msg.getMerchantId())
                .payload(msg.getPayload())
                .createdAt(Instant.now())
                .build();
            taskProducer.send("task.backend", msg.getMerchantId(), backendMsg);

            ack.acknowledge();

        } catch (Exception e) {
            log.error("OrderProcessJob failed: {}", e.getMessage());
            Map<String, Object> failPayload = new HashMap<>();
            failPayload.put("originalTopic", "order.process");
            failPayload.put("originalAction", msg.getTaskAction());
            failPayload.put("error", e.getMessage());
            TaskMessage failedMsg = TaskMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .taskType("dispatch")
                .taskAction("RETRY_DISPATCH")
                .sourceJobType("order-process-job")
                .merchantId(msg.getMerchantId())
                .payload(failPayload)
                .createdAt(Instant.now())
                .retryCount(msg.getRetryCount())
                .build();
            taskProducer.send("task.failed", null, failedMsg);
            ack.acknowledge();
        } finally {
            TaskMdcHelper.clear();
        }
    }
}
