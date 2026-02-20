package com.simpleec.frontendjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Frontend Job 應用程序入點
 *
 * 提供 REST API 給前端 UI，接收用戶操作並轉換為 Kafka 消息
 * - 手動出貨確認
 * - 產品/庫存同步觸發
 * - 退貨批准/拒絕
 * - 報表下載請求
 */
@SpringBootApplication(scanBasePackages = "com.simpleec")
@EnableKafka
public class FrontendJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(FrontendJobApplication.class, args);
    }
}
