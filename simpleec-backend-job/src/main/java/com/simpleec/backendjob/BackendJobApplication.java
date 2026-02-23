package com.simpleec.backendjob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.stereotype.Component;

/**
 * Backend Job 應用程序入點 (Layer B - Data Processing)
 *
 * 消費 task.backend topic 並執行：
 * - 退貨單 UPSERT (RETURN_UPSERT task type)
 * - 退貨狀態變更 (RETURN_STATUS_CHANGE task type)
 * - 賣場同步 (SYNC_PACK task type)
 * - 價格更新 (UPDATE_PRICE task type)
 *
 * 使用 EventHandlerRegistry 路由不同 TaskType 到對應 Handler
 */
@SpringBootApplication(
    scanBasePackages = {
        "com.simpleec.backendjob",
        "com.simpleec.common"
    },
    exclude = {
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        com.simpleec.core.config.ServiceAutoConfiguration.class
    }
)
@EnableKafka
public class BackendJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendJobApplication.class, args);
    }

    @Slf4j
    @Component
    public static class BackendJobStartupLogger {
        @EventListener(ApplicationReadyEvent.class)
        public void onApplicationReady() {
            log.info("╔════════════════════════════════════════════════════════════╗");
            log.info("║  BACKEND JOB Started (Layer B - Data Processing)          ║");
            log.info("║                                                            ║");
            log.info("║  LISTENS TO (Consumer):                                   ║");
            log.info("║    • task.backend (task types):                           ║");
            log.info("║      - RETURN_UPSERT, RETURN_STATUS_CHANGE               ║");
            log.info("║      - SYNC_PACK, UPDATE_PRICE                           ║");
            log.info("║                                                            ║");
            log.info("║  WRITES TO:                                               ║");
            log.info("║    • return_orders table (UPSERT logic)                  ║");
            log.info("║    • sell_pack table (SYNC_PACK)                         ║");
            log.info("║                                                            ║");
            log.info("║  HANDLER REGISTRY (TaskType routing):                     ║");
            log.info("║    • RETURN_UPSERT → ReturnEventHandler                  ║");
            log.info("║    • RETURN_STATUS_CHANGE → ReturnEventHandler           ║");
            log.info("║    • SYNC_PACK → PackSyncHandler (future)                ║");
            log.info("║    • UPDATE_PRICE → PriceUpdateHandler (future)          ║");
            log.info("║                                                            ║");
            log.info("║  CONSUMER GROUP: backend-job-group (concurrency: 6)       ║");
            log.info("║                                                            ║");
            log.info("║  NOTE: SYNC_INVENTORY 由 Scheduler 排程，不由此 Job 處理  ║");
            log.info("╚════════════════════════════════════════════════════════════╝");
        }
    }
}
