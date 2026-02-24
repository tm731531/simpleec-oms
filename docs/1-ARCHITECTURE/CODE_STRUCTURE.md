# SimpleEC OMS 代碼結構指南

> 目的：定義模組之間的邊界、命名規範、代碼組織方式

---

## 1. 多模組架構

### 1.1 模組依賴圖

```
simpleec-oms-app (主應用)
    ↓
simpleec-oms-kafka (消費者)
    ├─ 依賴 → simpleec-oms-core
    ├─ 依賴 → simpleec-oms-adapter
    └─ 依賴 → simpleec-oms-common

simpleec-oms-adapter (平台適配器)
    ├─ 依賴 → simpleec-oms-core
    └─ 依賴 → simpleec-oms-common

simpleec-oms-core (核心業務)
    └─ 依賴 → simpleec-oms-common

simpleec-oms-common (共用)
    └─ (無依賴)
```

### 1.2 各模組職責

| 模組 | 職責 | 不應該做的事 |
|------|------|------------|
| **common** | DTO, Enum, 工具函數 | 業務邏輯、DB 查詢 |
| **core** | Entity, Repository, Service | HTTP 調用、消息處理 |
| **adapter** | 平台 API 調用、數據轉換 | 業務邏輯、DB 修改 |
| **kafka** | 消息監聽、Handler | API 調用（應通過 Service）|
| **app** | Spring Boot 啟動、配置 | 業務邏輯、複雜計算 |

---

## 2. 命名規範

### 2.1 Package 結構

```
com.simpleec.oms
├─ common
│  ├─ constant         常數定義
│  ├─ dto              資料傳輸物件
│  ├─ enums            列舉
│  ├─ exception        自定義例外
│  └─ util             工具類
│
├─ core
│  ├─ entity           JPA 實體
│  ├─ repository       資料訪問層
│  ├─ service          業務邏輯層
│  └─ mapper           實體↔DTO 轉換
│
├─ adapter
│  ├─ base             Adapter 基類
│  ├─ momo             Momo 適配器
│  │  ├─ client        API 客戶端
│  │  ├─ dto           Momo 特定 DTO
│  │  └─ adapter       解析邏輯
│  ├─ shopee           Shopee 適配器
│  │  └─ ...
│  └─ cyberbiz         Cyberbiz 適配器
│     └─ ...
│
├─ kafka
│  ├─ config           Kafka 配置
│  ├─ handler          消息處理器
│  │  ├─ order         訂單相關
│  │  ├─ return        退貨相關
│  │  └─ ship          出貨相關
│  └─ message          消息定義
│
└─ app
   ├─ config           應用配置
   └─ OmsApplication   啟動類
```

### 2.2 命名約定

#### Java 類命名

```java
// Adapter
MomoOrderAdapter      // 平台 + 實體 + Adapter
ShopeeProductAdapter

// Handler
OrderUpsertHandler    // 實體 + 動作 + Handler
ReturnUpsertHandler
ShipOrderHandler

// Service
OrderService          // 實體 + Service
OrderShipmentService

// Repository
OrderRepository       // 實體 + Repository
RefundOrderRepository

// DTO
OrderData             // 實體 + Data (for Kafka)
ProcessOrderMessage   // 動作 + 實體 + Message (for Kafka)
MomoOrderResponse     // 平台 + 實體 + Response (from API)
```

#### 變數命名

```java
// 平台相關
String platformId = "momo";         // 平台識別
String channelId = "ch_momo_001";   // 通路實例 ID

// 訂單相關
String orderId = "ord_abc123";      // OMS 訂單 ID
String channelOrderId = "MOMO-001"; // 通路訂單 ID

// 規格相關
String channelProductId = "MOMO-SKU-001";  // 通路商品 ID
String channelSpecId = "MOMO-SPEC-001";    // 通路規格 ID
```

---

## 3. 分層架構詳細設計

### 3.1 Handler 層（Kafka Consumer）

```java
// kafka/handler/OrderUpsertHandler.java
@Component
@Slf4j
public class OrderUpsertHandler {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final ChannelShippingMappingRepository mappingRepository;

    @KafkaListener(topics = "order.process", groupId = "order-process-group")
    public void handle(ProcessOrderMessage message) {
        try {
            // 1. 驗證消息完整性
            validateMessage(message);

            // 2. 業務邏輯（委派給 Service）
            OrderInsertResult result = orderService.upsertOrder(message.getBody());

            // 3. 記錄成功
            log.info("Order {} processed successfully", result.getOrderId());

        } catch (ValidationException e) {
            // 驗證失敗 → DLT
            sendToDLT(message, e);
        } catch (Exception e) {
            // 未預期錯誤 → task.failed
            sendToFailedTopic(message, e);
        }
    }

    private void validateMessage(ProcessOrderMessage msg) {
        // 驗證邏輯
        // 不做業務邏輯，只做格式檢查
    }
}
```

### 3.2 Service 層（業務邏輯）

```java
// core/service/OrderService.java
@Service
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final ChannelShippingMappingRepository mappingRepository;

    public OrderInsertResult upsertOrder(OrderData orderData) {
        // 1. 檢查訂單是否存在
        Optional<Order> existing = orderRepository
            .findByChannelIdAndChannelOrderId(
                orderData.getChannelId(),
                orderData.getChannelOrderId()
            );

        if (existing.isPresent()) {
            // 2a. 訂單已存在 → 檢查 Hash
            return updateOrSkip(existing.get(), orderData);
        } else {
            // 2b. 新訂單 → INSERT
            return createNewOrder(orderData);
        }
    }

    private OrderInsertResult createNewOrder(OrderData orderData) {
        // 3. 建立訂單實體
        Order order = new Order();
        order.setId(generateNanoId());
        order.setChannelId(orderData.getChannelId());
        order.setChannelOrderId(orderData.getChannelOrderId());
        order.setBuyerName(orderData.getBuyerName());
        // ... 其他欄位

        // 4. 查詢物流公司（關鍵！）
        String logisticsCompany = resolveLogisticsCompany(
            orderData.getChannelId(),
            orderData.getShippingMethod()
        );

        // 5. 建立出貨單
        OrderShipment shipment = new OrderShipment();
        shipment.setOrderId(order.getId());
        shipment.setLogisticsCompany(logisticsCompany);
        shipment.setShippingStatus("PENDING");
        // ...

        // 6. 原子性保存（事務）
        order = orderRepository.save(order);
        shipmentRepository.save(shipment);

        return new OrderInsertResult(order.getId(), true);
    }

    private String resolveLogisticsCompany(String channelId, String shippingMethod) {
        // 查詢物流映射表
        Optional<ChannelShippingMapping> mapping =
            mappingRepository.findByChannelIdAndPlatformShippingMethod(
                channelId, shippingMethod
            );

        return mapping
            .map(ChannelShippingMapping::getLogisticsCompany)
            .orElse(null);  // 允許 null（記錄警告）
    }
}
```

### 3.3 Adapter 層（平台 API）

```java
// adapter/momo/MomoOrderAdapter.java
@Component
@Slf4j
public class MomoOrderAdapter implements OrderAdapter {

    private final MomoApiClient apiClient;

    @Override
    public List<OrderData> fetchOrders(String status) {
        // 1. 調用 Momo API（列表）
        List<MomoOrder> momoOrders = apiClient.listOrders(status);

        // 2. 判定 Mode（A 或 B）
        if (apiClient.hasDetailEndpoint()) {
            // Mode B：逐一獲取詳情
            return momoOrders.stream()
                .map(this::enrichWithDetail)
                .toList();
        } else {
            // Mode A：只用列表資料
            return momoOrders.stream()
                .map(this::toOrderData)
                .toList();
        }
    }

    private OrderData enrichWithDetail(MomoOrder momoOrder) {
        MomoOrderDetail detail = apiClient.getOrderDetail(momoOrder.getOrderId());
        return toOrderData(momoOrder, detail);
    }

    private OrderData toOrderData(MomoOrder momoOrder) {
        OrderData data = new OrderData();
        data.setChannelId("ch_momo");
        data.setChannelOrderId(momoOrder.getOrderId());
        data.setBuyerName(momoOrder.getBuyerName());
        // ... 轉換邏輯
        return data;
    }
}
```

### 3.4 Repository 層（資料訪問）

```java
// core/repository/OrderRepository.java
@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    // 查詢通路訂單
    Optional<Order> findByChannelIdAndChannelOrderId(
        String channelId, String channelOrderId
    );

    // 列表查詢（含排序）
    Page<Order> findByChannelId(String channelId, Pageable pageable);

    // 時間範圍查詢
    List<Order> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
```

---

## 4. 交叉關注點（Cross-cutting Concerns）

### 4.1 異常處理

```java
// common/exception/
├─ OmsException                    (基類)
├─ ValidationException             (驗證失敗)
├─ AdapterException                (API 呼叫失敗)
├─ DatabaseException               (DB 操作失敗)
└─ ConflictException               (數據衝突)
```

### 4.2 日誌記錄

```java
// 在每個層級記錄關鍵資訊

// Handler 層
log.info("Processing order message: requestId={}, orderId={}",
    message.getHeader().getRequestId(),
    message.getBody().getOrderData().getOrderId());

// Service 層
log.debug("Order {} hash matched, skipping update", orderId);
log.warn("Shipping method mapping not found: channel={}, method={}",
    channelId, shippingMethod);

// Adapter 層
log.info("Fetching orders from Momo API, mode={}", getMode());
log.error("Momo API error: status={}, message={}", statusCode, errorMsg);
```

### 4.3 監控指標

```java
// 在關鍵點注入 Micrometer 指標

@Timed(value = "oms.order.upsert", description = "Order upsert duration")
public OrderInsertResult upsertOrder(OrderData orderData) { ... }

Counter.builder("oms.order.validation.failure")
    .description("Order validation failures")
    .tag("reason", reason)
    .register(meterRegistry)
    .increment();
```

---

## 5. 測試位置與命名

```
src/test/java/com/simpleec/oms/
│
├─ handler/
│  ├─ OrderUpsertHandlerTest.java              (Unit)
│  └─ OrderUpsertHandlerIntegrationTest.java   (Integration)
│
├─ adapter/
│  ├─ momo/
│  │  ├─ MomoOrderAdapterTest.java             (Unit)
│  │  ├─ MomoOrderAdapterIntegrationTest.java  (Integration)
│  │  └─ MomoOrderAdapterVcrTest.java          (VCR)
│  └─ shopee/
│     └─ ...
│
├─ service/
│  ├─ OrderServiceTest.java                    (Unit)
│  └─ OrderServiceIntegrationTest.java         (Integration)
│
├─ common/
│  ├─ TestOrderBuilder.java                    (Test Fixture)
│  ├─ TestEventCaptor.java                     (Test Utility)
│  └─ TestDataFactory.java                     (Test Data)
│
├─ e2e/
│  └─ OrderManagementE2ETest.java              (E2E)
│
└─ resources/
   └─ fixtures/
      ├─ momo/
      │  ├─ order_response_single_item.json
      │  ├─ order_response_multi_items.json
      │  └─ ...
      └─ shopee/
         └─ ...
```

---

## 6. 代碼審查清單

### 6.1 提交 PR 前檢查

```
□ 代碼
  □ 遵循命名規範
  □ 沒有重複代碼
  □ 適當的注釋（複雜邏輯）
  □ 避免魔術數字

□ 測試
  □ 新代碼有對應的單元測試
  □ 測試覆蓋率 > 80%
  □ Integration 測試能通過

□ 設計
  □ 遵循分層架構
  □ 沒有跨層級的直接調用
  □ 異常處理恰當

□ 性能
  □ N+1 查詢已避免
  □ 適當的索引
  □ 沒有明顯的內存洩漏

□ 文檔
  □ 複雜邏輯已說明
  □ 破壞性改變已標註
```

### 6.2 常見問題

```
❌ 不應該：
  Service 層直接操作 Kafka 消息
  Handler 層包含複雜業務邏輯
  Adapter 層修改數據庫
  Repository 層做轉換邏輯

✅ 應該：
  Handler 層 → 驗證 + 委派給 Service
  Service 層 → 業務邏輯 + 編排
  Adapter 層 → API 調用 + 數據轉換
  Repository 層 → 單純 DB 查詢
```

---

## 7. 範例代碼流程

### 7.1 完整的訂單建立流程

```
1. Kafka 消息到達
   ProcessOrderMessage {
       header: { taskType: "PROCESS_ORDER", ... },
       body: { orderData: {...} }
   }

2. Handler 層接收
   OrderUpsertHandler.handle(message)
   ├─ validateMessage(message)        ← 驗證格式
   └─ orderService.upsertOrder(...)   ← 委派

3. Service 層處理
   OrderService.upsertOrder(orderData)
   ├─ 查詢現有訂單
   ├─ if 存在 → 檢查 Hash
   ├─ if 新訂單 → 建立
   │  ├─ Order entity 初始化
   │  ├─ 查詢物流映射 ← 重要！
   │  │  (orderRepository + mappingRepository)
   │  ├─ OrderShipment entity 初始化
   │  └─ 保存到 DB
   └─ return OrderInsertResult

4. 結果記錄
   ✅ 成功 → 記錄日誌
   ❌ 失敗 → 發送到 task.failed topic
```

---

**文件日期**：2026-02-20
**相關文件**：IMPLEMENTATION_PLAN.md
