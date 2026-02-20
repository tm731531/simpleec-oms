package com.simpleec.retryjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Retry Job 應用程序入點
 *
 * 消費 task.failed 和 task.dlt topics
 * - task.failed: 失敗消息的重試處理
 * - task.dlt: 死信隊列（達到最大重試次數）
 */
@SpringBootApplication(scanBasePackages = "com.simpleec")
@EnableKafka
public class RetryJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(RetryJobApplication.class, args);
    }
}
