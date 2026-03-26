package com.simpleec.retryjob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

/**
 * Retry Job Application (Layer A - Foundation)
 *
 * Consumes task.failed and task.dlt topics.
 * - task.failed: Error-type-aware retry with Redis-based delayed scheduling
 * - task.dlt: Dead letter queue for messages that exceeded max retries or are non-retryable
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
@EnableScheduling
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
            log.info("║  LISTENS TO: task.failed                                  ║");
            log.info("║  PUBLISHES TO: original topic (retry) / task.dlt          ║");
            log.info("║                                                            ║");
            log.info("║  ERROR-TYPE-AWARE RETRY:                                  ║");
            log.info("║    4xx / FORMAT_ERROR  -> DLT immediately                 ║");
            log.info("║    5xx                 -> max 3 retries + backoff         ║");
            log.info("║    NETWORK_ERROR       -> max 5 retries + backoff         ║");
            log.info("║    Unknown taskType    -> DLT immediately                 ║");
            log.info("║                                                            ║");
            log.info("║  BACKOFF: 1m, 5m, 30m, 60m, 120m (Redis sorted set)      ║");
            log.info("║  SCHEDULER: polls Redis every 10s                         ║");
            log.info("║  CONSUMER GROUP: retry-job-group                          ║");
            log.info("╚════════════════════════════════════════════════════════════╝");
        }
    }
}
