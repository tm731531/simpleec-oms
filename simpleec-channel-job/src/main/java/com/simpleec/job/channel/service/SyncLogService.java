package com.simpleec.job.channel.service;

import com.simpleec.core.kafka.TaskMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncLogService {

    public void log(TaskMessage msg, String status, String errorMessage) {
        log.info("SyncLog: action={}, status={}, channel={}, error={}",
            msg.getTaskAction(), status, msg.getOwnerId(), errorMessage);
        // TODO: implement DB write via mapper in Phase 4
    }

    public void logHealth(TaskMessage msg, String health) {
        log.info("SyncLog: action=CHECK_HEALTH, health={}, channel={}",
            health, msg.getOwnerId());
        // TODO: implement DB write via mapper in Phase 4
    }
}
