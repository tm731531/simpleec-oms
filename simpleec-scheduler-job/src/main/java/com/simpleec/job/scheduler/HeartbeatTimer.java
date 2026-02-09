package com.simpleec.job.scheduler;

import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class HeartbeatTimer {

    private final TaskProducer taskProducer;

    @Scheduled(fixedDelayString = "${scheduler.heartbeat.interval-ms:1000}")
    public void tick() {
        TaskMessage tick = TaskMessage.builder()
            .messageId(UUID.randomUUID().toString())
            .taskType("heartbeat")
            .taskAction("TICK")
            .sourceJobType("heartbeat-timer")
            .createdAt(Instant.now())
            .build();

        taskProducer.send("scheduler", null, tick);
    }
}
