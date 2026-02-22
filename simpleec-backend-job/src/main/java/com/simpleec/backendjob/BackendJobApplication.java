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
 * 消費 return.process 和 pack.sync topics 並執行：
 * - 退貨單 UPSERT (RETURN_UPSERT)
 * - 退貨狀態變更 (RETURN_STATUS_CHANGE)
 * - 賣場同步 (SYNC_PACK)
 * - 價格更新 (UPDATE_PRICE)
 *
 * 使用 EventHandlerRegistry 路由不同 TaskType 到對應 Handler
 */
@SpringBootApplication(scanBasePackages = "com.simpleec")
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
            log.info("║    • return.process (RETURN_UPSERT, RETURN_STATUS_CHG)   ║");
            log.info("║    • pack.sync (SYNC_PACK, UPDATE_PRICE)                 ║");
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
