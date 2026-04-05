# 商品同步流程規則 (Product Sync Flow)

> **核心概念：**
> - OMS 的通路層只有 `sell_pack`（套包），不直接擁有 `product`
> - `SYNC_PACK`：Channel Job 從平台抓取套包資訊 → 送至 `task.backend`
> - `SYNC_PRODUCT`：Backend Job (SyncPackHandler) 完成套包 upsert 後，自動觸發 → 建立/更新 product 記錄
> - **Channel Job 不負責 Pack→Product mapping**，該邏輯完全屬於 Backend Job

---

## 1. 業務規則

### 1.1 核心概念對應

| 層級 | 物件 | 說明 |
|------|------|------|
| 通路層 | `sell_pack` | 在特定通路上架的商品套包（含通路端 ID、規格） |
| OMS 內部 | `product` | OMS 的 SKU 級別商品，跨通路共用 |
| 對應關係 | sell_pack ↔ product | 多對一；多個套包可對應同一 product |

### 1.2 同步流程說明

1. **SYNC_PACK**：Channel Job 從平台 API 抓取商品/規格清單
   - 將平台欄位轉換為 OMS sell_pack 結構
   - 送至 `task.backend` topic，**不**直接寫 DB
2. **Backend Job (SyncPackHandler)**：
   - 接收 SYNC_PACK → upsert `sell_pack` 表
   - 決定 Pack→Product mapping（查 SKU / 名稱+規格 / 新建）
   - 自動觸發 SYNC_PRODUCT 至 `task.backend`
3. **Backend Job (SyncProductHandler)**：
   - 接收 SYNC_PRODUCT → upsert `product` 表
   - **同 SKU 絕不建立重複 product**

### 1.3 Shopify 特殊規則

- 每個 variant 對應唯一的 `inventory_item_id`（1:1）
- SYNC_PACK 時必須將 `inventory_item_id` 存入 `sell_pack.platform_metadata JSONB`
- 後續庫存調整（Inventory Sync）依賴此欄位

### 1.4 觸發時機

| 觸發方式 | 說明 |
|----------|------|
| Scheduler 定時觸發 | 排程每隔 N 小時發送 SYNC_PACK task |
| 商家手動觸發 | POST /api/channels/{id}/sync-packs |
| 訂單同步後觸發 | 若 sell_pack 不存在時，自動補同步 |

---

## 2. 前端 / API

### 2.1 端點清單

| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/products` | 商品列表（分頁、搜尋） |
| GET | `/api/products/{id}` | 商品詳情 |
| GET | `/api/sell-packs/{channelId}` | 指定通路的套包列表 |
| GET | `/api/sell-packs/{id}` | 套包詳情（含 platform_metadata preview） |
| POST | `/api/channels/{id}/sync-packs` | 手動觸發 SYNC_PACK |

### 2.2 GET /api/products 回應結構（範例）

```json
{
  "data": [
    {
      "id": "prod_nanoId20chars",
      "sku": "SKU-001",
      "name": "商品名稱",
      "merchantId": "merch_nanoId20chars",
      "sellPacks": [
        {
          "id": "pack_nanoId20chars",
          "channelId": "chan_nanoId20chars",
          "channelProductId": "1234567",
          "channelSpecId": "9876543",
          "packName": "紅色 / L",
          "price": 399,
          "syncStatus": "SYNCED",
          "lastSyncAt": "2026-04-05T08:00:00Z"
        }
      ]
    }
  ],
  "pagination": { "page": 1, "pageSize": 20, "total": 150 }
}
```

### 2.3 POST /api/channels/{id}/sync-packs

- Response: `{ "requestId": "...", "message": "Sync task enqueued" }`
- 非同步執行，前端應 polling sync status 或 WebSocket 通知

### 2.4 UI 顯示要求

- 套包列表顯示：套包名稱、通路、連結的 product SKU、同步狀態、上次同步時間
- platform_metadata preview：顯示 JSON 摘要（折疊展開）
- 未對應 product 的套包需標示警告

---

## 3. Kafka 契約

### 3.1 Topic 對應

| 方向 | Topic | taskType |
|------|-------|----------|
| Scheduler → Channel Job | `scheduler` | `SYNC_PACK` |
| Channel Job → Backend | `task.backend` | `SYNC_PACK` |
| Backend (SyncPackHandler) → Backend | `task.backend` | `SYNC_PRODUCT` |

### 3.2 Scheduler → Channel Job（SYNC_PACK 觸發）

```json
{
  "header": {
    "taskType": "SYNC_PACK",
    "merchantId": "merch_nanoId20chars",
    "platformId": "shopify",
    "channelId": "chan_nanoId20chars",
    "requestId": "req_nanoId20chars",
    "timestamp": "2026-04-05T08:00:00Z",
    "source": "scheduler",
    "version": "1.0",
    "isRollback": false
  },
  "body": {
    "timestamp": "2026-04-05T08:00:00Z"
  }
}
```

> Scheduler 只傳 timestamp，**時間窗口邏輯完全由 Channel Job 自主決定**。

### 3.3 Channel Job → task.backend（SYNC_PACK 結果）

```json
{
  "header": {
    "taskType": "SYNC_PACK",
    "merchantId": "merch_nanoId20chars",
    "platformId": "shopify",
    "channelId": "chan_nanoId20chars",
    "requestId": "req_nanoId20chars",
    "timestamp": "2026-04-05T08:01:00Z",
    "source": "channel-job",
    "version": "1.0",
    "isRollback": false
  },
  "body": {
    "sellPackId": "pack_nanoId20chars",
    "channelProductId": "shopify-product-id",
    "channelSpecId": "shopify-variant-id",
    "packName": "紅色 / L",
    "price": 399,
    "currency": "TWD",
    "platformMetadata": {
      "shopify": {
        "inventory_item_id": "shopify-inventory-item-id"
      }
    }
  }
}
```

**規則：**
- `sellPackId`：若已存在則填入 NanoID；首次同步為 `null`（Backend Job 負責建立）
- **所有 ID 使用 NanoID**；`channelProductId` / `channelSpecId` 存平台原始值（字串）
- `platformMetadata` 結構依平台不同，**以平台名稱為 key**

### 3.4 Backend (SyncPackHandler) → task.backend（SYNC_PRODUCT）

```json
{
  "header": {
    "taskType": "SYNC_PRODUCT",
    "merchantId": "merch_nanoId20chars",
    "platformId": "shopify",
    "channelId": "chan_nanoId20chars",
    "requestId": "req_nanoId20chars",
    "timestamp": "2026-04-05T08:01:05Z",
    "source": "backend-job",
    "version": "1.0",
    "isRollback": false
  },
  "body": {
    "sellPackId": "pack_nanoId20chars",
    "productId": "prod_nanoId20chars",
    "sku": "SKU-001",
    "packName": "紅色 / L",
    "price": 399
  }
}
```

---

## 4. Channel Job

### 4.1 職責邊界

| 職責 | Channel Job | Backend Job |
|------|-------------|-------------|
| 呼叫平台 API | ✅ | ❌ |
| 時間窗口決策 | ✅ 自主決定 | ❌ |
| 欄位格式轉換 | ✅ | ❌ |
| upsert sell_pack | ❌ | ✅ |
| Pack→Product mapping | ❌ | ✅ |
| 建立/更新 product | ❌ | ✅ |

### 4.2 SyncPackChannelJob 核心流程

```
1. 從 scheduler topic 接收 SYNC_PACK（含 timestamp）
2. 自主決定抓取範圍（全量 or 增量，依 platform.capabilities）
3. 呼叫平台 API 取得商品/規格清單
4. 對每個 variant/spec 轉換為 SYNC_PACK 訊息結構
5. 批次送至 task.backend topic
```

### 4.3 平台能力查詢

```java
// 使用 platform.capabilities，禁止 hardcode 平台判斷
boolean supportsIncrementalSync = platform.capabilities
    .path("product.incrementalSync").asBoolean(false);
boolean needsDetailFetch = platform.capabilities
    .path("product.requiresDetailFetch").asBoolean(false);
```

### 4.4 Shopify 特殊處理（inventory_item_id）

```java
// Shopify variants 回應中直接包含 inventory_item_id
// 必須存入 platformMetadata
platformMetadata.put("shopify", objectMapper.createObjectNode()
    .put("inventory_item_id", variant.get("inventory_item_id").asText()));
```

### 4.5 錯誤處理

- 平台 API 回傳 rate limit (429)：退避重試，最多 3 次
- 單一 pack 轉換失敗：記錄 error log，跳過該筆，**不中斷整批**
- 批次送 Kafka 失敗：整批重試（idempotent，Backend 端 upsert 安全）

---

## 5. 後端 Job

### 5.1 SyncPackHandler

**觸發：** 監聽 `task.backend` topic，`taskType = SYNC_PACK`

**流程：**
```
1. 根據 (channelId + channelProductId + channelSpecId) 查詢 sell_pack
2. 若不存在 → 生成 NanoID，INSERT sell_pack
3. 若已存在 → UPDATE sell_pack（價格、名稱、platform_metadata）
4. 查詢或建立 Pack→Product mapping：
   a. 先以 SKU 比對現有 product（sku + merchantId）
   b. 無 SKU 時以 packName + variant 比對
   c. 以上皆無 → 準備新建 product 資料
5. 自動發送 SYNC_PRODUCT 訊息至 task.backend
```

### 5.2 SyncProductHandler

**觸發：** 監聽 `task.backend` topic，`taskType = SYNC_PRODUCT`

**流程：**
```
1. 以 (sku + merchantId) 查詢 product
2. 若不存在 → 生成 NanoID，INSERT product
3. 若已存在 → UPDATE（名稱、價格等，SKU 不可變更）
4. 更新 sell_pack.product_id（若尚未關聯）
```

### 5.3 Pack→Product Mapping 規則

| 優先順序 | 條件 | 動作 |
|----------|------|------|
| 1 | packName 帶有明確 SKU 標示 | 以 SKU + merchantId 找 product |
| 2 | 名稱 + 規格完全一致 | 對應現有 product |
| 3 | 以上皆不符 | 建立新 product，sku = `AUTO-{nanoId}` |

**紅線：同一 merchantId + SKU 不得存在兩個 product 記錄。**

---

## 6. DB

### 6.1 相關資料表

```sql
-- 套包（通路商品）
CREATE TABLE sell_pack (
    id               VARCHAR(20)    PRIMARY KEY,  -- NanoID
    merchant_id      VARCHAR(20)    NOT NULL,
    channel_id       VARCHAR(20)    NOT NULL,
    product_id       VARCHAR(20),                 -- FK to product，mapping 後填入
    channel_product_id VARCHAR(100) NOT NULL,     -- 平台商品 ID（原始字串）
    channel_spec_id  VARCHAR(100),               -- 平台規格/variant ID
    pack_name        VARCHAR(500),
    price            NUMERIC(12,2),
    currency         VARCHAR(10),
    platform_metadata JSONB,
    sync_status      VARCHAR(20)    DEFAULT 'PENDING',
    last_sync_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ    DEFAULT NOW(),
    updated_at       TIMESTAMPTZ    DEFAULT NOW()
);

-- Shopify platform_metadata 結構範例：
-- { "shopify": { "inventory_item_id": "123456789" } }

-- OMS 商品（SKU 級別）
CREATE TABLE product (
    id          VARCHAR(20)   PRIMARY KEY,  -- NanoID
    merchant_id VARCHAR(20)   NOT NULL,
    sku         VARCHAR(100),
    name        VARCHAR(500),
    description TEXT,
    created_at  TIMESTAMPTZ   DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   DEFAULT NOW(),
    UNIQUE (merchant_id, sku)
);
```

### 6.2 Upsert 模式（sell_pack）

```sql
INSERT INTO sell_pack (id, merchant_id, channel_id, channel_product_id, channel_spec_id, ...)
VALUES (...)
ON CONFLICT (channel_id, channel_product_id, channel_spec_id)
DO UPDATE SET
    pack_name        = EXCLUDED.pack_name,
    price            = EXCLUDED.price,
    platform_metadata = sell_pack.platform_metadata || EXCLUDED.platform_metadata,
    sync_status      = 'SYNCED',
    last_sync_at     = NOW(),
    updated_at       = NOW();
```

> `platform_metadata` 使用 `||` 合併（保留既有其他平台資料），不整個覆蓋。

### 6.3 必要索引

```sql
-- sell_pack upsert 查詢
CREATE UNIQUE INDEX idx_sell_pack_channel_product_spec
    ON sell_pack (channel_id, channel_product_id, channel_spec_id);

-- 按商家 + 通路查詢套包
CREATE INDEX idx_sell_pack_merchant_channel
    ON sell_pack (merchant_id, channel_id);

-- product SKU 唯一
CREATE UNIQUE INDEX idx_product_merchant_sku
    ON product (merchant_id, sku) WHERE sku IS NOT NULL;
```

---

## 7. Platform API

### 7.1 各平台商品抓取方式差異

| 平台 | List API | 是否需要 Detail 呼叫 | 需擷取的關鍵 ID |
|------|---------|---------------------|----------------|
| Shopee | `GET /v2/product/get_item_list` | **需要**：`get_item_base_info`（最多 50 個/次） | `item_id`, `model_id` |
| Shopify | `GET /admin/api/{v}/products.json` | **不需要**（variants 已在 response） | `product_id`, `variant_id`, `inventory_item_id` |
| Cyberbiz | `GET /v1/products` + `GET /v1/products/{id}/product_variants` | **需要**（variants 須獨立呼叫） | `product_id`, `variant_id` |
| Shopline | `GET /openapi2/v1/products/products.json` | **不需要**（variants 已在 response） | `id`, `variants[].id` |

### 7.2 Shopee 批次規則

```
get_item_list → 取得 item_id 清單（分頁，max 100/page）
↓
每 50 個 item_id → 一次 get_item_base_info（含 models/規格）
↓
轉換每個 model 為 SYNC_PACK 訊息
```

### 7.3 Shopify inventory_item_id 位置

```json
{
  "products": [{
    "id": 123,
    "variants": [{
      "id": 456,
      "inventory_item_id": 789,
      "sku": "SKU-001",
      "price": "399.00"
    }]
  }]
}
```

> `inventory_item_id` 直接在 variant 物件中，無需額外 API 呼叫。

---

## 8. Cache

### 8.1 sell_pack 快取策略

| 快取對象 | Key 格式 | TTL | 失效時機 |
|----------|----------|-----|---------|
| 套包清單（by channel） | `sell_pack:channel:{channelId}` | 30 min | SYNC_PACK 完成後主動清除 |
| 套包詳情 | `sell_pack:{id}` | 60 min | upsert 後主動清除 |
| product 詳情 | `product:{id}` | 60 min | upsert 後主動清除 |

### 8.2 SKU mapping 快取（防重複查詢）

- SyncPackHandler 批次處理時，同一批次內快取已處理的 SKU→productId mapping
- 僅限批次記憶體快取（Map），**不寫 Redis**，批次完成後丟棄

---

## 9. QA Checklist

### Channel Job 層

- [ ] Channel Job **不**直接寫 DB；SYNC_PACK 結果只送至 `task.backend`
- [ ] Shopify: `inventory_item_id` 已正確存入 `platformMetadata.shopify.inventory_item_id`
- [ ] Shopee: `get_item_base_info` 以最多 50 個 item_id 批次呼叫（不超量）
- [ ] 平台判斷使用 `platform.capabilities.path("key").asBoolean(false)`，無 hardcode
- [ ] 單一 pack 轉換失敗不中斷整批處理

### Backend Job 層

- [ ] SyncPackHandler：upsert 使用 `ON CONFLICT (channel_id, channel_product_id, channel_spec_id)`
- [ ] SyncPackHandler 完成 upsert 後自動觸發 SYNC_PRODUCT（不依賴外部觸發）
- [ ] 同 merchantId + SKU 不建立重複 product（UNIQUE index 保護）
- [ ] Pack→Product mapping 邏輯：SKU → 名稱+規格 → 新建，順序正確
- [ ] `platform_metadata` 以 `||` 合併而非整個覆蓋（保留其他平台資料）

### 資料正確性

- [ ] 重複執行 SYNC_PACK（同通路、同商品）不產生重複 sell_pack 記錄
- [ ] 手動觸發 POST /api/channels/{id}/sync-packs 回應為非同步（不 block HTTP）
- [ ] sell_pack.product_id 在 SyncProductHandler 完成後已正確填入
- [ ] Shopify 的 inventory_item_id 可從 `sell_pack.platform_metadata` 正確讀回
