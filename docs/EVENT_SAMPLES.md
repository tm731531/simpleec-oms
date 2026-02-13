# SimpleEC OMS 事件流範例

本文件提供所有 Kafka topic 的訊息格式範例，用於開發參考與系統整合。

## 1. Channel Topics (通路主題)

### 1.1 Fast Topics (快速處理)
用於商品同步、訂單抓取等需要快速處理的任務。

#### momo.fast / shopee.fast / yahoo.fast / pchome.fast / cyberbiz.fast

```json
{
  "taskType": "FETCH_ORDERS",
  "merchantId": "M001",
  "channelId": "MOMO_001",
  "startTime": "2026-02-13T10:00:00Z",
  "endTime": "2026-02-13T11:00:00Z",
  "metadata": {
    "requestId": "req-20260213-100000",
    "retryCount": 0,
    "source": "scheduler"
  },
  "schemaVersion": 1
}
```

```json
{
  "taskType": "SYNC_PRODUCT_FAST",
  "merchantId": "M001",
  "channelId": "SHOPEE_001",
  "productIds": ["SKU001", "SKU002", "SKU003"],
  "action": "UPDATE",
  "metadata": {
    "requestId": "req-20260213-100100",
    "batchId": "batch-001",
    "totalCount": 3
  },
  "schemaVersion": 1
}
```

### 1.2 Slow Topics (慢速處理)
用於商品詳細資訊同步、大批量處理等耗時操作。

#### momo.slow / shopee.slow / yahoo.slow / pchome.slow / cyberbiz.slow

```json
{
  "taskType": "FETCH_PRODUCT_DETAIL",
  "merchantId": "M001",
  "channelId": "MOMO_001",
  "productIds": ["SKU001", "SKU002"],
  "includeInventory": true,
  "includeImages": true,
  "metadata": {
    "requestId": "req-20260213-100200",
    "parentTaskId": "task-fast-001",
    "priority": "LOW"
  },
  "schemaVersion": 1
}
```

```json
{
  "taskType": "SYNC_INVENTORY_BATCH",
  "merchantId": "M001",
  "channelId": "PCHOME_001",
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
  "metadata": {
    "requestId": "req-20260213-100300",
    "source": "manual_adjustment"
  },
  "schemaVersion": 1
}
```

## 2. Business Topics (業務主題)

### 2.1 order.process
訂單處理主題，處理訂單狀態變更、出貨等。

```json
{
  "orderId": "ORD20260213001",
  "merchantId": "M001",
  "channelId": "MOMO_001",
  "action": "SHIP",
  "shipmentInfo": {
    "trackingNumber": "TN123456789",
    "carrier": "BLACK_CAT",
    "shipDate": "2026-02-13",
    "estimatedDelivery": "2026-02-15"
  },
  "metadata": {
    "requestId": "req-20260213-100400",
    "operatorId": "USER001",
    "source": "backend_api"
  },
  "schemaVersion": 1
}
```

```json
{
  "orderId": "ORD20260213002",
  "merchantId": "M001",
  "channelId": "SHOPEE_001",
  "action": "CANCEL",
  "cancelReason": "OUT_OF_STOCK",
  "cancelNote": "商品已售完",
  "metadata": {
    "requestId": "req-20260213-100500",
    "operatorId": "SYSTEM",
    "source": "auto_cancel"
  },
  "schemaVersion": 1
}
```

### 2.2 task.backend
後端任務主題，處理系統內部任務。

```json
{
  "taskType": "GENERATE_REPORT",
  "merchantId": "M001",
  "reportType": "DAILY_SALES",
  "parameters": {
    "date": "2026-02-13",
    "format": "PDF",
    "includeDetails": true
  },
  "metadata": {
    "requestId": "req-20260213-100600",
    "scheduledTime": "2026-02-14T00:00:00Z"
  },
  "schemaVersion": 1
}
```

```json
{
  "taskType": "SYNC_MASTER_DATA",
  "merchantId": "M001",
  "dataType": "PRODUCT_CATEGORY",
  "action": "FULL_SYNC",
  "metadata": {
    "requestId": "req-20260213-100700",
    "source": "manual_trigger"
  },
  "schemaVersion": 1
}
```

### 2.3 task.frontend
前端任務主題，處理與前端相關的非同步任務。

```json
{
  "taskType": "EXPORT_DATA",
  "merchantId": "M001",
  "userId": "USER001",
  "exportType": "ORDER_LIST",
  "filters": {
    "startDate": "2026-02-01",
    "endDate": "2026-02-13",
    "status": ["SHIPPED", "DELIVERED"]
  },
  "metadata": {
    "requestId": "req-20260213-100800",
    "sessionId": "session-001",
    "callbackUrl": "/api/exports/callback"
  },
  "schemaVersion": 1
}
```

```json
{
  "taskType": "BULK_UPDATE",
  "merchantId": "M001",
  "userId": "USER002",
  "entityType": "PRODUCT",
  "updates": [
    {
      "id": "SKU001",
      "fields": {
        "price": 999,
        "name": "Updated Product Name"
      }
    }
  ],
  "metadata": {
    "requestId": "req-20260213-100900",
    "source": "admin_panel"
  },
  "schemaVersion": 1
}
```

### 2.4 scheduler
排程器主題，用於分發排程任務。

```json
{
  "scheduleId": "SCH001",
  "action": "FETCH_ALL_ORDERS",
  "merchantId": "M001",
  "channels": ["MOMO_001", "SHOPEE_001", "YAHOO_001"],
  "timeRange": {
    "start": "2026-02-13T10:00:00Z",
    "end": "2026-02-13T11:00:00Z"
  },
  "metadata": {
    "requestId": "req-20260213-101000",
    "scheduleMode": "interval",
    "intervalSeconds": 300
  },
  "schemaVersion": 1
}
```

```json
{
  "scheduleId": "SCH002",
  "action": "CHECK_ALL_HEALTH",
  "merchantId": "M001",
  "healthChecks": [
    {
      "type": "API_STATUS",
      "targets": ["MOMO", "SHOPEE", "YAHOO", "PCHOME", "CYBERBIZ"]
    },
    {
      "type": "DATABASE_CONNECTION",
      "targets": ["PRIMARY", "REPLICA"]
    }
  ],
  "metadata": {
    "requestId": "req-20260213-101100",
    "alertThreshold": 3
  },
  "schemaVersion": 1
}
```

### 2.5 task.failed
失敗任務主題，記錄處理失敗的任務。

```json
{
  "originalTopic": "momo.fast",
  "originalMessage": {
    "taskType": "FETCH_ORDERS",
    "merchantId": "M001",
    "channelId": "MOMO_001"
  },
  "failureInfo": {
    "timestamp": "2026-02-13T10:15:00Z",
    "errorCode": "API_TIMEOUT",
    "errorMessage": "MOMO API request timeout after 30s",
    "stackTrace": "java.net.SocketTimeoutException: Read timed out...",
    "retryCount": 3,
    "maxRetries": 3
  },
  "metadata": {
    "requestId": "req-20260213-100000",
    "processingNode": "worker-01"
  },
  "schemaVersion": 1
}
```

### 2.6 task.dlt (Dead Letter Topic)
死信主題，存放無法處理的訊息。

```json
{
  "originalTopic": "shopee.slow",
  "originalMessage": "{corrupted or unparseable message}",
  "dltInfo": {
    "timestamp": "2026-02-13T10:30:00Z",
    "reason": "DESERIALIZATION_ERROR",
    "errorMessage": "Cannot deserialize message: Invalid JSON structure",
    "processingAttempts": 5,
    "lastProcessor": "backend-job-02"
  },
  "metadata": {
    "messageKey": "key-001",
    "partition": 3,
    "offset": 12345
  },
  "schemaVersion": 1
}
```

## 3. 特殊案例

### 3.1 Yahoo CSV Webhook (特殊流程)
Yahoo 商品同步透過 webhook 接收 CSV，直接寫入 yahoo.slow。

```json
{
  "taskType": "PROCESS_YAHOO_CSV",
  "merchantId": "M001",
  "channelId": "YAHOO_001",
  "csvUrl": "https://storage.example.com/yahoo/products_20260213.csv",
  "csvMetadata": {
    "rows": 5000,
    "columns": ["product_id", "name", "price", "inventory"],
    "encoding": "UTF-8"
  },
  "metadata": {
    "requestId": "webhook-20260213-101200",
    "webhookId": "WH001",
    "receivedAt": "2026-02-13T10:12:00Z"
  },
  "schemaVersion": 1
}
```

### 3.2 批次處理任務
大量商品同步任務範例。

```json
{
  "taskType": "SYNC_PRODUCT_BATCH",
  "merchantId": "M001",
  "channelId": "PCHOME_001",
  "batchInfo": {
    "batchId": "BATCH-20260213-001",
    "totalProducts": 10000,
    "batchSize": 100,
    "currentBatch": 1
  },
  "products": [
    {
      "id": "SKU001",
      "name": "Product 1",
      "price": 1000,
      "inventory": 50
    },
    {
      "id": "SKU002",
      "name": "Product 2",
      "price": 2000,
      "inventory": 30
    }
  ],
  "metadata": {
    "requestId": "req-20260213-101300",
    "source": "manual_sync",
    "priority": "HIGH"
  },
  "schemaVersion": 1
}
```

## 4. Schema Version 處理

所有訊息都包含 `schemaVersion` 欄位：
- 目前版本: 1
- 不支援的版本會被路由到 task.dlt
- `SchemaVersionHandler.normalize(0)` 會轉換為 1

## 5. Metadata 標準欄位

所有訊息的 metadata 應包含：
- `requestId`: 唯一請求識別碼
- `source`: 訊息來源 (scheduler/manual/api/webhook)
- `retryCount`: 重試次數（如適用）
- `priority`: 優先級 (HIGH/NORMAL/LOW)（如適用）

## 6. 錯誤處理流程

1. **可重試錯誤** → task.failed (保留 1 天)
   - API timeout
   - Rate limiting
   - Temporary network issues

2. **不可重試錯誤** → task.dlt (保留 30 天)
   - Deserialization errors
   - Schema version mismatch
   - Invalid message structure

## 7. 配置覆寫

所有 topic 保留時間可透過 application.yml 覆寫：

```yaml
simpleec:
  kafka:
    retention:
      channel: 1d        # 所有 channel topics (fast/slow)
      order-process: 1d  # 未來會降至 2h
      task-backend: 1d
      task-frontend: 1d
      scheduler: 1d
      task-failed: 1d
      task-dlt: 30d      # 死信保留較久
```