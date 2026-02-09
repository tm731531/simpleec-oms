# FETCH_PRODUCTS — 同步商品事件流

## 1. 觸發方式

| 觸發源 | 方式 | Topic | Key |
|--------|------|-------|-----|
| 前端按鈕 | `POST /api/v1/channels/{channelId}/sync-products` → API 發 TaskMessage | `{platform}.slow` | `channelId`（同通路排隊，不能並行跑兩次） |
| 排程（未來） | SchedulerJob 定時觸發（尚未實作） | `{platform}.slow` | `channelId` |

## 2. 端到端事件流

```
前端「同步商品」按鈕
  │
  ▼
simpleec-api
  │  POST /api/v1/channels/{channelId}/sync-products
  │  產生 TaskMessage:
  │    topic      = "{platform}.slow"    (e.g. momo.slow)
  │    key        = channelId            (同通路排隊)
  │    taskAction = "FETCH_PRODUCTS"
  │    ownerId    = channelId
  ▼
┌──────────────────────────────────────────────────────────────┐
│  Topic: momo.slow                                            │
│  Key:   CH-MOMO-001                                          │
│                                                              │
│  {                                                           │
│    "messageId":     "uuid-...",                              │
│    "taskType":      "channel_action",                        │
│    "taskAction":    "FETCH_PRODUCTS",                        │
│    "sourceJobType": "api",                                   │
│    "merchantId":    "M001",                                  │
│    "ownerType":     "channel",                               │
│    "ownerId":       "CH-MOMO-001",                           │
│    "timezone":      "Asia/Taipei",                           │
│    "payload": {                                              │
│      "channelId":    "CH-MOMO-001",                          │
│      "platformType": "momo",                                 │
│      "syncMode":     "FULL"                                  │
│    },                                                        │
│    "partitionKey":  "CH-MOMO-001",                           │
│    "topic":         "momo.slow",                             │
│    "traceId":       "...",                                   │
│    "schemaVersion": 1                                        │
│  }                                                           │
└──────────────────────────────────────────────────────────────┘
  │
  ▼
ChannelJob (simpleec-channel-momo-slow)
  │
  │  FetchProductsActionService — 4 步生命週期：
  │
  │  ① setting(resource)
  │     - 從 msg 取得 channelId, merchantId
  │     - 從 resource 取得 adapter, taskProducer, redis
  │
  │  ② getPlatformTokens()
  │     - 查 DB: platform.credential1~N  (平台級認證)
  │     - 查 DB: channel.token1~token5   (館級 token)
  │     - 組合成 adapter 可用的認證資訊
  │
  │  ③ verifyNeedData()
  │     - 確認 channel.actived = true
  │     - 確認認證資訊有效
  │
  │  ④ doAction()
  │     - 呼叫平台 API 拉取該 channel 的所有商品（分頁拉完）
  │     - 對每個平台商品，做以下處理：
  │
  │     ┌──────────────────────────────────────────────────────┐
  │     │  每個平台商品的處理邏輯                                 │
  │     │                                                      │
  │     │  平台 API 回傳的資料（每筆商品）:                        │
  │     │    channelProductId   ← 平台賣編                       │
  │     │    channelProductName ← 平台顯示的商品名                 │
  │     │    channelProductUrl  ← 平台商品頁 URL                  │
  │     │    sellingPrice       ← 售價                           │
  │     │    quantity           ← 庫存                           │
  │     │    status             ← 上架狀態                       │
  │     │    specs[] (如果有規格):                                │
  │     │      channelSpecId    ← 平台規格編號                    │
  │     │      channelSpecName  ← 平台規格名稱                    │
  │     │      specPrice        ← 規格售價                       │
  │     │      specQuantity     ← 規格庫存                       │
  │     │      skuCode/barcode  ← SKU（如果有）                   │
  │     │                                                      │
  │     │  Step A: 查 sell_pack                                 │
  │     │    SELECT * FROM sell_pack                            │
  │     │    WHERE channel_id = ? AND channel_product_id = ?    │
  │     │      AND channel_spec_id = ?                          │
  │     │    (多規: 一個 channelProductId + N 個 channelSpecId)  │
  │     │    (單規: channelSpecId = null)                       │
  │     │                                                      │
  │     │  Step B: upsert sell_pack                             │
  │     │    ├── 找到 → UPDATE:                                 │
  │     │    │     channel_product_name = 平台商品名              │
  │     │    │     channel_spec_name    = 平台規格名              │
  │     │    │     channel_product_url  = 平台 URL               │
  │     │    │     selling_price        = 售價                   │
  │     │    │     quantity             = 庫存                   │
  │     │    │     status               = 上架狀態映射            │
  │     │    │     title                = 平台商品名(初始值)       │
  │     │    │     last_sync_at         = now()                 │
  │     │    │     updated_at           = now()                 │
  │     │    │                                                  │
  │     │    └── 找不到 → INSERT sell_pack:                     │
  │     │          merchant_id          = 從 channel 取          │
  │     │          product_id           = Step C 的結果          │
  │     │          channel_id           = channelId             │
  │     │          channel_product_id   = 平台賣編               │
  │     │          channel_spec_id      = 平台規格編號            │
  │     │          channel_product_name = 平台商品名              │
  │     │          channel_spec_name    = 平台規格名              │
  │     │          channel_product_url  = 平台 URL               │
  │     │          title                = 平台商品名              │
  │     │          selling_price        = 售價                   │
  │     │          quantity             = 庫存                   │
  │     │          status               = 上架狀態映射            │
  │     │          last_sync_at         = now()                 │
  │     │                                                      │
  │     │  Step C: match / auto-create product                  │
  │     │    用 skuCode (item_number) 查 product:               │
  │     │    SELECT * FROM product                              │
  │     │    WHERE merchant_id = ? AND item_number = ?          │
  │     │                                                      │
  │     │    ├── 找到 → sell_pack.product_id = product.id       │
  │     │    │                                                  │
  │     │    └── 找不到 → auto-create:                          │
  │     │          INSERT product:                              │
  │     │            merchant_id     = merchantId               │
  │     │            item_number     = skuCode or channelProductId │
  │     │            name            = 平台商品名                │
  │     │            status          = 'active'                 │
  │     │          如果有規格 → INSERT product_spec:              │
  │     │            product_id      = 新建的 product.id        │
  │     │            sku_code        = skuCode                  │
  │     │            spec_name       = channelSpecName          │
  │     │            spec_value      = channelSpecName          │
  │     │            price           = specPrice                │
  │     │            quantity        = specQuantity             │
  │     │                                                      │
  │     │  ★ sell_pack.product_id 指向 product.id              │
  │     │  ★ 後續人工可修正自動建立的 product 資料               │
  │     └──────────────────────────────────────────────────────┘
  │
  │  ⑤ SyncLog
  │     - 成功 → channel_sync_logs: sync_type='FETCH_PRODUCTS', status='success'
  │     - 失敗 → channel_sync_logs: sync_type='FETCH_PRODUCTS', status='failed', error_message=...
  │
  ▼
（結束，無下游 topic。同步商品是一步到位的。）

失敗時:
  → task.failed → RetryDispatchJob
    ├── slow topic → 可重打（retryCount < maxRetry）→ retryCount++ → 重新送回 momo.slow
    └── 超過 maxRetry → task.dlt
```

## 3. DB 欄位對照表

### sell_pack 表

| DB 欄位 | 來源 | 說明 |
|---------|------|------|
| `id` | 自增 | PK |
| `merchant_id` | channel.merchant_id | 從 channel 表取 |
| `product_id` | 自動 match / 新建 | FK → product |
| `channel_id` | msg.ownerId / channelId | FK → channel |
| `channel_product_id` | **平台 API 回傳** | 平台賣編（商品 ID） |
| `channel_spec_id` | **平台 API 回傳** | 平台規格編號 |
| `channel_product_name` | **平台 API 回傳** | 平台顯示的商品名 |
| `channel_spec_name` | **平台 API 回傳** | 平台顯示的規格名 |
| `channel_product_url` | **平台 API 回傳** | 平台商品頁 URL |
| `title` | 平台商品名（初始值，可修改） | 我方自訂的標題 |
| `selling_price` | **平台 API 回傳** | 售價 |
| `quantity` | **平台 API 回傳** | 庫存 |
| `status` | 平台狀態映射 | draft/pending/active/inactive/failed |
| `last_sync_at` | now() | 同步時間 |
| `created_at` | auto | |
| `updated_at` | auto | |

### product 表（自動建立時）

| DB 欄位 | 來源 | 說明 |
|---------|------|------|
| `merchant_id` | channel.merchant_id | |
| `item_number` | skuCode or channelProductId | 優先用平台的 SKU，沒有就用賣編 |
| `name` | 平台商品名 | |
| `status` | 'active' | 預設 |

### product_spec 表（自動建立時）

| DB 欄位 | 來源 | 說明 |
|---------|------|------|
| `product_id` | 新建的 product.id | FK |
| `sku_code` | skuCode | 平台的 SKU |
| `spec_name` | channelSpecName | 平台規格名稱 |
| `spec_value` | channelSpecName | 同 spec_name（初始值） |
| `price` | specPrice | 規格售價 |
| `quantity` | specQuantity | 規格庫存 |
| `barcode` | barcode (如果有) | |

## 4. Entity 缺口分析

### ❌ SellPack.java 缺少 3 個欄位

目前 `SellPack.java` 的 fields:
```
id, merchantId, productId, channelId, channelProductId,
channelProductUrl, title, sellingPrice, quantity, status,
lastSyncAt, createdAt, updatedAt
```

**缺少（DB 已有，Entity 沒有）：**

| 缺少的 field | DB 欄位 | 類型 |
|-------------|---------|------|
| `channelSpecId` | `channel_spec_id` | String |
| `channelProductName` | `channel_product_name` | String |
| `channelSpecName` | `channel_spec_name` | String |

### ❌ ChannelAdapter 缺少 fetchProducts 方法

目前 `ChannelAdapter.java` 沒有 `fetchProducts()` 方法。需要新增：

```java
/** 從通路拉取所有商品（含規格） */
List<ChannelProduct> fetchProducts(Long channelId);
```

需要新建 DTO: `ChannelProduct`（平台回傳的商品 DTO，不是我方的 product Entity）

### ❌ 需要新建 ChannelProduct DTO

```java
/**
 * 平台回傳的商品資料 DTO（Adapter 層使用）
 * 不是我方的 Product Entity
 */
@Data
public class ChannelProduct {
    private String channelProductId;      // 平台賣編
    private String channelProductName;    // 平台商品名
    private String channelProductUrl;     // 平台商品頁 URL
    private BigDecimal sellingPrice;      // 售價（單規時的價格）
    private Integer quantity;             // 庫存（單規時的庫存）
    private String status;                // 上架狀態（平台原始值）
    private String skuCode;               // SKU（如果有）

    private List<ChannelProductSpec> specs; // 規格列表（多規）
}

@Data
public class ChannelProductSpec {
    private String channelSpecId;         // 平台規格編號
    private String channelSpecName;       // 平台規格名稱
    private BigDecimal price;             // 規格售價
    private Integer quantity;             // 規格庫存
    private String skuCode;               // SKU
    private String barcode;               // 條碼
}
```

## 5. 平台上架狀態映射

| 平台狀態 | → sell_pack.status |
|---------|-------------------|
| 上架中 / 販售中 | `active` |
| 已下架 / 停售 | `inactive` |
| 審核中 / 待上架 | `pending` |
| 草稿 | `draft` |
| 審核失敗 | `failed` |

各平台狀態值不同，由各 Adapter 的 DataMapper 負責轉換。

## 6. 多規 vs 單規

```
單規商品（channel.multi_spec = false 或商品無規格）：
  1 個平台商品 → 1 個 sell_pack
  sell_pack.channel_spec_id = null
  sell_pack.channel_spec_name = null

多規商品（channel.multi_spec = true 且商品有規格）：
  1 個平台商品 + N 個規格 → N 個 sell_pack
  每個 sell_pack 有自己的:
    - channel_spec_id（平台規格編號）
    - channel_spec_name（平台規格名稱）
    - selling_price（該規格的售價）
    - quantity（該規格的庫存）
  共享同一個:
    - channel_product_id（平台賣編）
    - channel_product_name（平台商品名）
    - product_id（我方商品）
```

## 7. 冪等性保證

- **upsert key**: `(channel_id, channel_product_id, channel_spec_id)`
- 同一個通路 + 同一個平台賣編 + 同一個規格 = 同一筆 sell_pack
- 按兩下「同步商品」→ 第二次排在第一次後面（Kafka key = channelId）→ 跑完結果一樣
- sell_pack 表需要 **唯一約束**：`UNIQUE (channel_id, channel_product_id, channel_spec_id)`

### ❌ DB 缺少唯一約束

目前 `sell_pack` 沒有 `UNIQUE (channel_id, channel_product_id, channel_spec_id)` 約束。
有 index 但不是 unique：`idx_sellpack_channel_product (channel_id, channel_product_id)`

**需要加：**
```sql
CREATE UNIQUE INDEX idx_sellpack_channel_product_spec
    ON public.sell_pack (channel_id, channel_product_id, COALESCE(channel_spec_id, ''));
```
（用 COALESCE 處理 null 的 channel_spec_id，確保單規也能唯一）
