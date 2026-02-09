package com.simpleec.job.backend.action;

import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@Slf4j
public class ManagePartitionsActionService implements BackendActionService {

    private static final List<String> PARTITIONED_TABLES = List.of(
        "orders", "order_status_logs", "order_shipments",
        "channel_sync_logs", "daily_statistics", "failed_task_logs", "refund_orders"
    );

    @Override
    public String getAction() {
        return "MANAGE_PARTITIONS";
    }

    @Override
    public void setting(TaskMessage msg) {}

    @Override
    public void verify(TaskMessage msg) {}

    @Override
    public Object execute(TaskMessage msg) {
        LocalDate now = LocalDate.now();
        for (String table : PARTITIONED_TABLES) {
            for (int i = 1; i <= 2; i++) {
                LocalDate futureMonth = now.plusMonths(i).withDayOfMonth(1);
                String partitionName = String.format("%s_y%dm%02d",
                    table, futureMonth.getYear(), futureMonth.getMonthValue());
                log.info("ManagePartitions: would create {} if not exists", partitionName);
                // TODO Phase 4: execute CREATE TABLE IF NOT EXISTS via JdbcTemplate
            }
        }
        return null;
    }

    @Override
    public void routeNext(TaskProducer producer, TaskMessage msg, Object result) {
        // No downstream topic
    }
}
