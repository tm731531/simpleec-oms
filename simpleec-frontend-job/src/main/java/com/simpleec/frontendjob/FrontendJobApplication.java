package com.simpleec.frontendjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 前端任務應用 - 實時事件推送系統
 *
 * 透過 SSE (Server-Sent Events) 將後端事件推送到已連接的前端客戶端
 * 監聽 task.frontend topic，廣播到對應 merchant 的客戶端
 */
@SpringBootApplication(
    scanBasePackages = {"com.simpleec.common", "com.simpleec.frontendjob"},
    exclude = {
        RedisRepositoriesAutoConfiguration.class
    }
)
@EnableKafka
@EnableScheduling
public class FrontendJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(FrontendJobApplication.class, args);
    }
}
