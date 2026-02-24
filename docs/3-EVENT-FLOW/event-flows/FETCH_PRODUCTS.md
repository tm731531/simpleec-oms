# FETCH_PRODUCTS — 同步商品事件流

> **依據: SCHEMA.md v4（2026-02-09）**
>
> PK 全部 VARCHAR(20) NanoID | SKU 欄位統一為 `sku` | product = SKU 級別（無 product_spec）

## 1. 觸發方式

| 觸發源 | 方式 | Topic | Key |
|--------|------|-------|-----|
| 前端按鈕（唯一觸發方式） | `POST /api/v1/channels/{channelId}/sync-products` → API 發 TaskMessage | `{platform}.fast` | `channelId`（同通路排隊，不能並行跑兩次） |

> **設計決策：商品同步僅手動觸發，不走排程。**
> - 客戶自行決定何時同步哪個通路，逐通路操作
> - 避免系統同時對多平台發起大量 API 請求造成壓力
> - 訂單同步 (`FETCH_ORDERS`) 和退貨同步 (`FETCH_REFUND_ORDERS`) 才是排程自動觸發
>
> **Topic 為 fast（非 slow）：**
> - 列表 + diff 很快（一次分頁 API + Redis SET 比對）
> - 用戶期望立即看到結果（新增 N、移除 M）
> - 慢的 detail 抓取發散到 `{platform}.slow` topic 背景處理
> - 詳見 `multi-channel-architecture-design.md` Part 5

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
5. 發送到 `{platformType}.fast` topic，key=channelId
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

> **★ 三個 JOB 協作**：API → ChannelJob（拉取 + 路由）→ BackendJob（建立/更新）
> ChannelJob 不直接寫 DB，而是查 DB 判斷路由，再送 task.backend 讓 BackendJob 有序寫入。
> 這是為了**唯一性保證**：分散架構下多個 worker 可能同時處理同一商品，
> 用 Kafka key 排隊確保同一商品有序寫入，避免併發衝突。

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
═══════════════════════════════════════════════════════════════
 第一段：ChannelJob — 拉取 + 查 DB + 路由
═══════════════════════════════════════════════════════════════

ChannelJob (simpleec-channel-momo-slow)
  │
  │  FetchProductsActionService — 4 步生命週期：
  │
  │  ① setting(resource)
  │     - 從 msg 取得 channelId, merchantId
  │     - 從 resource 取得 adapter, taskProducer, productService
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
  │  ④ doAction()  ★ 兩段式拉取 + 路由
  │
  │     ┌──────────────────────────────────────────────────────┐
  │     │  Step 1: 取列表（GET LIST）                            │
  │     │                                                      │
  │     │  一般平台:                                             │
  │     │    adapter.fetchProductList(channelId)                │
  │     │    → 回傳商品編號 + 規格編號列表                        │
  │     │    → List<ChannelProductRef>                          │
  │     │                                                      │
  │     │  ★ Yahoo 特殊:                                       │
  │     │    API 請求 + 附帶 callbackUrl                        │
  │     │    → Yahoo 異步處理後回打 CSV 到 webhook               │
  │     │    callbackUrl = /webhook/yahoo/{merchantId}          │
  │     │    → 從 URI path 識別是誰的商品                        │
  │     │    → 解析 CSV → 商品編號列表                           │
  │     │    （對 ActionService 而言結果相同:                     │
  │     │     都是得到 List<ChannelProductRef>）                 │
  │     └──────────────────────────────────────────────────────┘
  │     │
  │     ▼
  │     ┌──────────────────────────────────────────────────────┐
  │     │  Step 2: 逐筆取明細（GET DETAIL）                      │
  │     │                                                      │
  │     │  for (ChannelProductRef ref : productRefs) {          │
  │     │    ChannelProduct detail =                            │
  │     │      adapter.fetchProductDetail(                      │
  │     │        channelId, ref.channelProductId);              │
  │     │                                                      │
  │     │    回傳的明細包含:                                     │
  │     │      channelProductId   ← 平台賣編                    │
  │     │      channelProductName ← 平台顯示的商品名              │
  │     │      channelProductUrl  ← 平台商品頁 URL               │
  │     │      sellingPrice       ← 售價                        │
  │     │      quantity           ← 庫存                        │
  │     │      status             ← 上架狀態                    │
  │     │      skuCode            ← 平台的 SKU                  │
  │     │      specs[] (如果有規格):                              │
  │     │        channelSpecId    ← 平台規格編號                 │
  │     │        channelSpecName  ← 平台規格名稱                 │
  │     │        specPrice        ← 規格售價                    │
  │     │        specQuantity     ← 規格庫存                    │
  │     │        skuCode/barcode  ← 規格的 SKU / 條碼           │
  │     └──────────────────────────────────────────────────────┘
  │     │
  │     ▼
  │     ┌──────────────────────────────────────────────────────┐
  │     │  Step 3: 查 DB 判斷路由 + 發 task.backend             │
  │     │                                                      │
  │     │  ★ 多規: 一個 product → N 個 spec → 各自判斷路由       │
  │     │  ★ 單規: 一個 product → 1 筆判斷路由                   │
  │     │                                                      │
  │     │  for (每個規格或整體) {                                 │
  │     │    String sku = spec.skuCode ?? detail.skuCode         │
  │     │                 ?? detail.channelProductId;            │
  │     │                                                      │
  │     │    // 查 DB: 這個 SKU 在我方有沒有對應的 product？      │
  │     │    Product product = productService                    │
  │     │      .findByMerchantAndSku(merchantId, sku);          │
  │     │                                                      │
  │     │    // Kafka key = channelId:channelProductId:channelSpecId
  │     │    // → 同一商品+規格在同一 partition → 有序寫入        │
  │     │    String key = channelId + ":"                        │
  │     │      + detail.channelProductId + ":"                   │
  │     │      + (spec.channelSpecId ?? "");                     │
  │     │                                                      │
  │     │    if (product == null) {                              │
  │     │      // ★ 沒有 product → 送 CREATE_PRODUCT            │
  │     │      //   BackendJob 建好 product 後                   │
  │     │      //   routeNext 接著發 CREATE_SELL_PACK            │
  │     │      taskProducer.send("task.backend", key,            │
  │     │        TaskMessage{                                    │
  │     │          action = "CREATE_PRODUCT",                    │
  │     │          payload = {                                   │
  │     │            sku, name, specSummary, barcode,            │
  │     │            channelId, channelProductId,                │
  │     │            channelSpecId, channelProductName,          │
  │     │            channelSpecName, channelProductUrl,         │
  │     │            sellingPrice, quantity, status              │
  │     │          }                                             │
  │     │        });                                             │
  │     │                                                      │
  │     │    } else {                                            │
  │     │      // ★ 有 product → 直接送 CREATE_SELL_PACK        │
  │     │      taskProducer.send("task.backend", key,            │
  │     │        TaskMessage{                                    │
  │     │          action = "CREATE_SELL_PACK",                  │
  │     │          payload = {                                   │
  │     │            productId = product.id,                     │
  │     │            channelId, channelProductId,                │
  │     │            channelSpecId, channelProductName,          │
  │     │            channelSpecName, channelProductUrl,         │
  │     │            sku, sellingPrice, quantity, status          │
  │     │          }                                             │
  │     │        });                                             │
  │     │    }                                                   │
  │     │  }                                                     │
  │     │                                                      │
  │     │  ★ ChannelJob 不寫 sell_pack / product                │
  │     │  ★ 全部送 task.backend，由 BackendJob 有序寫入         │
  │     └──────────────────────────────────────────────────────┘
  │
  │  ⑤ SyncLog
  │     - 成功 → channel_sync_logs: sync_type='FETCH_PRODUCTS', status='success'
  │     - 失敗 → channel_sync_logs: sync_type='FETCH_PRODUCTS', status='failed', error_message=...
  │
  ▼
═══════════════════════════════════════════════════════════════
 第二段：BackendJob — 建立 Product / SellPack
═══════════════════════════════════════════════════════════════

┌──────────────────────────────────────────────────────────────┐
│  Topic: task.backend                                         │
│  Key:   CH-MOMO-001:PROD-123:SPEC-A                         │
│  → 同一商品+規格在同一 partition，有序處理                      │
└──────────────────────────────────────────────────────────────┘
  │
  ▼
BackendJob (simpleec-backend-job)

  ┌─ 路由 A: CREATE_PRODUCT（product 不存在時）────────────────┐
  │                                                            │
  │  CreateProductActionService — 4 步生命週期:                  │
  │                                                            │
  │  setting(msg):                                              │
  │    從 payload 取 merchantId, sku, name, specSummary,        │
  │    barcode, channelId, channelProductId, channelSpecId,     │
  │    channelProductName, channelSpecName, channelProductUrl,  │
  │    sellingPrice, quantity, status                           │
  │                                                            │
  │  verify(msg):                                               │
  │    確認 merchantId 有效                                     │
  │                                                            │
  │  execute(msg):                                              │
  │    // 1. 再查一次（排隊期間可能已被建立）                     │
  │    Product product = productService                         │
  │      .findByMerchantAndSku(merchantId, sku);               │
  │                                                            │
  │    if (product == null) {                                   │
  │      // 2. 建立 product                                    │
  │      product = new Product();                               │
  │      product.id = NanoID();                                 │
  │      product.merchantId = merchantId;                       │
  │      product.sku = sku;                                     │
  │      product.name = name;                                   │
  │      product.specSummary = specSummary;                     │
  │      product.status = "active";                             │
  │      productService.insert(product);                        │
  │                                                            │
  │      // 3. 建立 barcode（如果有）                            │
  │      if (barcode != null) {                                 │
  │        productBarcodeService.insert(product.id, barcode);   │
  │      }                                                      │
  │    }                                                        │
  │                                                            │
  │  routeNext:                                                 │
  │    // ★ 條件式：payload 帶 channelId 才接力建 sell_pack    │
  │    //   FETCH_PRODUCTS 觸發 → 帶 channelId → 接力          │
  │    //   CSV 匯入觸發 → 不帶 channelId → 到此結束           │
  │    if (channelId != null) {                                 │
  │      payload += productId                                   │
  │      → taskProducer.send("task.backend",                   │
  │          同一個 key,                                        │
  │          TaskMessage{ action="CREATE_SELL_PACK",            │
  │            payload += productId })                          │
  │    }                                                        │
  │                                                            │
  └─────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
  ┌─ 路由 B: CREATE_SELL_PACK ─────────────────────────────────┐
  │  （來源 1: ChannelJob 直送 — product 已存在時）              │
  │  （來源 2: CREATE_PRODUCT routeNext — 建完 product 後接力） │
  │                                                            │
  │  CreateSellPackActionService — 4 步生命週期:                 │
  │                                                            │
  │  setting(msg):                                              │
  │    從 payload 取 merchantId, productId, channelId,          │
  │    channelProductId, channelSpecId,                         │
  │    channelProductName, channelSpecName, channelProductUrl,  │
  │    sku, sellingPrice, quantity, status                      │
  │                                                            │
  │  verify(msg):                                               │
  │    確認 productId 存在（防禦性檢查）                         │
  │    確認 channelId 存在                                      │
  │                                                            │
  │  execute(msg):                                              │
  │    // 1. 查 sell_pack 是否已存在                             │
  │    SellPack existing = sellPackService                      │
  │      .findByChannelAndProductSpec(                          │
  │        channelId, channelProductId, channelSpecId);         │
  │                                                            │
  │    if (existing != null) {                                  │
  │      // 2A. 更新                                            │
  │      existing.channelProductName = channelProductName;      │
  │      existing.channelSpecName = channelSpecName;            │
  │      existing.channelProductUrl = channelProductUrl;        │
  │      existing.sku = sku;                                    │
  │      existing.sellingPrice = sellingPrice;                  │
  │      existing.quantity = quantity;                           │
  │      existing.status = 上架狀態映射;                         │
  │      existing.lastSyncAt = now();                           │
  │      // ★ 補上 productId（如果之前是 null）                 │
  │      if (existing.productId == null) {                      │
  │        existing.productId = productId;                      │
  │      }                                                      │
  │      sellPackService.update(existing);                      │
  │                                                            │
  │    } else {                                                 │
  │      // 2B. 建立                                            │
  │      SellPack sp = new SellPack();                          │
  │      sp.id = NanoID();                                      │
  │      sp.merchantId = merchantId;                            │
  │      sp.productId = productId;                              │
  │      sp.channelId = channelId;                              │
  │      sp.sku = sku;                                          │
  │      sp.channelProductId = channelProductId;                │
  │      sp.channelSpecId = channelSpecId;                      │
  │      sp.channelProductName = channelProductName;            │
  │      sp.channelSpecName = channelSpecName;                  │
  │      sp.channelProductUrl = channelProductUrl;              │
  │      sp.title = channelProductName;                         │
  │      sp.sellingPrice = sellingPrice;                        │
  │      sp.quantity = quantity;                                 │
  │      sp.status = 上架狀態映射;                               │
  │      sp.lastSyncAt = now();                                 │
  │      sellPackService.insert(sp);                            │
  │    }                                                        │
  │                                                            │
  │  routeNext:                                                 │
  │    （無下游 — 商品同步終點）                                 │
  │                                                            │
  │  ★ sell_pack.product_id 指向 product.id                    │
  │  ★ sell_pack.sku = product.sku（冗餘，方便直接查）           │
  │  ★ 後續人工可修正自動建立的 product 資料                     │
  │  ★ product = SKU 級別，不同規格 = 不同 product              │
  └────────────────────────────────────────────────────────────┘

失敗時:
  ChannelJob 失敗:
    → task.failed → RetryDispatchJob
      ├── slow topic → 可重打（retryCount < maxRetry）→ retryCount++ → 重新送回 momo.slow
      └── 超過 maxRetry → task.dlt

  BackendJob 失敗:
    → task.failed → RetryDispatchJob
      ├── 可重打 → retryCount++ → 重新送回 task.backend（同 key → 同 partition）
      └── 超過 maxRetry → task.dlt
```

### 2.1 流程摘要圖

```
前端 → API → {platform}.slow → ChannelJob
                                  │
                                  ├─ fetchProductList()  ← Step 1: 取列表
                                  ├─ fetchProductDetail() ← Step 2: 取明細
                                  ├─ 查 DB: product 存在嗎？
                                  │
                                  ├─ 不存在 → task.backend (CREATE_PRODUCT)
                                  │             │
                                  │             ▼
                                  │           BackendJob
                                  │             │ 建 product + barcode
                                  │             │ routeNext ↓
                                  │             ▼
                                  │           task.backend (CREATE_SELL_PACK)
                                  │             │
                                  │             ▼
                                  │           BackendJob
                                  │             │ upsert sell_pack (掛上 productId)
                                  │             └─ 完成
                                  │
                                  └─ 存在 → task.backend (CREATE_SELL_PACK)
                                              │
                                              ▼
                                            BackendJob
                                              │ upsert sell_pack (掛上 productId)
                                              └─ 完成
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

### ❌ ChannelAdapter 缺少商品拉取方法（兩段式 API）

目前 `ChannelAdapter.java` 沒有商品拉取方法。需要新增**兩個**方法：

```java
/** Step 1: 取商品列表（編號 + 規格編號） */
List<ChannelProductRef> fetchProductList(String channelId);

/** Step 2: 取單一商品明細（含完整資料） */
ChannelProduct fetchProductDetail(String channelId, String channelProductId);
```

**注意：** `channelId` 是 `String`（NanoID），不是 `Long`。

**Yahoo 特殊**: `fetchProductList()` 內部走「API 請求 + callbackUrl → webhook 回打 CSV」模式。
callbackUrl = `/webhook/yahoo/{merchantId}`，從 URI path 識別商品歸屬。
對調用方而言，結果與其他平台一致（都回傳 `List<ChannelProductRef>`）。

### ❌ 需要新建 DTO

```java
/**
 * 商品列表項（Step 1 回傳）
 * 只有編號，不含完整資料
 */
@Data
public class ChannelProductRef {
    private String channelProductId;      // 平台商品編號
    // 有些平台列表 API 也回傳規格編號列表
    private List<String> channelSpecIds;  // 平台規格編號（可能為空）
}

/**
 * 平台回傳的商品完整資料 DTO（Step 2 回傳）
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
完整資料流:
  平台 API → ChannelProductRef (LIST) → ChannelProduct (DETAIL)
  → ChannelJob 查 DB → task.backend payload
  → BackendJob → sell_pack 表 / product 表

═══════ sell_pack 資料流 ═══════

平台 API → ChannelProduct DTO → task.backend payload → BackendJob → sell_pack 表

平台欄位              DTO 欄位                payload 欄位             DB 欄位
─────────           ──────────            ──────────               ────────────
product_id    →     channelProductId  →   channelProductId     →  channel_product_id      ✅
spec_id       →     channelSpecId     →   channelSpecId        →  channel_spec_id         ✅
product_name  →     channelProductName→   channelProductName   →  channel_product_name    ✅
spec_name     →     channelSpecName   →   channelSpecName      →  channel_spec_name       ✅
url           →     channelProductUrl →   channelProductUrl    →  channel_product_url     ✅
sku           →     skuCode           →   sku                  →  sku                     ✅
price         →     sellingPrice      →   sellingPrice         →  selling_price           ✅
qty           →     quantity          →   quantity             →  quantity                ✅
status        →     status            →   status               →  status                  ✅
(ChannelJob)  →     ---               →   productId            →  product_id              ✅ (查DB或CREATE_PRODUCT帶入)

═══════ product 資料流（自動建立時）═══════

平台 API → ChannelProduct DTO → task.backend payload → BackendJob → product 表

平台欄位              DTO 欄位                payload 欄位             DB 欄位
─────────           ──────────            ──────────               ────────────
sku           →     skuCode           →   sku                  →  sku                     ✅
product_name  →     channelProductName→   name                 →  name                    ✅
spec_name     →     channelSpecName   →   specSummary          →  spec_summary            ✅
barcode       →     barcode           →   barcode              →  product_barcode 表       ✅

★ product.id, sell_pack.id 都是 VARCHAR(20) NanoID（程式端產生）
★ product = SKU 級別，不再有 product_spec 表
★ ChannelJob 不直接寫 DB，全部透過 task.backend → BackendJob 寫入
```
