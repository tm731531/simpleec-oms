package com.simpleec.job.channel;

import com.simpleec.core.kafka.SchemaVersionHandler;
import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import com.simpleec.core.observability.TaskMdcHelper;
import com.simpleec.job.channel.action.ActionFactory;
import com.simpleec.job.channel.action.ActionService;
import com.simpleec.job.channel.model.Resource;
import com.simpleec.job.channel.service.SyncLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class ChannelJob {

    private final ActionFactory actionFactory;
    private final TaskProducer taskProducer;
    private final SyncLogService syncLogService;

    @KafkaListener(
        topics = "#{'${job.channel.topics}'.split(',')}",
        groupId = "${job.channel.group-id}",
        concurrency = "${job.channel.concurrency:4}")
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

            ActionService service = actionFactory.getService(msg.getTopic(), msg.getTaskAction());
            if (service == null) {
                log.error("Unknown action: topic={}, action={}", msg.getTopic(), msg.getTaskAction());
                ack.acknowledge();
                return;
            }

            Resource resource = buildResource(msg);
            service.setting(resource);
            service.getPlatformTokens();
            service.verifyNeedData();
            service.doAction();

            syncLogService.log(msg, "SUCCESS", null);
            ack.acknowledge();

        } catch (Exception e) {
            syncLogService.log(msg, "FAILED", e.getMessage());
            taskProducer.send("task.failed", null, buildFailedMessage(msg, e));
            ack.acknowledge();
            log.error("ChannelJob failed: action={}, error={}", msg.getTaskAction(), e.getMessage());
        } finally {
            TaskMdcHelper.clear();
        }
    }

    private Resource buildResource(TaskMessage msg) {
        Resource resource = new Resource();
        resource.setMsg(msg);
        resource.setTaskProducer(taskProducer);
        return resource;
    }

    private TaskMessage buildFailedMessage(TaskMessage original, Exception e) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("originalTopic", original.getTopic());
        payload.put("originalKey", original.getPartitionKey());
        payload.put("originalAction", original.getTaskAction());
        payload.put("error", e.getMessage());
        if (original.getPayload() != null) {
            payload.put("originalPayload", original.getPayload());
        }
        return TaskMessage.builder()
            .messageId(UUID.randomUUID().toString())
            .taskType("dispatch")
            .taskAction("RETRY_DISPATCH")
            .sourceJobType("channel-job")
            .merchantId(original.getMerchantId())
            .ownerType(original.getOwnerType())
            .ownerId(original.getOwnerId())
            .payload(payload)
            .createdAt(Instant.now())
            .retryCount(original.getRetryCount())
            .build();
    }
}
