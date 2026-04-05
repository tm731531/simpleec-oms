# Flow: Inventory Sync（庫存同步）

庫存同步流程：商家在 OMS 調整庫存 → 推送至各平台。

---

## 1. 業務規則

### 1.1 庫存來源唯一原則
- `sell_pack.quantity` 是 OMS 的**唯一庫存真實來源（Single Source of Truth）**
- `sell_pack_inventory` 是每個平台的**快照（snapshot）**，不是來源
- 商家永遠只在 OMS 改庫存；OMS 負責向平台同步

### 1.2 觸發條件
- 商家透過 OMS 前端或 API 更新 `sell_pack.quantity`
- 系統自動找出該 sell_pack 所有 active channel，逐一發送 `UPDATE_INVENTORY` Kafka 訊息

### 1.3 多地點平台（multiLocation）
- `platform.capabilities` JSONB 中 `multiLocation: true` 的平台（例如 Shopify），支援多倉庫庫存
- OMS **只推送到 `channel_location.is_sync_target = true` 的那一個地點**
- 不推送到其他地點；平台側的地點分配由商家自行在平台管理

### 1.4 無地點概念平台（no-location）
- `multiLocation: false`（或 capabilities 中無此欄位）的平台（例如 Shopee、Yahoo）
- OMS 推送 `sell_pack.quantity` 的總量到平台的單一庫存欄位

### 1.5 同步狀態
- 每次推送後，更新 `sell_pack_inventory.quantity` 和 `last_synced_at`
- 推送失敗時，記錄 `sync_status = 'failed'`，並送入 `task.failed` topic 做 retry

### 1.6 冪等性說明
- 庫存更新**不是冪等操作**：同一個 UPDATE_INVENTORY 發兩次，代表兩次不同的用戶動作
- 因此此流程**不做 Redis dedup**（dedup 僅用於 order 流程）

---

## 2. 前端 / API

### 2.1 Endpoint
```
PUT /api/sell-packs/{id}/quantity
```
Controller: `UserSellPackController`
Service: `SellPackSyncService`

### 2.2 Request Body
```json
{
  "newQuantity": 100
}
```

### 2.3 Response
回傳更新後的 sell_pack 物件，包含 `sync_status` 欄位：
```json
{
  "id": "abc123456789012345",
  "quantity": 100,
  "syncStatus": "syncing",
  "updatedAt": "2026-03-17T08:00:00Z"
}
```

### 2.4 Validation
- `newQuantity` 必須為整數，`>= 0`
- `id` 必須存在且屬於當前 merchant
- 非法輸入回傳 `400 Bad Request`

### 2.5 多地點平台的 UI 呈現
- 若商家的某個 channel 對應 `multiLocation = true` 的平台
- 前端應從 `sell_pack_inventory` 讀取**各地點庫存快照**，分行顯示
- 只有 `is_sync_target = true` 的地點會被 OMS 同步；其他地點顯示為 read-only

---

## 3. Kafka 契約

### 3.1 Topic
```
{platform}.fast
```
例如：`shopify.fast`、`shopee.fast`

### 3.2 TaskType
```
UPDATE_INVENTORY
```

### 3.3 訊息結構
```json
{
  "header": {
    "taskType": "UPDATE_INVENTORY",
    "merchantId": "merch_nanoid_20chars",
    "platformId": "platform_nanoid_20ch",
    "channelId": "channel_nanoid_20chr",
    "requestId": "uuid-v4",
    "timestamp": "2026-03-17T08:00:00Z",
    "source": "api",
    "version": "1.0",
    "isRollback": false
  },
  "body": {
    "sellPackId": "sellpack_nanoid_20ch",
    "operation": "SET",
    "oldValue": 80,
    "newValue": 100
  }
}
```

### 3.4 body 欄位說明

| 欄位 | 類型 | 必填 | 說明 |
|------|------|------|------|
| `sellPackId` | VARCHAR(20) NanoID | ✅ | OMS internal ID，**不是平台 ID** |
| `operation` | enum: `SET` / `ADJUST` | ✅ | 操作類型 |
| `oldValue` | integer | ✅ | **必填**，舊庫存值 |
| `newValue` | integer | ✅ | 新庫存值 |

**為何 `oldValue` 必填：**
Shopify 支援兩種 API：
- `inventory_levels/set`：直接設定絕對值
- `inventory_levels/adjust`：送 delta（`available_adjustment`）

Delta = `newValue - oldValue`。Channel Job 若沒有 `oldValue`，無法計算 delta；
即使現在只用 set，未來支援 adjust 模式時也需要 oldValue。因此一律必帶。

### 3.5 Source 固定值
`source: "api"` — 由 `SellPackSyncService` 發送，代表 merchant 主動操作

---

## 4. Channel Job

### 4.1 Handler 類別
`UpdateInventoryHandler`（位於 `oneec-channel` 模組下各平台 package）

### 4.2 Translation（NanoID → Platform ID）
Channel Job 負責將 body 中的 `sellPackId`（NanoID）轉換為平台可識別的 ID：

```java
// ChannelService.getSellPackChannelIds(sellPackId, channelId)
// returns: { channelProductId, channelSpecId }
```

**規則：永遠不 hardcode 平台名稱。**
用 `platform.capabilities.path("key").asBoolean(false)` 判斷行為。

### 4.3 多地點平台（multiLocation=true）

```java
boolean multiLocation = capabilities.path("multiLocation").asBoolean(false);

if (multiLocation) {
    // 1. 查詢 is_sync_target = true 的唯一地點
    Optional<Map<String, String>> syncTarget = channelService.getSyncTargetLocation(channelId);
    if (syncTarget.isEmpty()) {
        log.error("multiLocation=true but no sync-target location configured (channel={})", channelId);
        return;
    }
    String locationNanoId     = syncTarget.get().get("id");
    String platformLocationId = syncTarget.get().get("platformLocationId");

    // 2. 呼叫平台 API（TODO: Shopify adapter 尚未實作）
    //    當前實作：記錄 warn log，等 adapter 就緒後補上實際 API 呼叫
    log.warn("UPDATE_INVENTORY multi-location not yet fully implemented for channel={} location={}",
             channelId, platformLocationId);

    // 3. 即使 adapter 未呼叫，仍先寫入 snapshot（反映意圖，UI 可即時顯示）
    channelService.upsertInventorySnapshot(sellPackId, locationNanoId, newValue);
}
```

- 能力旗標：`capabilities.path("multiLocation").asBoolean(false)`（永遠不 hardcode 平台名稱）
- `getSyncTargetLocation(channelId)` 回傳 `{ id: locationNanoId, platformLocationId: ... }`
- `inventory_item_id`（Shopify 特有）存在 `sell_pack.platform_metadata` JSONB，不是 variant_id
- Shopify adapter 實作後再補上實際 `setInventoryLevel()` 呼叫；目前 stub 並提前寫 snapshot

### 4.4 無地點平台（multiLocation=false）

```java
if (!multiLocation) {
    // 1. 呼叫平台 API（以 cyberbizAdapter 為例；未來可依 platformCode 路由到對應 adapter）
    adapter.setCredentials(channel.getToken(), channel.getToken2());
    adapter.updateVariantInventory(channelProductId, channelSpecId, newValue);

    // 2. 確認推送成功後，以 channel_location_id = NULL 寫入 snapshot
    channelService.upsertInventorySnapshot(sellPackId, null, newValue);
}
```

### 4.5 Shopee asyncInventory 處理

```java
boolean isAsync = platform.capabilities.path("asyncInventory").asBoolean(false);

if (isAsync) {
    AsyncResult result = shopeeApi.updateStock(...);
    String taskId = result.getTaskId();
    // 存 taskId，輪詢結果
    // 檢查 failure_list，若非空則視為部分失敗
}
```

---

## 5. 後端 Job

### 5.1 同步結果寫回 DB
snapshot **直接由 Channel Job 在同一個 job 內透過 JDBC upsert 寫入**，不經 Kafka 傳回後端。
呼叫入口：`channelService.upsertInventorySnapshot(sellPackId, locationNanoId, quantity)`

因為 `channel_location_id` 可能為 NULL，無法用單一 ON CONFLICT 子句同時覆蓋兩種情況，
改用兩條 **partial index** upsert：

```sql
-- 無地點平台（channel_location_id IS NULL）
INSERT INTO sell_pack_inventory (id, sell_pack_id, channel_id, channel_location_id, quantity, last_synced_at)
VALUES (...)
ON CONFLICT (sell_pack_id, channel_id)
    WHERE channel_location_id IS NULL
DO UPDATE SET
    quantity = EXCLUDED.quantity,
    last_synced_at = NOW();

-- 多地點平台（channel_location_id IS NOT NULL）
INSERT INTO sell_pack_inventory (id, sell_pack_id, channel_id, channel_location_id, quantity, last_synced_at)
VALUES (...)
ON CONFLICT (sell_pack_id, channel_id, channel_location_id)
    WHERE channel_location_id IS NOT NULL
DO UPDATE SET
    quantity = EXCLUDED.quantity,
    last_synced_at = NOW();
```

### 5.2 多地點 vs 無地點的 channel_location_id
- 多地點平台：`channel_location_id = syncTarget.id`（locationNanoId，非 platformLocationId）
- 無地點平台：`channel_location_id = NULL`（傳 `null` 進 `upsertInventorySnapshot`）

### 5.3 失敗處理
```java
// 平台 API 失敗時
sellPackInventoryRepo.updateSyncStatus(sellPackId, channelId, "failed");
kafkaTemplate.send("task.failed", buildRetryMessage(originalMessage));
```

- `task.failed` topic 由 retry consumer 處理，指數退避重試
- 最終失敗需記 alert，不可靜默丟失

### 5.4 Shopee failure_list 處理
Shopee `update_stock` 回傳的 `failure_list` 若非空，代表部分 SKU 失敗：

```java
if (!result.getFailureList().isEmpty()) {
    log.error("Shopee partial update failure: {}", result.getFailureList());
    // 逐筆記錄失敗的 item，送 task.failed
}
```

---

## 6. DB

### 6.1 相關資料表

| 資料表 | 用途 |
|-------|------|
| `sell_pack` | OMS master 庫存（quantity = 真實值） |
| `sell_pack_inventory` | 各平台快照（quantity = 上次同步值） |
| `channel_location` | 平台地點清單，`is_sync_target` 標記推送目標 |
| `platform` | 平台能力設定，`capabilities` JSONB |

### 6.2 sell_pack_inventory 查詢規則

**正確寫法（必須帶 sell_pack_id）：**
```sql
-- 多地點平台
SELECT * FROM sell_pack_inventory
WHERE sell_pack_id = ? AND channel_location_id = ?;

-- 無地點平台
SELECT * FROM sell_pack_inventory
WHERE sell_pack_id = ? AND channel_location_id IS NULL;
```

**❌ 禁止寫法（缺少 sell_pack_id，沒有索引）：**
```sql
-- 永遠不這樣查
SELECT * FROM sell_pack_inventory WHERE channel_id = ?;
```

索引建在 `(sell_pack_id, channel_id, channel_location_id)`，缺少 `sell_pack_id` 會 seq scan。

### 6.3 Upsert 模式
`sell_pack_inventory` 一律使用 `INSERT ... ON CONFLICT DO UPDATE`，不用先 SELECT 再決定 INSERT/UPDATE。

Conflict key: `(sell_pack_id, channel_id, channel_location_id)`

```sql
-- channel_location_id IS NULL 的情況需要 partial unique index
CREATE UNIQUE INDEX uix_spi_no_location
    ON sell_pack_inventory (sell_pack_id, channel_id)
    WHERE channel_location_id IS NULL;
```

### 6.4 channel_location 查詢
```sql
SELECT * FROM channel_location
WHERE channel_id = :channelId AND is_sync_target = true
LIMIT 1;
```

---

## 7. Platform API

各平台庫存更新 API 差異：

| 平台 | capabilities | API Endpoint | 特殊說明 |
|------|-------------|-------------|---------|
| Shopee | `asyncInventory: true` | `POST /api/v2/product/update_stock` | 非同步，回傳 `task_id`；需輪詢結果；檢查 `failure_list` |
| Shopify | `multiLocation: true` | `POST /admin/api/inventory_levels/set` 或 `/adjust` | 需 `inventory_item_id`（來自 `platform_metadata`）+ `location_id`（is_sync_target） |
| Cyberbiz | — | `PUT /products/{pid}/product_variants/{vid}` | 需要 `channelProductId`（pid）和 `channelSpecId`（vid）兩個 ID |
| Shopline | — | `PUT /products/{id}/update_quantity` | form-data，送**絕對值** |

### 7.1 Shopify：inventory_item_id 來源
```java
// 正確：從 platform_metadata 取
String inventoryItemId = sellPack.getPlatformMetadata()
    .path("inventory_item_id").asText();

// ❌ 錯誤：不能用 variant_id
// String variantId = channelIds.getChannelSpecId(); // 這不對
```

### 7.2 Shopee：輪詢 task 結果
```java
String taskId = shopeeResponse.getTaskId();
// 存入 pending_tasks table 或 Redis
// 由獨立 task poller job 處理，避免阻塞主流程
```

---

## 8. Cache

### 8.1 庫存同步不使用 Redis Cache
- 庫存是高頻變動數據，cache 有過期風險，correctness 優先
- 不快取 `sell_pack.quantity`、`sell_pack_inventory.quantity`

### 8.2 不適用 Dedup
- 庫存更新**不是冪等操作**：每次 UPDATE_INVENTORY 都是新的 merchant 動作
- 若做 dedup，第二次相同操作會被跳過 → 庫存不正確
- **此流程明確排除 Redis dedup 機制**

### 8.3 Channel Location 可考慮短暫快取
`channel_location.is_sync_target` 為靜態配置，可 cache 在 application context：
- TTL: 5 分鐘，或在 channel config 更新時主動 evict
- 節省頻繁查 DB 的開銷

---

## 9. QA Checklist

### 核心邏輯驗證
- [ ] UPDATE_INVENTORY Kafka 訊息 body 包含 `oldValue`（非 null、非 0 的假值）
- [ ] `sell_pack_inventory` 在推送成功後有更新 `quantity` 和 `last_synced_at`
- [ ] 推送失敗時 `sync_status` 設為 `'failed'`，並有送進 `task.failed` topic

### Shopify 多地點驗證
- [ ] 使用 `sell_pack.platform_metadata.inventory_item_id`（不是 `channelSpecId`/`variant_id`）
- [ ] 只推送到 `channel_location.is_sync_target = true` 的地點
- [ ] 其他地點的 `sell_pack_inventory` 不被誤更新

### Shopee 非同步驗證
- [ ] `task_id` 有被儲存，後續有輪詢結果
- [ ] `failure_list` 非空時有記錄錯誤並觸發 retry
- [ ] 非同步成功後才更新 `sell_pack_inventory.sync_status = 'synced'`

### 能力判斷驗證
- [ ] 程式碼中沒有 `if (platform.equals("shopify"))` 等 hardcode 平台名稱
- [ ] 全部透過 `platform.capabilities.path("multiLocation").asBoolean(false)` 判斷
- [ ] `asyncInventory` 判斷同上，不 hardcode "shopee"

### DB 驗證
- [ ] `sell_pack_inventory` 查詢一律帶 `sell_pack_id` 條件
- [ ] 無地點平台的 upsert 使用 partial unique index（`WHERE channel_location_id IS NULL`）
- [ ] Upsert 不是先 SELECT 再 INSERT/UPDATE（避免 race condition）

### 邊界情境
- [ ] `newQuantity = 0` 正常處理（合法，代表缺貨）
- [ ] 同一個 sell_pack 有多個 channel，每個 channel 都各自發一條 Kafka 訊息
- [ ] 平台 API timeout 時，`sync_status` 設為 `'failed'`，不丟失
