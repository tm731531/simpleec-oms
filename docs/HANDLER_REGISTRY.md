# SimpleEC OMS Handler 註冊表

## 0. 平台模式與 Handler 架構對應

根據 PLATFORM_MAPPING.md §0 和 DATA_FLOW_MAPPING.md §0-2，每個平台的 Channel Job Handler 數量取決於其處理模式：

### Mode A 平台（直接模式）— 單一 Handler
列表 API 已含完整資訊 → **FETCH_ORDERS 直接產生 ORDER_UPSERT**

```
Handler: FetchOrdersHandler
  ├─ Input: FETCH_ORDERS message
  ├─ Logic:
  │   ├─ 呼叫列表 API（已有 items, shipping, customer）
  │   ├─ 逐單組織 OMS 結構
  │   ├─ 計算 Hash（TreeMap 排序）
  │   ├─ 讀 Redis 檢查 hash（第一層去重）
  │   └─ 發送 ORDER_UPSERT（若 hash 不同）
  └─ Output: ORDER_UPSERT message → order.process topic
```

**示例：Shopify, easystore（推測，待確認）**

### Mode B 平台（列表+詳情模式）— 兩個 Handler
列表 API 缺少關鍵資訊 → **FETCH_ORDERS 產生 FETCH_ORDER_DETAIL，詳情 Handler 產生 ORDER_UPSERT**

```
Handler 1: FetchOrdersHandler
  ├─ Input: FETCH_ORDERS message
  ├─ Logic:
  │   ├─ 呼叫列表 API
  │   └─ 逐單產生 FETCH_ORDER_DETAIL message
  └─ Output: FETCH_ORDER_DETAIL messages → {platform}.slow topic

Handler 2: FetchOrderDetailHandler
  ├─ Input: FETCH_ORDER_DETAIL message (per order)
  ├─ Logic:
  │   ├─ 呼叫詳情 API（取得 items, shipping, buyer）
  │   ├─ 組織 OMS 結構
  │   ├─ 計算 Hash
  │   ├─ 讀 Redis 檢查 hash（第一層去重）
  │   └─ 發送 ORDER_UPSERT（若 hash 不同）
  └─ Output: ORDER_UPSERT message → order.process topic
```

**示例：Shopee（確認），Momo, Yahoo, PChome, Cyberbiz（待確認）**

### 平台 Mode 對應表（待補充）

| 平台 | 模式 | Handler 數 | 說明 |
|------|------|-----------|------|
| Shopee | Mode B | 2 | FETCH_ORDERS + FETCH_ORDER_DETAIL |
| Shopify | Mode A | 1 | FETCH_ORDERS（直接） |
| Momo | ? | ? | 待確認 API 完整度 |
| Yahoo | ? | ? | 待確認 API 完整度 |
| PChome | ? | ? | 待確認 API 完整度 |
| easystore | ? | ? | 待確認 API 完整度 |
| Cyberbiz | ? | ? | 待確認 API 完整度 |

---

## 0.1 Heartbeat + Scheduler 驅動模型

**關鍵前提**：所有 Handler 的觸發源是 **Heartbeat + Scheduler** 系統

```
Heartbeat Job（每秒發脈搏）
    ↓ timestamp-driven
Scheduler Consumer（根據分鐘位判斷派發）
    ↓
Channel Job Handlers / Backend Handlers
```

### Scheduler 派發規則

| 分鐘位 | 派發目標 | Handler 類型 | 範例 |
|--------|---------|------------|------|
| :00, :05, :10... (% 5 == 0) | All {platform}.slow | Channel Job | FETCH_ORDERS, FETCH_RETURNS |
| :01, :06, :11... (% 5 == 1) | task.backend | Backend Job | Order Report Generation |
| :02, :07, :12... (% 5 == 2) | task.backend | Backend Job | Inventory Report Generation |
| :03, :08, :13... (% 5 == 3) | task.backend | Backend Job | Sales Report Generation |
| :04, :09, :14... (% 5 == 4) | task.backend | Backend Job | Return Report Generation |
| :05, :15, :25... (% 10 == 5) | task.backend | Backend Job | Kafka Health Check |
| :00, :30 | task.backend | Backend Job | Daily Report Generation |

**重點**：
- Scheduler 只派發「時間驅動」的任務（FETCH_ORDERS, FETCH_RETURNS, 報表生成）
- UI 觸發的任務（SYNC_PACK, SHIP_ORDER 由用戶手動點擊）直接發到對應 topic，不走 Scheduler
- 所有時間判斷都基於 **Heartbeat timestamp**，NOT 本地時間

---

## 1. Handler 架構設計

### 1.1 核心介面
```java
public interface TaskHandler {
    String getTaskType();           // 處理的 TaskType
    void handle(TaskMessage msg);   // 處理邏輯
    boolean canHandle(String taskType); // 是否能處理
}

public interface ChannelTaskHandler extends TaskHandler {
    String getPlatform();            // 所屬平台
    Priority getPriority();          // 處理優先級
}
```

### 1.2 註冊機制
```java
@Component
public class HandlerRegistry {
    private final Map<String, TaskHandler> handlers = new ConcurrentHashMap<>();

    @Autowired
    public void registerHandlers(List<TaskHandler> handlerList) {
        for (TaskHandler handler : handlerList) {
            handlers.put(handler.getTaskType(), handler);
        }
    }

    public TaskHandler getHandler(String taskType) {
        return handlers.get(taskType);
    }
}
```

## 2. TaskType → Handler 對應表

### 2.1 Channel Job Handlers

#### Shopee Channel Job
| TaskType | Handler Class | Topic | 說明 |
|----------|--------------|-------|------|
| FETCH_ORDERS | ShopeeOrderListHandler | shopee.slow | 抓取訂單列表 |
| FETCH_ORDER_DETAIL | ShopeeOrderDetailHandler | shopee.slow | 抓取訂單詳情 |
| FETCH_RETURNS | ShopeeReturnListHandler | shopee.slow | 抓取退貨列表 |
| FETCH_RETURN_DETAIL | ShopeeReturnDetailHandler | shopee.slow | 抓取退貨詳情 |
| SYNC_PACK | ShopeeSyncPackHandler | shopee.slow | **雙層檢查 + 條件派發 SYNC_PRODUCT / SYNC_PACK** |
| SHIP_ORDER | ShopeeShipOrderHandler | shopee.fast | 執行出貨 |
| UPDATE_INVENTORY | ShopeeInventoryHandler | shopee.fast | 更新庫存 |
| UPDATE_PRICE | ShopeePriceHandler | shopee.fast | 更新價格 |
| APPROVE_RETURN | ShopeeApproveReturnHandler | shopee.fast | 同意退貨 |

#### Momo Channel Job
| TaskType | Handler Class | Topic | 說明 |
|----------|--------------|-------|------|
| FETCH_ORDERS | MomoOrderListHandler | momo.slow | 抓取訂單列表 |
| FETCH_ORDER_DETAIL | MomoOrderDetailHandler | momo.slow | 抓取訂單詳情 |
| FETCH_RETURNS | MomoReturnListHandler | momo.slow | 抓取退貨列表 |
| FETCH_RETURN_DETAIL | MomoReturnDetailHandler | momo.slow | 抓取退貨詳情 |
| SYNC_PACK | MomoSyncPackHandler | momo.slow | **雙層檢查 + 條件派發 SYNC_PRODUCT / SYNC_PACK** |
| SHIP_ORDER | MomoShipOrderHandler | momo.fast | 執行出貨 |
| UPDATE_INVENTORY | MomoInventoryHandler | momo.fast | 更新庫存 |
| UPDATE_PRICE | MomoPriceHandler | momo.fast | 更新價格 |
| APPROVE_RETURN | MomoApproveReturnHandler | momo.fast | 同意退貨 |

#### Yahoo Channel Job
| TaskType | Handler Class | Topic | 說明 |
|----------|--------------|-------|------|
| FETCH_ORDERS | YahooOrderListHandler | yahoo.slow | 抓取訂單列表 |
| FETCH_ORDER_DETAIL | YahooOrderDetailHandler | yahoo.slow | 抓取訂單詳情 |
| FETCH_RETURNS | YahooReturnListHandler | yahoo.slow | 抓取退貨列表 |
| FETCH_RETURN_DETAIL | YahooReturnDetailHandler | yahoo.slow | 抓取退貨詳情 |
| SYNC_PACK | YahooSyncPackHandler | yahoo.slow | **雙層檢查 + 條件派發 SYNC_PRODUCT / SYNC_PACK** |
| PROCESS_YAHOO_CSV | YahooCsvHandler | yahoo.slow | 處理 CSV webhook |
| SHIP_ORDER | YahooShipOrderHandler | yahoo.fast | 執行出貨 |
| UPDATE_INVENTORY | YahooInventoryHandler | yahoo.fast | 更新庫存 |
| UPDATE_PRICE | YahooPriceHandler | yahoo.fast | 更新價格 |
| APPROVE_RETURN | YahooApproveReturnHandler | yahoo.fast | 同意退貨 |

#### PChome Channel Job
| TaskType | Handler Class | Topic | 說明 |
|----------|--------------|-------|------|
| FETCH_ORDERS | PChomeOrderListHandler | pchome.slow | 抓取訂單列表 |
| FETCH_ORDER_DETAIL | PChomeOrderDetailHandler | pchome.slow | 抓取訂單詳情 |
| FETCH_RETURNS | PChomeReturnListHandler | pchome.slow | 抓取退貨列表 |
| FETCH_RETURN_DETAIL | PChomeReturnDetailHandler | pchome.slow | 抓取退貨詳情 |
| SYNC_PACK | PCHomeSyncPackHandler | pchome.slow | **雙層檢查 + 條件派發 SYNC_PRODUCT / SYNC_PACK** |
| SHIP_ORDER | PChomeShipOrderHandler | pchome.fast | 執行出貨 |
| UPDATE_INVENTORY | PChomeInventoryHandler | pchome.fast | 更新庫存 |
| UPDATE_PRICE | PChomePriceHandler | pchome.fast | 更新價格 |
| APPROVE_RETURN | PChomeApproveReturnHandler | pchome.fast | 同意退貨 |

#### Cyberbiz Channel Job
| TaskType | Handler Class | Topic | 說明 |
|----------|--------------|-------|------|
| FETCH_ORDERS | CyberbizOrderListHandler | cyberbiz.slow | 抓取訂單列表 |
| FETCH_ORDER_DETAIL | CyberbizOrderDetailHandler | cyberbiz.slow | 抓取訂單詳情 |
| FETCH_RETURNS | CyberbizReturnListHandler | cyberbiz.slow | 抓取退貨列表 |
| FETCH_RETURN_DETAIL | CyberbizReturnDetailHandler | cyberbiz.slow | 抓取退貨詳情 |
| SYNC_PACK | CyberbizSyncPackHandler | cyberbiz.slow | **雙層檢查 + 條件派發 SYNC_PRODUCT / SYNC_PACK** |
| SHIP_ORDER | CyberbizShipOrderHandler | cyberbiz.fast | 執行出貨 |
| UPDATE_INVENTORY | CyberbizInventoryHandler | cyberbiz.fast | 更新庫存 |
| UPDATE_PRICE | CyberbizPriceHandler | cyberbiz.fast | 更新價格 |
| APPROVE_RETURN | CyberbizApproveReturnHandler | cyberbiz.fast | 同意退貨 |

### 2.2 Business Job Handlers

#### Order Process Job
| TaskType | Handler Class | Topic | 說明 |
|----------|--------------|-------|------|
| ORDER_UPSERT | OrderUpsertHandler | order.process | **新建或更新訂單**（內部根據 channelOrderId 存在性判定） |
| CANCEL_ORDER | CancelOrderHandler | order.process | 取消訂單 |
| ORDER_STATUS_CHANGE | OrderStatusHandler | order.process | 狀態變更 |

#### Return Process Job
| TaskType | Handler Class | Topic | 說明 |
|----------|--------------|-------|------|
| RETURN_UPSERT | ReturnUpsertHandler | return.process | **新建或更新退貨**（內部根據 channelReturnId 存在性判定） |
| APPROVE_RETURN | ApproveReturnHandler | return.process | 同意退貨 |
| REJECT_RETURN | RejectReturnHandler | return.process | 拒絕退貨 |

#### Backend Job
| TaskType | Handler Class | Topic | 說明 | 觸發時機 |
|----------|--------------|-------|------|---------|
| SYNC_PRODUCT | SyncProductHandler | task.backend | **獨立**：新建或更新 Product（根據 SKU 聚合自多通路） | 由通路 handler 派發 |
| SYNC_PACK | SyncPackHandler | task.backend | **獨立**：新建或更新 Pack（根據 platformId + specId 判定） | 由通路 handler 派發 |
| ORDER_REPORT | OrderReportHandler | task.backend | 生成訂單報表（統計各通路訂單） | 每5分鐘 (% 5 == 1) |
| INVENTORY_REPORT | InventoryReportHandler | task.backend | 生成庫存報表（統計各通路庫存） | 每5分鐘 (% 5 == 2) |
| SALES_REPORT | SalesReportHandler | task.backend | 生成銷售報表（統計各通路銷售） | 每5分鐘 (% 5 == 3) |
| RETURN_REPORT | ReturnReportHandler | task.backend | 生成退貨報表（統計各通路退貨） | 每5分鐘 (% 5 == 4) |
| KAFKA_HEALTH_CHECK | KafkaHealthHandler | task.backend | Kafka 健康檢查 | 每10分鐘 (% 10 == 5) |
| DAILY_REPORT | DailyReportHandler | task.backend | 每日報表生成 | 每日 :00 和 :30 |

## 3. Handler 實作範例

### 3.1 Shopee 訂單列表 Handler
```java
@Component
@ChannelHandler(platform = "shopee", taskType = "FETCH_ORDERS")
public class ShopeeOrderListHandler implements ChannelTaskHandler {

    private final ShopeeApiClient apiClient;
    private final KafkaProducer producer;

    @Override
    public void handle(TaskMessage message) {
        TimeRange timeRange = parseTimeRange(message.getBody());

        // 1. 呼叫 Shopee API
        List<ShopeeOrder> orders = fetchOrdersWithPagination(timeRange);

        // 2. 分析每筆訂單
        for (ShopeeOrder order : orders) {
            // 判斷是否需要詳情
            boolean needsDetail = shouldFetchDetail(order);

            // 發送到 order.process
            producer.send("order.process", buildNewOrderMessage(order, needsDetail));

            // 如需詳情，發送 FETCH_ORDER_DETAIL
            if (needsDetail) {
                producer.send("shopee.slow", buildFetchDetailMessage(order));
            }

            // 如有退貨，發送 FETCH_RETURN_DETAIL
            if (hasReturn(order)) {
                producer.send("shopee.slow", buildFetchReturnMessage(order));
            }
        }
    }

    private boolean shouldFetchDetail(ShopeeOrder order) {
        return order.getOrderStatus().equals("READY_TO_SHIP") ||
               order.getTotalAmount() > 10000 ||
               order.hasSpecialItems();
    }

    private List<ShopeeOrder> fetchOrdersWithPagination(TimeRange range) {
        List<ShopeeOrder> allOrders = new ArrayList<>();
        String cursor = "";

        do {
            PagedResponse<ShopeeOrder> response = apiClient.getOrderList(
                range, cursor, PAGE_SIZE
            );
            allOrders.addAll(response.getItems());
            cursor = response.getNextCursor();
        } while (cursor != null && !cursor.isEmpty());

        return allOrders;
    }
}
```

### 3.2 Order Process Handler (Upsert Pattern with Hash Deduplication)
```java
@Component
public class OrderUpsertHandler implements TaskHandler {

    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public String getTaskType() {
        return "ORDER_UPSERT";
    }

    @Transactional
    @Override
    public void handle(TaskMessage message) {
        OrderUpsertRequest request = parseBody(message.getBody(), OrderUpsertRequest.class);
        String merchantId = message.getHeader().getMerchantId();
        String channelId = request.getChannelId();
        String channelOrderId = request.getChannelOrderId();
        String orderHash = request.getOrderHash();  // 從 Channel Job 傳入

        // 0. 構建 Redis Key（從 REDIS_DEDUPLICATION.md）
        String redisKey = String.format("order:hash:%s:%s:%s",
            merchantId, channelId, channelOrderId);

        // 1. 再次檢查 Redis（避免並發重複）
        String existingHashInRedis = redisTemplate.opsForValue().get(redisKey);
        if (orderHash.equals(existingHashInRedis)) {
            log.info("Order already processed (Redis hash match): {}", channelOrderId);
            return;
        }

        // 2. 檢查資料庫中是否已存在
        Optional<Order> existing = orderService.findByChannelOrderId(channelId, channelOrderId);

        Order order;
        if (existing.isPresent()) {
            // 2A. 更新現有訂單
            order = existing.get();

            // 只有當 hash 不同時才真正更新（檢測是否有實質變化）
            // 重新計算 DB 中訂單的 hash（內存中，不是從 DB 欄位讀）
            String dbOrderHash = calculateOrderHash(existing.get());
            if (!orderHash.equals(dbOrderHash)) {
                order.update(orderMapper.fromChannelData(channelId, request.getOrderData()));
                log.info("Updated order: {} from channel {} (hash changed)",
                    order.getOrderId(), channelId);
            } else {
                log.debug("Order content unchanged: {}", channelOrderId);
                return;
            }
        } else {
            // 2B. 建立新訂單
            order = orderMapper.fromChannelData(channelId, request.getOrderData());
            log.info("Created new order: {} from channel {}",
                order.getOrderId(), channelId);
        }

        // 3. 儲存訂單到資料庫（insert or update）
        orderService.save(order);

        // 4. 更新 Redis Hash 快取（確保與 DB 同步）
        redisTemplate.opsForValue().set(
            redisKey,
            orderHash,
            Duration.ofDays(7)  // TTL 7 天
        );

        // 5. 觸發後續流程
        if (request.isNeedsDetail()) {
            order.setStatus(OrderStatus.PENDING_DETAIL);
        } else {
            triggerNextStep(order);
        }
    }

    private void triggerNextStep(Order order) {
        if (order.needsShipment()) {
            // 發送出貨準備訊息
            producer.send(getChannelTopic(order), buildPrepareShipmentMessage(order));
        }
    }
}
```

**Hash 包含的欄位** (參考 REDIS_DEDUPLICATION.md，§3.1 Order Hash 計算)：
- `orderStatus` — 訂單狀態
- `totalAmount` — 訂單總金額
- `shippingStatus` — 物流狀態
- `paymentStatus` — 付款狀態
- `items` — 項目列表（包含 SKU、數量等）
- `buyerInfo` — 買家信息
- `shippingInfo` — 物流信息（包含追蹤號）

**⚠️ Hash 計算務必使用 TreeMap 排序，避免 JSON 亂序**：
```java
// 使用 OrderHashService.calculateOrderHash() — 參考 REDIS_DEDUPLICATION.md §3.1
public String calculateOrderHash(OrderData orderData) {
    // 排序欄位確保一致性（無論來自哪個通路）
    TreeMap<String, Object> sortedData = new TreeMap<>();

    // 只包含會變動的業務欄位
    sortedData.put("orderStatus", orderData.getOrderStatus());
    sortedData.put("totalAmount", orderData.getTotalAmount());
    sortedData.put("shippingStatus", orderData.getShippingStatus());
    sortedData.put("paymentStatus", orderData.getPaymentStatus());
    sortedData.put("items", normalizeItems(orderData.getItems()));
    sortedData.put("buyerInfo", orderData.getBuyerInfo());
    sortedData.put("shippingInfo", orderData.getShippingInfo());

    String json = objectMapper.writeValueAsString(sortedData);
    return DigestUtils.sha256Hex(json);
}
```

### 3.3 Return Process Handler (Upsert Pattern with Hash Deduplication)
```java
@Component
public class ReturnUpsertHandler implements TaskHandler {

    private final ReturnService returnService;
    private final ReturnMapper returnMapper;
    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public String getTaskType() {
        return "RETURN_UPSERT";
    }

    @Transactional
    @Override
    public void handle(TaskMessage message) {
        ReturnUpsertRequest request = parseBody(message.getBody(), ReturnUpsertRequest.class);
        String merchantId = message.getHeader().getMerchantId();
        String channelId = request.getChannelId();
        String channelReturnId = request.getChannelReturnId();
        String returnHash = request.getReturnHash();  // 從 Channel Job 傳入

        // 0. 構建 Redis Key
        String redisKey = String.format("return:hash:%s:%s:%s",
            merchantId, channelId, channelReturnId);

        // 1. 再次檢查 Redis（避免並發重複）
        String existingHashInRedis = redisTemplate.opsForValue().get(redisKey);
        if (returnHash.equals(existingHashInRedis)) {
            log.info("Return already processed (Redis hash match): {}", channelReturnId);
            return;
        }

        // 2. 檢查資料庫中是否已存在
        Optional<Return> existing = returnService.findByChannelReturnId(channelId, channelReturnId);

        Return returnRecord;
        if (existing.isPresent()) {
            // 2A. 更新現有退貨
            returnRecord = existing.get();

            // 只有當 hash 不同時才真正更新（檢測是否有實質變化）
            // 重新計算 DB 中退貨的 hash（內存中，不是從 DB 欄位讀）
            String dbReturnHash = calculateReturnHash(existing.get());
            if (!returnHash.equals(dbReturnHash)) {
                returnRecord.update(returnMapper.fromChannelData(channelId, request.getReturnData()));
                log.info("Updated return: {} from channel {} (hash changed)",
                    returnRecord.getReturnId(), channelId);
            } else {
                log.debug("Return content unchanged: {}", channelReturnId);
                return;
            }
        } else {
            // 2B. 建立新退貨
            returnRecord = returnMapper.fromChannelData(channelId, request.getReturnData());
            log.info("Created new return: {} from channel {}",
                returnRecord.getReturnId(), channelId);
        }

        // 3. 儲存退貨到資料庫（insert or update）
        returnService.save(returnRecord);

        // 4. 更新 Redis Hash 快取（確保與 DB 同步）
        redisTemplate.opsForValue().set(
            redisKey,
            returnHash,
            Duration.ofDays(7)  // TTL 7 天
        );

        // 5. 觸發後續流程
        if (returnRecord.needsApproval()) {
            // 發送審批通知
            producer.send(getChannelTopic(returnRecord), buildApprovalMessage(returnRecord));
        }
    }
}
```

**Hash 包含的欄位** (參考 REDIS_DEDUPLICATION.md)：
- `returnStatus` — 退貨狀態
- `reason` — 退貨原因
- `items` — 退貨項目（數量、SKU 等）
- `refundAmount` — 退款金額

**⚠️ Return Hash 定義應在 REDIS_DEDUPLICATION.md §3.2 中補充**（目前還未定義）

## 4. Handler 生命週期

### 4.1 初始化階段
```java
@PostConstruct
public void init() {
    // 1. 註冊到 Registry
    registry.register(this);

    // 2. 載入配置
    loadConfiguration();

    // 3. 初始化連線
    initializeConnections();
}
```

### 4.2 錯誤處理
```java
@Override
public void handle(TaskMessage message) {
    try {
        doHandle(message);
    } catch (RetryableException e) {
        // 可重試錯誤
        sendToFailedTopic(message, e, true);
    } catch (Exception e) {
        // 不可重試錯誤
        sendToDltTopic(message, e);
    }
}
```

## 5. Handler 配置管理

### 5.1 動態配置
```yaml
handlers:
  shopee:
    order-list:
      enabled: true
      max-retries: 3
      timeout: 30s
      batch-size: 50
    order-detail:
      enabled: true
      max-retries: 5
      timeout: 10s
      concurrent-requests: 3
```

### 5.2 Feature Toggle
```java
@ConditionalOnProperty(
    prefix = "handlers.shopee.order-list",
    name = "enabled",
    havingValue = "true"
)
@Component
public class ShopeeOrderListHandler {
    // Handler 實作
}
```

## 6. Handler 測試策略

### 6.1 單元測試
```java
@Test
public void testShopeeOrderListHandler() {
    // Given
    TaskMessage message = buildTestMessage();
    when(apiClient.getOrderList()).thenReturn(mockOrders());

    // When
    handler.handle(message);

    // Then
    verify(producer, times(2)).send(eq("order.process"), any());
    verify(producer, times(1)).send(eq("shopee.slow"), any());
}
```

### 6.2 整合測試
```java
@KafkaIntegrationTest
public void testOrderFlow() {
    // 發送 FETCH_ORDERS
    sendMessage("shopee.slow", fetchOrdersMessage());

    // 驗證 order.process 收到訊息
    ConsumerRecord<String, String> record =
        KafkaTestUtils.getSingleRecord(consumer, "order.process");

    assertThat(record.value()).contains("NEW_ORDER");
}
```

## 7. Handler 擴展指南

### 7.1 新增通路 Handler
1. 建立 `{Platform}OrderListHandler` 類別
2. 實作 `ChannelTaskHandler` 介面
3. 加上 `@ChannelHandler` 註解
4. 實作通路特定邏輯
5. 加入配置檔

### 7.2 新增 TaskType
1. 在 `CORE_CONTRACTS.md` 定義 TaskType
2. 建立對應 Handler 類別
3. 更新此註冊表
4. 加入測試案例