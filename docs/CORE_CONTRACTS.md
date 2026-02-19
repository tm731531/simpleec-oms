# SimpleEC OMS 核心事件流契約 v1.0

## 1. 契約分級

### 1.1 不可變契約 (Breaking Change 需要版本升級)
- Topic 命名規則
- Header 基本結構
- 核心 TaskType 定義
- 錯誤處理流程

### 1.2 可擴展契約 (可新增，不可修改現有)
- Body 欄位（可加不可減）
- 新的 TaskType
- 通路特定處理邏輯

## 2. Topic 定義

### 2.1 Channel Topics (通路主題)
| Topic Pattern | 用途 | 處理時間要求 | Retention |
|--------------|------|-------------|-----------|
| `{platform}.fast` | 快速任務：狀態更新、出貨 | < 5s | 1d |
| `{platform}.slow` | 慢速任務：訂單列表、批次處理 | < 5m | 1d |

平台列表：`momo`, `shopee`, `yahoo`, `pchome`, `cyberbiz`

### 2.2 Business Topics (業務主題 - 7 個)
| Topic | 用途 | 資料特性 | Retention |
|-------|------|---------|-----------|
| `order.process` | 訂單資料處理 | Source of Truth（核心） | 1d→2h |
| `return.process` | 退貨資料處理 | Source of Truth | 1d |
| `task.backend` | 後端非同步任務 | 商品同步、庫存更新、賣場同步、出貨等 | 1d |
| `task.frontend` | 前端非同步任務 | UI 觸發的任務（匯出、批次更新等） | 1d |
| `scheduler` | 排程分發 | 排程引擎分發 | 1d |
| `task.failed` | 失敗任務 | 可重試的錯誤 | 1d |
| `task.dlt` | 死信隊列 | 無法處理的訊息 | 30d |

## 3. 訊息結構

### 3.1 統一 Header 結構
```json
{
  "header": {
    "taskType": "string",       // 必填：路由關鍵
    "merchantId": "string",     // 必填：商家識別
    "channelId": "string",      // 選填：通路實例 (如 SHOPEE_001)
    "requestId": "string",      // 必填：追蹤識別碼
    "timestamp": "ISO-8601",    // 必填：訊息時間
    "source": "string",         // 必填：來源 (scheduler/api/webhook/manual)
    "version": 1,               // 必填：訊息版本
    "retryCount": 0,           // 選填：重試次數
    "priority": "NORMAL",       // 選填：HIGH/NORMAL/LOW
    "correlationId": "string"  // 選填：關聯識別碼
  },
  "body": {
    // TaskType 特定資料
  }
}
```

### 3.2 Header 欄位說明
- `taskType`: 決定使用哪個 Handler Class 處理
- `merchantId`: 多商戶隔離，資料不互通
- `channelId`: 通路實例，如 SHOPEE_001, SHOPEE_002（多帳號）
- `requestId`: 唯一識別碼，用於追蹤和冪等性
- `correlationId`: 串連相關訊息，如 list→detail 的關聯

## 4. 核心 TaskType 定義

### 4.0 Channel Job 數據轉換原則
**核心責任**：Channel Job 是數據適配層，負責將各通路 API 的五花八門格式轉換為 OMS 統一的訂單結構。

- **FETCH_ORDERS**：快速掃描訂單列表，決定哪些訂單需要詳情
- **FETCH_ORDER_DETAIL**：深度獲取完整信息，**轉換成 OMS 標準訂單結構**（這是關鍵）
  - Shopee 的訂單 → orderData (OMS 格式)
  - Momo 的訂單 → orderData (OMS 格式)
  - Yahoo, PChome, Cyberbiz... → orderData (OMS 格式)
- **PROCESS_ORDER**：發送轉換後的標準結構到 order.process

order.process Handler 只需專注業務邏輯（查 DB、決定新建/更新、去重），不需處理多通路差異。

### 4.1 訂單相關
| TaskType | 來源 Topic | 目標 Topic | 說明 |
|----------|-----------|------------|------|
| FETCH_ORDERS | {platform}.fast | order.process | 抓取訂單列表 |
| FETCH_ORDER_DETAIL | {platform}.slow | order.process | 抓取訂單詳情 |
| PROCESS_ORDER | order.process | - | 訂單入庫（Handler 查詢 DB 決定 INSERT 或 UPDATE） |
| SHIP_ORDER | {platform}.fast | task.backend | 出貨作業 |

### 4.2 退貨相關
| TaskType | 來源 Topic | 目標 Topic | 說明 |
|----------|-----------|------------|------|
| FETCH_RETURNS | {platform}.slow | return.process | 抓取退貨列表 |
| FETCH_RETURN_DETAIL | {platform}.slow | return.process | 抓取退貨詳情 |
| PROCESS_RETURN | return.process | - | 退貨入庫（Handler 查詢 DB 決定 INSERT 或 UPDATE） |
| APPROVE_RETURN | {platform}.fast | return.process | 同意退貨 |

### 4.3 商品相關（進入 task.backend）
| TaskType | 來源 Topic | 目標 Topic | 說明 |
|----------|-----------|------------|------|
| SYNC_PRODUCT | {platform}.slow | task.backend | 同步商品 |
| UPDATE_INVENTORY | {platform}.fast | task.backend | 更新庫存 |
| UPDATE_PRICE | {platform}.fast | task.backend | 更新價格 |
| SYNC_STORE | {platform}.slow | task.backend | 同步賣場資訊 |

## 5. Body 資料規範

### 5.1 FETCH_ORDERS

**核心原則**：
- Scheduler **只傳遞時間戳**（代表「從何時開始 fetch」）
- Channel Job **根據通路規則和時間戳決定如何執行**：
  - Shopee：用時間戳決定 `create_time_from`，根據通路能力設定時間窗口寬度
  - Momo：用時間戳去查物流類型，再用時間範圍查訂單
  - Yahoo：用時間戳作 `updated_after` 參數（只有更新時間，無狀態分類）
- Channel Job **決定是否需要 DETAIL**（金額大、有異常狀態等）
- Channel Job **最終組 OMS 結構**（統一 orderData 格式）

**Scheduler 發送到 {platform}.slow:**
```json
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "source": "scheduler"
  },
  "body": {
    "fetchSpec": {
      "timestamp": "2026-02-13T09:00:00Z"
    }
  }
}
```

說明：
- `timestamp`: 基準時間戳。Channel Job 根據通路 API 規則使用此時間戳 fetch 訂單

**Channel Job 的內部決策**（根據通路規則）：
```
1️⃣ 收到 fetchSpec（e.g., PENDING, 1hr window）
2️⃣ 根據通路 API 規則判斷如何打 API
   ├─ Shopee: GET /api/orders?order_status=UNPAID&create_time_from=X&create_time_to=Y
   ├─ Momo: GET /api/orders?status=pending&created_time_start=X&created_time_end=Y
   └─ Yahoo: GET /api/orders?updated_after=X (不分狀態)
3️⃣ 抓回訂單清單
4️⃣ 判斷是否需要 DETAIL
   ├─ 金額 > 10000 → YES
   ├─ 訂單狀態異常 → YES
   └─ 否則 → NO
5️⃣ 組好 OMS 結構的 orderData
   └─ Shopee shop_order_id → channelOrderId
   └─ Shopee items[] → OMS items[] 統一格式
   └─ Shopee shipping → OMS shipping 統一欄位
6️⃣ 發到 order.process
```

**Channel Job 回傳到 order.process (PROCESS_ORDER):**
```json
{
  "header": {
    "taskType": "PROCESS_ORDER",
    "source": "channel_job",
    "correlationId": "原始 requestId"
  },
  "body": {
    "channelOrderId": "通路訂單號（保留原始格式，如 1002#100）",
    "orderData": {
      // ⭐ 已轉換成 OMS 統一結構（不是通路原始格式）
      "orderStatus": "PENDING",        // 統一狀態
      "orderDate": "2026-02-13T...",   // 統一日期格式
      "customer": {                    // 統一客戶結構
        "name": "...",
        "phone": "...",
        "email": "..."
      },
      "items": [                       // 統一項目結構
        {
          "productId": "SKU...",
          "quantity": 1,
          "unitPrice": 100,
          "subtotal": 100
        }
      ],
      "payment": { ... },              // 統一支付結構
      "shipping": { ... }              // 統一配送結構
    }
  }
}
```

### 5.2 FETCH_ORDER_DETAIL

**說明**：
- Channel Job 在 FETCH_ORDERS 階段判斷某些訂單需要詳情
- 決定發送 FETCH_ORDER_DETAIL 到 {platform}.slow（背景非同步打詳情 API）
- 根據通路 API 規則取得完整訊息（items、payments、shipping details 等）
- 再組成 OMS 統一結構發到 order.process

**Channel Job 內部決策後發送到 {platform}.slow:**
```json
{
  "header": {
    "taskType": "FETCH_ORDER_DETAIL",
    "source": "channel_job"
  },
  "body": {
    "orders": [
      {
        "channelOrderId": "MOMO-2026021300001",
        "metadata": {
          // 通路特定參數（如 Momo 的 API version、商店 ID 等）
          "apiVersion": "v3",
          "storeId": "STORE123"
        }
      }
    ]
  }
}
```

**Channel Job 根據通路規則打 DETAIL API，完成組 OMS 結構後發到 order.process：**
```json
{
  "header": {
    "taskType": "PROCESS_ORDER",
    "source": "channel_job",
    "correlationId": "原始 requestId"
  },
  "body": {
    "channelOrderId": "MOMO-2026021300001",
    "orderData": {
      // ⭐ 完整的 OMS 統一結構
      "orderStatus": "PENDING",
      "orderDate": "2026-02-13T09:30:00Z",
      "customer": { ... },
      "items": [ ... ],           // 來自 DETAIL API 的完整項目清單
      "payment": { ... },         // 來自 DETAIL API 的支付詳情
      "shipping": { ... },        // 來自 DETAIL API 的配送詳情
      "totals": { ... }           // 詳細金額分解
    }
  }
}
```

## 6. 錯誤處理契約

### 6.1 錯誤分類
| 錯誤類型 | 處理方式 | 目標 Topic |
|---------|----------|-----------|
| API 錯誤 (4xx) | 不重試 | task.failed |
| API 錯誤 (5xx) | 重試 3 次 | task.failed |
| 網路錯誤 | 重試 5 次 | task.failed |
| 資料格式錯誤 | 不重試 | task.dlt |
| 版本不支援 | 不重試 | task.dlt |

### 6.2 錯誤訊息格式
```json
{
  "header": {
    "taskType": "FAILED_TASK"
  },
  "body": {
    "originalHeader": { },
    "originalBody": { },
    "errorInfo": {
      "errorCode": "API_TIMEOUT",
      "errorMessage": "string",
      "retryable": true,
      "maxRetries": 3,
      "nextRetryTime": "ISO-8601"
    }
  }
}
```

## 7. 版本管理策略

### 7.1 版本相容性
- v1 Handler 必須能處理 v1 訊息
- v2 Handler 必須能處理 v1 和 v2 訊息
- 版本升級需要並行期（兩版本共存）

### 7.2 版本升級流程
1. 新增 v2 Handler（向下相容）
2. 逐步切換 Producer 到 v2
3. 確認無 v1 訊息後移除 v1 Handler

## 8. 冪等性保證

### 8.1 冪等鍵規則
- 訂單：`{merchantId}:{channelId}:{orderId}`
- 商品：`{merchantId}:{channelId}:{productId}`
- 任務：`{requestId}`
- **重要**：訂單號碼中的特殊字元（如 `#`, `-`, `@`）具有業務意義，必須完整保留
- 詳見：`REDIS_DEDUPLICATION.md`

### 8.2 重複處理策略
- 使用 Redis 記錄已處理的 requestId 和資源 Hash
- TTL = 7 天
- Hash 變更檢測：Channel Job 讀取，Process Job 寫入
- 詳見：`REDIS_DEDUPLICATION.md`