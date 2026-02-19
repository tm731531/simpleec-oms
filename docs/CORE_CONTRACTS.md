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

### 2.2 Business Topics (業務主題)
| Topic | 用途 | 資料特性 | Retention |
|-------|------|---------|-----------|
| `order.process` | 訂單資料處理 | Source of Truth | 1d→2h |
| `return.process` | 退貨處理 | 退貨資料 | 1d |
| `product.sync` | 商品同步結果 | 同步狀態 | 1d |
| `inventory.update` | 庫存更新 | 庫存變動 | 1d |
| `task.backend` | 後端任務 | 系統任務 | 1d |
| `task.frontend` | 前端任務 | UI 觸發 | 1d |
| `scheduler` | 排程分發 | 排程任務 | 1d |
| `task.failed` | 失敗任務 | 可重試 | 1d |
| `task.dlt` | 死信 | 不可處理 | 30d |

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

### 4.1 訂單相關
| TaskType | 來源 Topic | 目標 Topic | 說明 |
|----------|-----------|------------|------|
| FETCH_ORDERS | {platform}.slow | order.process | 抓取訂單列表 |
| FETCH_ORDER_DETAIL | {platform}.slow | order.process | 抓取訂單詳情 |
| NEW_ORDER | order.process | - | 新訂單入庫 |
| UPDATE_ORDER | order.process | - | 訂單狀態更新 |
| SHIP_ORDER | {platform}.fast | order.process | 出貨作業 |

### 4.2 退貨相關
| TaskType | 來源 Topic | 目標 Topic | 說明 |
|----------|-----------|------------|------|
| FETCH_RETURNS | {platform}.slow | return.process | 抓取退貨列表 |
| FETCH_RETURN_DETAIL | {platform}.slow | return.process | 抓取退貨詳情 |
| NEW_RETURN | return.process | - | 新退貨入庫 |
| APPROVE_RETURN | {platform}.fast | return.process | 同意退貨 |

### 4.3 商品相關
| TaskType | 來源 Topic | 目標 Topic | 說明 |
|----------|-----------|------------|------|
| SYNC_PRODUCT | {platform}.slow | product.sync | 同步商品 |
| UPDATE_INVENTORY | {platform}.fast | inventory.update | 更新庫存 |
| UPDATE_PRICE | {platform}.fast | product.sync | 更新價格 |

## 5. Body 資料規範

### 5.1 FETCH_ORDERS
```json
{
  "body": {
    "timeRange": {
      "start": "2024-01-01T00:00:00Z",
      "end": "2024-01-01T23:59:59Z"
    },
    "filters": {
      // 選填：額外過濾條件
    }
  }
}
```

**Channel 回傳到 order.process:**
```json
{
  "header": {
    "taskType": "NEW_ORDER",
    "correlationId": "原始 requestId"
  },
  "body": {
    "orders": [
      {
        "orderId": "通路訂單號",
        "orderData": {
          // 通路原始資料
        },
        "needsDetail": true,  // 是否需要抓詳情
        "metadata": {
          // Channel 判斷用資料
        }
      }
    ],
    "summary": {
      "total": 100,
      "fetched": 50,
      "hasMore": true
    }
  }
}
```

### 5.2 FETCH_ORDER_DETAIL
```json
{
  "body": {
    "orders": [
      {
        "orderId": "string",
        "metadata": {
          // Channel 特定參數
        }
      }
    ]
  }
}
```

**Channel 回傳到 order.process:**
```json
{
  "header": {
    "taskType": "UPDATE_ORDER",
    "correlationId": "原始 requestId"
  },
  "body": {
    "orderId": "通路訂單號",
    "fullOrderData": {
      // 完整訂單資料
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