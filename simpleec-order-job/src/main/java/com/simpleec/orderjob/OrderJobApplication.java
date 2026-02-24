package com.simpleec.orderjob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

@SpringBootApplication(
    scanBasePackages = {"com.simpleec.common", "com.simpleec.core", "com.simpleec.orderjob"},
    exclude = {
        RedisRepositoriesAutoConfiguration.class
    }
)
@EntityScan(basePackages = "com.simpleec.core.entity")
@EnableJpaRepositories(basePackages = "com.simpleec.core.repository")
@ComponentScan(basePackages = {"com.simpleec.core.config", "com.simpleec.core.service"})
@org.springframework.kafka.annotation.EnableKafka
public class OrderJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderJobApplication.class, args);
    }

    @Slf4j
    @Component
    public static class OrderJobStartupLogger {
        @EventListener(ApplicationReadyEvent.class)
        public void onApplicationReady() {
            log.info("╔════════════════════════════════════════════════════════════╗");
            log.info("║  ORDER JOB Started (Layer B - Data Processing)            ║");
            log.info("║                                                            ║");
            log.info("║  LISTENS TO (Consumer):                                   ║");
            log.info("║    • order.process (ORDER_UPSERT, ORDER_STATUS_CHANGE)   ║");
            log.info("║    • return.process (RETURN_UPSERT - legacy)             ║");
            log.info("║                                                            ║");
            log.info("║  WRITES TO:                                               ║");
            log.info("║    • orders table (INSERT/UPDATE)                        ║");
            log.info("║    • return_orders table (two-layer dedup)               ║");
            log.info("║                                                            ║");
            log.info("║  HANDLERS:                                                ║");
            log.info("║    • OrderUpsertHandler (Redis fast path + DB safe path)║");
            log.info("║      → Hash-based change detection (SHA256)             ║");
            log.info("║      → Two-layer deduplication                          ║");
            log.info("║    • OrderStatusChangeConsumer                          ║");
            log.info("║    • ReturnUpsertConsumer (dedicated return processing) ║");
            log.info("║                                                            ║");
            log.info("║  DEDUPLICATION:                                           ║");
            log.info("║    • Layer 1: Redis cache (fast path) - 24hr TTL        ║");
            log.info("║    • Layer 2: DB unique constraint (safe path)          ║");
            log.info("║    • Idempotent: No duplicate writes even with retries  ║");
            log.info("║                                                            ║");
            log.info("║  CONSUMER GROUP: order-job-group (concurrency: 4)        ║");
            log.info("╚════════════════════════════════════════════════════════════╝");
        }
    }
}
