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
      ├── TopicConstants (17 個 topic 名稱：10 channel + 7 business)
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
  │   ├── PlatformAccount (SimpleEC 平台管理員)
  │   ├── GlobalConfig (全域系統設定)
  │   ├── Merchant (商家/租戶)
  │   ├── Account (商家操作帳號)
  │   ├── MerchantOptions (商家自訂選項)
  │   ├── ProductGroup (商品群組)
  │   ├── Product (商品 = SKU 級別，倉庫單位)
  │   ├── ProductBarcode (產品條碼)
  │   ├── SellPack (通路上架映射：product × channel)
  │   ├── Platform (電商平台設定)
  │   ├── ChannelApiVersion (平台 API 版本管理)
  │   ├── Channel (通路/館：樞紐)
  │   ├── Order (訂單，items JSONB)
  │   ├── OrderStatusLog (訂單狀態變更記錄)
  │   ├── OrderShipment (出貨物流追蹤)
  │   ├── RefundOrder (退款單，items JSONB，獨立表)
  │   ├── ChannelSyncLog (同步/健康檢查記錄)
  │   ├── FailedTaskLog (Kafka 失敗任務 LOG)
  │   └── DailyStatistics (日統計，按月分區)
  ├── mapper/
  │   ├── MerchantMapper (MyBatis-Plus)
  │   ├── ProductMapper
  │   ├── SellPackMapper
  │   ├── OrderMapper
  │   ├── OrderStatusLogMapper
  │   ├── OrderShipmentMapper
  │   ├── RefundOrderMapper
  │   ├── ChannelMapper
  │   ├── PlatformMapper
  │   └── DailyStatisticsMapper
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
-- 19 張表，多租戶隔離、Kafka 驅動
-- 平台管理（2）: platform_account, global_config
-- 商家體系（3）: merchant, account, merchant_options
-- 商品管理（4）: product_group, product, product_barcode, sell_pack
-- 通路管理（3）: platform, channel_api_versions, channel
-- 訂單表（3）: orders (items JSONB), order_status_logs, order_shipments
-- 退款表（1）: refund_orders (items JSONB，獨立表非嵌入)
-- 同步監控（2）: channel_sync_logs, failed_task_logs
-- 統計表（1）: daily_statistics (按月分區)

-- 關鍵設計原則：
-- • PK: NanoID (VARCHAR(20)) — 自動生成、有序、分散式安全
-- • 多租戶隔離: 所有業務表都有 merchant_id FK
-- • sell_pack: 產品在特定通路的上架映射（product × channel），不是物理打包
-- • refund_orders: 獨立表（非 return_items in orders），items 為 JSONB
-- • orders.items: 訂單項目用 JSONB，每個訂單一筆記錄
-- • PII: AES-256-GCM 加密（客戶名、電話、地址、電郵）
-- • 一個訂單 = 一筆 INSERT/UPDATE（保證 ACID）
-- • token 1~5: channel 表支援多個認證 token，適配複雜認證機制
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

**Mode A/B 判定（在 Adapter 層面）**：
```java
// ShopifyAdapter implements ChannelAdapter
public class ShopifyAdapter implements ChannelAdapter {
  @Override
  public List<OrderDTO> fetchOrders(FetchOrdersFilter filters) {
    // Shopify: Mode A（列表 API 已包含完整資訊）
    return callApi("orders.json", filters)
      .map(this::parseCompleteOrder)
      .toList();
  }

  // fetchOrderDetail() 在 Mode A 實現中可以不用或拋異常
}

// ShopeeAdapter implements ChannelAdapter
public class ShopeeAdapter implements ChannelAdapter {
  @Override
  public List<OrderDTO> fetchOrders(FetchOrdersFilter filters) {
    // Shopee: Mode B（列表 API 只有概要）
    return callApi("order/search", filters)
      .map(this::parseOrderSummary)  // 只有基本欄位
      .toList();
  }

  @Override
  public OrderDTO fetchOrderDetail(String orderId) {
    // Mode B 特有：需要額外呼叫 detail API
    return callApi("order/get", orderId)
      .map(this::parseCompleteOrder)
      .orElse(null);
  }
}

// Channel Job Slow Consumer：
// 在運行時根據實際 API 返回內容決定是否需要 fetchOrderDetail
if (orderData.getItems().isEmpty() || orderData.getCustomer() == null) {
  // API 返回的資訊不完整，需要 detail fetch (Mode B 特性)
  detail = adapter.fetchOrderDetail(orderId);
} else {
  // 資訊完整，直接發 order.process (Mode A 特性)
}
```

**重點**：
- ✅ Mode A/B 由通路開發者在 Adapter 代碼中實現
- ✅ 不在資料庫 platforms 表中配置
- ✅ 平台 API 改變時，只需更新 Adapter 代碼

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

**Mode A/B 判定邏輯（運行時檢測）**：
```java
// ChannelJobSlowConsumer
@KafkaListener(topics = "{platform}.slow")
public void consume(KafkaMessage msg) {
  ChannelAdapter adapter = adapterFactory.getAdapter(msg.getPlatform());

  // 呼叫 fetchOrders，返回可能完整也可能不完整
  List<OrderDTO> orders = adapter.fetchOrders(msg.getFilters());

  // 運行時判定是否需要 detail fetch（根據返回資訊完整性）
  List<OrderDTO> completeOrders = new ArrayList<>();

  for (OrderDTO order : orders) {
    if (isOrderDataComplete(order)) {
      // Mode A 特性：API 已返回完整資訊
      completeOrders.add(order);
    } else {
      // Mode B 特性：需要額外 detail API
      OrderDTO detail = adapter.fetchOrderDetail(order.getId());
      if (detail != null) {
        completeOrders.add(mergeWithDetail(order, detail));
      }
    }
  }

  // 全部 send 到 order.process
  sendToOrderProcess(completeOrders);
}

// 判定函式（檢查必要欄位）
private boolean isOrderDataComplete(OrderDTO order) {
  return order.getItems() != null && !order.getItems().isEmpty()
    && order.getCustomer() != null
    && order.getShippingAddress() != null;
}
```

**優點**：
- ✅ 不依賴資料庫配置，自適應平台 API 變化
- ✅ 如果 Shopee API 升級變成 Mode A，自動適用
- ✅ 通路開發者只需確保 Adapter 返回正確格式，Consumer 自動判定

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
