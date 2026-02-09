package com.simpleec.job.frontend;

import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.observability.TaskMdcHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class FrontendJob {

    @KafkaListener(
        topics = "task.frontend",
        groupId = "frontend-job",
        concurrency = "${job.frontend.concurrency:2}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        TaskMdcHelper.set(msg);
        try {
            log.info("FrontendJob received: action={}", msg.getTaskAction());
            // Skeleton - to be implemented in future phases
            ack.acknowledge();
        } finally {
            TaskMdcHelper.clear();
        }
    }
}
