package com.simpleec.job.backend;

import com.simpleec.core.kafka.SchemaVersionHandler;
import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import com.simpleec.core.observability.TaskMdcHelper;
import com.simpleec.job.backend.action.BackendActionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class BackendJob {

    private final Map<String, BackendActionService> workers = new HashMap<>();
    private final TaskProducer taskProducer;

    public BackendJob(List<BackendActionService> actionServices, TaskProducer taskProducer) {
        this.taskProducer = taskProducer;
        for (BackendActionService service : actionServices) {
            workers.put(service.getAction(), service);
        }
    }

    @KafkaListener(
        topics = "task.backend",
        groupId = "backend-job",
        concurrency = "${job.backend.concurrency:4}")
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

            BackendActionService worker = workers.get(msg.getTaskAction());
            if (worker == null) {
                log.warn("Unknown backend action: {}", msg.getTaskAction());
                ack.acknowledge();
                return;
            }

            worker.setting(msg);
            worker.verify(msg);
            Object result = worker.execute(msg);
            worker.routeNext(taskProducer, msg, result);

            ack.acknowledge();

        } catch (Exception e) {
            log.error("BackendJob failed: action={}, error={}", msg.getTaskAction(), e.getMessage());
            Map<String, Object> payload = new HashMap<>();
            payload.put("originalTopic", "task.backend");
            payload.put("originalAction", msg.getTaskAction());
            payload.put("error", e.getMessage());
            TaskMessage failedMsg = TaskMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .taskType("dispatch")
                .taskAction("RETRY_DISPATCH")
                .sourceJobType("backend-job")
                .merchantId(msg.getMerchantId())
                .payload(payload)
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
