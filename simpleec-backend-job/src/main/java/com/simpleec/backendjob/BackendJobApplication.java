package com.simpleec.backendjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Backend Job 應用程序入點
 *
 * 消費 task.backend topic 並執行：
 * - 產品同步到各平台
 * - 庫存/SellerPack 同步
 * - 訂單、銷售、退貨、庫存報表生成
 * - 系統健康檢查
 */
@SpringBootApplication(scanBasePackages = "com.simpleec")
@EnableKafka
public class BackendJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendJobApplication.class, args);
    }
}
