# FETCH_ORDERS — 拉單事件流

> **這是系統中最重要的事件流：** 3 個 JOB 串接（ChannelJob → OrderProcessJob → BackendJob），
> 每一步的 payload 必須帶齊下一步所需的所有欄位，不能 miss。

## 1. 觸發方式

| 觸發源 | 方式 | Topic | Key |
|--------|------|-------|-----|
| 排程 | SchedulerJob 每 N 分鐘觸發（如：每 5 分鐘） | `{platform}.slow` | `null`（round-robin，最大吞吐） |
| 手動 | 前端「手動拉單」按鈕（未來） | `{platform}.slow` | `null` |

**為什麼拉單不需要 key？**
拉單由排程觸發，每個通路一支定時 → 本身就不會衝突。
整理訂單時才需要 key（在 order.process 用 `channelId:merchantId` 排序）。

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
│      "orderItems": [                                         │
│        {                                                     │
│          "channelProductId":   "MOMO-SKU-98765",             │
│          "channelSpecId":      "MOMO-SPEC-98765-A",          │
│          "channelProductName": "MOMO養生雞精禮盒限定組",        │
│          "channelSpecName":    "60ml×12入(單盒)",             │
│          "skuCode":            "HGJ-60-12",                  │
│          "productName":        "MOMO養生雞精禮盒限定組",        │
│          "specInfo":           "60ml×12入(單盒)",             │
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
  │    從 payload 解析出所有訂單欄位 + orderItems[]
  │
  │  Step 2: 查 DB 是否已存在
  │    SELECT * FROM orders
  │    WHERE channel_id = ? AND channel_order_id = ?
  │
  │  Step 3A: 新訂單（不存在）
  │    ├── INSERT orders 表（所有欄位見下方對照表）
  │    ├── 對每個 orderItem:
  │    │     ├── 用 channel_product_id + channel_spec_id 查 sell_pack
  │    │     │     → 找到 → 填入 sell_pack_id, product_id
  │    │     │     → 找不到 → sell_pack_id=null, product_id=null（訂單先入庫，商品後補）
  │    │     └── INSERT order_items 表
  │    ├── INSERT order_status_logs: from_status=null, to_status=payload.orderStatus
  │    └── statusChanged = true
  │
  │  Step 3B: 既有訂單但狀態變更
  │    ├── UPDATE orders SET order_status=?, updated_at=now()
  │    │   + 其他可能變更的欄位（shippedAt, paidAt 等）
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
│      "orderId":         12345,                               │
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

| DB 欄位 | payload 來源 | 說明 |
|---------|-------------|------|
| `id` | 自增 | PK |
| `merchant_id` | msg.merchantId | |
| `channel_id` | msg.ownerId | FK → channel |
| `channel_order_id` | `payload.channelOrderId` | 唯一約束: (channel_id, channel_order_id) |
| `order_status` | `payload.orderStatus` | pending/confirmed/processing/shipped/delivered/completed/cancelled/refunding/refunded |
| `buyer_name` | `payload.buyerName` | |
| `buyer_phone` | `payload.buyerPhone` | |
| `buyer_email` | `payload.buyerEmail` | |
| `shipping_address` | `payload.shippingAddress` | |
| `shipping_method` | `payload.shippingMethod` | |
| `payment_method` | `payload.paymentMethod` | |
| `total_amount` | `payload.totalAmount` | |
| `shipping_fee` | `payload.shippingFee` | |
| `discount_amount` | `payload.discountAmount` | |
| `channel_created_at` | `payload.channelCreatedAt` | 平台端建立時間 |
| `paid_at` | `payload.paidAt` | |
| `shipped_at` | `payload.shippedAt` | |
| `created_at` | auto | |
| `updated_at` | auto | |

### order_items 表 ← payload.orderItems[] 映射

| DB 欄位 | payload.orderItems[i] 來源 | 說明 |
|---------|--------------------------|------|
| `id` | 自增 | PK |
| `order_id` | 新建的 orders.id | FK |
| `product_id` | **sell_pack 查詢結果** | 透過 channelProductId + channelSpecId → sell_pack → product_id |
| `sell_pack_id` | **sell_pack 查詢結果** | 透過 channelProductId + channelSpecId 查 sell_pack.id |
| `sku_code` | `item.skuCode` | |
| `product_name` | `item.productName` | 平台商品名（存平台給的名稱） |
| `spec_info` | `item.specInfo` | 平台規格資訊 |
| `quantity` | `item.quantity` | |
| `unit_price` | `item.unitPrice` | |
| `subtotal` | `item.subtotal` | |

**★ order_items 存平台名稱（不是我方商品名）** — 訂單是平台來的，保留原始資訊。
product_id 和 sell_pack_id 靠 match 填入，找不到就先 null，商品同步後再補。

### order_items → sell_pack 的 match 邏輯

```
orderItem.channelProductId + orderItem.channelSpecId
  │
  ▼
SELECT id, product_id FROM sell_pack
WHERE channel_id = ? AND channel_product_id = ?
  AND (channel_spec_id = ? OR channel_spec_id IS NULL)
  │
  ├── 找到 → order_items.sell_pack_id = sell_pack.id
  │           order_items.product_id   = sell_pack.product_id
  │
  └── 找不到 → order_items.sell_pack_id = null
                order_items.product_id   = null
                （訂單先入庫，後續同步商品後再補關聯）
```

### order_status_logs 表

| DB 欄位 | 來源 | 說明 |
|---------|------|------|
| `order_id` | 新建或既有的 orders.id | FK |
| `from_status` | 新訂單=null, 狀態變更=舊 status | |
| `to_status` | payload.orderStatus | |
| `operator` | "SYSTEM" | 自動拉單 |
| `remark` | "FETCH_ORDERS from {platform}" | |

## 4. Entity 缺口分析

### ❌ Order.java — 類型不一致

| 欄位 | Entity | DB | 問題 |
|------|--------|-----|------|
| `merchantId` | `Long` | `varchar(20)` | **類型不一致！** DB 是 varchar，Entity 是 Long |
| `channelId` | `Long` | `varchar(20)` | **類型不一致！** DB 是 varchar，Entity 是 Long |

### ❌ OrderItem.java — 缺少平台 ID 欄位

目前 `OrderItem.java` 的 fields:
```
id, orderId, productId, sellPackId, skuCode,
productName, specInfo, quantity, unitPrice, subtotal
```

**缺少（payload 有帶但 DB 沒欄位存）：**

| 缺少 | 用途 | 建議 |
|------|------|------|
| `channelProductId` | 平台商品編號，用來 match sell_pack | **需要加到 DB + Entity** |
| `channelSpecId` | 平台規格編號，用來 match sell_pack | **需要加到 DB + Entity** |

**為什麼 order_items 需要存 channelProductId + channelSpecId？**
1. 訂單入庫時 sell_pack 可能還不存在（還沒同步商品），所以 sell_pack_id 可能是 null
2. 後續同步商品後，需要用 channelProductId + channelSpecId 來回填 sell_pack_id
3. 如果只存 sell_pack_id，一旦 sell_pack 被重建（ID 變了），訂單明細就失去關聯

### ❌ ChannelAdapter 的 fetchOrders 回傳類型

目前 `fetchOrders()` 回傳 `List<Order>` — 但 Order Entity 沒有 orderItems，也沒有平台端的商品 ID。

**建議新建 ChannelOrder DTO：**

```java
/**
 * 平台回傳的訂單 DTO（Adapter 層使用）
 * 包含訂單主檔 + 明細，以及平台原始的商品/規格 ID
 */
@Data
public class ChannelOrder {
    // 訂單主檔
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

    // 訂單明細
    private List<ChannelOrderItem> items;
}

@Data
public class ChannelOrderItem {
    private String channelProductId;      // 平台商品編號（賣編）
    private String channelSpecId;         // 平台規格編號
    private String channelProductName;    // 平台商品名
    private String channelSpecName;       // 平台規格名
    private String skuCode;               // SKU
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
}
```

修改 ChannelAdapter:
```java
List<ChannelOrder> fetchOrders(Long channelId, LocalDateTime from, LocalDateTime to);
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
                     │                    DB upsert  │
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
