# FETCH_PRODUCTS — 同步商品事件流

> **依據: SCHEMA.md v4（2026-02-09）**
>
> PK 全部 VARCHAR(20) NanoID | SKU 欄位統一為 `sku` | product = SKU 級別（無 product_spec）

## 1. 觸發方式

| 觸發源 | 方式 | Topic | Key |
|--------|------|-------|-----|
| 前端按鈕 | `POST /api/v1/channels/{channelId}/sync-products` → API 發 TaskMessage | `{platform}.slow` | `channelId`（同通路排隊，不能並行跑兩次） |
| 排程（未來） | SchedulerJob 定時觸發（尚未實作） | `{platform}.slow` | `channelId` |

### 1.1 API 端點

```
POST /api/v1/channels/{channelId}/sync-products

Headers:
  Authorization: Bearer {jwt}

Path:
  channelId — 通路 ID（NanoID）

Response: 202 Accepted
{
  "messageId": "uuid-...",
  "message": "商品同步任務已送出"
}

Error:
  401 — 未登入
  403 — 無此通路權限
  404 — 通路不存在
  409 — 同步進行中（前次尚未完成）
```

**後端邏輯（ChannelController）：**
1. 從 JWT 取得 merchantId
2. 查 `channel` 表確認 channelId 屬於此 merchant 且 actived=true
3. 查 `channel → platform` 取得 platformType（momo/shopee/...）
4. 組裝 TaskMessage（taskAction=FETCH_PRODUCTS）
5. 發送到 `{platformType}.slow` topic，key=channelId
6. 回傳 202 + messageId

### 1.2 前端觸發

```
頁面: ChannelListView.vue → 通路列表頁
元件: ChannelCard.vue → 每張通路卡片
按鈕: 「同步商品」按鈕（圖示: sync icon）

呼叫:
  channelApi.syncProducts(channelId)
  → POST /api/v1/channels/{channelId}/sync-products

UI 回饋:
  ├── 點擊後按鈕 loading 狀態
  ├── 成功 → Toast「商品同步任務已送出，請稍候查看結果」
  ├── 409  → Toast「同步進行中，請稍後再試」
  └── 其他 → Toast 顯示錯誤訊息

結果查看:
  通路詳情頁 → 同步歷史 tab
  GET /api/v1/channels/{channelId}/sync-logs?type=FETCH_PRODUCTS
  → 顯示 channel_sync_logs 列表（時間、狀態、錯誤訊息）
```

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
  │     │    skuCode            ← 平台的 SKU（對應我方 sku）       │
  │     │    specs[] (如果有規格):                                │
  │     │      channelSpecId    ← 平台規格編號                    │
  │     │      channelSpecName  ← 平台規格名稱                    │
  │     │      specPrice        ← 規格售價                       │
  │     │      specQuantity     ← 規格庫存                       │
  │     │      skuCode/barcode  ← 規格的 SKU（對應我方 sku）       │
  │     │                                                      │
  │     │  Step A: 查 sell_pack                                 │
  │     │    SELECT * FROM sell_pack                            │
  │     │    WHERE channel_id = ? AND channel_product_id = ?    │
  │     │      AND COALESCE(channel_spec_id, '') = ?            │
  │     │    (多規: 一個 channelProductId + N 個 channelSpecId)  │
  │     │    (單規: channelSpecId = null → COALESCE = '')        │
  │     │                                                      │
  │     │  Step B: upsert sell_pack                             │
  │     │    ├── 找到 → UPDATE:                                 │
  │     │    │     sku                  = 平台 skuCode           │
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
  │     │          id                   = NanoID()（程式端產生）  │
  │     │          merchant_id          = 從 channel 取          │
  │     │          product_id           = Step C 的結果          │
  │     │          channel_id           = channelId             │
  │     │          sku                  = 平台 skuCode           │
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
  │     │    用 sku 查 product:                                  │
  │     │    SELECT * FROM product                              │
  │     │    WHERE merchant_id = ? AND sku = ?                  │
  │     │                                                      │
  │     │    ├── 找到 → sell_pack.product_id = product.id       │
  │     │    │                                                  │
  │     │    └── 找不到 → auto-create:                          │
  │     │          INSERT product:                              │
  │     │            id              = NanoID()                 │
  │     │            merchant_id     = merchantId               │
  │     │            sku             = skuCode or channelProductId │
  │     │            name            = 平台商品名                │
  │     │            spec_summary    = channelSpecName（如有）    │
  │     │            status          = 'active'                 │
  │     │          如果有 barcode → INSERT product_barcode:       │
  │     │            id              = NanoID()                 │
  │     │            product_id      = 新建的 product.id        │
  │     │            barcode         = barcode                  │
  │     │            is_primary      = true                     │
  │     │                                                      │
  │     │  ★ sell_pack.product_id 指向 product.id              │
  │     │  ★ sell_pack.sku = product.sku（冗餘，方便直接查）     │
  │     │  ★ 後續人工可修正自動建立的 product 資料               │
  │     │  ★ product = SKU 級別，不同規格 = 不同 product        │
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

| DB 欄位 | 類型 | 來源 | 說明 |
|---------|------|------|------|
| `id` | VARCHAR(20) | NanoID() | PK |
| `merchant_id` | VARCHAR(20) | channel.merchant_id | 從 channel 表取 |
| `product_id` | VARCHAR(20) | 自動 match / 新建 | FK → product |
| `channel_id` | VARCHAR(20) | msg.ownerId / channelId | FK → channel |
| `sku` | VARCHAR(100) | **平台 API skuCode** | 我方 SKU（= product.sku，冗餘存此表方便 match） |
| `channel_product_id` | VARCHAR(256) | **平台 API 回傳** | 平台賣編（商品 ID） |
| `channel_spec_id` | VARCHAR(256) | **平台 API 回傳** | 平台規格編號 |
| `channel_product_name` | VARCHAR(512) | **平台 API 回傳** | 平台顯示的商品名 |
| `channel_spec_name` | VARCHAR(256) | **平台 API 回傳** | 平台顯示的規格名 |
| `channel_product_url` | VARCHAR(1024) | **平台 API 回傳** | 平台商品頁 URL |
| `title` | VARCHAR(512) | 平台商品名（初始值，可修改） | 我方自訂的標題 |
| `selling_price` | DECIMAL(12,2) | **平台 API 回傳** | 售價 |
| `quantity` | INTEGER | **平台 API 回傳** | 庫存 |
| `status` | VARCHAR(20) | 平台狀態映射 | draft/pending/active/inactive/failed |
| `last_sync_at` | TIMESTAMPTZ | now() | 同步時間 |
| `created_at` | TIMESTAMPTZ | auto | |
| `updated_at` | TIMESTAMPTZ | auto | |

### product 表（自動建立時）

| DB 欄位 | 類型 | 來源 | 說明 |
|---------|------|------|------|
| `id` | VARCHAR(20) | NanoID() | PK |
| `merchant_id` | VARCHAR(20) | channel.merchant_id | |
| `sku` | VARCHAR(100) | skuCode or channelProductId | 優先用平台的 SKU，沒有就用賣編 |
| `name` | VARCHAR(512) | 平台商品名 | |
| `spec_summary` | VARCHAR(256) | channelSpecName | 規格摘要（如有） |
| `status` | VARCHAR(20) | 'active' | 預設 |

### product_barcode 表（自動建立時，如果平台有提供 barcode）

| DB 欄位 | 類型 | 來源 | 說明 |
|---------|------|------|------|
| `id` | VARCHAR(20) | NanoID() | PK |
| `product_id` | VARCHAR(20) | 新建的 product.id | FK |
| `barcode` | VARCHAR(50) | 平台的 barcode | |
| `is_primary` | BOOLEAN | true | 第一筆設為 primary |

## 4. Entity 缺口分析

### ❌ SellPack.java 缺少 4 個欄位

目前 `SellPack.java` 的 fields:
```
id, merchantId, productId, channelId, channelProductId,
channelProductUrl, title, sellingPrice, quantity, status,
lastSyncAt, createdAt, updatedAt
```

**缺少（DB 已有，Entity 沒有）：**

| 缺少的 field | DB 欄位 | 類型 |
|-------------|---------|------|
| `sku` | `sku` | String |
| `channelSpecId` | `channel_spec_id` | String |
| `channelProductName` | `channel_product_name` | String |
| `channelSpecName` | `channel_spec_name` | String |

**另外所有 ID 欄位需改型別：**

| 欄位 | 目前類型 | 應該是 |
|------|---------|--------|
| `id` | Long | String (NanoID) |
| `merchantId` | Long | String |
| `productId` | Long | String |
| `channelId` | Long | String |

### ❌ ChannelAdapter 缺少 fetchProducts 方法

目前 `ChannelAdapter.java` 沒有 `fetchProducts()` 方法。需要新增：

```java
/** 從通路拉取所有商品（含規格） */
List<ChannelProduct> fetchProducts(String channelId);
```

**注意：** `channelId` 是 `String`（NanoID），不是 `Long`。

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
★ product = SKU 級別。不同規格 = 不同 product = 不同 sell_pack。

單規商品（channel.multi_spec = false 或商品無規格）：
  1 個平台商品 → 1 個 sell_pack → 1 個 product
  sell_pack.channel_spec_id = null
  sell_pack.channel_spec_name = null
  sell_pack.sku = 平台的 skuCode（或用 channelProductId 代替）

多規商品（channel.multi_spec = true 且商品有規格）：
  1 個平台商品 + N 個規格 → N 個 sell_pack → N 個 product
  每個 sell_pack 有自己的:
    - channel_spec_id（平台規格編號）
    - channel_spec_name（平台規格名稱）
    - sku（該規格的 SKU → 對應到該規格的 product.sku）
    - selling_price（該規格的售價）
    - quantity（該規格的庫存）
  共享同一個:
    - channel_product_id（平台賣編）
    - channel_product_name（平台商品名）
  各自對應不同的 product:
    - product_id → 各自的 product.id

★ 同一個 product_group 可以綁多個 product（前端管理用，共享描述/圖片/品牌）
```

## 7. 冪等性保證

- **upsert key**: `(channel_id, channel_product_id, COALESCE(channel_spec_id, ''))`
- 同一個通路 + 同一個平台賣編 + 同一個規格 = 同一筆 sell_pack
- 按兩下「同步商品」→ 第二次排在第一次後面（Kafka key = channelId）→ 跑完結果一樣
- sell_pack 表已有 **唯一約束**：

```sql
CREATE UNIQUE INDEX idx_sellpack_upsert_key
    ON public.sell_pack (channel_id, channel_product_id, COALESCE(channel_spec_id, ''));
```

（用 COALESCE 處理 null 的 channel_spec_id，確保單規也能唯一）

## 8. 資料流完整性驗證矩陣

```
平台 API → ChannelProduct DTO → sell_pack 表 → SellPack Entity

平台欄位              DTO 欄位                DB 欄位                  Entity 欄位
─────────           ──────────            ──────────               ────────────
product_id    →     channelProductId  →   channel_product_id   →  channelProductId    ✅ OK
spec_id       →     channelSpecId     →   channel_spec_id      →  channelSpecId       ❌ Entity 缺
product_name  →     channelProductName→   channel_product_name →  channelProductName  ❌ Entity 缺
spec_name     →     channelSpecName   →   channel_spec_name    →  channelSpecName     ❌ Entity 缺
url           →     channelProductUrl →   channel_product_url  →  channelProductUrl   ✅ OK
sku           →     skuCode           →   sku                  →  sku                 ❌ Entity 缺
price         →     sellingPrice      →   selling_price        →  sellingPrice        ✅ OK
qty           →     quantity          →   quantity             →  quantity            ✅ OK
status        →     status            →   status               →  status              ✅ OK

平台 API → ChannelProduct DTO → product 表 → Product Entity（自動建立時）

平台欄位              DTO 欄位                DB 欄位                  Entity 欄位
─────────           ──────────            ──────────               ────────────
sku           →     skuCode           →   sku                  →  sku                 ✅ OK（改名後）
product_name  →     channelProductName→   name                 →  name                ✅ OK
spec_name     →     channelSpecName   →   spec_summary         →  specSummary         ✅ OK
barcode       →     barcode           →   product_barcode 表    →  ProductBarcode      ✅ OK

★ product.id, sell_pack.id 都是 VARCHAR(20) NanoID（程式端產生）
★ product = SKU 級別，不再有 product_spec 表
```
