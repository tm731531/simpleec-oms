package com.simpleec.job.retry.service;

import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class FailedTaskLogService {

    private final TaskProducer taskProducer;

    public void save(TaskMessage msg, String reason) {
        log.info("FailedTaskLog: action={}, reason={}, merchant={}, retry={}",
            msg.getPayload() != null ? msg.getPayload().get("originalAction") : "unknown",
            reason, msg.getMerchantId(), msg.getRetryCount());

        // Send to Dead Letter Topic for permanent storage / inspection
        TaskMessage dltMsg = TaskMessage.builder()
            .messageId(UUID.randomUUID().toString())
            .taskType("dlt")
            .taskAction(reason)
            .sourceJobType("retry-dispatch-job")
            .merchantId(msg.getMerchantId())
            .payload(msg.getPayload())
            .createdAt(Instant.now())
            .retryCount(msg.getRetryCount())
            .traceId(msg.getTraceId())
            .build();
        taskProducer.send("task.dlt", null, dltMsg);

        // TODO Phase 4: also write to failed_task_logs table via mapper
    }
}
