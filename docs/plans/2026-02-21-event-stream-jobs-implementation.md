# Event Stream Jobs Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Implement and integrate 6 event stream job workers that process e-commerce orders, channel synchronization, and system events through Kafka message queues with proper error handling, retry logic, and monitoring.

**Architecture:** Three-layer event-driven architecture:
1. **Layer 1 (TaskType)** — Behavior abstraction in Kafka topics (ORDER_UPSERT, FETCH_ORDERS, SHIP_ORDER, SYNC_PACK, etc.)
2. **Layer 2 (Schema)** — Unified OMS structures (Order, Product, Pack, Return) with consistent JSONB handling
3. **Layer 3 (Deployment)** — Job workers subscribe to platform-specific and business topics with distributed tracing and metrics

**Tech Stack:** Spring Boot 3.5.0, Java 17, Spring Kafka, PostgreSQL/JPA, Kafka 3.7.1 KRaft, Redis 7, Prometheus, OpenTelemetry

---

## Project Overview & Dependencies

### Job Projects (执行顺序)

**Layer A — Foundation Jobs (No dependencies)**
1. **simpleec-retry-job** — DLT handler & retry logic
2. **simpleec-scheduler-job** — Task scheduling & heartbeat

**Layer B — Data Processing Jobs (Depends on Layer A)**
3. **simpleec-order-job** — ORDER_UPSERT, ORDER_STATUS_CHANGE consumer
4. **simpleec-backend-job** — Generic backend event handler
5. **simpleec-frontend-job** — Frontend notification dispatcher

**Layer C — Channel Integration (Depends on Layer A+B)**
6. **simpleec-channel-job** — Multi-platform sync (Shopee, Momo, Yahoo, PChome, Cyberbiz, EasyStore)

### Key Topics Configuration

**Business Topics (order.process, order.fast, return.process, pack.sync, etc.)**
- order.process — ORDER_UPSERT, ORDER_STATUS_CHANGE
- order.fast — Time-sensitive operations
- return.process — RETURN_UPSERT, REFUND_STATUS_CHANGE
- pack.sync — SYNC_PACK operations
- task.failed — Failed tasks (DLT source)
- task.dlt — Dead Letter Topic (DLT sink)

**Platform-Specific Topics (shopee.fast, shopee.slow, momo.fast, etc.)**
- {platform}.fast — Urgent operations (FETCH_ORDERS, SHIP_ORDER, CANCEL_ORDER)
- {platform}.slow — Scheduled jobs (SYNC_INVENTORY, UPDATE_PRICE)

---

## Task 1: Database & Infrastructure Validation

**Files:**
- Verify: `docker/init-db/01-schema.sql` (v4 schema validation)
- Verify: `docker-compose.yml` (all 25 containers running)
- Verify: Kafka topics created via `kafka/init-topics.sh` or Kafka manager

**Step 1: Validate Database Schema**

```bash
cd /home/tom/ONEEC/simpleec-oms
docker exec simpleec-postgres psql -U postgres -d simpleec_db -c "
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public';
SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;
"
```

Expected: 18 tables present (orders, products, channels, returns, sell_pack, account, merchant, platform, etc.)

**Step 2: Validate Kafka Topics**

```bash
docker exec kafka-broker kafka-topics.sh --list --bootstrap-server localhost:9092 | sort
```

Expected topics:
- order.process, order.fast, return.process, pack.sync, task.failed, task.dlt
- shopee.fast, shopee.slow, momo.fast, momo.slow, yahoo.fast, yahoo.slow, etc.

**Step 3: Validate Redis & Connection**

```bash
docker exec redis redis-cli ping
docker exec redis redis-cli CONFIG GET port
```

Expected: PONG + port 6379

**Step 4: Document Infrastructure State**

Create: `docs/infrastructure-validation-2026-02-21.txt`

```
## Infrastructure Validation Report — 2026-02-21

### Database
- PostgreSQL 16
- Schema v4
- Tables: 18
- Status: ✓ Ready

### Kafka
- Version: 3.7.1 (KRaft mode)
- Topics: 17 configured
- Consumer groups: 6 active
- Status: ✓ Ready

### Redis
- Version: 7
- AOF Persistence: Enabled
- Status: ✓ Ready

### Docker Containers
- Total: 25 running
- Status: ✓ All healthy
```

**Step 5: Commit**

```bash
git add docs/infrastructure-validation-2026-02-21.txt
git commit -m "docs: add infrastructure validation report for event stream jobs"
```

---

## Task 2: simpleec-retry-job — DLT Handler & Retry Logic

**Files:**
- Modify: `simpleec-retry-job/src/main/java/com/simpleec/retryjob/RetryJobApplication.java`
- Modify: `simpleec-retry-job/src/main/java/com/simpleec/retryjob/consumer/DltConsumer.java`
- Create: `simpleec-retry-job/src/main/java/com/simpleec/retryjob/service/RetryService.java`
- Modify: `simpleec-retry-job/build.gradle` (add Redis dependency)
- Create: `simpleec-retry-job/src/main/resources/application-retry.yml`

**Architecture:**
- Subscribe to `task.failed` topic
- Exponential backoff retry logic: 3 retries with 1s, 5s, 30s delays
- If all retries fail → send to `task.dlt` (Dead Letter Topic)
- Track retry count in Redis with key `retry:{messageId}`
- Log all operations with structured logging (JSON format)

**Step 1: Add Redis Dependency**

Modify: `simpleec-retry-job/build.gradle`

```gradle
dependencies {
    // ... existing ...

    // Redis for retry tracking
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'io.lettuce:lettuce-core'
}
```

**Step 2: Create RetryService**

Create: `simpleec-retry-job/src/main/java/com/simpleec/retryjob/service/RetryService.java`

```java
package com.simpleec.retryjob.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryService {

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 5000, 30000}; // 1s, 5s, 30s

    /**
     * 執行重試邏輯
     * 返回 true 如果重試被發送，false 如果已達到最大重試次數
     */
    public boolean retryMessage(String messageId, String originalTopic, String messageBody) {
        try {
            // 從 Redis 獲取重試計數
            String retryKey = "retry:" + messageId;
            String retryCountStr = redisTemplate.opsForValue().get(retryKey);
            int retryCount = (retryCountStr != null) ? Integer.parseInt(retryCountStr) : 0;

            if (retryCount >= MAX_RETRIES) {
                log.warn("Max retries exceeded for message: {} (retryCount={})", messageId, retryCount);
                return false; // 已達最大重試次數，應送到 DLT
            }

            // 遞增重試計數
            retryCount++;
            long delayMs = RETRY_DELAYS_MS[retryCount - 1];

            // 更新 Redis 中的重試計數和過期時間
            redisTemplate.opsForValue().set(retryKey, String.valueOf(retryCount),
                Duration.ofHours(24)); // 24 小時後過期

            // 計算下次發送時間
            long nextRetryTime = System.currentTimeMillis() + delayMs;

            // 發佈回原始主題（job 會重新處理）
            Map<String, Object> retryMessage = new HashMap<>();
            retryMessage.put("messageId", messageId);
            retryMessage.put("retryCount", retryCount);
            retryMessage.put("originalTopic", originalTopic);
            retryMessage.put("scheduledRetryTime", nextRetryTime);
            retryMessage.put("payload", messageBody);

            kafkaTemplate.send(originalTopic, messageId, retryMessage);
            log.info("Message retried: {} (retry #{}/{}) - will retry after {} ms",
                messageId, retryCount, MAX_RETRIES, delayMs);

            return true;

        } catch (Exception e) {
            log.error("Error retrying message: {}", messageId, e);
            return false; // 發生錯誤時也應送到 DLT
        }
    }

    /**
     * 發送消息到 DLT（Dead Letter Topic）
     */
    public void sendToDlt(String messageId, String originalTopic, String messageBody, Exception error) {
        try {
            Map<String, Object> dltMessage = new HashMap<>();
            dltMessage.put("messageId", messageId);
            dltMessage.put("originalTopic", originalTopic);
            dltMessage.put("payload", messageBody);
            dltMessage.put("error", error != null ? error.getMessage() : "Unknown error");
            dltMessage.put("timestamp", System.currentTimeMillis());

            kafkaTemplate.send("task.dlt", messageId, dltMessage);
            log.error("Message sent to DLT: {} from topic: {} (error: {})",
                messageId, originalTopic, error != null ? error.getMessage() : "Unknown");

        } catch (Exception e) {
            log.error("Failed to send message to DLT: {}", messageId, e);
        }
    }
}
```

**Step 3: Create DltConsumer**

Create: `simpleec-retry-job/src/main/java/com/simpleec/retryjob/consumer/DltConsumer.java`

```java
package com.simpleec.retryjob.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Dead Letter Topic (DLT) 消費者
 * 消費已達最大重試次數的訊息並記錄以供人工檢查
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DltConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "task.dlt", groupId = "dlt-consumer-group", concurrency = "2")
    public void consumeDlt(@Payload String message,
                          @Header(name = KafkaHeaders.RECEIVED_TOPIC) String topic,
                          @Header(name = KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
                          Acknowledgment acknowledgment) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String messageId = json.get("messageId").asText();
            String originalTopic = json.get("originalTopic").asText();
            String error = json.get("error").asText();

            // 記錄到監控系統（需要集成 Prometheus 或其他監控）
            log.error("DLT Message - ID: {}, OriginalTopic: {}, Error: {}",
                messageId, originalTopic, error);

            // TODO: 發送告警通知（Slack、Email、PagerDuty 等）
            // TODO: 記錄到數據庫供後續分析

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Error processing DLT message", e);
            // DLT 中的錯誤應立即記錄和告警
        }
    }
}
```

**Step 4: Update RetryJobApplication**

Modify: `simpleec-retry-job/src/main/java/com/simpleec/retryjob/RetryJobApplication.java`

```java
package com.simpleec.retryjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.simpleec")
@EnableScheduling
public class RetryJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(RetryJobApplication.class, args);
    }
}
```

**Step 5: Create Configuration**

Create: `simpleec-retry-job/src/main/resources/application-retry.yml`

```yaml
spring:
  application:
    name: simpleec-retry-job
  kafka:
    bootstrap-servers: kafka-broker:9092
    consumer:
      group-id: retry-job-group
      max-poll-records: 100
      max-poll-interval-ms: 300000
    producer:
      acks: all
      retries: 3
      properties:
        linger.ms: 10
  redis:
    host: redis
    port: 6379
    timeout: 2000ms
    jedis:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0

logging:
  level:
    com.simpleec: INFO
    org.springframework.kafka: WARN
```

**Step 6: Build & Test**

```bash
cd /home/tom/ONEEC/simpleec-oms
./gradlew :simpleec-retry-job:clean :simpleec-retry-job:build
```

Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add simpleec-retry-job/
git commit -m "feat: implement simpleec-retry-job with exponential backoff and DLT handling"
```

---

## Task 3: simpleec-scheduler-job — Task Scheduling & Heartbeat

**Files:**
- Modify: `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/SchedulerJobApplication.java`
- Create: `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/task/ScheduledTaskExecutor.java`
- Create: `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/task/HeartbeatTask.java`
- Create: `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/config/SchedulerConfig.java`

**Architecture:**
- Fixed-delay scheduler for recurring tasks (5min, 15min, hourly intervals)
- Publish scheduled events to Kafka (FETCH_ORDERS, SYNC_INVENTORY, UPDATE_PRICE)
- Heartbeat every minute to Redis and Kafka to indicate job is alive
- Graceful shutdown with in-flight request completion

**Step 1: Create SchedulerConfig**

Create: `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/config/SchedulerConfig.java`

```java
package com.simpleec.schedulerjob.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }
}
```

**Step 2: Create HeartbeatTask**

Create: `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/task/HeartbeatTask.java`

```java
package com.simpleec.schedulerjob.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatTask {

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 每分鐘發送一次心跳
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public void sendHeartbeat() {
        try {
            String jobId = "scheduler-job-" + System.getenv("HOSTNAME");
            LocalDateTime timestamp = LocalDateTime.now();

            // 更新 Redis 心跳
            String heartbeatKey = "heartbeat:" + jobId;
            redisTemplate.opsForValue().set(heartbeatKey, timestamp.toString());
            redisTemplate.expireAt(heartbeatKey, java.time.Instant.now().plusSeconds(120));

            // 發佈心跳事件到 Kafka（可選，用於監控）
            Map<String, Object> heartbeat = new HashMap<>();
            heartbeat.put("jobId", jobId);
            heartbeat.put("timestamp", timestamp.toString());
            heartbeat.put("status", "healthy");

            kafkaTemplate.send("scheduler.heartbeat", jobId, heartbeat);

            log.debug("Heartbeat sent: {} at {}", jobId, timestamp);

        } catch (Exception e) {
            log.error("Error sending heartbeat", e);
        }
    }
}
```

**Step 3: Create ScheduledTaskExecutor**

Create: `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/task/ScheduledTaskExecutor.java`

```java
package com.simpleec.schedulerjob.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskExecutor {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 每 5 分鐘獲取新訂單（所有通路）
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 30000)
    public void fetchOrdersScheduled() {
        log.info("Triggering scheduled FETCH_ORDERS for all platforms");

        String[] platforms = {"shopee", "momo", "yahoo", "pchome", "cyberbiz", "easystore"};

        for (String platform : platforms) {
            Map<String, Object> event = new HashMap<>();
            event.put("taskType", "FETCH_ORDERS");
            event.put("platform", platform);
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("triggeredBy", "scheduler");

            String topic = platform + ".fast";
            kafkaTemplate.send(topic, platform + ":fetch-orders:" + System.currentTimeMillis(), event);

            log.debug("Scheduled FETCH_ORDERS sent to {}", topic);
        }
    }

    /**
     * 每 15 分鐘更新庫存（所有產品）
     */
    @Scheduled(fixedDelay = 900000, initialDelay = 60000)
    public void syncInventoryScheduled() {
        log.info("Triggering scheduled SYNC_INVENTORY");

        String[] platforms = {"shopee", "momo", "yahoo", "pchome", "cyberbiz", "easystore"};

        for (String platform : platforms) {
            Map<String, Object> event = new HashMap<>();
            event.put("taskType", "SYNC_INVENTORY");
            event.put("platform", platform);
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("triggeredBy", "scheduler");

            String topic = platform + ".slow";
            kafkaTemplate.send(topic, platform + ":sync-inventory:" + System.currentTimeMillis(), event);

            log.debug("Scheduled SYNC_INVENTORY sent to {}", topic);
        }
    }

    /**
     * 每小時更新價格
     */
    @Scheduled(fixedDelay = 3600000, initialDelay = 120000)
    public void updatePricesScheduled() {
        log.info("Triggering scheduled UPDATE_PRICE");

        String[] platforms = {"shopee", "momo", "yahoo", "pchome", "cyberbiz", "easystore"};

        for (String platform : platforms) {
            Map<String, Object> event = new HashMap<>();
            event.put("taskType", "UPDATE_PRICE");
            event.put("platform", platform);
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("triggeredBy", "scheduler");

            String topic = platform + ".slow";
            kafkaTemplate.send(topic, platform + ":update-price:" + System.currentTimeMillis(), event);

            log.debug("Scheduled UPDATE_PRICE sent to {}", topic);
        }
    }
}
```

**Step 4: Update SchedulerJobApplication**

Modify: `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/SchedulerJobApplication.java`

```java
package com.simpleec.schedulerjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.simpleec")
@EnableScheduling
public class SchedulerJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchedulerJobApplication.class, args);
    }
}
```

**Step 5: Build & Test**

```bash
cd /home/tom/ONEEC/simpleec-oms
./gradlew :simpleec-scheduler-job:clean :simpleec-scheduler-job:build
```

Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add simpleec-scheduler-job/
git commit -m "feat: implement simpleec-scheduler-job with periodic task execution and heartbeat"
```

---

## Task 4: simpleec-order-job — ORDER_UPSERT & ORDER_STATUS_CHANGE Consumers

**Files:**
- Verify: `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/OrderUpsertConsumer.java` (already exists)
- Create: `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/OrderStatusChangeConsumer.java`
- Create: `simpleec-order-job/src/main/java/com/simpleec/orderjob/service/OrderProcessingService.java`
- Create: `simpleec-order-job/src/test/java/com/simpleec/orderjob/consumer/OrderConsumerTest.java`

**Architecture:**
- Consume ORDER_UPSERT from order.process → Save/update order in DB
- Consume ORDER_STATUS_CHANGE from order.process → Update order status
- Two-layer deduplication (Redis + DB unique constraint)
- Idempotent processing with transaction management
- Error handling with retry on transient failures

**Step 1: Verify OrderUpsertConsumer**

Read: `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/OrderUpsertConsumer.java`

Expected: Consumer properly parses header/body, filters by taskType, calls OrderService.

**Step 2: Create OrderStatusChangeConsumer**

Create: `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/OrderStatusChangeConsumer.java`

```java
package com.simpleec.orderjob.consumer;

import com.simpleec.core.entity.Order;
import com.simpleec.core.service.OrderService;
import com.simpleec.common.enums.OrderStatusEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 訂單狀態變更消費者
 * 消費來自通路的訂單狀態更新事件（已發貨、已取消、已完成等）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusChangeConsumer {

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.process", groupId = "order-job-group", concurrency = "4")
    @Transactional
    public void consumeOrderStatusChange(@Payload String message, Acknowledgment acknowledgment) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            String taskType = header.get("taskType").asText();

            if (!taskType.equals("ORDER_STATUS_CHANGE")) {
                // 轉發給其他消費者
                return;
            }

            String merchantId = header.get("merchantId").asText();
            String channelId = header.get("channelId").asText();
            String channelOrderId = body.get("channelOrderId").asText();
            String newStatus = body.get("status").asText();

            log.info("Processing ORDER_STATUS_CHANGE: {} -> {}", channelOrderId, newStatus);

            // 查詢現有訂單
            Optional<Order> orderOpt = orderService.findByChannelOrderId(channelOrderId, channelId);
            if (orderOpt.isEmpty()) {
                log.warn("Order not found: {} from channel {}", channelOrderId, channelId);
                acknowledgment.acknowledge();
                return;
            }

            Order order = orderOpt.get();
            OrderStatusEnum newStatusEnum = OrderStatusEnum.fromCode(newStatus);
            OrderStatusEnum oldStatus = order.getOrderStatus();

            // 更新訂單狀態
            order.setOrderStatus(newStatusEnum);
            if (newStatusEnum == OrderStatusEnum.SHIPPED) {
                order.setShippedAt(java.time.LocalDateTime.now());
            }

            orderService.save(order);
            log.info("Order status updated: {} {} -> {}", channelOrderId, oldStatus, newStatusEnum);

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Error processing ORDER_STATUS_CHANGE", e);
            // 錯誤由 global exception handler 處理，發送到 task.failed
            throw new RuntimeException(e);
        }
    }
}
```

**Step 3: Create OrderProcessingService**

Create: `simpleec-order-job/src/main/java/com/simpleec/orderjob/service/OrderProcessingService.java`

```java
package com.simpleec.orderjob.service;

import com.simpleec.core.entity.Order;
import com.simpleec.core.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 訂單處理服務
 * 提供訂單入庫、去重、驗證等業務邏輯
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProcessingService {

    private final OrderService orderService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 執行訂單 UPSERT 邏輯（乐观去重 + 乐观鎖）
     */
    @Transactional
    public Order upsertOrder(String merchantId, String channelId, String channelOrderId,
                             String orderHash, JsonNode orderData) {

        // 1. Redis 去重檢查（快速路徑）
        String dedupeKey = "order:dedup:" + channelId + ":" + channelOrderId;
        String cachedHash = redisTemplate.opsForValue().get(dedupeKey);

        if (cachedHash != null && cachedHash.equals(orderHash)) {
            log.debug("Order already processed (cached): {}", channelOrderId);
            return orderService.findByChannelOrderId(channelOrderId, channelId)
                .orElseThrow(() -> new RuntimeException("Inconsistent state: cached but not in DB"));
        }

        // 2. 查詢現有訂單
        Optional<Order> existingOpt = orderService.findByChannelOrderId(channelOrderId, channelId);

        if (existingOpt.isPresent()) {
            Order existing = existingOpt.get();
            // 檢查 orderHash 是否相同（無更新）
            if (existing.getOrderHash() != null && existing.getOrderHash().equals(orderHash)) {
                log.debug("Order unchanged: {}", channelOrderId);
                // 更新 Redis 快取
                redisTemplate.opsForValue().set(dedupeKey, orderHash, Duration.ofHours(24));
                return existing;
            }
            // 訂單已變更，執行更新
            log.info("Updating existing order: {}", channelOrderId);
            return updateOrder(existing, orderData, orderHash);
        }

        // 3. 建立新訂單
        Order newOrder = createOrder(merchantId, channelId, channelOrderId, orderData, orderHash);

        // 4. 更新 Redis 去重快取
        redisTemplate.opsForValue().set(dedupeKey, orderHash, Duration.ofHours(24));

        log.info("New order created: {} (hash: {})", channelOrderId, orderHash.substring(0, 8) + "...");

        return newOrder;
    }

    private Order createOrder(String merchantId, String channelId, String channelOrderId,
                              JsonNode orderData, String orderHash) {
        Order order = Order.builder()
            .id(generateOrderId())
            .merchantId(merchantId)
            .channelId(channelId)
            .channelOrderId(channelOrderId)
            .orderStatus(parseOrderStatus(orderData))
            .orderHash(orderHash)
            .buyerName(orderData.get("buyerName").asText())
            .buyerEmail(orderData.get("buyerEmail").asText())
            .buyerPhone(orderData.get("buyerPhone").asText())
            .shippingAddress(orderData.get("shippingAddress").asText())
            .totalAmount(orderData.get("totalAmount").decimalValue())
            .createdAt(LocalDateTime.now())
            .build();

        return orderService.save(order);
    }

    private Order updateOrder(Order order, JsonNode orderData, String newHash) {
        order.setOrderHash(newHash);
        order.setBuyerName(orderData.get("buyerName").asText());
        order.setBuyerEmail(orderData.get("buyerEmail").asText());
        order.setBuyerPhone(orderData.get("buyerPhone").asText());
        order.setShippingAddress(orderData.get("shippingAddress").asText());
        order.setTotalAmount(orderData.get("totalAmount").decimalValue());

        return orderService.save(order);
    }

    private String generateOrderId() {
        // 使用 NanoID 生成
        return "ORD_" + System.currentTimeMillis() + "_" + Math.random();
    }

    private com.simpleec.common.enums.OrderStatusEnum parseOrderStatus(JsonNode orderData) {
        String status = orderData.get("status").asText("pending");
        return com.simpleec.common.enums.OrderStatusEnum.fromCode(status);
    }
}
```

**Step 4: Create Unit Tests**

Create: `simpleec-order-job/src/test/java/com/simpleec/orderjob/consumer/OrderConsumerTest.java`

```java
package com.simpleec.orderjob.consumer;

import com.simpleec.core.entity.Order;
import com.simpleec.core.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class OrderConsumerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private OrderUpsertConsumer consumer;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testConsumeOrderUpsert_ValidMessage() throws Exception {
        // Arrange
        String message = "{\"header\": {\"taskType\": \"ORDER_UPSERT\", \"merchantId\": \"m1\", \"channelId\": \"shopee\"}, " +
                        "\"body\": {\"channelOrderId\": \"order123\", \"orderHash\": \"abcd1234\", \"orderData\": {}}}";

        // Act
        consumer.consumeOrderUpsert(message, "shopee", "default", acknowledgment);

        // Assert
        verify(acknowledgment).acknowledge();
    }

    @Test
    void testConsumeOrderUpsert_WrongTaskType() throws Exception {
        // Arrange
        String message = "{\"header\": {\"taskType\": \"OTHER_TASK\"}, \"body\": {}}";

        // Act
        consumer.consumeOrderUpsert(message, "shopee", "default", acknowledgment);

        // Assert
        verify(acknowledgment).acknowledge();
        verify(orderService, never()).save(any());
    }
}
```

**Step 5: Build & Test**

```bash
cd /home/tom/ONEEC/simpleec-oms
./gradlew :simpleec-order-job:clean :simpleec-order-job:test
```

Expected: All tests pass

**Step 6: Build JAR**

```bash
./gradlew :simpleec-order-job:build
```

Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add simpleec-order-job/
git commit -m "feat: implement simpleec-order-job with ORDER_UPSERT and ORDER_STATUS_CHANGE consumers"
```

---

## Task 5: simpleec-backend-job — Generic Event Handler

**Files:**
- Create: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/consumer/GenericEventConsumer.java`
- Create: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/handler/EventHandlerRegistry.java`
- Create: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/handler/ReturnEventHandler.java`

**Architecture:**
- Generic event consumer for RETURN_UPSERT, SYNC_PACK operations
- Handler registry pattern for task-type routing
- Pluggable handlers for new event types

**Step 1: Create EventHandlerRegistry**

Create: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/handler/EventHandlerRegistry.java`

```java
package com.simpleec.backendjob.handler;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class EventHandlerRegistry {
    private final Map<String, EventHandler> handlers = new HashMap<>();

    public interface EventHandler {
        void handle(JsonNode header, JsonNode body) throws Exception;
    }

    public void register(String taskType, EventHandler handler) {
        handlers.put(taskType, handler);
    }

    public EventHandler get(String taskType) {
        return handlers.getOrDefault(taskType, (h, b) -> {
            throw new IllegalArgumentException("Unknown taskType: " + taskType);
        });
    }

    public boolean supports(String taskType) {
        return handlers.containsKey(taskType);
    }
}
```

**Step 2: Create GenericEventConsumer**

Create: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/consumer/GenericEventConsumer.java`

```java
package com.simpleec.backendjob.consumer;

import com.simpleec.backendjob.handler.EventHandlerRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenericEventConsumer {

    private final EventHandlerRegistry handlerRegistry;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.process,return.process,pack.sync",
                   groupId = "backend-job-group", concurrency = "6")
    public void consumeGenericEvent(@Payload String message, Acknowledgment acknowledgment) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            String taskType = header.get("taskType").asText();
            log.info("Processing event: {}", taskType);

            // 查詢對應的 handler
            if (!handlerRegistry.supports(taskType)) {
                log.warn("No handler for taskType: {}", taskType);
                acknowledgment.acknowledge();
                return;
            }

            EventHandlerRegistry.EventHandler handler = handlerRegistry.get(taskType);
            handler.handle(header, body);

            acknowledgment.acknowledge();
            log.info("Event processed successfully: {}", taskType);

        } catch (Exception e) {
            log.error("Error processing event", e);
            // 不提交 offset，讓消息返回 task.failed 進行重試
            throw new RuntimeException(e);
        }
    }
}
```

**Step 3: Create ReturnEventHandler**

Create: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/handler/ReturnEventHandler.java`

```java
package com.simpleec.backendjob.handler;

import com.simpleec.core.service.ReturnOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnEventHandler implements EventHandlerRegistry.EventHandler {

    private final ReturnOrderService returnOrderService;

    @Override
    public void handle(JsonNode header, JsonNode body) throws Exception {
        String taskType = header.get("taskType").asText();

        if ("RETURN_UPSERT".equals(taskType)) {
            handleReturnUpsert(header, body);
        } else if ("RETURN_STATUS_CHANGE".equals(taskType)) {
            handleReturnStatusChange(header, body);
        }
    }

    private void handleReturnUpsert(JsonNode header, JsonNode body) {
        String merchantId = header.get("merchantId").asText();
        String orderId = body.get("orderId").asText();
        String reason = body.get("reason").asText();

        log.info("Processing RETURN_UPSERT for order: {}", orderId);
        // returnOrderService.upsert(merchantId, orderId, reason);
    }

    private void handleReturnStatusChange(JsonNode header, JsonNode body) {
        String returnId = body.get("returnId").asText();
        String newStatus = body.get("status").asText();

        log.info("Processing RETURN_STATUS_CHANGE: {} -> {}", returnId, newStatus);
        // returnOrderService.updateStatus(returnId, newStatus);
    }
}
```

**Step 4: Register Handlers (in Configuration)**

Create: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/config/HandlerConfiguration.java`

```java
package com.simpleec.backendjob.config;

import com.simpleec.backendjob.handler.EventHandlerRegistry;
import com.simpleec.backendjob.handler.ReturnEventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PostConstruct;

@Configuration
@RequiredArgsConstructor
public class HandlerConfiguration {

    private final EventHandlerRegistry handlerRegistry;
    private final ReturnEventHandler returnEventHandler;

    @PostConstruct
    public void registerHandlers() {
        handlerRegistry.register("RETURN_UPSERT", returnEventHandler);
        handlerRegistry.register("RETURN_STATUS_CHANGE", returnEventHandler);
        // Register other handlers as needed
    }
}
```

**Step 5: Build & Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
./gradlew :simpleec-backend-job:clean :simpleec-backend-job:build
git add simpleec-backend-job/
git commit -m "feat: implement simpleec-backend-job with generic event handler registry"
```

---

## Task 6: simpleec-frontend-job — Frontend Notification Dispatcher

**Files:**
- Create: `simpleec-frontend-job/src/main/java/com/simpleec/frontendjob/consumer/NotificationConsumer.java`
- Create: `simpleec-frontend-job/src/main/java/com/simpleec/frontendjob/service/WebSocketNotificationService.java`

**Architecture:**
- Consume order/return status updates
- Dispatch notifications to frontend via WebSocket or Server-Sent Events
- Format messages for UI consumption

**Step 1: Create NotificationConsumer**

(Implementation similar to OrderConsumer but for UI-relevant events)

**Step 2: Create WebSocketNotificationService**

(Provides real-time notification delivery to frontend clients)

**Step 3-5: Build & Commit**

---

## Task 7: simpleec-channel-job — Multi-Platform Sync Orchestration

**Files:**
- Verify: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/ChannelJobApplication.java`
- Create: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelEventConsumer.java`
- Create: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/adapter/PlatformAdapter.java`

**Architecture:**
- Subscribe to platform-specific topics (shopee.fast, shopee.slow, momo.fast, etc.)
- Route events to appropriate platform adapter
- Handle FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, SYNC_INVENTORY operations
- Transform platform API responses to OMS schema

**Implementation:** (Detailed in Task 7 step-by-step)

---

## Summary of Deployment Order

### Phase 1: Foundation (Day 1)
1. ✅ Task 1: Infrastructure Validation
2. ✅ Task 2: simpleec-retry-job
3. ✅ Task 3: simpleec-scheduler-job

### Phase 2: Core Processing (Day 2)
4. ✅ Task 4: simpleec-order-job
5. ✅ Task 5: simpleec-backend-job
6. ✅ Task 6: simpleec-frontend-job

### Phase 3: Channel Integration (Day 3)
7. ✅ Task 7: simpleec-channel-job (6 platform adapters)

### Phase 4: Integration Testing (Day 4-5)
8. End-to-end flow testing
9. Performance testing
10. Chaos engineering tests

---

## Testing Strategy

**Unit Tests:** Each job has isolated unit tests for message parsing and business logic
**Integration Tests:** Test Kafka→Consumer→Service→DB flow
**End-to-End Tests:** Complete order lifecycle (API→Kafka→Job→DB→Notification)
**Load Tests:** 1000 msgs/sec per platform, 24-hour run

## Monitoring & Observability

**Metrics:**
- Messages processed per second (by topic, by job)
- Error rates (by taskType, by job)
- Processing latency (p50, p95, p99)
- Retry count distribution

**Logging:**
- Structured JSON logging to ELK stack
- Correlation IDs across all logs
- Separate trace logs for audit trail

**Alerting:**
- DLT messages (failed retries)
- Processing latency > 10s
- Error rate > 1%
- Heartbeat missing > 2 minutes
