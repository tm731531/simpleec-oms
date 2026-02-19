# SimpleEC OMS 資料流對應表

## 1. 訂單資料流 (Order Flow)

### 1.1 完整流程圖
```
Scheduler                    Channel Job                 Order Job              Database
    │                             │                           │                      │
    ├──[FETCH_ORDERS]────────────>│                           │                      │
    │  {timeRange}                │                           │                      │
    │                             │                           │                      │
    │                        ┌────┴────┐                      │                      │
    │                        │呼叫 API │                      │                      │
    │                        │分頁處理 │                      │                      │
    │                        │判斷邏輯 │                      │                      │
    │                        └────┬────┘                      │                      │
    │                             │                           │                      │
    │                             ├──[NEW_ORDER]─────────────>│                      │
    │                             │  {orders[]}               │                      │
    │                             │  {needsDetail}            │                      │
    │                             │                           ├──────[SAVE]────────>│
    │                             │                           │                      │
    │                             ├──[FETCH_ORDER_DETAIL]────>│                      │
    │                             │  {orderIds[]}             │                      │
    │                             │                           │                      │
    │                        ┌────┴────┐                      │                      │
    │                        │呼叫詳情 │                      │                      │
    │                        └────┬────┘                      │                      │
    │                             │                           │                      │
    │                             ├──[UPDATE_ORDER]──────────>│                      │
    │                             │  {fullOrderData}          │                      │
    │                             │                           ├──────[UPDATE]──────>│
```

### 1.2 資料轉換對應

#### Shopee API → NEW_ORDER
| Shopee API 欄位 | 訊息 Body 欄位 | Order Entity 欄位 | 說明 |
|----------------|---------------|------------------|------|
| order_sn | orderId | channel_order_id | 通路訂單號 |
| order_status | metadata.status | - | Channel 判斷用 |
| create_time | orderData.createTime | - | 原始資料 |
| update_time | orderData.updateTime | - | 原始資料 |
| total_amount | orderData.totalAmount | - | 原始資料 |

#### UPDATE_ORDER 完整資料
| API 欄位 | 訊息 Body 欄位 | Order Entity 欄位 | 說明 |
|---------|---------------|------------------|------|
| order_sn | orderId | channel_order_id | 訂單識別 |
| item_list | fullOrderData.items | - | 商品明細 |
| recipient_address | fullOrderData.shipping | shipping_address | 收件地址 |
| total_amount | fullOrderData.payment.total | total_amount | 訂單金額 |
| order_status | fullOrderData.status | order_status | 訂單狀態 |

### 1.3 Channel 判斷邏輯

```javascript
// Shopee Channel Job 內部邏輯
function processOrderList(orders) {
  for (order of orders) {
    // 判斷是否需要詳情
    if (shouldFetchDetail(order)) {
      sendMessage('FETCH_ORDER_DETAIL', {
        orderId: order.order_sn,
        metadata: {
          status: order.order_status,
          hasReturn: order.return_status > 0
        }
      });
    }

    // 發送基本資料
    sendMessage('NEW_ORDER', {
      orderId: order.order_sn,
      orderData: order,  // 原始 API 資料
      needsDetail: shouldFetchDetail(order)
    });
  }
}

function shouldFetchDetail(order) {
  // Shopee 特定判斷
  return order.order_status === 'READY_TO_SHIP' ||
         order.order_status === 'PROCESSED' ||
         order.total_amount > 10000 ||
         order.return_status > 0;
}
```

## 2. 退貨資料流 (Return Flow)

### 2.1 流程圖
```
Channel Job                     Return Job                  Database
    │                               │                           │
    ├──[FETCH_RETURNS]─────────────>│                           │
    │                               │                           │
    ├──[NEW_RETURN]────────────────>│                           │
    │  {returnId, orderData}       │                           │
    │                               ├──────[SAVE]──────────────>│
    │                               │                           │
    ├──[FETCH_RETURN_DETAIL]──────>│                           │
    │                               │                           │
    ├──[UPDATE_RETURN]─────────────>│                           │
    │  {fullReturnData}            │                           │
    │                               ├──────[UPDATE]────────────>│
```

### 2.2 退貨判斷邏輯

| 通路 | 判斷條件 | API 端點 |
|------|---------|----------|
| Shopee | return_status > 0 | /api/v2/returns/get_return_list |
| Momo | order_type = 'RETURN' | 包含在訂單 API |
| Yahoo | 獨立退貨系統 | /returns/list |

## 3. 出貨資料流 (Shipping Flow)

### 3.1 Shopee 出貨流程（需要預處理）
```
Order Job              Channel Job              Shopee API
    │                      │                        │
    ├──[PREPARE_SHIPMENT]─>│                        │
    │  {orderId}           │                        │
    │                      ├──[get_shipping_param]─>│
    │                      │<──────[parameters]─────│
    │                      │                        │
    │<──[READY_TO_SHIP]────│                        │
    │   {parameters}       │                        │
    │                      │                        │
    ├──[SHIP_ORDER]───────>│                        │
    │  {orderId, params}   │                        │
    │                      ├──[ship_order]────────>│
    │                      │<──────[success]────────│
    │<──[SHIPPED]──────────│                        │
```

### 3.2 參數對應表

| Shopee Parameter | 訊息欄位 | 用途 |
|-----------------|----------|------|
| pickup.address_id | shippingParams.pickupAddressId | 取件地址 |
| pickup.time_slot_id | shippingParams.pickupTimeId | 取件時段 |
| dropoff.branch_id | shippingParams.dropoffBranchId | 送達門市 |
| tracking_no | shippingParams.trackingNumber | 物流單號 |

## 4. 商品同步資料流

### 4.1 商品差異同步
```
Scheduler → {platform}.slow (SYNC_PRODUCT)
    │
Channel Job
    ├─→ 抓取商品列表
    ├─→ 比對差異（Redis）
    └─→ product.sync (PRODUCT_UPDATED)
         │
    Backend Job
         └─→ 更新資料庫
```

### 4.2 庫存更新流程
```
Frontend → API → {platform}.fast (UPDATE_INVENTORY)
                       │
                  Channel Job
                       ├─→ 呼叫通路 API
                       └─→ inventory.update (INVENTORY_CHANGED)
                                │
                           Backend Job
                                └─→ 更新庫存表
```

## 5. 狀態映射表

### 5.1 訂單狀態映射
| 內部狀態 | Shopee | Momo | Yahoo | PChome |
|---------|--------|------|-------|---------|
| PENDING_PAYMENT | UNPAID | WAIT_PAY | PENDING | NEW |
| READY_TO_SHIP | READY_TO_SHIP | CONFIRMED | READY | CONFIRMED |
| SHIPPED | SHIPPED | DELIVERED | SHIPPED | SHIPPING |
| COMPLETED | COMPLETED | FINISHED | DONE | COMPLETED |
| CANCELLED | CANCELLED | CANCELED | CANCEL | VOID |

### 5.2 退貨狀態映射
| 內部狀態 | Shopee | Momo | Yahoo |
|---------|--------|------|-------|
| RETURN_REQUESTED | REQUESTED | APPLY | PENDING |
| RETURN_APPROVED | APPROVED | AGREED | APPROVED |
| RETURN_REJECTED | REJECTED | REFUSED | REJECTED |
| RETURN_COMPLETED | COMPLETED | DONE | FINISHED |

## 6. 資料完整性檢查

### 6.1 必要欄位檢查
```javascript
// Order Job 驗證
function validateNewOrder(order) {
  required = ['orderId', 'merchantId', 'channelId'];
  for (field of required) {
    if (!order[field]) {
      throw new ValidationError(`Missing ${field}`);
    }
  }
}
```

### 6.2 資料一致性
- 使用 correlationId 關聯 list 和 detail
- 使用 version 確保訊息相容性
- 使用 requestId 保證冪等性

## 7. 效能考量

### 7.1 批次處理建議
| 操作 | 建議批次大小 | 原因 |
|------|------------|------|
| FETCH_ORDERS | 50 | Shopee API 限制 |
| FETCH_ORDER_DETAIL | 10 | 避免超時 |
| UPDATE_INVENTORY | 20 | 平衡效能 |
| SHIP_ORDER | 1 | 需要即時回饋 |

### 7.2 並發控制
```yaml
channel-job:
  concurrency: 4  # 每個 partition

order-job:
  concurrency: 8  # 處理能力更強

backend-job:
  concurrency: 4  # 資料庫寫入