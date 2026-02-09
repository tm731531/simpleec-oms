package com.simpleec.job.backend.action;

import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DailyStatisticsActionService implements BackendActionService {

    @Override
    public String getAction() {
        return "DAILY_STATISTICS";
    }

    @Override
    public void setting(TaskMessage msg) {
        // Load merchant timezone from payload or DB
    }

    @Override
    public void verify(TaskMessage msg) {
        // Verify merchantId exists
    }

    @Override
    public Object execute(TaskMessage msg) {
        String merchantId = msg.getMerchantId();
        String statDate = msg.getPayload() != null ? (String) msg.getPayload().get("statDate") : null;
        log.info("DailyStatistics: merchant={}, date={}", merchantId, statDate);
        // TODO Phase 4: query orders table, upsert daily_statistics per channel
        return null;
    }

    @Override
    public void routeNext(TaskProducer producer, TaskMessage msg, Object result) {
        // No downstream topic — write to daily_statistics table is the end
    }
}
