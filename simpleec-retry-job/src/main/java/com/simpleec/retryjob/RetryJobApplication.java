package com.simpleec.retryjob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.stereotype.Component;

/**
 * Retry Job 應用程序入點 (Layer A - Foundation)
 *
 * 消費 task.failed 和 task.dlt topics
 * - task.failed: 失敗消息的重試處理
 * - task.dlt: 死信隊列（達到最大重試次數）
 */
@SpringBootApplication(
    scanBasePackages = {
        "com.simpleec.retryjob",
        "com.simpleec.common"
    },
    exclude = {
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        com.simpleec.core.config.ServiceAutoConfiguration.class
    }
)
@EnableKafka
public class RetryJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(RetryJobApplication.class, args);
    }

    @Slf4j
    @Component
    public static class RetryStartupLogger {
        @EventListener(ApplicationReadyEvent.class)
        public void onApplicationReady() {
            log.info("╔════════════════════════════════════════════════════════════╗");
            log.info("║  RETRY JOB Started (Layer A - Foundation)                 ║");
            log.info("║                                                            ║");
            log.info("║  LISTENS TO (Consumer):                                   ║");
            log.info("║    • task.failed (DLT Source)                            ║");
            log.info("║                                                            ║");
            log.info("║  PUBLISHES TO (Producer):                                 ║");
            log.info("║    • Original topic (on retry success)                   ║");
            log.info("║    • task.dlt (on exhaustion)                            ║");
            log.info("║                                                            ║");
            log.info("║  RETRY LOGIC:                                             ║");
            log.info("║    • Max 3 retries                                        ║");
            log.info("║    • Exponential backoff: 1s → 5s → 30s                  ║");
            log.info("║    • Retry count tracked in Redis: retry:{messageId}    ║");
            log.info("║    • 24hr expiry on Redis key                            ║");
            log.info("║                                                            ║");
            log.info("║  CONSUMER GROUP: retry-job-group                         ║");
            log.info("╚════════════════════════════════════════════════════════════╝");
        }
    }
}
