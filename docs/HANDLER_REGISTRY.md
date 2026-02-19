# SimpleEC OMS Handler 註冊表

## 0. Heartbeat + Scheduler 驅動模型

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
| ORDER_UPSERT | OrderUpsertHandler | order.process | **新建或更新訂單**（內部根據 orderId 存在性判定） |
| CANCEL_ORDER | CancelOrderHandler | order.process | 取消訂單 |
| ORDER_STATUS_CHANGE | OrderStatusHandler | order.process | 狀態變更 |

#### Return Process Job
| TaskType | Handler Class | Topic | 說明 |
|----------|--------------|-------|------|
| RETURN_UPSERT | ReturnUpsertHandler | return.process | **新建或更新退貨**（內部根據 returnId 存在性判定） |
| APPROVE_RETURN | ApproveReturnHandler | return.process | 同意退貨 |
| REJECT_RETURN | RejectReturnHandler | return.process | 拒絕退貨 |

#### Backend Job
| TaskType | Handler Class | Topic | 說明 |
|----------|--------------|-------|------|
| SYNC_PRODUCT | SyncProductHandler | task.backend | **獨立**：建立 Product（從 SKU 聚合） |
| SYNC_PACK | SyncPackHandler | task.backend | **獨立**：建立/更新 Pack 及其 Product 映射 |
| UPDATE_INVENTORY | UpdateInventoryHandler | task.backend | 更新庫存 |
| UPDATE_PRICE | UpdatePriceHandler | task.backend | 更新價格 |
| SHIP_ORDER | ShipOrderHandler | task.backend | 執行出貨（後端自動或用戶手動） |

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

### 3.2 Order Process Handler (Upsert Pattern)
```java
@Component
public class OrderUpsertHandler implements TaskHandler {

    private final OrderService orderService;
    private final OrderMapper orderMapper;

    @Override
    public String getTaskType() {
        return "ORDER_UPSERT";
    }

    @Override
    public void handle(TaskMessage message) {
        OrderUpsertRequest request = parseBody(message.getBody(), OrderUpsertRequest.class);

        // 1. 檢查是否已存在（根據 channelOrderId）
        Optional<Order> existing = orderService.findByChannelOrderId(
            request.getChannelId(),
            request.getChannelOrderId()
        );

        Order order;
        if (existing.isPresent()) {
            // 2A. 更新現有訂單
            order = existing.get();
            order.update(orderMapper.fromChannelData(
                message.getHeader().getChannelId(),
                request.getOrderData()
            ));
            log.info("Updated order: {} from channel {}",
                order.getOrderId(), request.getChannelId());
        } else {
            // 2B. 建立新訂單
            order = orderMapper.fromChannelData(
                message.getHeader().getChannelId(),
                request.getOrderData()
            );
            log.info("Created new order: {} from channel {}",
                order.getOrderId(), request.getChannelId());
        }

        // 3. 儲存訂單（insert or update）
        orderService.save(order);

        // 4. 觸發後續流程
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