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
- `taskType`: 決定使用哪個處理類別 (Handler Class)
- `merchantId`: 商家識別碼，用於多商戶隔離
- `channelId`: 通路實例 ID (如 MOMO_001, SHOPEE_002)
- `requestId`: 追蹤用的唯一識別碼
- `timestamp`: ISO 8601 格式時間戳記
- `source`: 訊息來源 (scheduler/api/webhook/manual)
- `version`: 訊息版本，用於向後相容
- `retryCount`: 當前重試次數（可選）
- `priority`: HIGH/NORMAL/LOW（可選）
- `correlationId`: 關聯識別碼，串連 list→detail（可選）

---

## Channel Topics (通路主題 - 10個)

### 平台快速主題：momo.fast / shopee.fast / yahoo.fast / pchome.fast / cyberbiz.fast

**TaskType: FETCH_ORDERS** - 通路發送訂單列表給 order.process（需要判斷是否需詳情）

```json
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "merchantId": "M001",
    "channelId": "MOMO_001",
    "requestId": "req-20260213-100000",
    "timestamp": "2026-02-13T10:00:00Z",
    "source": "scheduler",
    "version": 1,
    "priority": "HIGH"
  },
  "body": {
    "timeRange": {
      "start": "2026-02-13T09:00:00Z",
      "end": "2026-02-13T10:00:00Z"
    },
    "orders": [
      {
        "orderId": "MOMO-2026021300001",
        "orderData": {
          "orderStatus": "READY_TO_SHIP",
          "totalAmount": 15000,
          "shippingStatus": "PROCESSING"
        },
        "needsDetail": true,
        "metadata": {
          "reason": "大單需詳情"
        }
      },
      {
        "orderId": "MOMO-2026021300002",
        "orderData": {
          "orderStatus": "PENDING",
          "totalAmount": 500
        },
        "needsDetail": false,
        "metadata": {}
      }
    ],
    "summary": {
      "total": 100,
      "fetched": 2,
      "hasMore": true
    }
  }
}
```

**TaskType: SHIP_ORDER** - 出貨指令（主要用於 {platform}.fast）

```json
{
  "header": {
    "taskType": "SHIP_ORDER",
    "merchantId": "M001",
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

**TaskType: FETCH_ORDER_DETAIL** - 抓取訂單詳情

```json
{
  "header": {
    "taskType": "FETCH_ORDER_DETAIL",
    "merchantId": "M001",
    "channelId": "MOMO_001",
    "requestId": "req-20260213-200000",
    "timestamp": "2026-02-13T10:30:00Z",
    "source": "order_process_job",
    "version": 1,
    "correlationId": "req-20260213-100000",
    "priority": "NORMAL"
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

**TaskType: SYNC_PRODUCT** - 同步商品（商品詳情、屬性、圖片等）

```json
{
  "header": {
    "taskType": "SYNC_PRODUCT",
    "merchantId": "M001",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-200001",
    "timestamp": "2026-02-13T10:45:00Z",
    "source": "product_sync_job",
    "version": 1,
    "priority": "LOW"
  },
  "body": {
    "action": "FULL_SYNC",
    "products": [
      {
        "productId": "SH-SKU-001",
        "name": "iPhone 15 Pro Max",
        "description": "最新款 iPhone",
        "category": "Electronics > Mobile",
        "price": 44900,
        "images": [
          "https://cdn.shopee.tw/product-001-01.jpg",
          "https://cdn.shopee.tw/product-001-02.jpg"
        ],
        "variants": [
          {
            "variant": "256GB",
            "sku": "SH-SKU-001-256",
            "price": 44900,
            "inventory": 50
          },
          {
            "variant": "512GB",
            "sku": "SH-SKU-001-512",
            "price": 49900,
            "inventory": 30
          }
        ],
        "attributes": {
          "brand": "Apple",
          "color": "Black",
          "warranty": "12 months"
        }
      }
    ],
    "totalProducts": 1,
    "syncMetadata": {
      "source": "api",
      "lastModified": "2026-02-13T10:00:00Z"
    }
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

## Business Topics (業務主題 - 6個)

### order.process - 訂單處理（Source of Truth）
保留時間：1d（穩定後考慮降至 2h）

**TaskType: NEW_ORDER** - 新訂單（Channel Job 抓取的訂單列表）

```json
{
  "header": {
    "taskType": "NEW_ORDER",
    "merchantId": "M001",
    "channelId": "MOMO_001",
    "requestId": "req-20260213-300000",
    "timestamp": "2026-02-13T10:30:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "req-20260213-100000"
  },
  "body": {
    "orderId": "ORD-SYS-20260213-001",
    "channelOrderId": "MOMO-2026021300001",
    "orderData": {
      "orderStatus": "PENDING",
      "orderDate": "2026-02-13T09:30:00Z",
      "customer": {
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
        "address": "南京東路三段100號"
      },
      "items": [
        {
          "productId": "SKU001",
          "productName": "iPhone 15 Pro Max",
          "channelSkuId": "MOMO-SKU-001",
          "quantity": 1,
          "unitPrice": 44900,
          "discount": 1000,
          "subtotal": 43900
        }
      ],
      "payment": {
        "method": "CREDIT_CARD",
        "status": "PAID",
        "paidAmount": 43900
      },
      "shipping": {
        "method": "HOME_DELIVERY",
        "carrier": "BLACK_CAT",
        "shippingFee": 0
      },
      "totals": {
        "subtotal": 44900,
        "discount": 1000,
        "shippingFee": 0,
        "tax": 2090,
        "grandTotal": 43900
      }
    }
  }
}
```

**TaskType: UPDATE_ORDER** - 訂單狀態更新（Channel Job 抓取的詳情）

```json
{
  "header": {
    "taskType": "UPDATE_ORDER",
    "merchantId": "M001",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-300001",
    "timestamp": "2026-02-13T14:00:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "req-20260213-200000"
  },
  "body": {
    "orderId": "ORD-SYS-20260213-002",
    "channelOrderId": "SH202602130456",
    "orderData": {
      "orderStatus": "SHIPPED",
      "shipmentInfo": {
        "shippedTime": "2026-02-13T14:00:00Z",
        "trackingNumber": "SE123456789",
        "carrier": "7-ELEVEN",
        "storeId": "131415"
      },
      "items": [],
      "totals": {}
    }
  }
}
```

---

### return.process - 退貨處理（Source of Truth）
保留時間：1d

**TaskType: NEW_RETURN** - 新退貨

```json
{
  "header": {
    "taskType": "NEW_RETURN",
    "merchantId": "M001",
    "channelId": "YAHOO_001",
    "requestId": "req-20260213-310000",
    "timestamp": "2026-02-13T11:30:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "req-20260213-200002"
  },
  "body": {
    "returnId": "RET-SYS-20260213-001",
    "channelReturnId": "YH-RET-2026021300001",
    "orderId": "ORD-SYS-20260213-XXX",
    "returnData": {
      "returnStatus": "PENDING_APPROVAL",
      "reason": "SIZE_MISMATCH",
      "requestedAmount": 5000,
      "requestDate": "2026-02-13T11:00:00Z",
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

### product.sync - 商品同步結果
保留時間：1d

**TaskType: PRODUCT_SYNCED** - 商品同步結果（Handler 處理結果寫回）

```json
{
  "header": {
    "taskType": "PRODUCT_SYNCED",
    "merchantId": "M001",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-320000",
    "timestamp": "2026-02-13T11:00:00Z",
    "source": "product_sync_handler",
    "version": 1,
    "correlationId": "req-20260213-200001"
  },
  "body": {
    "syncStatus": "SUCCESS",
    "totalProducts": 100,
    "successCount": 98,
    "failureCount": 2,
    "syncTime": "2026-02-13T11:00:00Z",
    "failures": [
      {
        "productId": "SKU-INVALID",
        "reason": "Missing required field: description"
      }
    ]
  }
}
```

---

### inventory.update - 庫存更新事件
保留時間：1d

**TaskType: INVENTORY_UPDATED** - 庫存更新完成

```json
{
  "header": {
    "taskType": "INVENTORY_UPDATED",
    "merchantId": "M001",
    "channelId": "PCHOME_001",
    "requestId": "req-20260213-330000",
    "timestamp": "2026-02-13T10:30:00Z",
    "source": "inventory_update_handler",
    "version": 1,
    "correlationId": "req-20260213-100003"
  },
  "body": {
    "updateStatus": "SUCCESS",
    "updates": [
      {
        "productId": "SKU-001",
        "previousQuantity": 100,
        "newQuantity": 50,
        "difference": -50
      }
    ],
    "updateTime": "2026-02-13T10:30:00Z"
  }
}
```

---

### task.failed - 失敗任務
保留時間：1d

```json
{
  "header": {
    "taskType": "FAILED_TASK",
    "merchantId": "M001",
    "channelId": "MOMO_001",
    "requestId": "req-20260213-400000",
    "timestamp": "2026-02-13T10:35:00Z",
    "source": "error_handler",
    "version": 1
  },
  "body": {
    "originalTopic": "momo.fast",
    "originalTaskType": "FETCH_ORDERS",
    "originalRequestId": "req-20260213-100000",
    "failureInfo": {
      "errorCode": "API_TIMEOUT",
      "errorMessage": "MOMO API request timeout after 30s",
      "retryCount": 3,
      "maxRetries": 3,
      "isRetryable": false,
      "failedAt": "2026-02-13T10:35:00Z"
    }
  }
}
```

---

### task.dlt - 死信隊列
保留時間：30d

```json
{
  "header": {
    "taskType": "DLT_MESSAGE",
    "merchantId": "UNKNOWN",
    "requestId": "dlt-20260213-500000",
    "timestamp": "2026-02-13T10:40:00Z",
    "source": "kafka_handler",
    "version": 1
  },
  "body": {
    "originalTopic": "shopee.slow",
    "originalMessage": "{corrupted JSON...}",
    "dltReason": "DESERIALIZATION_ERROR",
    "errorMessage": "Cannot deserialize message",
    "processingAttempts": 5,
    "kafkaMetadata": {
      "partition": 2,
      "offset": 54321,
      "timestamp": 1707820500000
    }
  }
}
```

---

## Redis 去重策略

見 `REDIS_DEDUPLICATION.md`

- **Key 格式**: `order:hash:{merchantId}:{channelId}:{orderId}`
- **特殊字元保留**: 訂單號中的 `#`, `-`, `@` 等字元必須完整保留
- **例子**:
  - `order:hash:M001:SHOPIFY_001:1002#100` (不同於 `order:hash:M001:SHOPIFY_001:1002100`)
  - `order:hash:M001:CYBERBIZ_002:ORD@2024-001`

---

## 冪等性保證

### TaskType 路由
| TaskType | Handler | 來源 Topic | 目標 Topic | 說明 |
|----------|---------|-----------|-----------|------|
| FETCH_ORDERS | FetchOrdersHandler | {platform}.fast | order.process | 通路訂單列表 |
| FETCH_ORDER_DETAIL | FetchOrderDetailHandler | {platform}.slow | order.process | 訂單詳情 |
| SHIP_ORDER | ShipOrderHandler | {platform}.fast | order.process | 出貨指令 |
| UPDATE_PRICE | UpdatePriceHandler | {platform}.fast | product.sync | 價格更新 |
| UPDATE_INVENTORY | UpdateInventoryHandler | {platform}.fast | inventory.update | 庫存更新 |
| SYNC_PRODUCT | SyncProductHandler | {platform}.slow | product.sync | 商品詳情 |
| FETCH_RETURNS | FetchReturnsHandler | {platform}.slow | return.process | 退貨列表 |
| FETCH_RETURN_DETAIL | FetchReturnDetailHandler | {platform}.slow | return.process | 退貨詳情 |
| APPROVE_RETURN | ApproveReturnHandler | {platform}.fast | return.process | 同意退貨 |
| NEW_ORDER | NewOrderHandler | order.process | - | 新訂單入庫 |
| UPDATE_ORDER | UpdateOrderHandler | order.process | - | 訂單狀態更新 |
| NEW_RETURN | NewReturnHandler | return.process | - | 新退貨入庫 |
| PRODUCT_SYNCED | ProductSyncedHandler | product.sync | - | 同步完成 |
| INVENTORY_UPDATED | InventoryUpdatedHandler | inventory.update | - | 庫存完成 |
| FAILED_TASK | FailedTaskHandler | task.failed | - | 失敗紀錄 |
| DLT_MESSAGE | DltHandler | task.dlt | - | 死信處理 |

---

## 文件交叉參考

- **CORE_CONTRACTS.md**: 核心契約和 TaskType 定義
- **REDIS_DEDUPLICATION.md**: Redis 去重和特殊字元處理
- **DATA_FLOW_MAPPING.md**: 通路資料流轉
- **HANDLER_REGISTRY.md**: Handler 實現對應
- **CHANNEL_IMPLEMENTATION_GUIDE.md**: Channel Job 實作指南