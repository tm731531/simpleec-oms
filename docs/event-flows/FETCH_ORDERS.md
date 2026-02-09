# FETCH_ORDERS — 拉單事件流

> **依據: SCHEMA.md v4（2026-02-09）**
>
> PK 全部 VARCHAR(20) NanoID | 訂單明細 = orders.items JSONB（無 order_items 獨立表） | SKU 欄位統一為 `sku`

> **這是系統中最重要的事件流：** 3 個 JOB 串接（ChannelJob → OrderProcessJob → BackendJob），
> 每一步的 payload 必須帶齊下一步所需的所有欄位，不能 miss。

## 1. 觸發方式

| 觸發源 | 方式 | Topic | Key |
|--------|------|-------|-----|
| 排程 | SchedulerJob 每 N 分鐘觸發（如：每 5 分鐘） | `{platform}.slow` | `null`（round-robin，最大吞吐） |
| 手動（未來） | 前端「手動拉單」按鈕 | `{platform}.slow` | `null` |

**為什麼拉單不需要 key？**
拉單由排程觸發，每個通路一支定時 → 本身就不會衝突。
整理訂單時才需要 key（在 order.process 用 `channelId:merchantId` 排序）。

### 1.1 排程自動觸發（主要方式）

```
SchedulerJob (simpleec-scheduler)
  │
  │  HeartbeatTimer 每秒 tick
  │  判斷 FETCH_ORDERS 規則：每 5 分鐘執行一次
  │
  │  到期時:
  │    SELECT * FROM channel WHERE actived=true AND enable_sync=true
  │    對每個 channel:
  │      1. 取得 platformType（channel → platform）
  │      2. 計算 fromDate = channel.last_sync_time ?? (now - 1 hour)
  │      3. 計算 toDate = now()
  │      4. 組裝 TaskMessage (taskAction=FETCH_ORDERS)
  │      5. 發送到 {platformType}.slow topic, key=null
  │      6. UPDATE channel SET last_sync_time = now()
  │
  │  ★ enable_sync=false 的通路不會觸發
  │  ★ 新建的通路要手動啟用 enable_sync
  │  ★ 首次同步可用 first_sync_start_time/first_sync_end_time 做較大範圍拉取
```

### 1.2 手動拉單 API（未來）

```
POST /api/v1/channels/{channelId}/sync-orders

Headers:
  Authorization: Bearer {jwt}

Path:
  channelId — 通路 ID（NanoID）

Body:
{
  "fromDate": "2026-02-09T00:00:00Z",
  "toDate":   "2026-02-09T23:59:59Z"
}

Response: 202 Accepted
{
  "messageId": "uuid-...",
  "message": "拉單任務已送出"
}

Error:
  401 — 未登入
  403 — 無此通路權限
  404 — 通路不存在
```

**後端邏輯（ChannelController）：**
1. 從 JWT 取得 merchantId
2. 查 `channel` 表確認 channelId 屬於此 merchant 且 actived=true
3. 查 `channel → platform` 取得 platformType
4. 組裝 TaskMessage（taskAction=FETCH_ORDERS, payload 帶 fromDate/toDate）
5. 發送到 `{platformType}.slow` topic，key=null
6. 回傳 202 + messageId

### 1.3 前端呈現

```
訂單列表頁: OrderListView.vue
  │
  │  資料來源:
  │    GET /api/v1/orders?merchantId=xxx&status=xxx&page=0&size=20
  │    ★ 回傳 OrderVO — PII 遮罩（王*明、0912***678、to***@gmail.com、台北市***）
  │
  │  篩選條件:
  │    ├── 通路（channelId）
  │    ├── 狀態（order_status）
  │    ├── 日期區間（channel_created_at）
  │    └── 搜尋（buyer_name / channel_order_id）
  │
  │  匯出:
  │    GET /api/v1/orders/export?merchantId=xxx  → CSV 下載（完整明文 PII）
  │
  │  列表欄位:
  │    平台訂單編號 | 買家（遮罩） | 金額 | 狀態 badge | 平台建立時間
  │
  │  狀態 badge 映射:
  │    pending    → 待處理（黃色）
  │    confirmed  → 已確認（藍色）
  │    processing → 處理中（藍色）
  │    shipped    → 已出貨（紫色）
  │    delivered  → 已送達（綠色）
  │    completed  → 已完成（綠色）
  │    cancelled  → 已取消（灰色）
  │    refunding  → 退款中（橙色）
  │    refunded   → 已退款（紅色）
  │
  ▼
訂單詳情頁: OrderDetailView.vue
  │
  │  資料來源:
  │    GET /api/v1/orders/{orderId}?merchantId=xxx
  │    ★ 回傳 OrderVO — 完整明文 PII（點開解鎖）
  │
  │  顯示區塊:
  │    ├── 訂單基本資訊（訂單編號、狀態、金額）
  │    ├── 買家資訊（★ 完整明文：姓名、電話、Email、地址）
  │    ├── 商品明細（items JSONB → 表格顯示）
  │    │     SKU | 商品名 | 規格名 | 數量 | 單價 | 小計
  │    ├── 物流資訊（出貨記錄、追蹤號碼）
  │    └── 狀態變更歷史（order_status_logs → timeline）
  │
  │  操作按鈕:
  │    ├── 出貨確認 → POST /api/v1/orders/{id}/ship
  │    └── 取消訂單 → POST /api/v1/orders/{id}/cancel
```

## 2. 端到端事件流（3 階段）

```
=======================================================================
  階段 1: SchedulerJob → {platform}.slow
=======================================================================

HeartbeatTimer (每秒 tick)
  │
  ▼
SchedulerJob
  │  判斷 FETCH_ORDERS 規則到期
  │  查 DB: SELECT * FROM channel WHERE actived=true AND enable_sync=true
  │  對每個 active channel 各發一則 TaskMessage
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│  Topic: momo.slow                                            │
│  Key:   null                                                 │
│                                                              │
│  {                                                           │
│    "messageId":     "uuid-...",                              │
│    "taskType":      "channel_action",                        │
│    "taskAction":    "FETCH_ORDERS",                          │
│    "sourceJobType": "scheduler-job",                         │
│    "merchantId":    "M001",                                  │
│    "ownerType":     "channel",                               │
│    "ownerId":       "CH-MOMO-001",                           │
│    "timezone":      "Asia/Taipei",                           │
│    "payload": {                                              │
│      "channelId":    "CH-MOMO-001",                          │
│      "platformType": "momo",                                 │
│      "fromDate":     "2026-02-09T00:00:00Z",                │
│      "toDate":       "2026-02-09T10:30:00Z",                │
│      "orderStatuses": ["COMPLETED", "SHIPPED", "PROCESSING"] │
│    },                                                        │
│    "partitionKey":  null,                                    │
│    "topic":         "momo.slow",                             │
│    "traceId":       "...",                                   │
│    "schemaVersion": 1                                        │
│  }                                                           │
└──────────────────────────────────────────────────────────────┘


=======================================================================
  階段 2: ChannelJob (FetchOrdersActionService)
=======================================================================

ChannelJob (simpleec-channel-momo-slow)
  │
  │  FetchOrdersActionService — 4 步生命週期：
  │
  │  ① setting(resource)
  │     - 從 msg.payload 取得 channelId, fromDate, toDate, orderStatuses
  │     - 從 resource 取得 adapter, taskProducer, redis
  │
  │  ② getPlatformTokens()
  │     - 查 DB: platform.credential1~N
  │     - 查 DB: channel.token1~token5
  │     - 組合認證
  │
  │  ③ verifyNeedData()
  │     - 確認 channel.actived = true
  │     - 確認認證有效
  │     - 確認 fromDate, toDate 合理
  │
  │  ④ doAction()
  │     - 呼叫 adapter.fetchOrders(channelId, from, to)
  │     - 平台 API 回傳 List<ChannelOrder>（可能 1~1000+ 筆）
  │     - 對每筆訂單做 Hash Dedup：
  │
  │     ┌──────────────────────────────────────────────────────┐
  │     │  Hash Dedup 邏輯（Producer side）                      │
  │     │                                                      │
  │     │  for (ChannelOrder order : fetchedOrders) {           │
  │     │    hashKey = "order:hash:{merchantId}:{channelId}"   │
  │     │             + ":{channelOrderId}"                    │
  │     │    newHash = SHA-256(order 全欄位 JSON)               │
  │     │    existingHash = redis.GET(hashKey)                  │
  │     │                                                      │
  │     │    if (newHash != existingHash) {                     │
  │     │      // 有變動或新訂單 → 送 order.process             │
  │     │      → 組裝 TaskMessage (見下方 payload)              │
  │     │      → taskProducer.send("order.process",            │
  │     │          channelId + ":" + merchantId, orderMsg)     │
  │     │    }                                                 │
  │     │    // hash 相同 → skip（訂單無變動）                   │
  │     │  }                                                   │
  │     └──────────────────────────────────────────────────────┘
  │
  │  ★ 注意：ChannelJob 不寫 Redis hash！
  │    只 READ hash 判斷是否有變 → 發到 order.process
  │    hash 寫入由 OrderProcessJob 負責（Consumer side）
  │    這樣確保只有真正入庫成功的訂單才更新 hash
  │
  │  ⑤ SyncLog
  │     channel_sync_logs: sync_type='FETCH_ORDERS', status='success'/'failed'
  │
  ▼

每筆有變動的訂單，發到 order.process:

┌──────────────────────────────────────────────────────────────┐
│  Topic: order.process                                        │
│  Key:   CH-MOMO-001:M001  (同通路+商家有序)                    │
│                                                              │
│  {                                                           │
│    "messageId":     "uuid-...",                              │
│    "taskType":      "order",                                 │
│    "taskAction":    "PROCESS_ORDER",                         │
│    "sourceJobType": "channel-job",                           │
│    "merchantId":    "M001",                                  │
│    "ownerType":     "channel",                               │
│    "ownerId":       "CH-MOMO-001",                           │
│    "timezone":      "Asia/Taipei",                           │
│    "payload": {                                              │
│      "channelOrderId":   "MOMO-ORD-12345",                  │
│      "orderStatus":      "COMPLETED",                        │
│      "orderHash":        "sha256:e3b0c44...",                │
│                                                              │
│      "buyerName":        "王小明",                            │
│      "buyerPhone":       "0912345678",                       │
│      "buyerEmail":       "wang@example.com",                 │
│      "shippingAddress":  "台北市信義區松仁路100號",              │
│      "shippingMethod":   "黑貓宅急便",                        │
│      "paymentMethod":    "信用卡",                            │
│                                                              │
│      "totalAmount":      1580.00,                            │
│      "shippingFee":      60.00,                              │
│      "discountAmount":   100.00,                             │
│                                                              │
│      "channelCreatedAt": "2026-02-09T08:30:00Z",            │
│      "paidAt":           "2026-02-09T08:31:00Z",            │
│      "shippedAt":        null,                               │
│                                                              │
│      "items": [                                              │
│        {                                                     │
│          "sku":                "HGJ-60-12",                  │
│          "channelProductId":   "MOMO-SKU-98765",             │
│          "channelSpecId":      "MOMO-SPEC-98765-A",          │
│          "channelProductName": "MOMO養生雞精禮盒限定組",        │
│          "channelSpecName":    "60ml×12入(單盒)",             │
│          "productName":        "MOMO養生雞精禮盒限定組",        │
│          "quantity":           2,                             │
│          "unitPrice":          790.00,                        │
│          "subtotal":           1580.00                        │
│        }                                                     │
│      ]                                                       │
│    },                                                        │
│    "partitionKey":  "CH-MOMO-001:M001",                      │
│    "topic":         "order.process",                         │
│    "traceId":       "...",                                   │
│    "schemaVersion": 1                                        │
│  }                                                           │
└──────────────────────────────────────────────────────────────┘


=======================================================================
  階段 3: OrderProcessJob — 訂單整理入庫
=======================================================================

OrderProcessJob (simpleec-order-job)
  │
  │  收到單筆訂單 TaskMessage（已過 Hash Dedup，保證有變動）
  │
  │  Step 1: 提取訂單資料
  │    從 payload 解析出所有訂單欄位 + items[]
  │
  │  Step 2: 查 DB 是否已存在
  │    SELECT * FROM orders
  │    WHERE channel_id = ? AND channel_order_id = ?
  │
  │  Step 3A: 新訂單（不存在）
  │    ├── 產生 orderId = NanoID()
  │    ├── 對每個 item 做 sell_pack match:
  │    │     用 channel_product_id + channel_spec_id 查 sell_pack
  │    │     → 找到 → item.sellPackId = sell_pack.id
  │    │              item.productId  = sell_pack.product_id
  │    │     → 找不到 → item.sellPackId = null
  │    │                item.productId  = null
  │    │                （訂單先入庫，商品同步後再補）
  │    ├── 組裝 items JSONB array
  │    ├── INSERT orders 表（所有欄位見下方對照表）
  │    ├── INSERT order_status_logs: from_status=null, to_status=payload.orderStatus
  │    └── statusChanged = true
  │
  │  Step 3B: 既有訂單但狀態變更
  │    ├── UPDATE orders SET order_status=?, updated_at=now()
  │    │   + 其他可能變更的欄位（shippedAt, paidAt 等）
  │    │   + 更新 items JSONB（如果平台有帶更新的明細）
  │    ├── INSERT order_status_logs: from_status=舊, to_status=新
  │    └── statusChanged = true
  │
  │  Step 3C: 既有訂單且無變更
  │    └── 理論上不會到這裡（Hash Dedup 已過濾），但以防萬一直接跳過
  │
  │  Step 4: 更新 Redis hash
  │    hashKey = "order:hash:{merchantId}:{channelId}:{channelOrderId}"
  │    redis.SET(hashKey, payload.orderHash, 7 天 TTL)
  │    ★ 只有入庫成功才寫 hash → 確保下次拉單時能正確判斷
  │
  │  Step 5: 狀態有變 → 自治路由到 task.backend
  │    if (statusChanged) → 送 task.backend:
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│  Topic: task.backend                                         │
│  Key:   M001  (同商家有序)                                     │
│                                                              │
│  {                                                           │
│    "messageId":     "uuid-...",                              │
│    "taskType":      "backend",                               │
│    "taskAction":    "ORDER_STATUS_CHANGED",                  │
│    "sourceJobType": "order-process-job",                     │
│    "merchantId":    "M001",                                  │
│    "payload": {                                              │
│      "orderId":         "ord_Abc123xYz",                     │
│      "channelOrderId":  "MOMO-ORD-12345",                   │
│      "channelId":       "CH-MOMO-001",                       │
│      "fromStatus":      null,                                │
│      "toStatus":        "COMPLETED",                         │
│      "totalAmount":     1580.00,                             │
│      "buyerName":       "王小明"                              │
│    },                                                        │
│    "partitionKey":  "M001",                                  │
│    "topic":         "task.backend",                          │
│    "traceId":       "...",                                   │
│    "schemaVersion": 1                                        │
│  }                                                           │
└──────────────────────────────────────────────────────────────┘
  │
  ▼
BackendJob → ORDER_STATUS_CHANGED handler
  ├── 更新 daily_statistics（如果需要）
  ├── 發前台通知（task.frontend）
  └── 結束
```

## 3. DB 欄位對照表

### orders 表 ← payload 直接映射

| DB 欄位 | 類型 | payload 來源 | 說明 |
|---------|------|-------------|------|
| `id` | VARCHAR(20) | NanoID() | PK（程式端產生） |
| `merchant_id` | VARCHAR(20) | msg.merchantId | |
| `channel_id` | VARCHAR(20) | msg.ownerId | FK → channel |
| `channel_order_id` | VARCHAR(100) | `payload.channelOrderId` | 唯一約束: (channel_id, channel_order_id) |
| `order_status` | VARCHAR(20) | `payload.orderStatus` | pending/confirmed/processing/shipped/delivered/completed/cancelled/refunding/refunded |
| `buyer_name` | VARCHAR(512) | `payload.buyerName` | ★ AES-256-GCM 加密（TypeHandler 透明處理） |
| `buyer_phone` | VARCHAR(256) | `payload.buyerPhone` | ★ AES-256-GCM 加密 |
| `buyer_email` | VARCHAR(512) | `payload.buyerEmail` | ★ AES-256-GCM 加密 |
| `shipping_address` | TEXT | `payload.shippingAddress` | ★ AES-256-GCM 加密 |
| `shipping_method` | VARCHAR(50) | `payload.shippingMethod` | |
| `payment_method` | VARCHAR(50) | `payload.paymentMethod` | |
| `total_amount` | DECIMAL(12,2) | `payload.totalAmount` | |
| `shipping_fee` | DECIMAL(12,2) | `payload.shippingFee` | |
| `discount_amount` | DECIMAL(12,2) | `payload.discountAmount` | |
| `items` | JSONB | `payload.items[]` | **訂單明細（見下方 JSONB 結構）** |
| `channel_created_at` | TIMESTAMPTZ | `payload.channelCreatedAt` | 平台端建立時間 |
| `paid_at` | TIMESTAMPTZ | `payload.paidAt` | |
| `shipped_at` | TIMESTAMPTZ | `payload.shippedAt` | |
| `created_at` | TIMESTAMPTZ | auto | |
| `updated_at` | TIMESTAMPTZ | auto | |

### orders.items JSONB ← payload.items[] 映射

```json
[
  {
    "sku":                "HGJ-60-12",
    "channelProductId":   "MOMO-SKU-98765",
    "channelSpecId":      "MOMO-SPEC-98765-A",
    "channelProductName": "MOMO養生雞精禮盒限定組",
    "channelSpecName":    "60ml×12入(單盒)",
    "productName":        "MOMO養生雞精禮盒限定組",
    "quantity":           2,
    "unitPrice":          790.00,
    "subtotal":           1580.00,
    "sellPackId":         "sp_abc123",
    "productId":          "pd_xyz789"
  }
]
```

| JSONB 欄位 | payload.items[i] 來源 | 說明 |
|------------|----------------------|------|
| `sku` | `item.sku` | 我方 SKU（平台帶的 skuCode） |
| `channelProductId` | `item.channelProductId` | 平台商品編號（賣編） |
| `channelSpecId` | `item.channelSpecId` | 平台規格編號 |
| `channelProductName` | `item.channelProductName` | 平台商品名 |
| `channelSpecName` | `item.channelSpecName` | 平台規格名 |
| `productName` | `item.productName` | 存平台給的名稱（保留原始資訊） |
| `quantity` | `item.quantity` | |
| `unitPrice` | `item.unitPrice` | |
| `subtotal` | `item.subtotal` | |
| `sellPackId` | **sell_pack 查詢結果** | 透過 channelProductId + channelSpecId 查 sell_pack.id |
| `productId` | **sell_pack 查詢結果** | 透過 sell_pack → product_id |

**★ sellPackId 和 productId 在訂單入庫時嘗試 match：**

```
item.channelProductId + item.channelSpecId
  │
  ▼
SELECT id, product_id FROM sell_pack
WHERE channel_id = ? AND channel_product_id = ?
  AND COALESCE(channel_spec_id, '') = COALESCE(?, '')
  │
  ├── 找到 → item.sellPackId = sell_pack.id
  │           item.productId  = sell_pack.product_id
  │
  └── 找不到 → item.sellPackId = null
                item.productId  = null
                （訂單先入庫，後續同步商品後再補關聯）
```

### order_status_logs 表

| DB 欄位 | 類型 | 來源 | 說明 |
|---------|------|------|------|
| `id` | VARCHAR(20) | NanoID() | PK |
| `order_id` | VARCHAR(20) | 新建或既有的 orders.id | FK |
| `from_status` | VARCHAR(20) | 新訂單=null, 狀態變更=舊 status | |
| `to_status` | VARCHAR(20) | payload.orderStatus | |
| `operator` | VARCHAR(100) | "SYSTEM" | 自動拉單 |
| `remark` | TEXT | "FETCH_ORDERS from {platform}" | |
| `created_at` | TIMESTAMPTZ | auto | |

## 4. Entity 缺口分析

### ✅ Order.java — 已修正（Level 1 + Level 1.5）

| 欄位 | 修正前 | 修正後 | 狀態 |
|------|--------|--------|------|
| `id` | `Long` | `String` + `IdType.ASSIGN_UUID` | ✅ 已修 |
| `merchantId` | `Long` | `String` | ✅ 已修 |
| `channelId` | `Long` | `String` | ✅ 已修 |
| `items` | 無 | `String` (JSONB) | ✅ 已修 |
| PII 4 欄位 | 明文 | AES-256-GCM 加密（EncryptedFieldTypeHandler） | ✅ 已修 |

> **PII 在事件流中的處理：**
> - ChannelJob：平台 API 回傳明文 PII → Kafka payload 帶明文（記憶體中）
> - OrderProcessJob：INSERT orders 時 MyBatis TypeHandler 自動加密寫入 DB
> - API 層：TypeHandler 自動解密 → OrderVO 遮罩（列表）/ 明文（詳情+匯出）

### ✅ OrderItem.java — 已刪除（Level 1）

OrderItem.java + OrderItemMapper.java + ProductSpec.java + ProductSpecMapper.java 已刪除。

### ❌ ChannelAdapter 的 fetchOrders 回傳類型 + 參數類型

目前：
```java
List<Order> fetchOrders(Long channelId, LocalDateTime from, LocalDateTime to);
```

應改為：
```java
List<ChannelOrder> fetchOrders(String channelId, LocalDateTime from, LocalDateTime to);
```

**2 個問題：**
1. 回傳 `Order` Entity → 應改 `ChannelOrder` DTO（平台原始資料）
2. `channelId` 是 `Long` → 應改 `String`（NanoID）

### ❌ 需要新建 ChannelOrder DTO

```java
/**
 * 平台回傳的訂單 DTO（Adapter 層使用）
 * 包含訂單主檔 + 明細，以及平台原始的商品/規格 ID
 */
@Data
public class ChannelOrder {
    private String channelOrderId;
    private String orderStatus;
    private String buyerName;
    private String buyerPhone;
    private String buyerEmail;
    private String shippingAddress;
    private String shippingMethod;
    private String paymentMethod;
    private BigDecimal totalAmount;
    private BigDecimal shippingFee;
    private BigDecimal discountAmount;
    private LocalDateTime channelCreatedAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;

    private List<ChannelOrderItem> items;
}

@Data
public class ChannelOrderItem {
    private String channelProductId;      // 平台商品編號（賣編）
    private String channelSpecId;         // 平台規格編號
    private String channelProductName;    // 平台商品名
    private String channelSpecName;       // 平台規格名
    private String sku;                   // 我方 SKU
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
}
```

## 5. Hash Dedup 流程圖

```
                 ChannelJob                    OrderProcessJob
                 (Producer)                    (Consumer)
                     │                              │
  平台 API 回傳      │                              │
  1000 筆訂單        │                              │
       │             │                              │
       ▼             │                              │
  逐筆計算 hash      │                              │
       │             │                              │
       ▼             │                              │
  Redis GET hash     │                              │
  ┌─────────┐        │                              │
  │hash相同?│──YES──→│ SKIP（跳過，不發）            │
  └────┬────┘        │                              │
       │ NO          │                              │
       ▼             │                              │
  送 order.process───┼──────────────────────────────▶│
                     │                              │
                     │                    收到訂單   │
                     │                       │      │
                     │                    match     │
                     │                    sell_pack │
                     │                    填 JSONB  │
                     │                       │      │
                     │                    INSERT    │
                     │                    orders    │
                     │                    (items    │
                     │                     JSONB)   │
                     │                       │      │
                     │                    ┌───┴───┐  │
                     │                    │成功?  │  │
                     │                    └───┬───┘  │
                     │                        │ YES  │
                     │                    Redis SET  │
                     │                    hash=new   │
                     │                    TTL=7天    │
                     │                        │      │
                     │                    送 task.   │
                     │                    backend    │
                     │                    (if 狀態   │
                     │                     有變)     │

  ★ ChannelJob 只 READ hash → OrderProcessJob 才 WRITE hash
  ★ 確保只有真正入庫成功的訂單才更新 hash
  ★ 如果 OrderProcessJob 失敗（沒寫 hash）→ 下次拉單會再次送出 → 天然重試
```

## 6. 失敗處理

### 階段 2 失敗（ChannelJob）
```
FetchOrdersActionService 拋異常
  → task.failed → RetryDispatchJob
    ├── slow topic → 可重打 → retryCount++ → 重送 momo.slow
    └── 超過 maxRetry → task.dlt
```

### 階段 3 失敗（OrderProcessJob）
```
OrderProcessJob handle() 拋異常
  → task.failed → RetryDispatchJob
    ├── order.process → 可重打 → retryCount++ → 重送 order.process
    └── 超過 maxRetry → task.dlt
★ 因為 hash 沒更新 → 下一次 FETCH_ORDERS 時 ChannelJob 會再發一次 → 天然補償
```

### 階段 4 失敗（BackendJob）
```
BackendJob handle() 拋異常
  → task.failed → RetryDispatchJob → 可重打
★ 訂單已入庫（Step 3 成功了），只是通知失敗 → 不影響資料完整性
```

## 7. 冪等性保證

| 環節 | 冪等機制 |
|------|---------|
| ChannelJob → order.process | Hash Dedup（Producer-side，SHA-256） |
| OrderProcessJob 入庫 | UNIQUE (channel_id, channel_order_id) — 插入衝突 → 改 update |
| OrderProcessJob 更新 hash | SET 覆蓋（冪等） |
| BackendJob 通知 | 重複收到同樣的 ORDER_STATUS_CHANGED → 重複處理但無副作用 |

## 8. 時序保證

```
同一通路的訂單整理有序:
  order.process partition key = channelId:merchantId
  → 同通路 + 同商家的訂單在同一個 partition → Kafka 保證有序

不同通路可以並行:
  momo 的訂單 和 shopee 的訂單 → 不同 key → 不同 partition → 可並行

同一張訂單的多次更新有序:
  同 channelOrderId 的 hash 一定不同 → 一定會送出
  partition key = channelId:merchantId → 同 partition → 有序
```

## 9. 資料流完整性驗證矩陣

```
平台 API → ChannelOrder DTO → Kafka payload → orders 表 (items JSONB) → Order Entity

                     DTO                 payload               DB (orders)            Entity (Order)
                    ─────               ─────────             ──────────             ─────────────
channelOrderId  →   channelOrderId  →   channelOrderId    →  channel_order_id    →  channelOrderId     ✅ OK
orderStatus     →   orderStatus     →   orderStatus       →  order_status        →  orderStatus        ✅ OK
buyerName       →   buyerName       →   buyerName         →  buyer_name          →  buyerName          ✅ OK
totalAmount     →   totalAmount     →   totalAmount       →  total_amount        →  totalAmount        ✅ OK
merchantId      →   (from msg)      →   msg.merchantId    →  merchant_id (varchar) → merchantId        ✅ 已改 String
channelId       →   (from msg)      →   msg.ownerId       →  channel_id (varchar)  → channelId         ✅ 已改 String
items           →   items           →   items[]           →  items (JSONB)       →  items              ✅ 已加

                     DTO (item)           payload (item)        orders.items[] JSONB
                    ─────────            ──────────            ──────────────────
channelProductId →  channelProductId →   channelProductId  →  channelProductId        ✅ OK
channelSpecId    →  channelSpecId    →   channelSpecId     →  channelSpecId           ✅ OK
channelProductName→ channelProductName→  channelProductName→  channelProductName      ✅ OK
channelSpecName  →  channelSpecName  →   channelSpecName   →  channelSpecName         ✅ OK
sku              →  sku              →   sku               →  sku                     ✅ OK
quantity         →  quantity         →   quantity          →  quantity                ✅ OK
unitPrice        →  unitPrice        →   unitPrice         →  unitPrice               ✅ OK
subtotal         →  subtotal         →   subtotal          →  subtotal                ✅ OK
(match sell_pack)→  ---              →   ---               →  sellPackId               ✅ OrderProcessJob 填入
(match sell_pack)→  ---              →   ---               →  productId                ✅ OrderProcessJob 填入

★ orders.id, order_status_logs.id 都是 VARCHAR(20) NanoID（程式端產生）
★ 無 order_items 獨立表 — 明細直接存在 orders.items JSONB
★ sellPackId + productId 由 OrderProcessJob 在入庫時 match 填入
★ buyer_name, buyer_phone, buyer_email, shipping_address — AES-256-GCM 加密存儲
★ API 列表回傳 OrderVO（PII 遮罩），詳情/匯出回傳明文
```
