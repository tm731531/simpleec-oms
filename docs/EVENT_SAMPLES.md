# SimpleEC OMS 事件流範例 v1.0

本文件提供所有 Kafka topic 的訊息格式範例，確認事件流設計上下對應。

## 統一訊息結構

所有 Kafka topic 都使用相同的 `header/body` 結構：

```json
{
  "header": {
    "taskType": "具體任務類型",
    "merchantId": "商家ID",
    "channelId": "通路ID（可選）",
    "requestId": "唯一請求識別碼",
    "timestamp": "訊息產生時間",
    "source": "訊息來源",
    "version": "訊息版本",
    "retryCount": "重試次數",
    "priority": "優先級",
    "correlationId": "關聯識別碼（可選）"
  },
  "body": {
    // 業務資料根據 taskType 而定
  }
}
```

### Header 欄位說明
- `taskType`: 決定使用哪個處理類別 (Handler Class)（必填）
- `merchantId`: 商家識別碼，用於多商戶隔離（必填）
- `platformId`: 通路編號 (shopee/momo/yahoo/pchome/cyberbiz/easystore)（必填）
- `channelId`: 通路實例 ID (如 MOMO_001, SHOPEE_002)（選填）
- `requestId`: 追蹤用的唯一識別碼（必填）
- `timestamp`: ISO 8601 格式時間戳記（必填）
- `source`: 訊息來源 (scheduler/api/webhook/manual/channel_job)（必填）
- `version`: 訊息版本，用於向後相容（必填）
- `retryCount`: 當前重試次數（選填）
- `priority`: HIGH/NORMAL/LOW（選填）
- `correlationId`: 關聯識別碼，串連 list→detail→process（選填）

---

## Channel Topics (通路主題 - 10個)

### 平台快速主題：momo.fast / shopee.fast / yahoo.fast / pchome.fast / cyberbiz.fast

**TaskType: FETCH_ORDERS** - Scheduler 觸發，Channel Job 根據時間戳和訂單生命週期分批拉取

Scheduler 只傳遞時間戳。Channel Job 自行決定如何分批：
- 1小時內的新訂單（PENDING）
- 3天內的待出貨訂單（CONFIRMED）
- 5天內的出貨中訂單（SHIPPED）
- 7天後的已完成訂單（COMPLETED）

```json
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "merchantId": "M001",
    "platformId": "momo",
    "channelId": "MOMO_001",
    "requestId": "sched-20260213-fetch-001",
    "timestamp": "2026-02-13T10:00:00Z",
    "source": "scheduler",
    "version": 1,
    "priority": "NORMAL"
  },
  "body": {
    "fetchSpec": {}
  }
}
```

**Channel Job 內部流程**（不在訊息中，説明用）：
1. 讀取 header.timestamp = 2026-02-13T10:00:00Z
2. 分批打 API：
   - API_1: PENDING 訂單（1小時內）
   - API_2: CONFIRMED 訂單（3天內）
   - API_3: SHIPPED 訂單（5天內）
   - API_4: COMPLETED 訂單（7天後）
3. 判斷是否需要 DETAIL API（根據平台能力）
4. 組成 OMS 統一結構，發送 PROCESS_ORDER 訊息到 order.process

**TaskType: SHIP_ORDER** - 出貨指令（主要用於 {platform}.fast）

```json
{
  "header": {
    "taskType": "SHIP_ORDER",
    "merchantId": "M001",
    "platformId": "shopee",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-100001",
    "timestamp": "2026-02-13T10:05:00Z",
    "source": "admin_ui",
    "version": 1,
    "priority": "HIGH"
  },
  "body": {
    "orderId": "SH202602130456",
    "shippingMethod": "SHOPEE_PICKUP",
    "trackingNumber": "SE123456789",
    "carrierInfo": {
      "name": "7-ELEVEN",
      "storeId": "131415"
    }
  }
}
```

**TaskType: UPDATE_PRICE** - 更新商品價格（快速同步）

```json
{
  "header": {
    "taskType": "UPDATE_PRICE",
    "merchantId": "M001",
    "platformId": "yahoo",
    "channelId": "YAHOO_001",
    "requestId": "req-20260213-100002",
    "timestamp": "2026-02-13T10:10:00Z",
    "source": "price_sync_job",
    "version": 1,
    "priority": "NORMAL"
  },
  "body": {
    "products": [
      {
        "productId": "YH-SKU-001",
        "price": 2999,
        "originalPrice": 3500,
        "currency": "TWD",
        "effectiveTime": "2026-02-13T10:10:00Z"
      },
      {
        "productId": "YH-SKU-002",
        "price": 1999,
        "originalPrice": 2500,
        "currency": "TWD"
      }
    ]
  }
}
```

**TaskType: UPDATE_INVENTORY** - 更新庫存（快速同步）

```json
{
  "header": {
    "taskType": "UPDATE_INVENTORY",
    "merchantId": "M001",
    "platformId": "pchome",
    "channelId": "PCHOME_001",
    "requestId": "req-20260213-100003",
    "timestamp": "2026-02-13T10:15:00Z",
    "source": "inventory_sync",
    "version": 1,
    "priority": "HIGH"
  },
  "body": {
    "updates": [
      {
        "productId": "PC-SKU-001",
        "quantity": 50,
        "type": "ABSOLUTE",
        "warehouseId": "WH-001"
      },
      {
        "productId": "PC-SKU-002",
        "quantity": -3,
        "type": "RELATIVE",
        "warehouseId": "WH-001",
        "reason": "SALE"
      }
    ]
  }
}
```

**TaskType: APPROVE_RETURN** - 同意退貨

```json
{
  "header": {
    "taskType": "APPROVE_RETURN",
    "merchantId": "M001",
    "platformId": "cyberbiz",
    "channelId": "CYBERBIZ_001",
    "requestId": "req-20260213-100004",
    "timestamp": "2026-02-13T10:20:00Z",
    "source": "api",
    "version": 1,
    "priority": "NORMAL"
  },
  "body": {
    "returnId": "RET-CYBER-2026021300001",
    "status": "APPROVED",
    "returnShippingMethod": "STORE_PICKUP",
    "pickupInfo": {
      "storeId": "ST001",
      "storeName": "台北信義門市"
    },
    "approvedAmount": 10000,
    "approvedAt": "2026-02-13T10:20:00Z"
  }
}
```

---

### 平台慢速主題：momo.slow / shopee.slow / yahoo.slow / pchome.slow / cyberbiz.slow

**TaskType: FETCH_ORDER_DETAIL** - Channel Job 決定某訂單需詳情，打 DETAIL API

```json
{
  "header": {
    "taskType": "FETCH_ORDER_DETAIL",
    "merchantId": "M001",
    "platformId": "momo",
    "channelId": "MOMO_001",
    "requestId": "detail_req_001",
    "timestamp": "2026-02-13T10:30:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "fetch_req_001",
    "priority": "NORMAL",
    "isRollback": false
  },
  "body": {
    "orders": [
      {
        "orderId": "MOMO-2026021300001",
        "metadata": {
          "apiVersion": "v3",
          "includeItems": true,
          "includePayment": true
        }
      }
    ]
  }
}
```

**TaskType: FETCH_RETURNS** - 抓取退貨列表

```json
{
  "header": {
    "taskType": "FETCH_RETURNS",
    "merchantId": "M001",
    "channelId": "YAHOO_001",
    "requestId": "req-20260213-200002",
    "timestamp": "2026-02-13T11:00:00Z",
    "source": "scheduler",
    "version": 1,
    "priority": "NORMAL"
  },
  "body": {
    "timeRange": {
      "start": "2026-02-13T10:00:00Z",
      "end": "2026-02-13T11:00:00Z"
    },
    "returns": [
      {
        "returnId": "YH-RET-2026021300001",
        "orderId": "YH-ORD-2026021300100",
        "returnStatus": "PENDING_APPROVAL",
        "reason": "SIZE_MISMATCH",
        "requestedAmount": 5000,
        "needsDetail": true
      }
    ],
    "summary": {
      "total": 10,
      "fetched": 1,
      "hasMore": true
    }
  }
}
```

**TaskType: FETCH_RETURN_DETAIL** - 抓取退貨詳情

```json
{
  "header": {
    "taskType": "FETCH_RETURN_DETAIL",
    "merchantId": "M001",
    "channelId": "CYBERBIZ_001",
    "requestId": "req-20260213-200003",
    "timestamp": "2026-02-13T11:15:00Z",
    "source": "return_process_job",
    "version": 1,
    "correlationId": "req-20260213-200002",
    "priority": "NORMAL"
  },
  "body": {
    "returns": [
      {
        "returnId": "CYBER-RET-2026021300001",
        "metadata": {
          "includePhotos": true,
          "includeShippingInfo": true
        }
      }
    ]
  }
}
```

---

## Business Topics (業務主題 - 7 個)

### scheduler - 排程分發
保留時間：1d

Scheduler 根據時間和優先級，定期向各 Channel 的 fast/slow topics 發送任務指令。

**TaskType: DISPATCH_ORDER_FETCH** - 分發訂單抓取任務

```json
{
  "header": {
    "taskType": "DISPATCH_ORDER_FETCH",
    "merchantId": "M001",
    "channelId": "MOMO_001",
    "requestId": "sched-20260213-080000",
    "timestamp": "2026-02-13T08:00:00Z",
    "source": "scheduler",
    "version": 1,
    "priority": "HIGH"
  },
  "body": {
    "fetchSpec": {
      "orderStatus": "PENDING",
      "description": "1小時內新訂單（08:00-09:00）"
    }
  }
}
```

**Scheduler 策略示例**：
```
08:00 → DISPATCH(PENDING)      # 1小時內新訂單
09:00 → DISPATCH(PENDING)      # 滑動窗口
...
12:00 → DISPATCH(PROCESSING)   # 3天內出貨中訂單
12:30 → DISPATCH(SHIPPED)      # 7天內已出貨訂單
13:00 → DISPATCH(COMPLETED)    # 7~15天內已完成訂單
06:00 → DISPATCH(FULL_SYNC)    # 全量商品同步（每天一次）
每小時 → UPDATE_PRICE/INVENTORY # 定期同步
```

---

### order.process - 訂單處理（Source of Truth）
保留時間：1d（穩定後考慮降至 2h）

**Flow: Channel Job → order.process → Handler 判斷新建/更新**

Channel Job 收到訂單後，根據 Redis Hash 判斷是否需要詳情。然後發送到 order.process。
order.process Handler 接收後，查詢資料庫判斷是新訂單還是已存在，決定 INSERT 或 UPDATE。

**TaskType: PROCESS_ORDER** - Channel Job 發送的完整統一訂單資料

```json
{
  "header": {
    "taskType": "PROCESS_ORDER",
    "merchantId": "M001",
    "platformId": "momo",
    "channelId": "MOMO_001",
    "requestId": "process_req_001",
    "timestamp": "2026-02-13T10:35:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "fetch_req_001",
    "isRollback": false
  },
  "body": {
    "orderData": {
      "orderId": "ord_abc123def456",
      "channelOrderId": "MOMO-2026021300001",
      "orderStatus": "PENDING",
      "buyerName": "王小明",
      "buyerPhone": "0912345678",
      "buyerEmail": "wang@example.com",
      "shippingAddress": "台北市中山區南京東路三段100號",
      "shippingMethod": "HOME_DELIVERY",
      "paymentMethod": "CREDIT_CARD",
      "totalAmount": 43900.00,
      "shippingFee": 0.00,
      "discountAmount": 1000.00,
      "channelCreatedAt": "2026-02-13T09:30:00Z",
      "paidAt": "2026-02-13T09:31:00Z",
      "items": [
        {
          "sku": "IPHONE-15-PRO-MAX",
          "productId": "pd_xyz789",
          "channelProductId": "MOMO-SKU-001",
          "channelSpecId": "MOMO-SPEC-001",
          "channelItemId": "MOMO-ITEM-2026021300001",
          "channelProductName": "iPhone 15 Pro Max",
          "channelSpecName": "太空黑/256GB",
          "productName": "iPhone 15 Pro Max",
          "quantity": 1,
          "unitPrice": 44900.00,
          "subtotal": 44900.00,
          "sellPackId": "sp_abc123"
        }
      ]
    }
  }
}
```

**說明**: order.process Handler 接收後：
1. 計算 orderData 的 hash（SHA256）
2. 查詢資料庫是否已存在該 channelOrderId
3. 新訂單 → INSERT（id=orderId）
4. 已存在 + Hash 不同 → UPDATE
5. 已存在 + Hash 相同 → 跳過（冪等性）

注：orderId 由 Handler 在新增時生成，更新時由 orderData 帶入。

---

### return.process - 退貨處理（Source of Truth）
保留時間：1d

**Flow: Channel Job → return.process → Handler 判斷新建/更新**

同 order.process 邏輯，Handler 判斷是新退貨還是已存在。

```json
{
  "header": {
    "taskType": "PROCESS_RETURN",
    "merchantId": "M001",
    "channelId": "YAHOO_001",
    "requestId": "req-20260213-310000",
    "timestamp": "2026-02-13T11:30:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "req-20260213-200002"
  },
  "body": {
    "orderId": "ord_xyz789",                     // ← OMS 訂單 ID（FK 到 orders）
    "channelReturnId": "YH-RET-2026021300001",
    "returnData": {
      "returnStatus": "PENDING_APPROVAL",
      "reason": "SIZE_MISMATCH",
      "requestedAmount": 5000,
      "requestedAt": "2026-02-13T11:00:00Z",    // ← 退貨申請時間
      "items": [
        {
          "productId": "SKU002",
          "quantity": 1,
          "unitPrice": 5000
        }
      ]
    }
  }
}
```

---

### task.backend - 後端非同步任務
保留時間：1d

**TaskType: SYNC_PRODUCT** - 商品同步（由 {platform}.slow 派發，獨立 Handler 處理）

> **說明**：SYNC_PRODUCT 同步的是通用的商品元數據（SKU, 名稱, 成本）。
> 通路特定的規格屬性應由 SYNC_PACK 消息提供。

```json
{
  "header": {
    "taskType": "SYNC_PRODUCT",
    "merchantId": "M001",
    "platformId": "shopee",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-320000",
    "timestamp": "2026-02-13T11:00:00Z",
    "source": "channel_job",
    "version": 1
  },
  "body": {
    "sku": "SH-SKU-001",
    "name": "iPhone 15 Pro Max",
    "costPrice": 35000,
    "suggestPrice": 44900
  }
}
```

**TaskType: UPDATE_INVENTORY** - 庫存更新

```json
{
  "header": {
    "taskType": "UPDATE_INVENTORY",
    "merchantId": "M001",
    "channelId": "PCHOME_001",
    "requestId": "req-20260213-330000",
    "timestamp": "2026-02-13T10:30:00Z",
    "source": "channel_job",
    "version": 1
  },
  "body": {
    "updates": [
      {
        "productId": "SKU-001",
        "quantity": 50,
        "type": "ABSOLUTE"
      }
    ]
  }
}
```

**TaskType: SHIP_ORDER** - 出貨指令

```json
{
  "header": {
    "taskType": "SHIP_ORDER",
    "merchantId": "M001",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-340000",
    "timestamp": "2026-02-13T12:00:00Z",
    "source": "scheduler",
    "version": 1
  },
  "body": {
    "orderId": "ORD-SYS-20260213-001",
    "channelOrderId": "SH202602130456",
    "shippingMethod": "SHOPEE_PICKUP",
    "trackingNumber": "SE123456789"
  }
}
```

**TaskType: SYNC_PACK** - 上架映射同步（UI 驅動，{platform}.slow 執行雙層檢查 + 條件派發）

```json
{
  "header": {
    "taskType": "SYNC_PACK",
    "merchantId": "M001",
    "platformId": "pchome",
    "channelId": "PCHOME_001",
    "requestId": "req-20260213-350000",
    "timestamp": "2026-02-13T06:30:00Z",
    "source": "admin_ui",
    "version": 1
  },
  "body": {
    "channelProductId": "PC-2026021300001",    // ← 通路商品 ID（Unique Key 的一部分）
    "channelSpecId": "SPEC-001",                // ← 通路規格 ID
    "channelProductName": "iPhone 15 Pro Max - Space Black 256GB",
    "channelSpecName": "太空黑 / 256GB",
    "sku": "PCHOME-SKU-001",                   // ← 通路 SKU
    "channelSpecAttrs": {
      "color": "Space Black",
      "capacity": "256GB"
    },
    "sellingPrice": 44900,
    "packInfo": {
      "packStatus": "ACTIVE",
      "visibility": "VISIBLE"
    }
  }
}
```

---

### task.frontend - 前端非同步任務
保留時間：1d

前端 UI 觸發的非同步任務，如資料匯出、批次更新等。

**TaskType: SHIP_ORDER** (手動出貨)

```json
{
  "header": {
    "taskType": "SHIP_ORDER",
    "merchantId": "M001",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-360000",
    "timestamp": "2026-02-13T14:00:00Z",
    "source": "admin_ui",
    "version": 1,
    "priority": "HIGH"
  },
  "body": {
    "orderId": "ORD-SYS-20260213-002",
    "channelOrderId": "SH202602130500",
    "shippingMethod": "BLACK_CAT",
    "carrier": "BLACK_CAT",
    "trackingNumber": "BC123456789"
  }
}
```

**說明**：task.frontend 訊息隨後會被轉發到 task.backend 由 backend-task-handler 實際執行。

---

### task.failed - 失敗任務與死信隊列
保留時間：1d (task.failed) / 30d (task.dlt)

**說明**：
- task.failed：可重試的失敗訊息，error-handler 會根據錯誤類型決定是否重試或轉移到 task.dlt
- task.dlt：無法恢復的訊息，dlt-handler 記錄詳細資訊並發出告警

詳見 CORE_CONTRACTS.md 第 6 章「錯誤處理契約」

---

## Redis 去重策略

見 `REDIS_DEDUPLICATION.md`

- **Key 格式**: `order:hash:{merchantId}:{channelId}:{orderId}`
- **特殊字元保留**: 訂單號中的 `#`, `-`, `@` 等字元必須完整保留
- **例子**:
  - `order:hash:M001:SHOPIFY_001:1002#100` (不同於 `order:hash:M001:SHOPIFY_001:1002100`)
  - `order:hash:M001:CYBERBIZ_002:ORD@2024-001`

---

## TaskType 路由對應表

### 通路主題 → 業務主題 的 TaskType 對應

| TaskType | 來源 Topic | 目標 Topic | Consumer Group | 說明 |
|----------|-----------|-----------|----------------|------|
| FETCH_ORDERS | {platform}.slow | order.process | channel-job-{platform}-slow | 通路訂單列表 |
| FETCH_ORDER_DETAIL | {platform}.slow | order.process | channel-job-{platform}-slow | 訂單詳情 |
| SHIP_ORDER | {platform}.fast | task.backend | channel-job-{platform}-fast | 出貨指令 |
| UPDATE_PRICE | {platform}.fast | task.backend | channel-job-{platform}-fast | 價格更新 |
| UPDATE_INVENTORY | {platform}.fast | task.backend | channel-job-{platform}-fast | 庫存更新 |
| SYNC_PACK | {platform}.slow | task.backend | channel-job-{platform}-slow | 通路套包同步 |
| FETCH_RETURNS | {platform}.slow | return.process | channel-job-{platform}-slow | 退貨列表 |
| FETCH_RETURN_DETAIL | {platform}.slow | return.process | channel-job-{platform}-slow | 退貨詳情 |
| APPROVE_RETURN | {platform}.fast | return.process | channel-job-{platform}-fast | 同意退貨 |

### 業務主題 → 處理邏輯 的 TaskType 對應

| TaskType | 來源 Topic | Handler | 說明 |
|----------|-----------|---------|------|
| PROCESS_ORDER | order.process | order-process-handler | 訂單入庫（Handler 查詢 DB 決定 INSERT 或 UPDATE） |
| PROCESS_RETURN | return.process | return-process-handler | 退貨入庫（Handler 查詢 DB 決定 INSERT 或 UPDATE） |
| SYNC_PRODUCT | task.backend | backend-task-handler | 商品同步 |
| UPDATE_INVENTORY | task.backend | backend-task-handler | 庫存更新 |
| UPDATE_PRICE | task.backend | backend-task-handler | 價格更新 |
| SYNC_STORE | task.backend | backend-task-handler | 賣場同步 |
| SHIP_ORDER | task.backend | backend-task-handler | 出貨作業 |

### 錯誤處理 TaskType

| TaskType | 來源 Topic | Handler | 說明 |
|----------|-----------|---------|------|
| FAILED_TASK | task.failed | error-handler | 可重試的失敗訊息（max retry 後轉移到 task.dlt） |
| DLT_MESSAGE | task.dlt | dlt-handler | 無法恢復的訊息，記錄並發出告警 |

---

## 文件交叉參考

- **CORE_CONTRACTS.md**: 核心契約和 TaskType 定義
- **REDIS_DEDUPLICATION.md**: Redis 去重和特殊字元處理
- **DATA_FLOW_MAPPING.md**: 通路資料流轉
- **HANDLER_REGISTRY.md**: Handler 實現對應
- **CHANNEL_IMPLEMENTATION_GUIDE.md**: Channel Job 實作指南