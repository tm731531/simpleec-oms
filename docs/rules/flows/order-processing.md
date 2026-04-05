# Flow: Order Processing（訂單處理）

訂單處理流程：Scheduler 觸發 → Channel Job 從各平台抓取訂單 → Kafka → OMS 處理並寫入 DB。

---

## 1. 業務規則

### 1.1 OMS 是被動接收方
- OMS **接受**平台傳來的任何訂單狀態，**不主動拒絕**任何狀態轉換
- 平台是訂單狀態的權威來源；OMS 只是 mirror
- 不做狀態機驗證（例如：不拒絕從 SHIPPED 變回 PENDING）

### 1.2 收入歸屬（isRollback）
- `isRollback = false`（正常）：收入計算在**今天**，`stats.revenue += order.total`
- `isRollback = true`（補單/回填）：收入追溯至 `order.channelCreatedAt`，stats 標記為 backfill，不計入今日統計

### 1.3 去重（Deduplication）規則
同一訂單可能被多次抓取（不同時間窗口重疊），需做 dedup：

| 情況 | 處理方式 |
|------|---------|
| `channelOrderId` 不存在 | INSERT 新訂單 |
| `channelOrderId` 存在，hash 相同 | **Skip**（完全跳過） |
| `channelOrderId` 存在，hash 不同 | **UPDATE** 現有訂單 |

Hash = SHA256 of sorted JSON of **mutable fields**（狀態、金額、物流等）

### 1.4 可空欄位（Nullable Fields）
以下欄位在訂單初期可能不存在，OMS 必須接受 null，後續狀態更新時填入：
- `tracking_number`（出貨後才有）
- `escrow_amount`（結算後才有）
- `actual_shipping_fee`（到貨後確認）
- `pay_time`（付款後才有）

### 1.5 狀態變更記錄
每次訂單狀態變更，必須在 `order_status_logs` 插入一筆新紀錄，**永遠不覆蓋舊紀錄**。

### 1.6 PII 加密
`buyer_name`、`buyer_phone`、`buyer_email`、`shipping_address` 必須在寫入 DB 前加密。

---

## 2. 前端 / API

### 2.1 列表 Endpoint
```
GET /api/orders
```
Controller: `OrderController`

Query parameters:
| 參數 | 類型 | 說明 |
|-----|------|------|
| `status` | string | 訂單狀態篩選 |
| `channelId` | NanoID | 指定 channel |
| `dateFrom` | ISO-8601 | 建立時間起 |
| `dateTo` | ISO-8601 | 建立時間迄 |
| `merchantId` | NanoID | 商家篩選（admin 用） |
| `page` | int | 頁碼（default: 0） |
| `size` | int | 每頁筆數（default: 20） |

### 2.2 詳情 Endpoint
```
GET /api/orders/{id}
```

### 2.3 Response 結構
```json
{
  "id": "order_nanoid_20chars",
  "channelOrderId": "platform_specific_id",
  "status": "READY_TO_SHIP",
  "channelCreatedAt": "2026-03-17T08:00:00Z",
  "buyer": {
    "name": "張小明",
    "phone": "0912345678",
    "email": "buyer@example.com",
    "shippingAddress": "台北市信義區..."
  },
  "items": [...],
  "financials": {
    "totalAmount": 1500,
    "shippingFee": 60,
    "escrowAmount": null,
    "actualShippingFee": null,
    "payTime": null
  },
  "trackingNumber": null
}
```

- PII 欄位（`buyer.*`）在 API 層解密後回傳
- `null` 欄位合法，前端需處理

### 2.4 PII 解密
```java
// OrderController 或 OrderService
EncryptionContext.setMerchantId(merchantId);
Order decrypted = encryptionService.decrypt(order);
```

---

## 3. Kafka 契約

### 3.1 Scheduler → Channel Job（觸發訊息）

Topic: `scheduler`

```json
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "merchantId": "merch_nanoid_20chars",
    "platformId": "platform_nanoid_20ch",
    "channelId": "channel_nanoid_20chr",
    "requestId": "uuid-v4",
    "timestamp": "2026-03-17T08:00:00Z",
    "source": "scheduler",
    "version": "1.0",
    "isRollback": false
  },
  "body": {
    "timestamp": "2026-03-17T08:00:00Z"
  }
}
```

**⚠️ 關鍵規則：`body` 只有 `timestamp`，絕不含 `from`/`to` 時間範圍。**
時間窗口由 Channel Job 內部自主計算。

### 3.2 Channel Job → Handler（訂單資料訊息）

Topic: `{platform}.slow`（例如：`shopee.slow`、`shopify.slow`）

TaskType: `ORDER_UPSERT`

```json
{
  "header": {
    "taskType": "ORDER_UPSERT",
    "merchantId": "merch_nanoid_20chars",
    "platformId": "platform_nanoid_20ch",
    "channelId": "channel_nanoid_20chr",
    "requestId": "uuid-v4",
    "timestamp": "2026-03-17T08:00:00Z",
    "source": "channel_job",
    "version": "1.0",
    "isRollback": false
  },
  "body": {
    "orderData": {
      "channelOrderId": "shopee_order_id_123",
      "orderId": null,
      "status": "READY_TO_SHIP",
      "channelCreatedAt": "2026-03-17T06:00:00Z",
      "totalAmount": 1500,
      "shippingFee": 60,
      "trackingNumber": null,
      "escrowAmount": null,
      "payTime": null
    },
    "items": [
      {
        "channelItemId": "item_001",
        "sellPackId": null,
        "productName": "商品A",
        "quantity": 2,
        "unitPrice": 750
      }
    ],
    "isRollback": false
  }
}
```

**Inbound Exception（入站例外）：**
- `body.orderData.channelOrderId` **一定要有**（平台原始訂單號）
- `body.orderData.orderId`（OMS NanoID）**可以為 null**：這是新訂單，OMS 尚未分配 ID
- `body.items[].sellPackId` 也可為 null：Channel Job 不做 product mapping，由 Handler 負責

### 3.3 Consumer
`OrderUpsertConsumer` → `OrderUpsertHandler`

---

## 4. Channel Job

### 4.1 時間窗口計算規則
Channel Job 使用 `header.timestamp` 作為基準時間計算窗口，**絕不呼叫 `Instant.now()`**。

```java
// ✅ 正確
Instant baseTime = Instant.parse(header.getTimestamp());
Instant from = baseTime.minus(Duration.ofHours(1));

// ❌ 錯誤：回填時 Instant.now() 會造成時間計算錯誤
Instant from = Instant.now().minus(Duration.ofHours(1));
```

### 4.2 各平台抓單策略

**Shopee：**
- 多個狀態窗口並行抓取（避免遺漏）：
  - `UNPAID`：最近 1 小時
  - `READY_TO_SHIP`：最近 3 天
  - `SHIPPED`：最近 5 天
  - `COMPLETED`：最近 7 天
- `get_order_list` 只回傳 order_sn，需再呼叫 `get_order_detail`（max 50/call）取完整資料
- `get_order_detail` 必須帶 `optional_fields`：items、地址、付款資訊

**Cyberbiz：**
- 兩條 query 合併：
  - `created_at_min` = 7 天前（抓新訂單）
  - `updated_at_min` = 1 天前（抓更新訂單）
- 合併結果後，以 `order_id` dedup；`updated_at` 較新的版本勝出
- 每個訂單需個別呼叫 `GET /v1/orders/{id}` 取完整資料

**Yahoo：**
- `updated_after` = 1 天前
- 單次 API 回傳完整資料，不需要 detail call

**easystore：**
- 7 天時間窗口，每批最多 50 筆完整訂單（list 即含完整資料）
- 使用 offset pagination

### 4.3 格式轉換
Channel Job 負責將各平台原始訂單格式轉換為 OMS unified structure **再送 Kafka**。
- 不允許把平台原始 JSON 直接丟進 ORDER_UPSERT
- `channelCreatedAt` 必須轉為 ISO-8601 UTC

### 4.4 平台 ID 保留規則
Channel Job **保留** `channelOrderId`（平台訂單號）；
Channel Job **不負責** 將 `channelOrderId` 轉換為 OMS `orderId`（那是 Handler 的事）。

---

## 5. 後端 Job

### 5.1 Consumer 流程
```
OrderUpsertConsumer.consume(KafkaMessage)
  → OrderUpsertHandler.handle(OrderUpsertBody)
    → 1. Redis dedup check
    → 2. DB fallback dedup (if Redis miss/fail)
    → 3. Decide: skip / insert / update
    → 4. Encrypt PII
    → 5. Write to DB
    → 6. Insert order_status_logs (if status changed)
    → 7. isRollback handling
```

### 5.2 Dedup 邏輯

```java
String hashKey = "dedup:" + channelId + ":" + channelOrderId + ":" + orderHash;

// Step 1: Redis check
String cached = redis.get(hashKey);
if (cached != null) {
    // same hash → skip
    return;
}

// Step 2: DB fallback（Redis 故障時不阻塞）
Optional<Order> existing = orderRepo.findByChannelOrderId(channelId, channelOrderId);
if (existing.isPresent()) {
    String dbHash = existing.get().getOrderHash();
    if (dbHash.equals(orderHash)) {
        // same hash → skip，但補寫 Redis
        redis.set(hashKey, "1", Duration.ofHours(24));
        return;
    } else {
        // different hash → UPDATE
        updateOrder(existing.get(), body);
    }
} else {
    // not found → INSERT
    insertOrder(body);
}

// 最後更新 Redis
redis.set(hashKey, "1", Duration.ofHours(24));
```

**Redis 故障處理：**
```java
try {
    // Redis 操作
} catch (RedisException e) {
    log.warn("Redis dedup unavailable, falling back to DB: {}", e.getMessage());
    // 繼續走 DB fallback，不丟擲例外
}
```

### 5.3 PII 加密
```java
// 在 DB write 之前
EncryptionContext.setMerchantId(merchantId);
order.setBuyerName(encryptionService.encrypt(order.getBuyerName()));
order.setBuyerPhone(encryptionService.encrypt(order.getBuyerPhone()));
order.setBuyerEmail(encryptionService.encrypt(order.getBuyerEmail()));
order.setShippingAddress(encryptionService.encrypt(order.getShippingAddress()));
```

### 5.4 isRollback 處理
```java
if (body.isRollback()) {
    // 收入歸屬日期 = channelCreatedAt，不是今天
    order.setRevenueDate(order.getChannelCreatedAt().toLocalDate());
    order.setIsBackfill(true);
} else {
    order.setRevenueDate(LocalDate.now());
    order.setIsBackfill(false);
}
```

### 5.5 狀態變更日誌（QA-C6）
`handleOrderUpsert()` 在訂單寫入 DB 後，嘗試寫入 `order_status_logs`。

**觸發條件：**

| 情況 | fromStatus | toStatus | 寫入？ |
|------|-----------|---------|-------|
| INSERT（新訂單） | `null` | `<新狀態>` | ✅ 寫入 |
| UPDATE，status 有變 | `<舊狀態>` | `<新狀態>` | ✅ 寫入 |
| UPDATE，status 未變 | — | — | ❌ 不寫入 |

**欄位：**
```java
OrderStatusLog.builder()
    .id(NanoIdUtil.generate())
    .orderId(savedOrder.getId())
    .fromStatus(isNewOrder ? null : oldStatus)   // INSERT 時為 null
    .toStatus(newStatus)
    .operator("system")
    .remark("ORDER_UPSERT from channel " + channelId)
    .build();
```

**非致命性：** 寫入失敗只記錄 `WARN` 日誌，不影響訂單 upsert 的整體成功。

---

## 6. DB

### 6.1 相關資料表

| 資料表 | 用途 |
|-------|------|
| `orders` | 主訂單表，每個平台訂單一筆 |
| `order_status_logs` | 狀態變更歷史，只 INSERT，不 UPDATE |

### 6.2 orders 表關鍵欄位

```sql
-- 訂單主表（精簡示意）
CREATE TABLE orders (
    id                  VARCHAR(20) PRIMARY KEY,  -- OMS NanoID
    channel_id          VARCHAR(20) NOT NULL,
    channel_order_id    VARCHAR(255) NOT NULL,     -- 平台訂單號
    order_hash          VARCHAR(64),               -- SHA256 dedup hash
    status              VARCHAR(50),
    channel_created_at  TIMESTAMPTZ NOT NULL,
    revenue_date        DATE,
    is_backfill         BOOLEAN DEFAULT FALSE,

    -- PII 加密欄位
    buyer_name          TEXT,   -- encrypted
    buyer_phone         TEXT,   -- encrypted
    buyer_email         TEXT,   -- encrypted
    shipping_address    TEXT,   -- encrypted

    -- 後期才會有值的欄位（允許 NULL）
    tracking_number     VARCHAR(255),
    escrow_amount       NUMERIC(12,2),
    actual_shipping_fee NUMERIC(10,2),
    pay_time            TIMESTAMPTZ,

    -- items 存 JSONB
    items               JSONB,

    created_at          TIMESTAMPTZ DEFAULT NOW(),
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE UNIQUE INDEX uix_orders_channel_order
    ON orders (channel_id, channel_order_id);
```

### 6.3 nullable 欄位規則
- **永遠不假設** `tracking_number`、`escrow_amount` 等欄位有值
- 寫入時允許 null，讀取時做 null-safe 處理
- 後續狀態更新帶入這些欄位時做 UPDATE

### 6.4 order_status_logs
```sql
CREATE TABLE order_status_logs (
    id          VARCHAR(20) PRIMARY KEY,
    order_id    VARCHAR(20) NOT NULL REFERENCES orders(id),
    from_status VARCHAR(50),
    to_status   VARCHAR(50) NOT NULL,
    changed_at  TIMESTAMPTZ DEFAULT NOW(),
    source      VARCHAR(50)   -- 'ORDER_UPSERT', 'MANUAL', etc.
);
```

**規則：只 INSERT，絕不 DELETE 或 UPDATE。**

### 6.5 查詢時 PII 解密
Service 層讀取 orders 後，需在回傳前解密：
```java
// 必須先設 merchantId，才能用對應的 key 解密
EncryptionContext.setMerchantId(order.getMerchantId());
```

---

## 7. Platform API

各平台訂單抓取 API 差異：

| 平台 | List API | 需要 Detail call？ | Pagination | 時間篩選參數 |
|------|---------|------------------|------------|-------------|
| Shopee | `GET /api/v2/order/get_order_list` | **是**（`get_order_detail` max 50/call，需帶 `optional_fields`） | cursor | `create_time` 或 `update_time` |
| Cyberbiz | `GET /v1/orders?created_at_min=...` | **是**（每筆個別 `GET /v1/orders/{id}`） | time slice | `created_at` 和 `updated_at` 分開查 |
| Shopline | `POST /orders/search` | 否（list 包含完整資料） | cursor（`previous_id`） | `created_before`/`created_after` |
| easystore | `GET /orders` | 否（每批 50 筆完整資料） | offset | `from_date`/`to_date` |

### 7.1 Shopee get_order_detail 必帶 optional_fields
```java
// 不帶 optional_fields 只會拿到基本資訊
ShopeeDetailRequest.builder()
    .orderSnList(orderSnBatch) // max 50
    .optionalFields(List.of(
        "item_list",
        "buyer_user_id",
        "recipient_address",
        "pay_time",
        "actual_shipping_fee_confirmed"
    ))
    .build();
```

### 7.2 Cyberbiz 雙 query 合併邏輯
```java
// 兩批結果合併，updated_at 較新的版本優先
Map<String, CyberbizOrder> merged = new HashMap<>();
for (CyberbizOrder order : createdBatch)  merged.put(order.getId(), order);
for (CyberbizOrder order : updatedBatch)  {
    merged.merge(order.getId(), order,
        (existing, newer) -> newer.getUpdatedAt().isAfter(existing.getUpdatedAt()) ? newer : existing);
}
```

---

## 8. Cache

### 8.1 Dedup Redis Key 格式
```
dedup:{channelId}:{channelOrderId}:{orderHash}
```

- `channelId`：NanoID（20 chars）
- `channelOrderId`：平台原始訂單號
- `orderHash`：SHA256（64 hex chars）

TTL: **24 小時**

### 8.2 Hash 計算方式
```java
// 只 hash mutable fields（狀態、金額等），不 hash 不變的欄位（channelOrderId 等）
Map<String, Object> mutableFields = Map.of(
    "status", order.getStatus(),
    "totalAmount", order.getTotalAmount(),
    "trackingNumber", order.getTrackingNumber(),
    "escrowAmount", order.getEscrowAmount()
    // ... 其他可能變動的欄位
);
// sorted JSON → SHA256
String hash = sha256(JsonUtils.toSortedJson(mutableFields));
```

### 8.3 Redis 故障降級
- Redis 不可用時，**降級為 DB-only dedup**
- **不可因 Redis 故障而阻塞訂單處理**
- 降級期間 log `WARN`，不 throw exception

```java
// Redis 故障不影響主流程
try {
    return redis.get(key);
} catch (Exception e) {
    log.warn("Redis unavailable for dedup, using DB fallback");
    return null; // null 觸發 DB fallback
}
```

---

## 9. QA Checklist

### Scheduler 訊息驗證
- [ ] Scheduler 發出的 Kafka 訊息 body **只有 `timestamp`**，沒有 `from`/`to`
- [ ] `timestamp` 格式為 ISO-8601 UTC（例如 `2026-03-17T08:00:00Z`）

### Channel Job 行為驗證
- [ ] Channel Job 從 `header.timestamp` 計算時間窗口，**沒有呼叫 `Instant.now()`**
- [ ] 每個平台按照各自策略計算窗口（Shopee 多窗口、Cyberbiz 雙 query 等）
- [ ] 所有平台訂單都轉換為 OMS unified structure 再送 Kafka（不送平台原始格式）
- [ ] ORDER_UPSERT body 包含 `channelOrderId`（不可為 null）

### 去重驗證
- [ ] 同一訂單連續抓取兩次（相同 hash）→ 只處理一次，DB 只有一筆
- [ ] 訂單狀態更新（不同 hash）→ 觸發 UPDATE，不是第二筆 INSERT
- [ ] Redis 故障時 → 降級為 DB dedup，訂單處理不中斷

### PII 加密驗證
- [ ] `buyer_name`、`buyer_phone`、`buyer_email`、`shipping_address` 在 DB 中是密文
- [ ] API response 中的買家資訊是明文（已解密）
- [ ] `EncryptionContext.setMerchantId()` 在解密前有設定

### Nullable 欄位驗證
- [ ] `tracking_number` 為 null 的訂單可以正常 INSERT
- [ ] `escrow_amount` 為 null 的訂單可以正常 INSERT
- [ ] 後續 UPDATE 帶入 tracking_number 時，正常更新，不拋 NPE

### isRollback 驗證
- [ ] `isRollback = false`：`revenue_date = today`，`is_backfill = false`
- [ ] `isRollback = true`：`revenue_date = channelCreatedAt.toLocalDate()`，`is_backfill = true`
- [ ] `isRollback` 從 Kafka header 正確傳遞到 DB 欄位

### 狀態驗證
- [ ] OMS 接受任何狀態轉換（不拋 InvalidStateTransitionException 或類似）
- [ ] 狀態變更時，`order_status_logs` 有新增一筆紀錄
- [ ] `order_status_logs` 中的舊紀錄沒有被修改或刪除

### order_status_logs 寫入驗證（QA-C6）
- [ ] **新訂單 INSERT**：`order_status_logs` 寫入一筆，`from_status = NULL`，`to_status = <初始狀態>`，`operator = 'system'`，`remark` 含 `ORDER_UPSERT from channel <channelId>`
- [ ] **UPDATE，status 有變**：`order_status_logs` 寫入一筆，`from_status = <舊狀態>`，`to_status = <新狀態>`
- [ ] **UPDATE，status 未變**：`order_status_logs` **不寫入**新紀錄
- [ ] **Redis 故障、`order_status_logs` 寫入失敗**：整體 upsert 仍返回成功，僅記錄 WARN 日誌（非致命）

### Shopee 特有驗證
- [ ] `get_order_detail` 帶了 `optional_fields`（items、address、pay_time）
- [ ] 每批 detail call 不超過 50 筆

### Cyberbiz 特有驗證
- [ ] 兩批 query（created + updated）有合併，`updated_at` 較新的版本優先
- [ ] 合併後的每筆訂單有個別呼叫 `GET /v1/orders/{id}` 取完整資料
