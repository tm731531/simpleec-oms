package com.simpleec.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Gateway 應用程序入點
 *
 * 接收來自各平台的 webhook，轉換為 OMS 標準格式，發送到 Kafka
 * - Shopify: /webhook/shopify/{event-type}
 * - Shopee: /webhook/shopee/{event-type}
 * - Easystore: /webhook/easystore/{event-type}
 */
@SpringBootApplication(scanBasePackages = "com.simpleec")
@EnableKafka
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
