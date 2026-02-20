# SimpleEC OMS 模組設計

## 11 個 Gradle 模組架構

```
simpleec-oms/
├── simpleec-common              [Layer 1] 基礎層
├── simpleec-core                [Layer 1] 基礎層
├── simpleec-channel             [Layer 2] 介面適配層
├── simpleec-api                 [Layer 2] 介面適配層
├── simpleec-gateway             [Layer 2] 介面適配層
├── simpleec-channel-job         [Layer 3] 工作層
├── simpleec-order-job           [Layer 3] 工作層
├── simpleec-scheduler-job       [Layer 3] 工作層
├── simpleec-backend-job         [Layer 3] 工作層
├── simpleec-frontend-job        [Layer 3] 工作層
└── simpleec-retry-job           [Layer 3] 工作層
```

---

## Layer 1：基礎層

### simpleec-common
**職責**：常數、DTO、工具類
**依賴**：無
**關鍵類**：
```
com.simpleec.common.
  ├── enums/
  │   ├── PlatformEnum (Momo, Shopee, Yahoo, PChome, Cyberbiz)
  │   ├── ModeEnum (A, B)
  │   ├── TaskTypeEnum (FETCH_ORDERS, FETCH_ORDER_DETAIL, PROCESS_ORDER, ...)
  │   ├── OrderStatusEnum (PENDING, CONFIRMED, SHIPPED, ...)
  │   └── ReturnStatusEnum (REQUESTED, APPROVED, SHIPPED, ...)
  ├── dto/
  │   ├── OrderDTO
  │   ├── OrderItemDTO
  │   ├── ReturnDTO
  │   ├── ProductDTO
  │   ├── PlatformMappingDTO
  │   └── KafkaMessageDTO
  ├── util/
  │   ├── AESUtil (加密/解密)
  │   ├── DateUtil (iDempiere 日期格式轉換)
  │   ├── RedisKeyUtil (Redis key 生成)
  │   ├── NanoIdUtil (ID 生成)
  │   └── JsonUtil (序列化/反序列化)
  └── constants/
      ├── TopicConstants (16 個 topic 名稱)
      ├── RedisKeyConstants (Layer1/Layer2 鍵前綴)
      └── ErrorConstants (錯誤代碼)
```

### simpleec-core
**職責**：Entity、Mapper、Service、Kafka 基礎設定
**依賴**：simpleec-common
**關鍵類**：
```
com.simpleec.core.
  ├── entity/
  │   ├── Order (訂單主表)
  │   ├── OrderItem (訂單行項目)
  │   ├── Return (退貨單)
  │   ├── ReturnItem (退貨行項目)
  │   ├── Product (商品)
  │   ├── SKU (商品 SKU)
  │   ├── Platform (通路設定)
  │   ├── PlatformMapping (欄位映射規則)
  │   ├── Shipment (出貨紀錄)
  │   ├── DailyStatistics (日統計，分區)
  │   └── ErrorLog (錯誤日誌)
  ├── mapper/
  │   ├── OrderMapper (MyBatis-Plus)
  │   ├── OrderItemMapper
  │   ├── ReturnMapper
  │   ├── ProductMapper
  │   ├── PlatformMapper
  │   └── ShipmentMapper
  ├── service/
  │   ├── OrderService (CRUD + 業務邏輯)
  │   ├── ProductService
  │   ├── PlatformService
  │   ├── InventoryService (庫存快照)
  │   └── StatisticsService (報表)
  ├── kafka/
  │   ├── KafkaConsumerConfig (9 個 consumer bean)
  │   ├── KafkaProducerConfig (producer template)
  │   ├── KafkaTopicConfig (16 個 topic bean)
  │   └── SerdeConfig (JSON 序列化)
  └── redis/
      ├── RedisConfig (連線池、序列化)
      └── RedisTemplateFactory
```

**資料庫表設計**：
```sql
-- 16 張表（訂單為完整業務單位）
-- 核心表（2）: Orders (items JSONB), Returns (items JSONB)
-- 商業表（8）: Products, SKUs, Platforms, PlatformMappings,
--             Categories, Shipments, DailyStatistics, WarehouseQueues
-- 稽核表（2）: AuditLogs, DltMessages
-- 設定表（4）: PlatformCredentials, SyncRules, JobConfigs, FeatureFlags

-- 設計原則：
-- • PK: NanoID (VARCHAR(20)) — 自動生成、有序、分散式安全
-- • Items: JSONB 儲存（訂單/退貨作為完整單位）
-- • PII: AES-256-GCM 加密（客戶名、電話、地址、電郵）
-- • 一個訂單 = 一筆 INSERT/UPDATE（保證 ACID）
```

---

## Layer 2：介面適配層

### simpleec-channel
**職責**：ChannelAdapter 介面 + 各平台實現
**依賴**：simpleec-core
**關鍵類**：
```
com.simpleec.channel.
  ├── adapter/
  │   ├── ChannelAdapter (介面)
  │   │   ├── fetchOrders(...): List<OrderDTO>
  │   │   ├── fetchOrderDetail(orderId): OrderDTO
  │   │   ├── fetchProducts(...): List<ProductDTO>
  │   │   ├── fetchInventory(...): Map<SKU, qty>
  │   │   ├── shipOrder(shipmentId): boolean
  │   │   └── cancelOrder(orderId): boolean
  │   │
  │   ├── impl/
  │   │   ├── MomoAdapter implements ChannelAdapter
  │   │   ├── ShopeeAdapter implements ChannelAdapter
  │   │   ├── YahooAdapter implements ChannelAdapter
  │   │   ├── PChomeAdapter implements ChannelAdapter
  │   │   ├── CyberbizAdapter implements ChannelAdapter
  │   │   └── ShopifyAdapter implements ChannelAdapter
  │   │
  │   └── AdapterFactory
  │       └── getAdapter(platform): ChannelAdapter
  │
  ├── mapping/
  │   ├── PlatformFieldMapper (欄位映射規則執行)
  │   └── CurrencyConverter (金額單位轉換)
  │
  ├── client/
  │   ├── MomoHttpClient
  │   ├── ShopeeHttpClient
  │   └── (各平台 REST client)
  │
  └── exception/
      ├── PlatformException
      ├── ApiQuotaExceededException
      └── ChannelAuthException
```

**Mode A/B 邏輯**：
```java
// ChannelAdapter.fetchOrders()
if (platform.getMode() == Mode.A) {
  // 直接返回完整訂單
  return callApi("orders", filters);
} else {
  // 只返回訂單列表（需後續 detail fetch）
  return callApi("orders", filters);
  // Channel Job Slow 會根據此結果決定是否呼叫 fetchOrderDetail
}
```

### simpleec-api
**職責**：REST API 端點
**依賴**：simpleec-core
**關鍵 REST 端點**：
```
GET /orders                      查詢訂單
GET /orders/{id}                詳情
POST /orders                    建立訂單（通常不用，由 Kafka 驅動）
GET /orders/{id}/shipments      查詢出貨單
POST /orders/{id}/ship          手動觸發出貨（發到 Kafka）

GET /returns                    查詢退貨單
POST /orders/{id}/return        建立退貨

GET /products                  查詢商品
GET /products/{id}/inventory   查詢庫存快照

GET /statistics/daily          日報表

POST /sync/pack                手動觸發打包同步 (→ task.backend)
POST /sync/product             手動觸發商品同步 (→ task.backend)
```

### simpleec-gateway
**職責**：Webhook 入口（各通路推送消息）
**依賴**：simpleec-core + simpleec-channel
**關鍵類**：
```
com.simpleec.gateway.
  ├── webhook/
  │   ├── MomoWebhookController
  │   │   └── POST /webhook/momo/order  (HMAC 驗證)
  │   │
  │   ├── ShopeeWebhookController
  │   │   └── POST /webhook/shopee/order
  │   │
  │   └── (各平台 Webhook handler)
  │
  ├── security/
  │   ├── HmacVerifier
  │   └── WebhookSignatureValidator
  │
  └── converter/
      └── WebhookToKafkaMessageConverter
          └── 將 webhook payload → Kafka message
```

**Webhook 流程**：
```
通路推送 → Gateway Webhook Controller → 驗證簽名
  → 轉換成標準 KafkaMessage → 發到 {platform}.slow/fast
  → 返回 200 OK（立即）
  → 後續由 Channel Job 消費
```

---

## Layer 3：工作層

### simpleec-channel-job
**職責**：通路同步 Job（Channel Job Fast / Slow）
**依賴**：simpleec-channel
**關鍵類**：
```
com.simpleec.channel.job.
  ├── consumer/
  │   ├── ChannelJobFastConsumer implements KafkaListener
  │   │   └── consume({platform}.fast) → SYNC_PRODUCT / SYNC_PACK
  │   │
  │   └── ChannelJobSlowConsumer implements KafkaListener
  │       └── consume({platform}.slow) → FETCH_ORDERS / FETCH_ORDER_DETAIL
  │
  ├── handler/
  │   ├── FastSyncHandler (商品、庫存、配運)
  │   │   ├── syncProducts(...)
  │   │   ├── syncInventory(...)
  │   │   └─ produce → task.backend
  │   │
  │   ├── SlowSyncHandler (訂單、退貨)
  │   │   ├── fetchOrders(...) [Mode A/B]
  │   │   ├── fetchOrderDetail(...) [Mode B only]
  │   │   └─ produce → order.process / return.process
  │   │
  │   └── RedisDeduplicationHandler (Layer 1 去重)
  │       ├── checkExists(orderId): boolean
  │       └─ setProcessed(orderId)
  │
  └── task/
      ├── ChannelJobFastTask
      └─ ChannelJobSlowTask
```

**Mode A/B 邏輯**：
```java
// ChannelJobSlowConsumer
@KafkaListener(topics = "{platform}.slow")
public void consume(KafkaMessage msg) {
  Platform platform = platformService.getByCode(msg.getPlatform());
  
  if (platform.getMode() == Mode.A) {
    // Mode A: 直接 FETCH_ORDERS
    orders = channelAdapter.fetchOrders(msg.getFilters());
    sendToOrderProcess(orders);
  } else {
    // Mode B: 分兩步
    orders = channelAdapter.fetchOrders(msg.getFilters());
    for (Order order : orders) {
      detail = channelAdapter.fetchOrderDetail(order.getId());
      orders[i] = merge(order, detail);
    }
    sendToOrderProcess(orders);
  }
}
```

### simpleec-order-job
**職責**：訂單入庫（OrderUpsertHandler）
**依賴**：simpleec-core
**關鍵類**：
```
com.simpleec.order.job.
  ├── consumer/
  │   ├── OrderUpsertConsumer implements KafkaListener
  │   │   └── consume(order.process)
  │   │
  │   └── ReturnUpsertConsumer implements KafkaListener
  │       └── consume(return.process)
  │
  ├── handler/
  │   ├── OrderUpsertHandler
  │   │   ├── checkRedisLock(...) [Layer 2]
  │   │   ├── upsertOrder(orderDTO)
  │   │   ├── updateInventory(...)
  │   │   ├── updateRedisCache(...) [Layer 2]
  │   │   └─ produce → task.backend
  │   │
  │   └── ReturnUpsertHandler
  │       ├── checkRedisLock(...)
  │       ├── upsertReturn(returnDTO)
  │       └─ produce → task.backend
  │
  └── lock/
      ├── DistributedLockService
      │   ├── acquire(key, ttl): Lock
      │   └─ release(lock)
      │
      └── RedisLockImpl
```

**Redis 兩層去重邏輯**：
```java
// Layer 1（Channel Job）
@KafkaListener(topics = "{platform}.slow")
public void consume(KafkaMessage msg) {
  String orderId = msg.getOrderId();
  if (redisCache.exists("processed:" + orderId)) {
    log.info("Skip duplicate: {}", orderId);
    return; // 秒速丟棄，不發到 order.process
  }
  // 發到 order.process
  send(topic("order.process"), msg);
}

// Layer 2（OrderUpsertHandler）
@KafkaListener(topics = "order.process")
public void handle(KafkaMessage msg) {
  String orderId = msg.getOrderId();
  Lock lock = redisLock.acquire(orderId, Duration.ofSeconds(30));
  try {
    // DB INSERT/UPDATE
    orderService.upsert(msg);
    redisCache.set("processed:" + orderId, nowAt(), Duration.ofDays(7));
  } finally {
    lock.release();
  }
}
```

### simpleec-scheduler-job
**職責**：時間源（HeartbeatJob + SchedulerConsumer）
**依賴**：simpleec-core
**關鍵類**：
```
com.simpleec.scheduler.job.
  ├── job/
  │   └── HeartbeatJob
  │       ├── @Scheduled(fixedRate = 1000)
  │       └─ produce → scheduler (timestamp only)
  │
  ├── consumer/
  │   └── SchedulerConsumer implements KafkaListener
  │       └── consume(scheduler)
  │
  ├── handler/
  │   └── SchedulerHandler
  │       ├── onHeartbeat(timestamp)
  │       ├── decideTasksToRun(timestamp)
  │       └─ produce → {platform}.slow / task.backend
  │
  └── logic/
      └── ScheduleDecisionLogic
          ├── minute % 5 == 0? → FETCH_ORDERS
          ├── minute % 15 == 0? → SYNC_PRODUCT
          └─ minute % 30 == 0? → GENERATE_REPORT
```

**時間驅動邏輯**：
```java
// HeartbeatJob
@Scheduled(fixedRate = 1000) // 每秒
public void beat() {
  kafkaTemplate.send("scheduler", new HeartbeatMessage(System.currentTimeMillis()));
}

// SchedulerConsumer
@KafkaListener(topics = "scheduler")
public void consume(HeartbeatMessage msg) {
  LocalDateTime time = msg.getTimestamp();
  int minute = time.getMinute();
  
  if (minute % 5 == 0) {
    // 觸發 FETCH_ORDERS
    for (Platform platform : platforms) {
      send("momo.slow", new FetchOrdersTask(...));
      send("shopee.slow", new FetchOrdersTask(...));
      // ...
    }
  }
  
  if (minute % 15 == 0) {
    // 觸發 SYNC_PRODUCT
    for (Platform platform : platforms) {
      send("momo.fast", new SyncProductTask(...));
      // ...
    }
  }
  
  // 定時報表
  if (minute == 0 && time.getHour() == 0) {
    send("task.backend", new GenerateReportTask(...));
  }
}
```

### simpleec-backend-job
**職責**：後端非同步任務（GENERATE_SHIPMENT, UPDATE_INVENTORY, SEND_TO_WAREHOUSE）
**依賴**：simpleec-core + simpleec-channel
**關鍵類**：
```
com.simpleec.backend.job.
  ├── consumer/
  │   └── BackendTaskConsumer implements KafkaListener
  │       └── consume(task.backend)
  │
  ├── handler/
  │   ├── GenerateShipmentHandler
  │   │   └─ 為訂單生成出貨單
  │   │
  │   ├── UpdateInventoryHandler
  │   │   └─ 更新庫存快照
  │   │
  │   ├── SendToWarehouseHandler
  │   │   └─ 通知倉庫系統
  │   │
  │   ├── GenerateReportHandler
  │   │   └─ 生成日報表
  │   │
  │   └── HealthCheckHandler
  │       └─ 檢查通路連線狀態
  │
  └── service/
      ├── ShipmentService
      ├── WarehouseIntegrationService
      └─ ReportGenerationService
```

### simpleec-frontend-job
**職責**：UI 事件驅動任務（暫未實現，預留）
**依賴**：simpleec-core
**關鍵類**：
```
com.simpleec.frontend.job.
  ├── consumer/
  │   └── FrontendEventConsumer (預留)
  │
  └── handler/
      ├── OrderConfirmationHandler (訂單確認通知)
      └─ ShipmentTrackingHandler (出貨追蹤通知)
```

### simpleec-retry-job
**職責**：重試與死信（ErrorHandler + DltHandler）
**依賴**：simpleec-core
**關鍵類**：
```
com.simpleec.retry.job.
  ├── consumer/
  │   ├── ErrorConsumer implements KafkaListener
  │   │   └── consume(task.failed)
  │   │
  │   └── DltConsumer implements KafkaListener
  │       └── consume(task.dlt)
  │
  ├── handler/
  │   ├── ErrorHandler
  │   │   ├── parseErrorMessage(msg)
  │   │   ├── decideRetry(retryCount, exception)
  │   │   ├── calculateBackoffTime(...) [指數退避]
  │   │   └─ produce → {原topic} or task.dlt
  │   │
  │   └── DltHandler
  │       ├── storeToDb(dltMessage) [dlt_messages]
  │       ├── sendAlert(...)
  │       └─ log for manual inspection
  │
  └── service/
      ├── ErrorAnalysisService
      └─ DltStorageService
```

**重試邏輯**：
```java
// ErrorHandler
@KafkaListener(topics = "task.failed")
public void handle(ErrorMessage msg) {
  int retryCount = msg.getRetryCount();
  
  if (retryCount <= 3) {
    // 重試
    long backoffMs = calculateBackoff(retryCount); // 2^n exponential
    schedule(() -> {
      msg.setRetryCount(retryCount + 1);
      send(msg.getOriginalTopic(), msg); // 重新發到原 topic
    }, backoffMs);
  } else {
    // 進死信隊列
    send("task.dlt", msg);
    alertService.sendAlert("Message dead-lettered: " + msg.getId());
  }
}
```

---

## 模組依賴關係圖

```
simpleec-common (無依賴)
    ↑
    ├─ simpleec-core
    │   ├─ simpleec-channel
    │   │   └─ simpleec-channel-job
    │   │
    │   ├─ simpleec-api
    │   ├─ simpleec-gateway
    │   │   └─ simpleec-channel (API 適配)
    │   │
    │   ├─ simpleec-order-job
    │   ├─ simpleec-scheduler-job
    │   ├─ simpleec-backend-job
    │   │   └─ simpleec-channel (適配各平台)
    │   ├─ simpleec-frontend-job
    │   └─ simpleec-retry-job

依賴層次：
  Layer 1: common, core (獨立)
  Layer 2: channel, api, gateway (依賴 core)
  Layer 3: channel-job, order-job, scheduler-job, backend-job, frontend-job, retry-job (依賴 core ± channel)
```

---

**上次更新**：2026-02-20
**版本**：1.0 - PLAN Phase I Release
