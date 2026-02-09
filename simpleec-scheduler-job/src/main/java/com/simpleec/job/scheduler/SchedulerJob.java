package com.simpleec.job.scheduler;

import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import com.simpleec.core.observability.TaskMdcHelper;
import com.simpleec.job.scheduler.config.ScheduleConfig;
import com.simpleec.job.scheduler.config.ScheduleRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class SchedulerJob {

    private final TaskProducer taskProducer;
    private final ScheduleConfig config;

    private final ConcurrentHashMap<String, Long> lastTriggerCache = new ConcurrentHashMap<>();

    @KafkaListener(
        topics = "scheduler",
        groupId = "scheduler-job",
        concurrency = "${job.scheduler.concurrency:1}")
    public void handle(TaskMessage tick, Acknowledgment ack) {
        TaskMdcHelper.set(tick);
        try {
        Instant now = tick.getCreatedAt();
        if (now == null) {
            now = Instant.now();
        }
        long nowEpoch = now.getEpochSecond();

        for (ScheduleRule rule : config.getRules()) {
            try {
                Long lastEpoch = lastTriggerCache.get(rule.getId());
                if (lastEpoch != null && (nowEpoch - lastEpoch) < rule.getMinGapSeconds()) {
                    continue;
                }

                if (shouldTrigger(rule, now, lastEpoch, nowEpoch)) {
                    dispatch(rule, now);
                    lastTriggerCache.put(rule.getId(), nowEpoch);
                    log.info("Schedule triggered: {} at {}", rule.getAction(), now);
                }
            } catch (Exception e) {
                log.error("Schedule error for {}: {}", rule.getId(), e.getMessage());
            }
        }

        ack.acknowledge();
        } finally {
            TaskMdcHelper.clear();
        }
    }

    private boolean shouldTrigger(ScheduleRule rule, Instant now, Long lastEpoch, long nowEpoch) {
        if ("interval".equals(rule.getMode())) {
            if (lastEpoch == null) return true;
            return (nowEpoch - lastEpoch) >= rule.getIntervalSeconds();
        }
        if ("cron".equals(rule.getMode())) {
            // Simplified cron: parse "H M * * *" pattern for daily triggers
            ZoneId tz = ZoneId.of(rule.getTimezone() != null ? rule.getTimezone() : "UTC");
            ZonedDateTime localNow = now.atZone(tz);
            String[] parts = rule.getCronExpression().split("\s+");
            if (parts.length >= 2) {
                int minute = Integer.parseInt(parts[0]);
                int hour = Integer.parseInt(parts[1]);
                return localNow.getHour() == hour && localNow.getMinute() == minute;
            }
        }
        return false;
    }

    private void dispatch(ScheduleRule rule, Instant now) {
        switch (rule.getAction()) {
            case "FETCH_ALL_ORDERS" -> dispatchFetchOrders(rule, now);
            case "CHECK_ALL_HEALTH" -> dispatchCheckHealth(rule, now);
            case "DAILY_STATISTICS" -> dispatchDailyStatistics(rule, now);
            case "MANAGE_PARTITIONS" -> dispatchManagePartitions(now);
            default -> log.warn("Unknown schedule action: {}", rule.getAction());
        }
    }

    private void dispatchFetchOrders(ScheduleRule rule, Instant now) {
        // TODO Phase 4: query DB for active channels, dispatch per channel
        String[] platforms = {"momo", "shopee", "yahoo", "pchome"};
        for (String platform : platforms) {
            TaskMessage msg = TaskMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .taskType("channel_action")
                .taskAction("FETCH_ORDERS")
                .sourceJobType("scheduler-job")
                .merchantId(rule.getMerchantId())
                .createdAt(now)
                .build();
            taskProducer.send(platform + ".slow", null, msg);
        }
    }

    private void dispatchCheckHealth(ScheduleRule rule, Instant now) {
        // TODO Phase 4: query DB for active channels, dispatch per channel
        String[] platforms = {"momo", "shopee", "yahoo", "pchome"};
        for (String platform : platforms) {
            TaskMessage msg = TaskMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .taskType("channel_action")
                .taskAction("CHECK_HEALTH")
                .sourceJobType("scheduler-job")
                .merchantId(rule.getMerchantId())
                .createdAt(now)
                .build();
            taskProducer.send(platform + ".fast", "health-check", msg);
        }
    }

    private void dispatchDailyStatistics(ScheduleRule rule, Instant now) {
        TaskMessage msg = TaskMessage.builder()
            .messageId(UUID.randomUUID().toString())
            .taskType("backend")
            .taskAction("DAILY_STATISTICS")
            .sourceJobType("scheduler-job")
            .merchantId(rule.getMerchantId())
            .timezone(rule.getTimezone())
            .createdAt(now)
            .build();
        taskProducer.send("task.backend", rule.getMerchantId(), msg);
    }

    private void dispatchManagePartitions(Instant now) {
        TaskMessage msg = TaskMessage.builder()
            .messageId(UUID.randomUUID().toString())
            .taskType("backend")
            .taskAction("MANAGE_PARTITIONS")
            .sourceJobType("scheduler-job")
            .createdAt(now)
            .build();
        taskProducer.send("task.backend", "system", msg);
    }
}
