# SimpleEC OMS 事件流範例

本文件提供所有 Kafka topic 的訊息格式範例，用於開發參考與系統整合。

## 統一訊息結構

所有 Kafka topic 都使用相同的 `header/body` 結構：

```json
{
  "header": {
    "taskType": "具體任務類型",
    "merchantId": "商家ID",
    "channelId": "通路ID",
    "requestId": "唯一請求識別碼",
    "timestamp": "訊息產生時間",
    "source": "訊息來源",
    "version": "訊息版本",
    "retryCount": "重試次數",
    "priority": "優先級"
  },
  "body": {
    // 業務資料根據 taskType 而定
  }
}
```

### Header 欄位說明
- `taskType`: 決定使用哪個處理類別 (Handler Class)
- `merchantId`: 商家識別碼，用於多商戶隔離
- `channelId`: 通路實例 ID (如 MOMO_001, SHOPEE_002)
- `requestId`: 追蹤用的唯一識別碼
- `timestamp`: ISO 8601 格式時間戳記
- `source`: 訊息來源 (scheduler/api/webhook/manual)
- `version`: 訊息版本，用於向後相容
- `retryCount`: 當前重試次數（可選）
- `priority`: HIGH/NORMAL/LOW（可選）

## 1. Channel Topics (通路主題)

### 1.1 Fast Topics (快速處理)
用於訂單抓取、快速商品同步等需要即時處理的任務。

#### momo.fast / shopee.fast / yahoo.fast / pchome.fast / cyberbiz.fast

**範例 1: 抓取訂單 (FETCH_ORDERS)**
```json
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "merchantId": "M001",
    "channelId": "MOMO_001",
    "requestId": "req-20260213-100000",
    "timestamp": "2026-02-13T10:00:00Z",
    "source": "scheduler",
    "version": 1
  },
  "body": {
    "startTime": "2026-02-13T09:00:00Z",
    "endTime": "2026-02-13T10:00:00Z",
    "orderStatus": ["PENDING", "PROCESSING"],
    "pageSize": 100
  }
}
```

**範例 2: 快速商品同步 (SYNC_PRODUCT_FAST)**
```json
{
  "header": {
    "taskType": "SYNC_PRODUCT_FAST",
    "merchantId": "M001",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-100100",
    "timestamp": "2026-02-13T10:01:00Z",
    "source": "api",
    "version": 1,
    "priority": "HIGH"
  },
  "body": {
    "products": [
      {
        "productId": "SKU001",
        "action": "UPDATE_PRICE",
        "price": 999
      },
      {
        "productId": "SKU002",
        "action": "UPDATE_STOCK",
        "stock": 100
      }
    ],
    "batchId": "batch-001"
  }
}
```

### 1.2 Slow Topics (慢速處理)
用於商品詳細資訊同步、大批量處理等耗時操作。

#### momo.slow / shopee.slow / yahoo.slow / pchome.slow / cyberbiz.slow

**範例 1: 抓取商品詳情 (FETCH_PRODUCT_DETAIL)**
```json
{
  "header": {
    "taskType": "FETCH_PRODUCT_DETAIL",
    "merchantId": "M001",
    "channelId": "MOMO_001",
    "requestId": "req-20260213-100200",
    "timestamp": "2026-02-13T10:02:00Z",
    "source": "api",
    "version": 1,
    "priority": "LOW"
  },
  "body": {
    "productIds": ["SKU001", "SKU002", "SKU003"],
    "includeFields": ["description", "images", "attributes", "variants"],
    "parentTaskId": "task-fast-001"
  }
}
```

**範例 2: 批次庫存同步 (SYNC_INVENTORY_BATCH)**
```json
{
  "header": {
    "taskType": "SYNC_INVENTORY_BATCH",
    "merchantId": "M001",
    "channelId": "PCHOME_001",
    "requestId": "req-20260213-100300",
    "timestamp": "2026-02-13T10:03:00Z",
    "source": "manual",
    "version": 1
  },
  "body": {
    "inventoryUpdates": [
      {
        "productId": "SKU001",
        "warehouseId": "WH001",
        "quantity": 100,
        "type": "ABSOLUTE"
      },
      {
        "productId": "SKU002",
        "warehouseId": "WH001",
        "quantity": -5,
        "type": "RELATIVE"
      }
    ],
    "updateMode": "BATCH",
    "validateStock": true
  }
}
```

## 2. Business Topics (業務主題)

### 2.1 order.process
訂單處理主題，存放**完整訂單資料**作為事實來源 (Source of Truth)。

**範例 1: 新訂單 (NEW_ORDER)**
```json
{
  "header": {
    "taskType": "NEW_ORDER",
    "merchantId": "M001",
    "channelId": "MOMO_001",
    "requestId": "req-20260213-100400",
    "timestamp": "2026-02-13T10:04:00Z",
    "source": "order_fetch",
    "version": 1
  },
  "body": {
    "order": {
      "orderId": "ORD20260213001",
      "channelOrderId": "MOMO-2026021300123",
      "orderStatus": "PENDING",
      "orderDate": "2026-02-13T09:30:00Z",
      "customer": {
        "customerId": "CUST001",
        "name": "王小明",
        "phone": "0912345678",
        "email": "wang@example.com"
      },
      "shippingAddress": {
        "recipient": "王小明",
        "phone": "0912345678",
        "postalCode": "10491",
        "city": "台北市",
        "district": "中山區",
        "address": "南京東路三段100號5樓"
      },
      "items": [
        {
          "lineId": "L001",
          "productId": "SKU001",
          "productName": "iPhone 15 Pro Max 256GB",
          "channelSkuId": "MOMO-SKU-001",
          "quantity": 1,
          "unitPrice": 44900,
          "discount": 1000,
          "lineTotal": 43900
        }
      ],
      "payment": {
        "method": "CREDIT_CARD",
        "status": "PAID",
        "paidAmount": 43900,
        "paidTime": "2026-02-13T09:31:00Z",
        "transactionId": "TXN123456789"
      },
      "shipping": {
        "method": "HOME_DELIVERY",
        "carrier": "BLACK_CAT",
        "shippingFee": 0,
        "estimatedDelivery": "2026-02-15"
      },
      "totals": {
        "subtotal": 44900,
        "shippingFee": 0,
        "totalDiscount": 1000,
        "tax": 2090,
        "grandTotal": 43900
      }
    }
  }
}
```

**範例 2: 訂單更新 (UPDATE_ORDER)**
```json
{
  "header": {
    "taskType": "UPDATE_ORDER",
    "merchantId": "M001",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-100500",
    "timestamp": "2026-02-13T14:00:00Z",
    "source": "order_fetch",
    "version": 1
  },
  "body": {
    "orderId": "ORD20260213002",
    "channelOrderId": "SH202602130456",
    "updateType": "STATUS_CHANGE",
    "previousStatus": "PROCESSING",
    "newStatus": "SHIPPED",
    "shipmentInfo": {
      "shippedTime": "2026-02-13T14:00:00Z",
      "trackingNumber": "SE123456789",
      "carrier": "7-ELEVEN",
      "storeId": "131415",
      "storeName": "台中西屯門市"
    },
    "fullOrder": {
      // 完整訂單資料（同 NEW_ORDER 結構）
    }
  }
}
```

### 2.2 task.backend
後端任務主題，處理系統內部任務。

**範例 1: 產生報表 (GENERATE_REPORT)**
```json
{
  "header": {
    "taskType": "GENERATE_REPORT",
    "merchantId": "M001",
    "requestId": "req-20260213-100600",
    "timestamp": "2026-02-13T10:06:00Z",
    "source": "scheduler",
    "version": 1
  },
  "body": {
    "reportType": "DAILY_SALES",
    "reportDate": "2026-02-13",
    "format": "PDF",
    "includeDetails": true,
    "emailTo": ["manager@example.com"]
  }
}
```

**範例 2: 同步主資料 (SYNC_MASTER_DATA)**
```json
{
  "header": {
    "taskType": "SYNC_MASTER_DATA",
    "merchantId": "M001",
    "requestId": "req-20260213-100700",
    "timestamp": "2026-02-13T10:07:00Z",
    "source": "manual",
    "version": 1
  },
  "body": {
    "dataType": "PRODUCT_CATEGORY",
    "action": "FULL_SYNC",
    "sourceSystem": "ERP",
    "targetChannels": ["MOMO", "SHOPEE", "YAHOO"]
  }
}
```

### 2.3 task.frontend
前端任務主題，處理與前端相關的非同步任務。

**範例 1: 匯出資料 (EXPORT_DATA)**
```json
{
  "header": {
    "taskType": "EXPORT_DATA",
    "merchantId": "M001",
    "requestId": "req-20260213-100800",
    "timestamp": "2026-02-13T10:08:00Z",
    "source": "admin_ui",
    "version": 1
  },
  "body": {
    "userId": "USER001",
    "exportType": "ORDER_LIST",
    "filters": {
      "startDate": "2026-02-01",
      "endDate": "2026-02-13",
      "status": ["SHIPPED", "DELIVERED"],
      "channels": ["MOMO", "SHOPEE"]
    },
    "format": "EXCEL",
    "callbackUrl": "/api/exports/callback"
  }
}
```

**範例 2: 批次更新 (BULK_UPDATE)**
```json
{
  "header": {
    "taskType": "BULK_UPDATE",
    "merchantId": "M001",
    "requestId": "req-20260213-100900",
    "timestamp": "2026-02-13T10:09:00Z",
    "source": "admin_ui",
    "version": 1,
    "priority": "HIGH"
  },
  "body": {
    "userId": "USER002",
    "entityType": "PRODUCT",
    "updates": [
      {
        "id": "SKU001",
        "fields": {
          "price": 999,
          "name": "Updated Product Name"
        }
      },
      {
        "id": "SKU002",
        "fields": {
          "price": 1299,
          "stock": 50
        }
      }
    ],
    "validateBeforeUpdate": true
  }
}
```

### 2.4 scheduler
排程器主題，用於分發排程任務。

**範例 1: 分發訂單抓取任務 (DISPATCH_ORDER_FETCH)**
```json
{
  "header": {
    "taskType": "DISPATCH_ORDER_FETCH",
    "merchantId": "M001",
    "requestId": "req-20260213-101000",
    "timestamp": "2026-02-13T10:10:00Z",
    "source": "scheduler",
    "version": 1
  },
  "body": {
    "scheduleId": "SCH001",
    "action": "FETCH_ALL_ORDERS",
    "channels": [
      {"channelId": "MOMO_001", "enabled": true},
      {"channelId": "SHOPEE_001", "enabled": true},
      {"channelId": "YAHOO_001", "enabled": false}
    ],
    "timeRange": {
      "start": "2026-02-13T10:00:00Z",
      "end": "2026-02-13T11:00:00Z"
    },
    "scheduleConfig": {
      "mode": "interval",
      "intervalSeconds": 300,
      "retryOnFailure": true
    }
  }
}
```

**範例 2: 健康檢查 (HEALTH_CHECK)**
```json
{
  "header": {
    "taskType": "HEALTH_CHECK",
    "merchantId": "M001",
    "requestId": "req-20260213-101100",
    "timestamp": "2026-02-13T10:11:00Z",
    "source": "scheduler",
    "version": 1
  },
  "body": {
    "checkTargets": [
      {
        "type": "API_STATUS",
        "channels": ["MOMO", "SHOPEE", "YAHOO", "PCHOME", "CYBERBIZ"]
      },
      {
        "type": "DATABASE",
        "databases": ["PRIMARY", "REPLICA"]
      },
      {
        "type": "KAFKA",
        "topics": ["order.process", "task.backend"]
      }
    ],
    "alertConfig": {
      "threshold": 3,
      "notifyChannels": ["EMAIL", "SLACK"]
    }
  }
}
```

### 2.5 task.failed
失敗任務主題，記錄處理失敗的任務。

```json
{
  "header": {
    "taskType": "FAILED_TASK",
    "merchantId": "M001",
    "channelId": "MOMO_001",
    "requestId": "req-20260213-100000",
    "timestamp": "2026-02-13T10:15:00Z",
    "source": "task_processor",
    "version": 1
  },
  "body": {
    "originalTopic": "momo.fast",
    "originalHeader": {
      "taskType": "FETCH_ORDERS",
      "merchantId": "M001",
      "channelId": "MOMO_001",
      "requestId": "req-20260213-100000",
      "timestamp": "2026-02-13T10:00:00Z",
      "source": "scheduler",
      "version": 1
    },
    "originalBody": {
      "startTime": "2026-02-13T09:00:00Z",
      "endTime": "2026-02-13T10:00:00Z"
    },
    "failureInfo": {
      "errorCode": "API_TIMEOUT",
      "errorMessage": "MOMO API request timeout after 30s",
      "stackTrace": "java.net.SocketTimeoutException: Read timed out...",
      "failedAt": "2026-02-13T10:15:00Z",
      "retryCount": 3,
      "maxRetries": 3,
      "processingNode": "worker-01"
    }
  }
}
```

### 2.6 task.dlt (Dead Letter Topic)
死信主題，存放無法處理的訊息。

```json
{
  "header": {
    "taskType": "DLT_MESSAGE",
    "merchantId": "UNKNOWN",
    "requestId": "dlt-20260213-101500",
    "timestamp": "2026-02-13T10:15:00Z",
    "source": "error_handler",
    "version": 1
  },
  "body": {
    "originalTopic": "shopee.slow",
    "originalMessage": "{corrupted or unparseable JSON}",
    "originalKey": "key-001",
    "dltInfo": {
      "reason": "DESERIALIZATION_ERROR",
      "errorMessage": "Cannot deserialize message: Invalid JSON structure",
      "processingAttempts": 5,
      "lastProcessor": "backend-job-02",
      "kafkaMetadata": {
        "partition": 3,
        "offset": 12345,
        "timestamp": 1707820500000
      }
    }
  }
}
```

## 3. 特殊案例

### 3.1 Yahoo CSV Webhook
Yahoo 商品同步透過 webhook 接收 CSV，直接寫入 yahoo.slow。

```json
{
  "header": {
    "taskType": "PROCESS_YAHOO_CSV",
    "merchantId": "M001",
    "channelId": "YAHOO_001",
    "requestId": "webhook-20260213-101200",
    "timestamp": "2026-02-13T10:12:00Z",
    "source": "webhook",
    "version": 1,
    "priority": "NORMAL"
  },
  "body": {
    "csvUrl": "https://storage.example.com/yahoo/products_20260213.csv",
    "csvMetadata": {
      "fileSize": 1048576,
      "rows": 5000,
      "columns": ["product_id", "name", "price", "inventory"],
      "encoding": "UTF-8",
      "delimiter": ","
    },
    "webhookInfo": {
      "webhookId": "WH001",
      "receivedAt": "2026-02-13T10:12:00Z",
      "signature": "sha256=..."
    },
    "processingOptions": {
      "validateData": true,
      "updateExisting": true,
      "createNew": false
    }
  }
}
```

### 3.2 大批量商品同步
分批處理 10000 筆商品。

```json
{
  "header": {
    "taskType": "SYNC_PRODUCT_BATCH",
    "merchantId": "M001",
    "channelId": "PCHOME_001",
    "requestId": "batch-20260213-001-01",
    "timestamp": "2026-02-13T10:13:00Z",
    "source": "batch_processor",
    "version": 1,
    "priority": "LOW"
  },
  "body": {
    "batchInfo": {
      "batchId": "BATCH-20260213-001",
      "totalProducts": 10000,
      "totalBatches": 100,
      "currentBatch": 1,
      "batchSize": 100
    },
    "products": [
      {
        "id": "SKU001",
        "name": "Product 1",
        "price": 1000,
        "inventory": 50,
        "attributes": {
          "color": "red",
          "size": "M"
        }
      },
      {
        "id": "SKU002",
        "name": "Product 2",
        "price": 2000,
        "inventory": 30,
        "attributes": {
          "color": "blue",
          "size": "L"
        }
      }
      // ... 98 more products
    ],
    "syncOptions": {
      "updatePrice": true,
      "updateInventory": true,
      "updateAttributes": false
    }
  }
}
```

## 4. TaskType 對應 Handler Class

根據 header.taskType 決定使用哪個處理類別：

| TaskType | Handler Class | Topic |
|----------|--------------|-------|
| FETCH_ORDERS | FetchOrdersHandler | {platform}.fast |
| SYNC_PRODUCT_FAST | SyncProductFastHandler | {platform}.fast |
| FETCH_PRODUCT_DETAIL | FetchProductDetailHandler | {platform}.slow |
| SYNC_INVENTORY_BATCH | SyncInventoryBatchHandler | {platform}.slow |
| PROCESS_YAHOO_CSV | ProcessYahooCsvHandler | yahoo.slow |
| NEW_ORDER | NewOrderHandler | order.process |
| UPDATE_ORDER | UpdateOrderHandler | order.process |
| GENERATE_REPORT | GenerateReportHandler | task.backend |
| SYNC_MASTER_DATA | SyncMasterDataHandler | task.backend |
| EXPORT_DATA | ExportDataHandler | task.frontend |
| BULK_UPDATE | BulkUpdateHandler | task.frontend |
| DISPATCH_ORDER_FETCH | DispatchOrderFetchHandler | scheduler |
| HEALTH_CHECK | HealthCheckHandler | scheduler |
| FAILED_TASK | FailedTaskHandler | task.failed |
| DLT_MESSAGE | DltMessageHandler | task.dlt |

## 5. 版本管理

- 所有訊息 header 都包含 `version` 欄位
- 目前版本: 1
- 不支援的版本會被路由到 task.dlt
- 版本升級策略：
  - v1 → v2: Handler 需同時支援兩個版本
  - 逐步遷移，確保向後相容
  - 廢棄舊版本前需公告週期

## 6. 錯誤處理流程

1. **可重試錯誤** → task.failed (保留 1 天)
   - API timeout
   - Rate limiting
   - Temporary network issues
   - Database connection issues

2. **不可重試錯誤** → task.dlt (保留 30 天)
   - Deserialization errors
   - Version mismatch (unsupported version)
   - Invalid message structure
   - Missing required fields in header

## 7. 配置覆寫

所有 topic 保留時間可透過 application.yml 覆寫：

```yaml
simpleec:
  kafka:
    retention:
      channel: 1d        # 所有 channel topics (fast/slow)
      order-process: 1d  # 未來穩定後會降至 2h
      task-backend: 1d
      task-frontend: 1d
      scheduler: 1d
      task-failed: 1d
      task-dlt: 30d      # 死信保留較久以便調查
```

## 8. 最佳實踐

1. **Header 設計原則**
   - Header 只包含路由和追蹤資訊
   - 業務資料全部放在 body
   - Header 欄位保持精簡，避免過度設計

2. **Body 設計原則**
   - 根據 taskType 定義明確的資料結構
   - 使用嵌套物件而非扁平結構
   - 保留擴展性，使用物件而非基本型別

3. **TaskType 命名規範**
   - 使用 UPPER_SNAKE_CASE
   - 動詞_名詞格式 (如 FETCH_ORDERS)
   - 避免過於通用的名稱

4. **錯誤處理**
   - 可重試的暫時性錯誤送往 task.failed
   - 無法解析或結構錯誤送往 task.dlt
   - 保留完整錯誤上下文以便除錯