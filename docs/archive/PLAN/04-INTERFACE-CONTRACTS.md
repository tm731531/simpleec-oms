# Kafka 訊息契約 & Java 介面定義

## 概述

本文檔定義系統所有 Kafka 訊息格式、Consumer/Producer 介面、以及模組間的 Java API 契約。

**目標**：確保各模組間的通信規範統一，防止不相容的升級。

---

## Part I：Kafka 訊息契約 (Schema)

### 使用 Avro Schema

所有 Kafka 訊息採用 **Apache Avro** 格式定義。

**核心訊息類型**：
1. HeartbeatMessage (scheduler)
2. FetchOrdersTask ({platform}.slow)
3. ProcessOrderMessage (order.process)
4. ProcessReturnMessage (return.process)
5. BackendTaskMessage (task.backend)
6. FailedMessage (task.failed)
7. DltMessage (task.dlt)

---

## Part II：Consumer/Producer 介面設計

### 多生產者 + TaskType 路由

**單一 Topic 內的訊息結構**：

```json
{
  "taskType": "FETCH_ORDERS" | "FETCH_ORDER_DETAIL" | "SYNC_PACK" | ...,
  "producer": "SchedulerConsumer" | "Channel Job Slow" | "UI/Gateway" | ...,
  "payload": { /* taskType 特定欄位 */ }
}
```

**Consumer 職責**：
1. 消費 Topic 的所有訊息
2. 根據 **taskType** 路由到不同的處理邏輯
3. 每個 taskType 可能有不同的 ACID 保證策略（Redis、分鎖、去重等）

**示例：ChannelJobSlowConsumer**

```java
@KafkaListener(topics = "{platform}.slow")
public void consume(KafkaMessage msg) {
  switch(msg.getTaskType()) {
    case FETCH_ORDERS:
      handleFetchOrders(msg);  // Mode A/B 決策、去重、發 order.process
      break;

    case FETCH_ORDER_DETAIL:
      handleFetchOrderDetail(msg);  // Mode B 詳情取得、去重、發 order.process
      break;

    case SYNC_PACK:
      handleSyncPack(msg);  // 打包同步、發 task.backend
      break;

    default:
      log.warn("Unknown taskType: {}", msg.getTaskType());
  }
}
```

---

### KafkaMessageListener 基類

```java
/**
 * 所有 Kafka Consumer 的基類
 * 提供標準的錯誤處理、重試邏輯、監控埋點
 */
public abstract class KafkaMessageListener<T> {

    protected Logger log = LoggerFactory.getLogger(getClass());
    protected KafkaTemplate<String, String> kafkaTemplate;
    protected MeterRegistry meterRegistry;

    /**
     * 消費訊息的主邏輯
     * 子類實現此方法，根據 taskType 分派到具體處理器
     *
     * @param message 解析後的訊息物件
     * @throws ProcessingException 處理異常
     */
    protected abstract void handleMessage(T message) throws ProcessingException;

    /**
     * 訊息監聽入口
     * 已配置 @KafkaListener，由框架自動呼叫
     */
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            T message = deserialize(record.value());
            meterRegistry.counter("kafka.message.received",
                "topic", record.topic()).increment();
            handleMessage(message);
            meterRegistry.counter("kafka.message.processed",
                "topic", record.topic()).increment();
        } catch (ProcessingException e) {
            handleProcessingError(record, e);
        } catch (Exception e) {
            handleUnexpectedError(record, e);
        }
    }

    protected abstract T deserialize(String value);
    protected abstract String serialize(Object obj);
}
```

---

### ChannelAdapter 介面

```java
/**
 * 通路適配器介面
 * 各平台實現此介面，以統一的方式訪問平台 API
 */
public interface ChannelAdapter {

    /**
     * 取訂單列表
     * Mode A: 完整資訊
     * Mode B: 摘要（需 getOrderDetail）
     */
    List<OrderDTO> getOrders(OrderQueryCriteria criteria)
        throws PlatformApiException;

    /**
     * 取單一訂單詳情 (Mode B 專用)
     */
    OrderDTO getOrderDetail(String platformOrderId)
        throws PlatformApiException;

    /**
     * 取商品清單
     */
    List<ProductDTO> getProducts(ProductQueryCriteria criteria)
        throws PlatformApiException;

    /**
     * 查詢庫存快照
     */
    Map<String, Integer> getInventory(List<String> skuIds)
        throws PlatformApiException;

    /**
     * 更新平台庫存
     */
    void updateInventory(String skuId, Integer quantity)
        throws PlatformApiException;

    /**
     * 更新訂單狀態
     */
    void updateOrderStatus(String platformOrderId, OrderStatus status)
        throws PlatformApiException;
}
```

---

### OrderUpsertHandler (order.process Consumer)

```java
/**
 * 訂單入庫 Consumer
 * 責任：Layer 2 分佈式鎖 + DB 寫入
 */
@Component
@Slf4j
public class OrderUpsertHandler extends KafkaMessageListener<ProcessOrderMessage> {

    private final OrderService orderService;
    private final RedisDistributedLockService lockService;

    @KafkaListener(
        topics = KafkaTopics.ORDER_PROCESS,
        groupId = "order-upsert-group",
        concurrency = "12"
    )
    @Override
    public void onMessage(ConsumerRecord<String, String> record) {
        super.onMessage(record);
    }

    @Override
    protected void handleMessage(ProcessOrderMessage message) 
            throws ProcessingException {
        String orderId = message.getOrderId();

        DistributedLock lock = lockService.acquire(
            "order:upsert:" + orderId,
            Duration.ofSeconds(30)
        );

        if (lock == null) {
            throw new ProcessingException("LOCK_FAILED", "Failed to acquire lock");
        }

        try {
            Order savedOrder = orderService.upsertOrder(message);
            sendBackendTask(savedOrder);
            recordMetrics(savedOrder);
        } finally {
            lockService.release(lock);
        }
    }
}
```

---

### SchedulerConsumer (scheduler Consumer)

```java
/**
 * 時間驅動決策中樞
 * 根據分鐘位置決策發送任務
 */
@Component
@Slf4j
public class SchedulerConsumer extends KafkaMessageListener<HeartbeatMessage> {

    @KafkaListener(
        topics = KafkaTopics.SCHEDULER,
        groupId = "scheduler-group",
        concurrency = "1"
    )
    @Override
    public void onMessage(ConsumerRecord<String, String> record) {
        super.onMessage(record);
    }

    @Override
    protected void handleMessage(HeartbeatMessage heartbeat) 
            throws ProcessingException {
        LocalDateTime now = Instant.ofEpochMilli(heartbeat.getTimestamp())
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime();

        int minute = now.getMinute();

        if (minute % 5 == 0) {
            dispatchFetchOrdersTask();
        }
        if (minute % 15 == 0) {
            dispatchSyncProductsTask();
        }
        if (minute % 30 == 0) {
            dispatchSyncInventoryTask();
        }
    }
}
```

---

## Part III：Redis 分佈式鎖

### DistributedLock 介面

```java
/**
 * 分佈式鎖服務
 * 使用 Redis SET NX EX 實現
 */
public interface RedisDistributedLockService {

    /**
     * 獲得鎖
     * @return Lock 物件（非 null = 成功），null = 失敗
     */
    DistributedLock acquire(String key, Duration ttl);

    /**
     * 釋放鎖（驗證 token）
     */
    boolean release(DistributedLock lock);

    /**
     * 檢查鎖是否存在
     */
    boolean exists(String key);
}

@Data
public class DistributedLock {
    private String key;
    private String token;  // UUID
    private Duration ttl;
    private long acquiredAt;
}
```

---

## Part IV：錯誤處理

### 標準異常類

```java
public class ProcessingException extends Exception {
    private String errorCode;
    private String message;

    public ProcessingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

public class PlatformApiException extends ProcessingException {
    // 平台 API 呼叫失敗
}

public class DataAccessException extends ProcessingException {
    // 資料庫操作失敗
}
```

---

## Part V：訊息流設計原則

1. **唯一性**：每個 Topic 只有一個明確的 Producer
2. **順序性**：使用 orderId 作為 Partition Key
3. **可重放性**：訊息保留 7-30 天
4. **冪等性**：使用 Redis Layer 1/2 去重確保

---

**上次更新**：2026-02-20
**版本**：1.0 - PLAN Phase I Release
