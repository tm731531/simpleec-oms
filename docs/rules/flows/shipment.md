# 出貨流程規則 (Shipment Flow)

> **核心流程：**
> 商家在 OMS 填寫出貨資訊（追蹤號、物流商）→ OMS 儲存 shipment 記錄
> → Kafka SHIP_ORDER 送至 `{platform}.fast` topic
> → Channel Job（ShipOrderHandler）呼叫平台出貨 API
> → 平台確認後更新 shipment.status、寫入 shipment_status_logs

---

## 1. 業務規則

### 1.1 出貨模型

| 概念 | 說明 |
|------|------|
| Shipment | 單次出貨記錄，含追蹤號、物流商、出貨項目 |
| ShipmentItem | Shipment 中的特定套包與數量 |
| ShipmentBatch | 多筆 Shipment 的批次操作（一次處理多訂單） |
| ShipmentStatusLog | Shipment 狀態變更的不可變稽核記錄（只 INSERT，不 UPDATE） |

### 1.2 部分出貨規則

- **一張訂單可產生多筆 Shipment**（部分出貨 / partial fulfillment）
- 每次出貨可指定要出貨的 `orderItemId` 與數量
- 所有 items 出貨完成後，才更新 `order.fulfillment_status = FULFILLED`
- 部分出貨：`order.fulfillment_status = PARTIAL`

### 1.3 批次出貨規則

- 商家可透過 ShipmentBatch 同時處理多筆訂單的出貨
- BatchID 記錄在每筆 shipment 上（`shipment_batch_id`）
- 批次中任一筆失敗不影響其他筆（獨立處理）

### 1.4 狀態流轉

```
PENDING → PROCESSING → SHIPPED → DELIVERED
                    ↘ FAILED
```

- 狀態變更**必須寫 shipment_status_logs**，禁止直接 UPDATE 狀態欄位而不留記錄
- `shipment_status_logs` 只 INSERT，永不 UPDATE/DELETE

---

## 2. 前端 / API

### 2.1 端點清單

| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/orders/{id}/shipments` | 建立出貨記錄（觸發 SHIP_ORDER） |
| GET | `/api/orders/{id}/shipments` | 取得訂單的出貨列表 |
| GET | `/api/shipments/{id}` | 出貨詳情（含 status logs） |
| GET | `/api/shipment-batches` | 批次出貨列表 |
| POST | `/api/shipment-batches` | 建立批次出貨 |
| GET | `/api/shipment-batches/{id}` | 批次詳情（含各筆狀態） |

### 2.2 POST /api/orders/{id}/shipments

**Request Body:**
```json
{
  "trackingNumber": "7560123456789",
  "carrier": "HSINCHU",
  "items": [
    {
      "orderItemId": "item_nanoId20chars",
      "quantity": 2
    }
  ],
  "note": "小心易碎"
}
```

**Response:**
```json
{
  "shipmentId": "shpm_nanoId20chars",
  "orderId": "ord_nanoId20chars",
  "status": "PENDING",
  "trackingNumber": "7560123456789",
  "carrier": "HSINCHU",
  "createdAt": "2026-04-05T10:00:00Z",
  "message": "Shipment created, pushing to platform"
}
```

### 2.3 POST /api/shipment-batches

**Request Body:**
```json
{
  "shipments": [
    {
      "orderId": "ord_nanoId20chars",
      "trackingNumber": "7560123456789",
      "carrier": "HSINCHU",
      "items": [{ "orderItemId": "item_nanoId20chars", "quantity": 1 }]
    }
  ]
}
```

### 2.4 驗證規則

- `trackingNumber`：必填，非空白
- `carrier`：必填，使用 OMS 標準 carrier code（不接受平台原始字串）
- `items`：至少一筆；每筆 quantity 不得超過 orderItem.pendingQuantity
- 同一 orderItemId 在單一 shipment 中不得重複

---

## 3. Kafka 契約

### 3.1 Topic 對應

| 方向 | Topic | taskType |
|------|-------|----------|
| API → Channel Job | `{platform}.fast` | `SHIP_ORDER` |
| Channel Job → Backend（確認） | `task.backend` | `SHIP_ORDER_CONFIRMED` |

### 3.2 API → {platform}.fast（SHIP_ORDER）

```json
{
  "header": {
    "taskType": "SHIP_ORDER",
    "merchantId": "merch_nanoId20chars",
    "platformId": "shopee",
    "channelId": "chan_nanoId20chars",
    "requestId": "req_nanoId20chars",
    "timestamp": "2026-04-05T10:00:00Z",
    "source": "api",
    "version": "1.0",
    "isRollback": false
  },
  "body": {
    "orderId": "ord_nanoId20chars",
    "shipmentId": "shpm_nanoId20chars",
    "trackingNumber": "7560123456789",
    "carrier": "HSINCHU",
    "items": [
      {
        "orderItemId": "item_nanoId20chars",
        "sellPackId": "pack_nanoId20chars",
        "quantity": 2
      }
    ]
  }
}
```

**關鍵規則：**
- `body.orderId` 使用 **OMS NanoID**，**不是** `channelOrderId`
- Channel Job 負責翻譯：查 `orders` 表取得 `channel_order_id`
- `shipmentId` 也是 OMS NanoID

### 3.3 Channel Job → task.backend（SHIP_ORDER_CONFIRMED）

```json
{
  "header": {
    "taskType": "SHIP_ORDER_CONFIRMED",
    "merchantId": "merch_nanoId20chars",
    "platformId": "shopee",
    "channelId": "chan_nanoId20chars",
    "requestId": "req_nanoId20chars",
    "timestamp": "2026-04-05T10:01:00Z",
    "source": "channel-job",
    "version": "1.0",
    "isRollback": false
  },
  "body": {
    "orderId": "ord_nanoId20chars",
    "shipmentId": "shpm_nanoId20chars",
    "platformStatus": "SHIPPED",
    "confirmedAt": "2026-04-05T10:01:00Z"
  }
}
```

---

## 4. Channel Job

### 4.1 ShipOrderHandler 職責

```
1. 接收 {platform}.fast topic 的 SHIP_ORDER 訊息
2. 翻譯：orderId (NanoID) → channelOrderId（查 orders 表）
3. 依平台執行前置步驟（見 §7）
4. 呼叫平台出貨 API
5. 成功後送出 SHIP_ORDER_CONFIRMED 至 task.backend
6. 失敗後送出 SHIP_ORDER_FAILED（含 errorCode, errorMessage）
```

### 4.2 翻譯層規則

```java
// Channel Job 翻譯 orderId → channelOrderId
String channelOrderId = orderRepository
    .findById(message.body.orderId)
    .orElseThrow(() -> new OrderNotFoundException(message.body.orderId))
    .getChannelOrderId();
```

- **禁止** 在 Channel Job 直接使用 Kafka body 中的任何平台原生 ID（因為 body 全是 NanoID）
- 所有平台 ID 的查詢，從對應的 OMS 資料表取得

### 4.3 Shopee 特殊前置步驟

```
1. GET /v2/logistics/get_shipping_parameter?order_sn={channelOrderId}
   → 取得 address_id 或 pickup_time_id（依物流類型）
2. 組合出貨參數
3. POST /v2/logistics/ship_order（含 address_id / pickup_time_id）
```

> Shopee 不做此步驟將收到 error_code: 90000010（missing shipping parameter）

### 4.4 Shopify 特殊處理

```
選項 A（REST，單一追蹤號）：
  GET /admin/api/{v}/orders/{id}/fulfillment_orders.json → 取 fulfillment_order_id
  POST /admin/api/{v}/fulfillments.json

選項 B（GraphQL，多追蹤號）：
  mutation fulfillmentCreate
```

### 4.5 錯誤處理

- 平台 API 返回 `READY_TO_SHIP` 前置狀態錯誤（Shopee）：記錄，送 FAILED 訊息
- 平台 API 逾時：退避重試，最多 3 次
- 超過重試上限：送 SHIP_ORDER_FAILED 至 task.backend，前端可重新觸發

---

## 5. 後端 Job

### 5.1 ShipOrderConfirmedHandler

**觸發：** 監聽 `task.backend` topic，`taskType = SHIP_ORDER_CONFIRMED`

**流程：**
```
1. 查詢 shipment（by shipmentId）
2. 更新 shipment.status = SHIPPED / FAILED
3. INSERT shipment_status_log（新狀態、時間戳、來源）
4. 統計訂單已出貨 quantity vs 總 quantity
5. 若全部出貨完成 → UPDATE orders.fulfillment_status = FULFILLED
6. 若部分出貨 → UPDATE orders.fulfillment_status = PARTIAL
```

### 5.2 fulfillment_status 計算邏輯

```sql
-- 計算已出貨數量
SELECT
    oi.id AS order_item_id,
    oi.quantity AS total_qty,
    COALESCE(SUM(si.quantity), 0) AS shipped_qty
FROM order_items oi
LEFT JOIN shipment_items si
    ON si.order_item_id = oi.id
    AND EXISTS (
        SELECT 1 FROM shipments s
        WHERE s.id = si.shipment_id
          AND s.status = 'SHIPPED'
    )
WHERE oi.order_id = :orderId
GROUP BY oi.id, oi.quantity;

-- 全部出貨 → FULFILLED，部分出貨 → PARTIAL，均未出貨 → UNFULFILLED
```

### 5.3 ShipOrderFailedHandler

- 更新 shipment.status = FAILED
- INSERT shipment_status_log（含 errorCode, errorMessage）
- **不** 更新 order.fulfillment_status（仍維持原狀態，等待重試）

---

## 6. DB

### 6.1 資料表結構

```sql
-- 出貨記錄
CREATE TABLE shipments (
    id                VARCHAR(20)   PRIMARY KEY,  -- NanoID
    order_id          VARCHAR(20)   NOT NULL,     -- FK to orders
    merchant_id       VARCHAR(20)   NOT NULL,
    channel_id        VARCHAR(20)   NOT NULL,
    shipment_batch_id VARCHAR(20),               -- FK to shipment_batches（批次出貨才填）
    tracking_number   VARCHAR(100),
    carrier           VARCHAR(50),
    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    note              TEXT,
    created_at        TIMESTAMPTZ   DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   DEFAULT NOW()
);

-- 出貨項目
CREATE TABLE shipment_items (
    id             VARCHAR(20)   PRIMARY KEY,  -- NanoID
    shipment_id    VARCHAR(20)   NOT NULL,     -- FK to shipments
    order_item_id  VARCHAR(20)   NOT NULL,     -- FK to order_items
    sell_pack_id   VARCHAR(20)   NOT NULL,     -- FK to sell_pack
    quantity       INT           NOT NULL,
    created_at     TIMESTAMPTZ   DEFAULT NOW()
);

-- 狀態變更日誌（不可變，只 INSERT）
CREATE TABLE shipment_status_logs (
    id            VARCHAR(20)   PRIMARY KEY,  -- NanoID
    shipment_id   VARCHAR(20)   NOT NULL,     -- FK to shipments
    from_status   VARCHAR(20),
    to_status     VARCHAR(20)   NOT NULL,
    source        VARCHAR(50),               -- 'channel-job', 'api', 'admin'
    error_code    VARCHAR(50),
    error_message TEXT,
    created_at    TIMESTAMPTZ   DEFAULT NOW()
);

-- 批次出貨
CREATE TABLE shipment_batches (
    id           VARCHAR(20)   PRIMARY KEY,  -- NanoID
    merchant_id  VARCHAR(20)   NOT NULL,
    total_count  INT           NOT NULL,
    success_count INT          DEFAULT 0,
    failed_count  INT          DEFAULT 0,
    status        VARCHAR(20)  DEFAULT 'PROCESSING',
    created_at    TIMESTAMPTZ  DEFAULT NOW(),
    completed_at  TIMESTAMPTZ
);
```

### 6.2 狀態更新模式

```sql
-- 永遠先 INSERT log，再 UPDATE status（同一 transaction）
BEGIN;
  INSERT INTO shipment_status_logs (id, shipment_id, from_status, to_status, source, created_at)
  VALUES (:id, :shipmentId, :fromStatus, :toStatus, :source, NOW());

  UPDATE shipments SET status = :toStatus, updated_at = NOW()
  WHERE id = :shipmentId;
COMMIT;
```

### 6.3 必要索引

```sql
CREATE INDEX idx_shipments_order_id ON shipments (order_id);
CREATE INDEX idx_shipments_batch_id ON shipments (shipment_batch_id);
CREATE INDEX idx_shipment_items_shipment_id ON shipment_items (shipment_id);
CREATE INDEX idx_shipment_items_order_item_id ON shipment_items (order_item_id);
CREATE INDEX idx_shipment_status_logs_shipment_id ON shipment_status_logs (shipment_id);
```

---

## 7. Platform API

### 7.1 各平台出貨 API 差異

| 平台 | 前置條件 | 出貨 API | 注意事項 |
|------|----------|----------|---------|
| Shopee | **必須**先呼叫 `GET /v2/logistics/get_shipping_parameter` 取得 address_id 或 pickup_time_id | `POST /v2/logistics/ship_order` | 訂單需在 `READY_TO_SHIP` 狀態 |
| Shopify | 需先取得 `fulfillment_order_id` | `POST /admin/api/{v}/fulfillments.json`（REST）或 `fulfillmentCreate` mutation（GraphQL） | REST：單一追蹤號；GraphQL：支援多追蹤號 |
| Cyberbiz | 無 | `POST /v1/orders/{id}/fulfillments/custom_shipping` | 直接帶入 tracking 資訊 |
| Shopline | 無 | `POST /openapi2/v1/orders/{id}/fulfillments.json` | `tracking_info_list`（最多 10 筆） |

### 7.2 Shopee get_shipping_parameter 範例

```
GET /v2/logistics/get_shipping_parameter
  ?order_sn={channelOrderId}
  &partner_id={partnerId}
  &shop_id={shopId}
  &timestamp={ts}
  &sign={sign}

Response:
{
  "response": {
    "info_needed": {
      "dropoff": [],
      "pickup": ["address_id", "pickup_time_id"]
    },
    "pickup": {
      "address_list": [{"address_id": 123456, ...}],
      "time_slot_list": [{"pickup_time_id": "...", ...}]
    }
  }
}
```

### 7.3 Shopify fulfillment_order_id 取得

```
GET /admin/api/2024-01/orders/{orderId}/fulfillment_orders.json
→ fulfillment_orders[0].id  ← 此為 fulfillmentOrderId
```

---

## 9. QA Checklist

### Kafka 契約

- [ ] SHIP_ORDER Kafka body 中 `orderId` 為 OMS NanoID（不是 channelOrderId）
- [ ] Channel Job 從 `orders` 表查詢 `channel_order_id`，不信任 body 中的平台原生 ID

### 平台特殊流程

- [ ] Shopee：`get_shipping_parameter` 在 `ship_order` 之前呼叫
- [ ] Shopee：訂單狀態為 `READY_TO_SHIP` 才能出貨（否則 API 會失敗）
- [ ] Shopify：REST 出貨前先取得 `fulfillment_order_id`

### 狀態管理

- [ ] 出貨狀態變更同時寫 `shipment_status_logs`（INSERT），不單獨 UPDATE
- [ ] `shipment_status_logs` 無 UPDATE 或 DELETE 操作
- [ ] 部分出貨：`order.fulfillment_status = PARTIAL`（非 FULFILLED）
- [ ] 全部出貨完成：`order.fulfillment_status = FULFILLED`
- [ ] 計算 fulfillment_status 只計算 status = SHIPPED 的 shipment_items

### 批次出貨

- [ ] ShipmentBatch：單筆失敗不影響其他筆（獨立 try-catch）
- [ ] `shipment.shipment_batch_id` 正確設定
- [ ] `shipment_batches.success_count` / `failed_count` 在批次完成後正確統計

### API 層

- [ ] POST /api/orders/{id}/shipments 驗證：quantity 不超過 `pendingQuantity`
- [ ] 同一 orderItemId 在單一 shipment request 中不重複出現
