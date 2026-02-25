# Queue 消息生成規範 v1.0

> 如何在代碼中正確生成每個 TaskType 的消息
> 確保 Header 和 Body 的一致性

---

## Header 生成通用規則

所有消息的 Header 遵循以下規則：

```java
{
  "taskType": "由調用者決定（見下表）",
  "merchantId": "固定值（配置中讀取，通常是 M001）",
  "platformId": "通路編號（momo/shopee/yahoo/pchome/cyberbiz/easystore）",
  "channelId": "通路實例（MOMO_001, SHOPEE_002 等）",
  "requestId": "由生成方生成（UUID 或自定義格式）",
  "timestamp": "ISO-8601 格式（UTC）",
  "source": "由生成方決定（scheduler/channel_job/api/webhook）",
  "version": 1,
  "retryCount": 0,
  "priority": "根據 TaskType 決定（HIGH/NORMAL/LOW）",
  "correlationId": "可選，用於串聯多個消息"
}
```

### Header 字段詳解

| 字段 | 規則 | 例子 |
|------|------|------|
| `taskType` | 由調用方決定，見 TaskType 列表 | FETCH_ORDERS |
| `merchantId` | **動態值**，來自具體訂單/請求的商家 ID | M001 |
| `platformId` | 通路編號（小寫） | momo |
| `channelId` | 通路實例 ID | MOMO_001 |
| `requestId` | UUID 或「來源-時間-序列號」格式 | sched-20260213-100001 |
| `timestamp` | **一路帶下去**，不重新生成（見規則） | 2026-02-13T10:00:00Z |
| `source` | scheduler, channel_job, api, webhook, manual | scheduler |
| `version` | 固定值 1（未來有破壞性改動才升級） | 1 |
| `retryCount` | 初始為 0，失敗後遞增 | 0 |
| `priority` | HIGH, NORMAL, LOW | NORMAL |
| `correlationId` | 可選，用於串聯 list→detail→process | sched-20260213-fetch-001 |

#### timestamp 傳遞規則（重要！）

```
原則：timestamp 代表「這個事件是為了處理什麼時間的事」，一旦設定就不變

來源分為兩種：

1. Scheduler 心跳（DISPATCH_ORDER_FETCH 等）：
   ├─ 心跳發送：timestamp = 2026-02-13T10:00:00Z
   ├─ 發到 {platform}.slow：帶同一個 timestamp
   ├─ Channel Job 發 FETCH_ORDER_DETAIL：帶同一個 timestamp
   └─ 最後發 PROCESS_ORDER：帶同一個 timestamp

2. API/UI 發起（如人工審核返回訂單）：
   ├─ UI 發起：timestamp = 2026-02-13T10:05:23Z
   ├─ 發到 task.backend：帶同一個 timestamp
   └─ 所有下游都帶同一個 timestamp

╔════════════════════════════════════════════════════════╗
║ ✅ DO：timestamp 一路帶下去                              ║
║ ❌ DON'T：在每個環節重新生成 Instant.now()             ║
╚════════════════════════════════════════════════════════╝
```

#### 列表 Body 批量限制規則（性能優化）

```
原則：如果 Body 中包含列表（orders, products 等），
      每個消息最多包含 10 條記錄

應用場景：
- FETCH_ORDER_DETAIL：orders 陣列 ≤ 10
- SYNC_PACK_DETAIL：products 陣列 ≤ 10
- 其他列表型 TaskType：≤ 10

實施方式：
如果有超過 10 條，分批發送多個消息（每個消息 ≤ 10 條）

╔════════════════════════════════════════════════════════╗
║ ✅ DO：分批發送，每個消息 ≤ 10 條                       ║
║ ❌ DON'T：一個消息包含 100+ 條記錄                     ║
╚════════════════════════════════════════════════════════╝
```

---

## Body 生成規則（按 TaskType）

### 訂單相關

#### 1. FETCH_ORDERS（訂單列表拉取）

**來源**：Scheduler

**Body 特性**：**空白**（只是觸發）

```json
{
  "body": {
    "fetchSpec": {}
  }
}
```

**生成方式**：
```java
KafkaMessage msg = new KafkaMessage();
msg.header = buildHeader(taskType="FETCH_ORDERS", ...);
msg.body = new FetchOrdersBody();  // 空白
```

**Channel Job 接收後**：
```
讀取 header.timestamp，自主決策時間窗口（不在 body 中）
```

---

#### 2. FETCH_ORDER_DETAIL（訂單詳情拉取）

**來源**：Channel Job（LIST 階段判斷需要詳情時）

**Body 特性**：訂單編號清單

```json
{
  "body": {
    "orders": [
      {
        "channelOrderId": "MOMO-2026021300001"
      },
      {
        "channelOrderId": "MOMO-2026021300002"
      }
    ]
  }
}
```

**生成方式**（在 ChannelJob 中）：
```java
// 步驟 1：找出需要詳情的訂單
List<String> needsDetail = detectDifference(sellPacksFromAPI);  // 查 DB，對比差異

// 步驟 2：分批發送（每個消息最多 10 條）← 性能優化
List<List<String>> batches = partition(needsDetail, 10);

for (List<String> batch : batches) {
  List<FetchOrderDetailRequest> orders = batch
    .stream()
    .map(channelOrderId -> new FetchOrderDetailRequest(channelOrderId))
    .collect(toList());

  KafkaMessage msg = new KafkaMessage();
  msg.header = buildHeader(
    taskType="FETCH_ORDER_DETAIL",
    timestamp=extractedHeader.getTimestamp(),       // ← 一路帶下去
    merchantId=extractedHeader.getMerchantId(),     // ← 一路帶下去
    correlationId="sched-20260213-fetch-001",       // 關聯到原始 LIST
    requestId=generateRequestId("channel_job"),     // 每個批次有不同的 requestId
    ...
  );
  msg.body = new FetchOrderDetailBody(orders);

  // 發送到 Kafka
  kafkaTemplate.send("{platform}.slow", msg);
}

// 輔助方法：分批
private <T> List<List<T>> partition(List<T> list, int size) {
  List<List<T>> result = new ArrayList<>();
  for (int i = 0; i < list.size(); i += size) {
    result.add(list.subList(i, Math.min(i + size, list.size())));
  }
  return result;
}
```

---

#### 3. PROCESS_ORDER（訂單處理）

**來源**：Channel Job（完成 LIST/DETAIL 後）

**Body 特性**：**完整訂單資料**

```json
{
  "body": {
    "orderData": {
      "orderId": "ord_abc123def456",
      "channelOrderId": "MOMO-2026021300001",
      "orderStatus": "PENDING",
      "buyerName": "[加密]",
      "buyerPhone": "[加密]",
      "buyerEmail": "[加密]",
      "shippingAddress": "[加密]",
      "shippingMethod": "HOME_DELIVERY",
      "shippingStatus": "PENDING",
      "paymentMethod": "CREDIT_CARD",
      "totalAmount": 43900.00,
      "shippingFee": 0.00,
      "discountAmount": 1000.00,
      "channelCreatedAt": "2026-02-13T09:30:00Z",
      "paidAt": "2026-02-13T09:31:00Z",
      "items": [
        {
          "sku": "IPHONE-15-PRO-MAX",
          "productId": "pd_xyz789",
          "channelProductId": "MOMO-SKU-001",
          "channelSpecId": "MOMO-SPEC-001",
          "channelItemId": "MOMO-ITEM-2026021300001",
          "channelProductName": "iPhone 15 Pro Max",
          "channelSpecName": "太空黑/256GB",
          "productName": "iPhone 15 Pro Max",
          "quantity": 1,
          "unitPrice": 44900.00,
          "subtotal": 44900.00,
          "sellPackId": "sp_abc123"
        }
      ]
    }
  }
}
```

**生成方式**（在 ChannelJob 中）：
```java
// 前提：已經收到 FETCH_ORDERS 消息，extractedHeader 包含原始 timestamp 和 merchantId

// 步驟 1：解析平台 API 響應，轉換為 OMS 結構
Order order = parseAndConvert(platformResponse);

// 步驟 2：加密 PII 字段
order.buyerName = encryptPII(order.buyerName);
order.buyerPhone = encryptPII(order.buyerPhone);
order.buyerEmail = encryptPII(order.buyerEmail);
order.shippingAddress = encryptPII(order.shippingAddress);

// 步驟 3：生成 orderId（如果是新訂單）
if (order.orderId == null) {
  order.orderId = IdGenerator.nextId();
}

// 步驟 4：決定是否是回補訂單（isRollback）
boolean isRollback = determineIfRollback(order);  // 根據業務規則判斷

// 步驟 5：構建消息（重要：使用原始 timestamp 和 merchantId）
KafkaMessage msg = new KafkaMessage();
msg.header = buildHeader(
  taskType="PROCESS_ORDER",
  merchantId=extractedHeader.getMerchantId(),     // ← 不變
  platformId=extractedHeader.getPlatformId(),
  channelId=extractedHeader.getChannelId(),
  timestamp=extractedHeader.getTimestamp(),       // ← 不變，一路帶下去
  source="channel_job",
  priority="NORMAL",
  correlationId=extractedHeader.getCorrelationId()
);
msg.body = new ProcessOrderBody(order);
msg.header.setIsRollback(isRollback);            // ← 額外標籤

// 步驟 6：發送到 Kafka
kafkaTemplate.send("order.process", msg);
```

**核心檢查清單**：
- [ ] 所有 PII 字段已加密
- [ ] channelSpecId 已正確填充
- [ ] items 陣列完整
- [ ] 金額欄位（totalAmount, shippingFee, discountAmount）都有值

---

### 商品相關

#### 1. SYNC_PACK_LIST（商品列表同步）

**來源**：Scheduler

**Body 特性**：**空白**（只是觸發）

```json
{
  "body": {
    "syncSpec": {}
  }
}
```

**生成方式**：
```java
KafkaMessage msg = new KafkaMessage();
msg.header = buildHeader(taskType="SYNC_PACK_LIST", priority="HIGH", ...);
msg.body = new SyncPackListBody();  // 空白
```

**Channel Job 接收後**：
```
呼叫平台 LIST API，獲得所有產品
與 DB SellPack 表對比差異
只有有差異的產品進入 DETAIL
```

---

#### 2. SYNC_PACK_DETAIL（商品詳情同步）

**來源**：Channel Job（LIST 階段判斷有差異時）

**Body 特性**：只含有差異的產品 ID 清單

```json
{
  "body": {
    "products": [
      {
        "channelProductId": "CYBER-SKU-001"
      },
      {
        "channelProductId": "CYBER-SKU-002"
      }
    ]
  }
}
```

**生成方式**（在 ChannelJob 中）：
```java
// 步驟 1：呼叫 LIST API，取得所有產品
List<SellPackDTO> allProducts = adapter.fetchProductList(channelId);

// 步驟 2：與 DB 對比，找出有差異的產品
List<String> changedProductIds = allProducts.stream()
  .filter(prod -> hasChanged(channelId, prod))  // 對比價格、庫存、狀態
  .map(prod -> prod.getChannelProductId())
  .collect(toList());

// 步驟 3：分批發送（每個消息最多 10 個產品）← 性能優化
List<List<String>> batches = partition(changedProductIds, 10);

for (List<String> batch : batches) {
  List<SyncPackDetailRequest> products = batch.stream()
    .map(id -> new SyncPackDetailRequest(id))
    .collect(toList());

  // 步驟 4：構建消息
  KafkaMessage msg = new KafkaMessage();
  msg.header = buildHeader(
    taskType="SYNC_PACK_DETAIL",
    timestamp=extractedHeader.getTimestamp(),       // ← 一路帶下去
    merchantId=extractedHeader.getMerchantId(),     // ← 一路帶下去
    correlationId="sched-20260213-synclist-001",    // 關聯到原始 LIST
    requestId=generateRequestId("channel_job"),     // 每個批次有不同的 requestId
    priority="HIGH",
    ...
  );
  msg.body = new SyncPackDetailBody(products);

  // 發送到 Kafka
  kafkaTemplate.send("{platform}.slow", msg);
}
```

**核心設計**：
- 「差異檢測」在 LIST 階段完成，不在 DETAIL 中
- 只發送需要詳情的產品，節省 API 呼叫

---

#### 3. SYNC_PACK_COMPLETE（商品資料完成）

**來源**：Channel Job（完成 DETAIL 後）

**Body 特性**：**完整商品資料**

```json
{
  "body": {
    "products": [
      {
        "channelProductId": "CYBER-SKU-001",
        "channelSpecId": "CYBER-SPEC-001",
        "sku": "CYBER-SKU-001",
        "productName": "Samsung 55吋 QLED 電視",
        "specName": "55吋/黑色",
        "description": "高端電視...",
        "sellingPrice": 24999,
        "originalPrice": 29999,
        "quantity": 150,
        "status": "active",
        "imageUrl": "https://..."
      }
    ]
  }
}
```

**生成方式**（在 ChannelJob 中）：
```java
// 步驟 1：呼叫 DETAIL API，取得完整商品資訊
Map<String, DetailResponse> details = adapter.fetchProductDetails(channelId, changedProductIds);

// 步驟 2：轉換為 OMS 結構
List<SellPack> products = details.entrySet().stream()
  .map(entry -> {
    String channelProductId = entry.getKey();
    DetailResponse detail = entry.getValue();

    SellPack pack = new SellPack();
    pack.setChannelProductId(channelProductId);
    pack.setChannelSpecId(detail.getSpecId());  // ← 在此階段提取
    pack.setSellingPrice(detail.getPrice());
    pack.setQuantity(detail.getStock());
    pack.setStatus("active");  // 轉換平台狀態為 OMS 標準狀態
    ...
    return pack;
  })
  .collect(toList());

// 步驟 3：構建消息
KafkaMessage msg = new KafkaMessage();
msg.header = buildHeader(
  taskType="SYNC_PACK_COMPLETE",
  correlationId="sched-20260213-synclist-001",
  ...
);
msg.body = new SyncPackCompleteBody(products);
```

**核心檢查清單**：
- [ ] 所有產品都有 channelSpecId
- [ ] 狀態已轉換為 OMS 標準狀態（active/inactive/draft）
- [ ] 價格和庫存都已填充

---

## 實用代碼範本

### Header 構建函數

```java
private KafkaMessageHeader buildHeader(
    String taskType,
    String merchantId,              // ← 動態值，來自訂單/請求
    String platformId,
    String channelId,
    String timestamp,               // ← 不重新生成，傳入既有值
    String source,
    String priority,
    String correlationId
) {
    return KafkaMessageHeader.builder()
        .taskType(taskType)
        .merchantId(merchantId)     // ← 動態傳入
        .platformId(platformId)
        .channelId(channelId)
        .requestId(generateRequestId(source))
        .timestamp(timestamp)       // ← 使用傳入的值，不生成新的
        .source(source)
        .version(1)
        .retryCount(0)
        .priority(priority != null ? priority : "NORMAL")
        .correlationId(correlationId)
        .build();
}

private String generateRequestId(String source) {
    return String.format("%s-%s-%d",
        source,
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")),
        System.nanoTime() % 10000
    );
}
```

**使用範例**（PROCESS_ORDER）：

```java
// 接收來自 Channel Job 的數據，保持原始 timestamp 和 merchantId
String originalTimestamp = orderData.getHeader().getTimestamp();
String originalMerchantId = orderData.getHeader().getMerchantId();

KafkaMessage msg = new KafkaMessage();
msg.header = buildHeader(
    "PROCESS_ORDER",
    originalMerchantId,         // ← 用原始商家 ID
    platformId,
    channelId,
    originalTimestamp,          // ← 用原始 timestamp，不生成新的
    "channel_job",
    "NORMAL",
    correlationId
);
msg.body = new ProcessOrderBody(order);
```

### 差異檢測函數

```java
private boolean hasChanged(String channelId, SellPackDTO newProduct) {
    // 查詢 DB 中現有的 SellPack
    SellPack existing = sellPackRepository
        .findByChannelAndProductId(channelId, newProduct.getChannelProductId(), null)
        .orElse(null);

    if (existing == null) {
        return true;  // 新產品
    }

    // 對比欄位
    boolean priceChanged = !Objects.equals(
        existing.getSellingPrice(),
        newProduct.getSellingPrice()
    );

    boolean quantityChanged = !Objects.equals(
        existing.getQuantity(),
        newProduct.getQuantity()
    );

    boolean statusChanged = !Objects.equals(
        existing.getStatus(),
        "active"
    );

    return priceChanged || quantityChanged || statusChanged;
}
```

---

## 檢查清單

### 生成任何消息前

- [ ] Header 中的 `taskType` 正確？
- [ ] `merchantId` 從配置讀取？
- [ ] `platformId` 和 `channelId` 都有值？
- [ ] `requestId` 唯一且可追蹤？
- [ ] `timestamp` 是 UTC ISO-8601 格式？
- [ ] `source` 準確反映消息來源？
- [ ] `version` 是 1？

### 生成 PROCESS_ORDER 前

- [ ] 所有 PII 字段已加密？
- [ ] `channelSpecId` 已填充？
- [ ] `items` 陣列非空？
- [ ] 金額欄位都有值？
- [ ] `isRollback` 標籤已設置？

### 生成 SYNC_PACK_DETAIL 前

- [ ] 只包含有差異的產品 ID？
- [ ] 產品 ID 清單非空？
- [ ] `correlationId` 指向原始 LIST？

### 生成 SYNC_PACK_COMPLETE 前

- [ ] 所有產品都有 `channelSpecId`？
- [ ] 狀態已轉換為標準值？
- [ ] 價格和庫存都已填充？

---

## 更新日誌

| 日期 | 版本 | 變更 |
|------|------|------|
| 2026-02-25 | 1.0 | 初始版本：Header/Body 生成規則、代碼範本 |

