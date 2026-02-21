package com.simpleec.schedulerjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 排程應用 - 輕量級的心跳發送器與時間驅動的任務派發器
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
}
