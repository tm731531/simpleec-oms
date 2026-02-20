# SimpleEC OMS 介面契約定義

## Consumer / Producer Java 介面

### 1. Kafka 消費者介面

#### KafkaConsumer 基礎介面
```java
/**
 * 所有 Kafka Consumer 的基礎介面
 * 統一異常處理、重試邏輯、offset 管理
 */
public interface BaseKafkaConsumer<T> {
  
  /**
   * 消費訊息
   * @param message 反序列化後的訊息
   * @throws ConsumerException 業務異常（將被捕捉並發到 task.failed）
   */
  void consume(T message) throws ConsumerException;
  
  /**
   * 取得消費的 topic 名稱
   */
  String getTopic();
  
  /**
   * 取得 consumer group 名稱
   */
  String getConsumerGroup();
}
```

#### SchedulerConsumer
```java
@Component
@KafkaListener(topics = "scheduler", groupId = "scheduler-consumer-group")
public class SchedulerConsumer implements BaseKafkaConsumer<HeartbeatMessage> {
  
  @Autowired
  private SchedulerHandler schedulerHandler;
  
  /**
   * 消費 Heartbeat 訊息，根據時間決策發動作
   * 
   * @param heartbeat {
   *   "timestamp": "2026-02-20T10:15:00Z",
   *   "sequenceNumber": 61234
   * }
   */
  @Override
  public void consume(HeartbeatMessage heartbeat) throws ConsumerException {
    try {
      LocalDateTime time = parseIdempiereDateTime(heartbeat.getTimestamp());
      List<String> tasksToRun = decideTasksToRun(time);
      
      for (String task : tasksToRun) {
        schedulerHandler.handle(task, time);
      }
    } catch (Exception e) {
      throw new ConsumerException("Scheduler logic failed", e);
    }
  }
  
  private List<String> decideTasksToRun(LocalDateTime time) {
    List<String> tasks = new ArrayList<>();
    int minute = time.getMinute();
    
    if (minute % 5 == 0) {
      tasks.add("FETCH_ORDERS");        // 觸發訂單抓取
    }
    if (minute % 15 == 0) {
      tasks.add("SYNC_PRODUCT");        // 觸發商品同步
    }
    if (minute % 30 == 0) {
      tasks.add("HEALTH_CHECK");        // 通路連線檢查
    }
    if (time.getHour() == 0 && minute == 0) {
      tasks.add("GENERATE_REPORT");     // 日報表（午夜 0 點）
    }
    
    return tasks;
  }
  
  @Override
  public String getTopic() { return "scheduler"; }
  
  @Override
  public String getConsumerGroup() { return "scheduler-consumer-group"; }
}
```

#### ChannelJobFastConsumer
```java
@Component
@KafkaListener(topics = "#{T(com.simpleec.common.constants.TopicConstants).CHANNEL_FAST_TOPICS}", 
               groupId = "channel-job-fast-group",
               containerFactory = "batchFactory")
public class ChannelJobFastConsumer implements BaseKafkaConsumer<ChannelFastMessage> {
  
  @Autowired
  private FastSyncHandler fastSyncHandler;
  
  /**
   * 消費快速同步訊息（商品、庫存、配運）
   * 
   * @param message {
   *   "platform": "momo",
   *   "action": "SYNC_PRODUCT" | "SYNC_PACK" | "SYNC_INVENTORY",
   *   "payload": {...}
   * }
   */
  @Override
  public void consume(ChannelFastMessage message) throws ConsumerException {
    try {
      Platform platform = platformService.getByCode(message.getPlatform());
      ChannelAdapter adapter = adapterFactory.getAdapter(platform);
      
      switch (message.getAction()) {
        case "SYNC_PRODUCT":
          fastSyncHandler.syncProducts(adapter, platform);
          break;
        case "SYNC_PACK":
          fastSyncHandler.syncPack(adapter, platform);
          break;
        case "SYNC_INVENTORY":
          fastSyncHandler.syncInventory(adapter, platform);
          break;
      }
      
      // 同步完成後發送後端任務
      kafkaTemplate.send("task.backend", new BackendTask("UPDATE_REPORT", ...));
    } catch (Exception e) {
      throw new ConsumerException("Fast sync failed", e);
    }
  }
  
  @Override
  public String getTopic() { return "momo.fast,shopee.fast,yahoo.fast,..."; }
  
  @Override
  public String getConsumerGroup() { return "channel-job-fast-group"; }
}
```

#### ChannelJobSlowConsumer（Mode A/B）
```java
@Component
@KafkaListener(topics = "#{T(com.simpleec.common.constants.TopicConstants).CHANNEL_SLOW_TOPICS}",
               groupId = "channel-job-slow-group")
public class ChannelJobSlowConsumer implements BaseKafkaConsumer<ChannelSlowMessage> {
  
  @Autowired
  private SlowSyncHandler slowSyncHandler;
  @Autowired
  private RedisTemplate<String, String> redisTemplate;
  
  /**
   * 消費慢速同步訊息（訂單、退貨詳情）
   * 支援 Mode A 和 Mode B 兩種模式
   * 
   * Mode A: FETCH_ORDERS → 直接 order.process
   * Mode B: FETCH_ORDERS → FETCH_ORDER_DETAIL → order.process
   * 
   * @param message {
   *   "platform": "shopee",
   *   "action": "FETCH_ORDERS" | "FETCH_ORDER_DETAIL",
   *   "payload": {...}
   * }
   */
  @Override
  public void consume(ChannelSlowMessage message) throws ConsumerException {
    try {
      Platform platform = platformService.getByCode(message.getPlatform());
      ChannelAdapter adapter = adapterFactory.getAdapter(platform);
      
      // Layer 1 去重：檢查是否已處理
      for (Order order : message.getOrders()) {
        String cacheKey = RedisKeyUtil.processedOrder(order.getId());
        if (redisTemplate.hasKey(cacheKey)) {
          log.warn("Duplicate order, skipping: {}", order.getId());
          continue;
        }
        
        // 根據平台 Mode 決策處理方式
        if (platform.getMode() == PlatformMode.A) {
          // Mode A: 列表 API 已包含完整資訊
          handleModeA(adapter, platform, order);
        } else {
          // Mode B: 列表 API 只有概要，需詳情
          handleModeB(adapter, platform, order);
        }
      }
    } catch (Exception e) {
      throw new ConsumerException("Slow sync failed", e);
    }
  }
  
  private void handleModeA(ChannelAdapter adapter, Platform platform, Order order) {
    // Mode A: 直接發到 order.process
    OrderProcessMessage msg = OrderProcessMessage.builder()
      .orderId(order.getId())
      .platform(platform.getCode())
      .items(order.getItems())
      .amount(order.getAmount())
      .customer(order.getCustomer())
      .shippingAddress(order.getShippingAddress())
      .build();
    
    kafkaTemplate.send("order.process", msg);
  }
  
  private void handleModeB(ChannelAdapter adapter, Platform platform, Order order) {
    // Mode B: 先取詳情再發
    OrderDetail detail = adapter.fetchOrderDetail(order.getId());
    
    OrderProcessMessage msg = OrderProcessMessage.builder()
      .orderId(order.getId())
      .platform(platform.getCode())
      .items(detail.getItems())
      .amount(detail.getAmount())
      .customer(detail.getCustomer())
      .shippingAddress(detail.getShippingAddress())
      .build();
    
    kafkaTemplate.send("order.process", msg);
  }
  
  @Override
  public String getTopic() { return "momo.slow,shopee.slow,yahoo.slow,..."; }
  
  @Override
  public String getConsumerGroup() { return "channel-job-slow-group"; }
}
```

#### OrderUpsertConsumer
```java
@Component
@KafkaListener(topics = "order.process", groupId = "order-upsert-handler-group")
public class OrderUpsertConsumer implements BaseKafkaConsumer<OrderProcessMessage> {
  
  @Autowired
  private OrderUpsertHandler handler;
  
  /**
   * 消費訂單訊息，寫入資料庫
   * Layer 2 去重：使用 Redis 分鎖保護並行寫入
   * 
   * @param message {
   *   "orderId": "ORD-xxx",
   *   "platform": "momo",
   *   "items": [...],
   *   "customer": {...},
   *   "shippingAddress": {...}
   * }
   */
  @Override
  public void consume(OrderProcessMessage message) throws ConsumerException {
    handler.upsertOrder(message);
  }
  
  @Override
  public String getTopic() { return "order.process"; }
  
  @Override
  public String getConsumerGroup() { return "order-upsert-handler-group"; }
}
```

#### ReturnUpsertConsumer
```java
@Component
@KafkaListener(topics = "return.process", groupId = "return-upsert-handler-group")
public class ReturnUpsertConsumer implements BaseKafkaConsumer<ReturnProcessMessage> {
  
  @Autowired
  private ReturnUpsertHandler handler;
  
  @Override
  public void consume(ReturnProcessMessage message) throws ConsumerException {
    handler.upsertReturn(message);
  }
  
  @Override
  public String getTopic() { return "return.process"; }
  
  @Override
  public String getConsumerGroup() { return "return-upsert-handler-group"; }
}
```

#### BackendTaskConsumer
```java
@Component
@KafkaListener(topics = "task.backend", groupId = "backend-task-handler-group")
public class BackendTaskConsumer implements BaseKafkaConsumer<BackendTask> {
  
  @Autowired
  private GenerateShipmentHandler shipmentHandler;
  @Autowired
  private UpdateInventoryHandler inventoryHandler;
  @Autowired
  private SendToWarehouseHandler warehouseHandler;
  
  /**
   * 消費後端非同步任務
   * 
   * @param task {
   *   "taskId": "xxx",
   *   "taskType": "GENERATE_SHIPMENT" | "UPDATE_INVENTORY" | "SEND_TO_WAREHOUSE",
   *   "payload": {...}
   * }
   */
  @Override
  public void consume(BackendTask task) throws ConsumerException {
    switch (task.getTaskType()) {
      case GENERATE_SHIPMENT:
        shipmentHandler.handle(task);
        break;
      case UPDATE_INVENTORY:
        inventoryHandler.handle(task);
        break;
      case SEND_TO_WAREHOUSE:
        warehouseHandler.handle(task);
        break;
    }
  }
  
  @Override
  public String getTopic() { return "task.backend"; }
  
  @Override
  public String getConsumerGroup() { return "backend-task-handler-group"; }
}
```

#### ErrorHandler
```java
@Component
@KafkaListener(topics = "task.failed", groupId = "error-handler-group")
public class ErrorHandler implements BaseKafkaConsumer<ErrorMessage> {
  
  /**
   * 消費失敗訊息，實施重試邏輯
   * 指數退避：2^(retryCount) 秒後重試
   * 超過 3 次後進死信隊列
   * 
   * @param error {
   *   "originalTopic": "order.process",
   *   "retryCount": 1,
   *   "exception": "DatabaseException: ...",
   *   "message": {...}
   * }
   */
  @Override
  public void consume(ErrorMessage error) throws ConsumerException {
    if (error.getRetryCount() <= 3) {
      long backoffMs = (long) Math.pow(2, error.getRetryCount()) * 1000;
      schedule(() -> {
        error.setRetryCount(error.getRetryCount() + 1);
        kafkaTemplate.send(error.getOriginalTopic(), error.getMessage());
      }, backoffMs);
    } else {
      kafkaTemplate.send("task.dlt", error);
    }
  }
  
  @Override
  public String getTopic() { return "task.failed"; }
  
  @Override
  public String getConsumerGroup() { return "error-handler-group"; }
}
```

#### DltHandler
```java
@Component
@KafkaListener(topics = "task.dlt", groupId = "dlt-handler-group")
public class DltHandler implements BaseKafkaConsumer<ErrorMessage> {
  
  /**
   * 消費死信訊息，存儲並發警報
   * 
   * @param error 最終無法處理的訊息
   */
  @Override
  public void consume(ErrorMessage error) throws ConsumerException {
    // 存儲到資料庫
    dltMessageService.save(DltMessage.builder()
      .originalTopic(error.getOriginalTopic())
      .message(error.getMessage())
      .exception(error.getException())
      .retryCount(error.getRetryCount())
      .build());
    
    // 發警報
    alertService.sendAlert("Message dead-lettered: " + error.getMessage().getId());
  }
  
  @Override
  public String getTopic() { return "task.dlt"; }
  
  @Override
  public String getConsumerGroup() { return "dlt-handler-group"; }
}
```

---

### 2. Platform Adapter 介面

```java
/**
 * 各通路的適配器介面
 * Mode A/B 由實現類決定如何處理
 */
public interface ChannelAdapter {
  
  /**
   * 抓取訂單列表
   * 
   * Mode A: 返回完整訂單資訊
   * Mode B: 只返回訂單概要，需後續 fetchOrderDetail
   * 
   * @param filters 查詢條件 (createdAfter, createdBefore)
   * @return 訂單列表
   */
  List<OrderDTO> fetchOrders(FetchOrdersFilter filters) throws ChannelApiException;
  
  /**
   * 抓取訂單詳情（Mode B 專用）
   * Mode A 平台無此呼叫
   * 
   * @param orderId 訂單 ID
   * @return 完整訂單資訊
   */
  OrderDTO fetchOrderDetail(String orderId) throws ChannelApiException;
  
  /**
   * 抓取商品列表
   * 
   * @param filters 查詢條件 (category, priceRange)
   * @return 商品列表
   */
  List<ProductDTO> fetchProducts(FetchProductsFilter filters) throws ChannelApiException;
  
  /**
   * 查詢庫存
   * 
   * @param skus SKU 列表
   * @return SKU → 可用庫存數 mapping
   */
  Map<String, Integer> fetchInventory(List<String> skus) throws ChannelApiException;
  
  /**
   * 出貨（通知通路）
   * 
   * @param shipmentId 本地出貨單 ID
   * @return 是否成功
   */
  boolean shipOrder(String shipmentId) throws ChannelApiException;
  
  /**
   * 取消訂單
   * 
   * @param orderId 通路訂單 ID
   * @return 是否成功
   */
  boolean cancelOrder(String orderId) throws ChannelApiException;
  
  /**
   * 取得平台代碼
   */
  String getPlatformCode();
}
```

---

### 3. Kafka 訊息 Schema

#### HeartbeatMessage
```json
{
  "timestamp": "2026-02-20T10:15:00Z",
  "sequenceNumber": 61234
}
```

#### OrderProcessMessage
```json
{
  "orderId": "ORD-2026-0001",
  "platform": "momo",
  "items": [
    {
      "sku": "SKU-123",
      "quantity": 2,
      "unitPrice": 99.99,
      "currency": "TWD"
    }
  ],
  "totalAmount": 199.98,
  "customer": {
    "name": "[AES encrypted]",
    "email": "[AES encrypted]",
    "phone": "[AES encrypted]"
  },
  "shippingAddress": {
    "street": "[AES encrypted]",
    "city": "Taipei",
    "zipCode": "10001"
  },
  "createdAt": "2026-02-20T08:00:00Z"
}
```

#### ErrorMessage
```json
{
  "originalTopic": "order.process",
  "originalMessage": {...},
  "retryCount": 2,
  "exception": "DatabaseException: Connection timeout",
  "failedAt": "2026-02-20T10:15:30Z"
}
```

---

### 4. 異常處理

```java
/**
 * 系統異常層級
 */
public class ConsumerException extends RuntimeException {
  // Consumer 業務邏輯異常
  // 將被捕捉並發到 task.failed → ErrorHandler
}

public class ChannelApiException extends RuntimeException {
  // 通路 API 異常（連線、超時、API 變動）
  // 可重試
}

public class DataIntegrityException extends RuntimeException {
  // 資料完整性異常（重複鍵、約束違反）
  // 一般無法重試，進 DLT
}

public class ConfigurationException extends RuntimeException {
  // 配置異常（缺少必要設定）
  // 需人工修正
}
```

---

**上次更新**：2026-02-20
**版本**：1.0 - PLAN Phase I Release
