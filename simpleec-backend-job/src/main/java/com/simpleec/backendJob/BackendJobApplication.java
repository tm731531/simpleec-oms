package com.simpleec.backendJob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 後端任務應用 - 報表生成與系統健檢
 *
 * 監聽 task.backend topic，根據 taskType 路由到相應的事件處理器
 * - ORDER_REPORT: 定時生成訂單報表
 * - INVENTORY_REPORT: 庫存變動報表
 * - SALES_REPORT: 銷售報表
 * - RETURN_REPORT: 退貨報表
 * - KAFKA_HEALTH_CHECK: Kafka 連線健檢
 * - DAILY_REPORT: 日終報表
 */
@SpringBootApplication(
    scanBasePackages = {"com.simpleec.common", "com.simpleec.backendJob"},
    exclude = {
        RedisRepositoriesAutoConfiguration.class
    }
)
@EntityScan(basePackages = "com.simpleec.core.entity")
@EnableJpaRepositories(basePackages = "com.simpleec.core.repository")
@EnableScheduling
@org.springframework.kafka.annotation.EnableKafka
public class BackendJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendJobApplication.class, args);
    }
}
