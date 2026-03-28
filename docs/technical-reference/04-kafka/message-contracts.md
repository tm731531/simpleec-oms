# Kafka 訊息契約

SimpleEC OMS 中所有訊息——橫跨 17 個以上的 Kafka topic——均採用相同的頂層
`header` / `body` 信封結構。`header` 負責路由層；`body` 負責承載業務資料。

---

## 1. 通用信封結構

```json
{
  "header": {
    "taskType":   "ORDER_UPSERT",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-a1b2c3d4-e5f6",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    // 依 taskType 而定——詳見下方各節
  }
}
```

### Header 欄位說明

| 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|
| `taskType` | String | 是 | 將訊息路由至正確 handler。完整清單見下方。 |
| `merchantId` | String (NanoID 20) | 是 | 租戶隔離。所有 DB 寫入均以此商家為範圍。 |
| `platformId` | String | 是 | 平台代碼：`momo`、`shopee`、`yahoo`、`pchome`、`cyberbiz`、`easystore`、`shopline`、`shopify` |
| `channelId` | String (NanoID 20) | 是 | 特定通路實例 ID（例如某商家的 Shopee 店舖）。外鍵對應 `channel.id`。 |
| `requestId` | String (UUID) | 是 | 分散式追蹤 ID。透過 `TaskMdcHelper.set(msg)` 傳播至 MDC。 |
| `timestamp` | String (ISO-8601 UTC) | 是 | Scheduler 的心跳時間戳。**Channel Job 以此計算自身的時間窗口**——不接受 from/to 範圍。 |
| `source` | String | 是 | 訊息來源：`scheduler`、`api`、`webhook`、`channel_job` |
| `version` | Integer | 是 | Schema 版本，目前為 `1`。不支援的版本會被路由至 `task.dlt`。 |
| `isRollback` | Boolean | 是 | `false` = 即時訂單；`true` = 歷史回補。影響統計歸因與庫存處理。詳見第 4 節。 |

### 所有 Task Type

| Task Type | Topic | 方向 | 說明 |
|-----------|-------|------|------|
| `FETCH_ORDERS` | `{platform}.slow` | Scheduler → Channel Job | 觸發從平台拉取訂單清單 |
| `FETCH_ORDER_DETAIL` | `{platform}.slow` | Channel Job → Channel Job | 觸發拉取特定訂單的詳細資料 |
| `FETCH_RETURNS` | `{platform}.slow` | Scheduler → Channel Job | 觸發從平台拉取退貨清單 |
| `FETCH_RETURN_DETAIL` | `{platform}.slow` | Channel Job → Channel Job | 觸發拉取特定退貨的詳細資料 |
| `SYNC_PACK` | `{platform}.slow` | Scheduler → Channel Job | 觸發套包／商品 Listing 同步 |
| `SHIP_ORDER` | `{platform}.fast` | API → Channel Job | 將出貨資訊推送至平台 |
| `UPDATE_PRICE` | `{platform}.fast` | API → Channel Job | 更新平台上的 Listing 價格 |
| `UPDATE_INVENTORY` | `{platform}.fast` | API → Channel Job | 將庫存數量推送至平台 |
| `APPROVE_RETURN` | `{platform}.fast` | API → Channel Job | 在平台核准退貨申請 |
| `ORDER_UPSERT` | `order.process` | Channel Job → Order Job | 在 DB 中新增或更新訂單 |
| `RETURN_UPSERT` | `return.process` | Channel Job → Return Job | 在 DB 中新增或更新退貨／退款 |
| `SYNC_PRODUCT` | `task.backend` | Backend Job → Backend Job | 建立 Pack → Product 映射 |
| `STATS_RECALC` | `task.backend` | Order/Return Job → Backend Job | 觸發每日統計重新計算 |
| `HEARTBEAT` | `scheduler` | Scheduler → Scheduler | 週期性計時器（每秒一次） |
| `EXPORT_ORDERS` | `task.frontend` | API → Frontend Job | 非同步匯出訂單為 CSV/Excel |
| `BATCH_SHIP` | `task.frontend` | API → Frontend Job | 批次出貨更新 |
| `BATCH_CANCEL` | `task.frontend` | API → Frontend Job | 批次取消訂單 |

---

## 2. 各 Task Type 的 Body Schema

### 2.1 ORDER_UPSERT

發布至 `order.process`。這是系統中最重要的訊息——是將平台訂單正規化為 OMS 格式後的標準表示。

```json
{
  "header": {
    "taskType":   "ORDER_UPSERT",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-a1b2c3d4-e5f6",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "orderHash": "sha256-of-normalized-order-content",
    "orderData": {
      "orderId":           "nano-id-20chars-here",
      "channelOrderId":    "2503281234567890",
      "channelOrderNumber": "SHP-2503-1234",
      "channelCreatedAt":  "2026-03-28T08:30:00Z",
      "channelUpdatedAt":  "2026-03-28T09:00:00Z",
      "status":            "confirmed",
      "totalAmount":       1580.00,
      "shippingFee":       60.00,
      "discountAmount":    0.00,
      "currency":          "TWD",
      "buyerName":         "張三",
      "buyerPhone":        "0912345678",
      "buyerEmail":        "buyer@example.com",
      "shippingAddress":   "台北市信義區信義路五段7號10樓",
      "shippingMethod":    "711",
      "paymentMethod":     "credit_card",
      "paidAt":            "2026-03-28T08:31:00Z",
      "items": [
        {
          "channelItemId":  "shopee-item-99988877",
          "productName":    "無線藍牙耳機 黑色",
          "sku":            "BT-HEADPHONE-BLK",
          "quantity":       2,
          "unitPrice":      790.00,
          "totalPrice":     1580.00
        }
      ]
    }
  }
}
```

**`orderHash`**：正規化訂單內容（排除時間戳）的 SHA-256 雜湊值。`OrderUpsertConsumer`
以此進行去重判斷——若 `Redis.get(orderHash)` 命中，則跳過該訊息不碰 DB；若雜湊值與先前儲存的不同，則更新訂單。

**`orderId`**：由 Channel Job 在發布前產生的 OMS 內部 NanoID。這確保同一筆邏輯訂單
即使 Kafka 訊息被重新投遞，也始終擁有相同的 `orderId`。

### 2.2 RETURN_UPSERT

發布至 `return.process`。

```json
{
  "header": {
    "taskType":   "RETURN_UPSERT",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-b9c8d7e6-f5a4",
    "timestamp":  "2026-03-28T11:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "returnData": {
      "orderId":          "oms-order-nano-id",
      "channelRefundId":  "REFUND-99887766",
      "refundStatus":     "REQUESTED",
      "refundAmount":     790.00,
      "reason":           "商品瑕疵",
      "requestedAt":      "2026-03-28T10:55:00Z",
      "items": [
        {
          "channelItemId": "shopee-item-99988877",
          "productName":   "無線藍牙耳機 黑色",
          "sku":           "BT-HEADPHONE-BLK",
          "quantity":      1,
          "refundAmount":  790.00
        }
      ]
    }
  }
}
```

### 2.3 FETCH_ORDERS

由 SchedulerEventHandler 每 5 分鐘針對每個啟用的通路發布至 `{platform}.slow`。

```json
{
  "header": {
    "taskType":   "FETCH_ORDERS",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-heartbeat-derived",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "scheduler",
    "version":    1,
    "isRollback": false
  },
  "body": {}
}
```

**Body 刻意留空。** Channel Job 負責自行決定：
- 要查詢哪些時間窗口（例如 Shopee：PENDING 用 1h，AWAITING_SHIPMENT 用 3d，以此類推）
- 是否需要呼叫 detail API
- 分頁策略

`header.timestamp` 是唯一的時間輸入。Channel Job 在內部從中推導所有時間範圍。

### 2.4 FETCH_ORDER_DETAIL

當平台的清單 API 回傳不完整資料時（例如 Shopee 清單 API 不含商品明細、付款及物流資訊），
由 Channel Job 發布至 `{platform}.slow`。

```json
{
  "header": {
    "taskType":   "FETCH_ORDER_DETAIL",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "channelOrderId": "2503281234567890"
  }
}
```

### 2.5 SHIP_ORDER

在商家確認出貨後，由 API 發布至 `{platform}.fast`。

```json
{
  "header": {
    "taskType":   "SHIP_ORDER",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-api-generated",
    "timestamp":  "2026-03-28T14:00:00Z",
    "source":     "api",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "orderId":         "oms-order-nano-id",
    "channelOrderId":  "2503281234567890",
    "trackingNumber":  "123456789012",
    "shippingMethod":  "711",
    "logisticsCompany": "7-ELEVEN"
  }
}
```

### 2.6 STATS_RECALC

在訂單或退貨寫入 DB 後發布至 `task.backend`。觸發對受影響商家／通路／日期的
`daily_statistics` 進行重新計算。

```json
{
  "header": {
    "taskType":   "STATS_RECALC",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-derived-from-order",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "order_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "statDate": "2026-03-28"
  }
}
```

**注意**：`OrderUpsertConsumer` 並非同步發布此訊息，而是在每次訂單 upsert 後向 Redis ZSet
寫入一個「dirty 標記」。`DailyStatisticsService` 依排程讀取這些 dirty 標記，批次重新計算統計數據。
STATS_RECALC 這個 task type 可用於透過管理 API 觸發的明確按需重算。

---

## 3. Schema 版本處理

每個 consumer 在處理前均會呼叫 `SchemaVersionHandler.validate(json)`，
作為應對未來 schema 演進的防禦性檢查。

**目前版本**：`1`

**處理規則**：

| 條件 | 動作 |
|------|------|
| `header.version == 1` | 正常處理 |
| 缺少 `header.version` 欄位 | 視為版本 1（向後相容） |
| `header.version > 1`（未知的未來版本） | 發布至 `task.dlt`，原因為 `UNSUPPORTED_VERSION` |
| 完全缺少 `header` 欄位 | 發布至 `task.dlt`，原因為 `MALFORMED_MESSAGE` |

**未來遷移方式**：引入破壞性變更時，將 `version` 升至 `2`，並新增將 v1 → v2 結構轉換的
version handler，在派發前執行。舊 consumer 可持續運作，直到所有 producer 完成更新。

---

## 4. isRollback 標誌

`header.isRollback` 是訊息契約中的一等公民。當 Channel Job 偵測到自己正在抓取歷史時間窗口
的訂單（即執行回補作業）時，由 Channel Job 設定此標誌。

### isRollback = false（正常即時訂單）

```
統計：歸因至今日（以 channel_created_at 日期作為 stat_date）
庫存：正常扣減庫存
事件：正常的訂單生命週期通知
排名：計入商家當日排名
```

### isRollback = true（歷史回補）

```
統計：歸因至 channelCreatedAt 的日期（而非今日）
       → 4 月份回補的 3 月訂單，加入 3 月統計，而非 4 月
庫存：庫存調整可能需要特殊處理
事件：回補專用事件（例如觸發重新產生每日報表）
排名：可能從當日排名計算中排除
```

**哪些 task type 攜帶 isRollback**：

- `FETCH_ORDERS` — 當 scheduler 派發歷史日期範圍時由其設定
- `FETCH_ORDER_DETAIL` — 從父層 FETCH_ORDERS 訊息繼承
- `ORDER_UPSERT` — 從 FETCH_ORDER_DETAIL 繼承
- `RETURN_UPSERT` — 針對歷史退貨回補以相同方式設定
- `SHIP_ORDER` — 通常為 false（出貨動作發生在即時環境）

**Channel Job 中的偵測邏輯**：

```java
// 範例：Cyberbiz Channel Job
boolean isRollback = requestTimestamp.isBefore(Instant.now().minus(2, HOURS));
// 若 scheduler 的時間戳超過 2 小時前，則視為回補
```

---

## 5. 訊息 Body 中的 PII 欄位

`orderData` 中下列欄位含有買家個人資料（PII）。這些資料在 Kafka 訊息中以明文傳輸
（正式環境透過 TLS 加密傳輸），並在 PostgreSQL 中透過 `EncryptedAttributeConverter`
以 AES-256-GCM 加密靜態儲存。

| 欄位 | PII 類別 | DB 加密 |
|------|---------|---------|
| `buyerName` | 姓名 | 是 — `buyer_name` 欄位 |
| `buyerPhone` | 電話號碼 | 是 — `buyer_phone` 欄位 |
| `buyerEmail` | 電子郵件地址 | 是 — `buyer_email` 欄位 |
| `shippingAddress` | 實體地址 | 是 — `shipping_address` 欄位 |

任何讀取這些 DB 欄位的程式碼，都必須將操作包在以下區塊內：

```java
EncryptionContext.setMerchantId(merchantId);
try {
    // 含 PII 欄位的 DB 讀寫
} finally {
    EncryptionContext.clear();
}
```

---

## 6. 訊息追蹤

每條訊息在 header 中攜帶 `requestId`。此 ID 由 `TaskMdcHelper` 寫入 MDC：

```java
// 在每個 consumer 的 handle() 方法中：
TaskMdcHelper.set(msg);
try {
    // 處理邏輯
} finally {
    TaskMdcHelper.clear();
}
```

這會在訊息處理期間，將 `requestId`、`merchantId` 和 `taskType` 傳播至所有日誌記錄。
可在 Grafana Loki 中透過篩選 `requestId` 來關聯相關日誌。
