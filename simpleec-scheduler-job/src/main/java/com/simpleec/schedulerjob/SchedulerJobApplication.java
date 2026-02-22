package com.simpleec.schedulerjob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

/**
 * 排程應用 - 輕量級的心跳發送器與時間驅動的任務派發器 (Layer A)
 *
 * 啟用 JPA 支援是為了查詢通路資料庫，在派發任務前驗證通路是否已啟用且開啟同步
 */
@SpringBootApplication(
    scanBasePackages = {"com.simpleec.common", "com.simpleec.schedulerjob"},
    exclude = {
        RedisRepositoriesAutoConfiguration.class  // 只排除 Redis 倉庫，其他 JPA 配置都啟用
    }
)
@EntityScan(basePackages = "com.simpleec.core.entity")
@EnableJpaRepositories(basePackages = "com.simpleec.core.repository")
@EnableScheduling
public class SchedulerJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchedulerJobApplication.class, args);
    }

    @Slf4j
    @Component
    public static class SchedulerStartupLogger {
        @EventListener(ApplicationReadyEvent.class)
        public void onApplicationReady() {
            log.info("╔════════════════════════════════════════════════════════════╗");
            log.info("║  SCHEDULER JOB Started (Layer A - Foundation)              ║");
            log.info("║                                                            ║");
            log.info("║  PUBLISHES TO (Producer):                                 ║");
            log.info("║    • shopee.fast / shopee.slow                           ║");
            log.info("║    • momo.fast / momo.slow                               ║");
            log.info("║    • yahoo.fast / yahoo.slow                             ║");
            log.info("║    • pchome.fast / pchome.slow                           ║");
            log.info("║    • cyberbiz.fast / cyberbiz.slow                       ║");
            log.info("║    • easystore.fast / easystore.slow                     ║");
            log.info("║    • scheduler.heartbeat (every 1 min)                   ║");
            log.info("║                                                            ║");
            log.info("║  SCHEDULED TASKS:                                         ║");
            log.info("║    • Every 5min:   FETCH_ORDERS → platform.fast          ║");
            log.info("║    • Every 15min:  SYNC_INVENTORY → platform.slow        ║");
            log.info("║    • Every 1hr:    UPDATE_PRICE → platform.slow          ║");
            log.info("║    • Every 1min:   Heartbeat → scheduler.heartbeat       ║");
            log.info("║                                                            ║");
            log.info("║  STATE: Heartbeat key in Redis for monitoring            ║");
            log.info("╚════════════════════════════════════════════════════════════╝");
        }
    }
}
