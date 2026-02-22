package com.simpleec.frontendjob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

/**
 * 前端任務應用 - 實時事件推送系統 (Layer B - Data Processing)
 *
 * 透過 WebSocket/SSE (Server-Sent Events) 將後端事件推送到已連接的前端客戶端
 * 監聽 order.process 和 return.process topics，廣播訂單狀態變更到對應 merchant 的客戶端
 */
@SpringBootApplication(
    scanBasePackages = {"com.simpleec.common", "com.simpleec.frontendjob"},
    exclude = {
        RedisRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        com.simpleec.core.config.ServiceAutoConfiguration.class
    }
)
@EnableKafka
@EnableScheduling
public class FrontendJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(FrontendJobApplication.class, args);
    }

    @Slf4j
    @Component
    public static class FrontendJobStartupLogger {
        @EventListener(ApplicationReadyEvent.class)
        public void onApplicationReady() {
            log.info("╔════════════════════════════════════════════════════════════╗");
            log.info("║  FRONTEND JOB Started (Layer B - Data Processing)         ║");
            log.info("║                                                            ║");
            log.info("║  LISTENS TO (Consumer):                                   ║");
            log.info("║    • order.process (filtered: ORDER_STATUS_CHANGE only)  ║");
            log.info("║    • return.process (filtered: RETURN_STATUS_CHANGE only)║");
            log.info("║                                                            ║");
            log.info("║  SENDS TO:                                                ║");
            log.info("║    • WebSocket/SSE clients (real-time push)              ║");
            log.info("║    • Scoped by merchantId (isolation)                    ║");
            log.info("║                                                            ║");
            log.info("║  NOTIFICATION TYPES:                                      ║");
            log.info("║    • ORDER_STATUS_CHANGE: Pending → Shipped → Completed  ║");
            log.info("║    • RETURN_STATUS_CHANGE: Requested → Approved → Done   ║");
            log.info("║                                                            ║");
            log.info("║  CONSUMER GROUP: frontend-job-group (concurrency: 4)      ║");
            log.info("║                                                            ║");
            log.info("║  FLOW: Kafka → Filter by taskType → Format for UI → SSE ║");
            log.info("╚════════════════════════════════════════════════════════════╝");
        }
    }
}
