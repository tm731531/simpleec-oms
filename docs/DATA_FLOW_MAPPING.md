# SimpleEC OMS 資料流對應表

## 1. 訂單資料流 (Order Flow)

### 1.1 完整流程圖（含 Hash 去重）
```
Scheduler                    Channel Job                     Order Job                  Redis       Database
    │                             │                              │                        │              │
    ├──[FETCH_ORDERS]────────────>│                              │                        │              │
    │  {timeRange}                │                              │                        │              │
    │                             │                              │                        │              │
    │                        ┌────┴────┐                         │                        │              │
    │                        │呼叫列表 API                       │                        │              │
    │                        │判斷是否需要詳情  │                │                        │              │
    │                        │（同步呼叫詳情）  │               │                        │              │
    │                        │計算 hash       │                 │                        │              │
    │                        └────┬────┘                         │                        │              │
    │                             │                              │                        │              │
    │                             ├─────────────────────────────────────[讀 hash]────────>│              │
    │                             │                                    (第一層：        │              │
    │                             │                                    判斷是否跳過)      │              │
    │                             │                              │                        │              │
    │                             ├──[ORDER_UPSERT]────────────>│ [檢查 hash]          │              │
    │                             │  {orderData}                │ (第二層：             │              │
    │                             │  {orderHash}        ┌──────>│ 並發安全)            │              │
    │                             │                     │       │                        │              │
    │                             │                     └──────────[寫入 hash]         │              │
    │                             │                              │   (TTL 7天)          │              │
    │                             │                              ├──────[SAVE/UPDATE]──>│              │
    │                             │                              │                        │              │
```

### 1.2 資料轉換對應

#### Shopee API → ORDER_UPSERT（基本列表資料）
| Shopee API 欄位 | 訊息 Body 欄位 | Order Entity 欄位 | 說明 |
|----------------|---------------|------------------|------|
| order_sn | orderId | channel_order_id | 通路訂單號 |
| order_status | metadata.status | - | Channel 判斷用 |
| create_time | orderData.createTime | - | 原始資料 |
| update_time | orderData.updateTime | - | 原始資料 |
| total_amount | orderData.totalAmount | - | 原始資料 |
| **(內存計算)** | **orderHash** | **（不存儲）** | **SHA-256，傳遞給 order.process** |

#### ORDER_UPSERT（完整詳情資料）
| API 欄位 | 訊息 Body 欄位 | Order Entity 欄位 | 說明 |
|---------|---------------|------------------|------|
| order_sn | orderId | channel_order_id | 訂單識別 |
| item_list | fullOrderData.items | - | 商品明細 |
| recipient_address | fullOrderData.shipping | shipping_address | 收件地址 |
| total_amount | fullOrderData.payment.total | total_amount | 訂單金額 |
| order_status | fullOrderData.status | order_status | 訂單狀態 |
| **(內存計算)** | **orderHash** | **（不存儲）** | **SHA-256，用於去重驗證** |

**⚠️ 重要：orderHash 只在記憶體和 Redis 中**
- ✓ Channel Job 內存計算
- ✓ Kafka 訊息傳遞
- ✓ Redis 存儲（value，鍵為 order:hash:...）
- ✓ OrderUpsertHandler 內存重新計算驗證
- ✗ **不存儲在 Order Entity 或資料庫表**

### 1.3 Channel 判斷邏輯（含 Hash 計算和去重）

```javascript
// Shopee Channel Job 內部邏輯
function processOrderList(orders) {
  for (order of orders) {
    // *** 第一步：判斷是否需要詳情，若需要則同步呼叫詳情 API ***
    let fullOrderData = order;  // 預設用列表資料

    if (shouldFetchDetail(order)) {
      try {
        // ⭐ 重要：同步呼叫 API 取得完整詳情（不是非同步訊息）
        fullOrderData = fetchOrderDetailSync(order.order_sn);
      } catch (error) {
        log.warn(`Failed to fetch detail for ${order.order_sn}, using list data`, error);
        // 降級處理：用列表資料繼續
        fullOrderData = order;
      }
    }

    // *** 第二步：計算訂單內容 Hash（必須在完整資料基礎上）***
    const orderHash = calculateOrderHash({
      orderStatus: fullOrderData.order_status,
      totalAmount: fullOrderData.total_amount,
      shippingStatus: fullOrderData.shipping_status,
      paymentStatus: fullOrderData.payment_status,
      items: fullOrderData.item_list,        // 完整的商品清單（可能來自詳情 API）
      buyerInfo: fullOrderData.buyer_info,   // 完整的買家資訊
      shippingInfo: fullOrderData.shipping_info  // 完整的物流資訊
    });

    // *** 第三步：檢查 Redis 中的舊 Hash（避免重複處理）***
    const redisKey = `order:hash:${merchantId}:${channelId}:${order.order_sn}`;
    const existingHash = redis.get(redisKey);

    if (existingHash === orderHash) {
      // Hash 相同，訂單內容未變化，跳過
      log.debug(`Order unchanged (hash match): ${order.order_sn}`);
      continue;
    }

    // *** 第四步：發送 ORDER_UPSERT 訊息（帶完整資料和 Hash）***
    sendMessage('ORDER_UPSERT', {
      orderId: order.order_sn,
      orderData: fullOrderData,  // ⭐ 必須是完整資料
      orderHash: orderHash       // ⭐ 根據完整資料計算的 Hash
    });
  }
}

// 同步呼叫通路 API 取得訂單詳情
function fetchOrderDetailSync(orderId) {
  // 直接呼叫 Shopee API，非同步訊息
  const response = await shopeeApi.get(`/order/${orderId}/detail`);
  return response.data;
}

function shouldFetchDetail(order) {
  // Shopee 特定判斷：判斷是否需要完整資料
  // 列表 API 不含商品明細、買家資訊、物流詳情，這些在詳情 API 才有
  return order.order_status === 'READY_TO_SHIP' ||   // 出貨需要完整物流資訊
         order.order_status === 'PROCESSED' ||
         order.return_status > 0 ||                    // 有退貨需要詳細項目資訊
         !order.item_list;                            // 列表無商品資訊時必須取詳情
}

// ⭐ Hash 計算（須使用 TreeMap 確保欄位排序一致）
function calculateOrderHash(orderData) {
  const sortedData = {
    orderStatus: orderData.orderStatus,
    totalAmount: orderData.totalAmount,
    shippingStatus: orderData.shippingStatus,
    paymentStatus: orderData.paymentStatus,
    items: orderData.items,
    buyerInfo: orderData.buyerInfo,
    shippingInfo: orderData.shippingInfo
  };
  const json = JSON.stringify(sortedData);
  return sha256(json);  // 使用 SHA-256
}
```

**⚠️ 重要：Channel Job 計算 Hash 的前提是完整資料**
- 必須先判斷是否需要詳情，需要則同步呼叫詳情 API 獲得完整資料
- 基於完整資料（items, shippingInfo 等）計算 Hash
- 檢查 Redis 中是否存在相同 Hash，相同則跳過（資源優化）
- 不同 Hash 或 Redis 無紀錄時才發送 ORDER_UPSERT 訊息

**去重的兩層架構**
- **第一層（Channel Job）**：讀 Redis，判斷是否跳過，避免發送重複訊息（資源優化）
- **第二層（order.process OrderUpsertHandler）**：再次檢查 Redis + 資料庫，確保並發安全，避免並發重複寫入

## 2. 退貨資料流 (Return Flow)

### 2.1 流程圖（含 Hash 去重）
```
Scheduler                    Channel Job                     Return Job                 Redis        Database
    │                             │                              │                        │              │
    ├──[FETCH_RETURNS]───────────>│                              │                        │              │
    │                             │                              │                        │              │
    │                        ┌────┴────┐                         │                        │              │
    │                        │呼叫列表 API                       │                        │              │
    │                        │判斷是否需要詳情  │                │                        │              │
    │                        │（同步呼叫詳情）  │               │                        │              │
    │                        │計算 hash       │                 │                        │              │
    │                        └────┬────┘                         │                        │              │
    │                             │                              │                        │              │
    │                             ├─────────────────────────────────────[讀 hash]────────>│              │
    │                             │                                    (第一層：        │              │
    │                             │                                    判斷是否跳過)      │              │
    │                             │                              │                        │              │
    │                             ├──[RETURN_UPSERT]───────────>│ [檢查 hash]          │              │
    │                             │  {returnData}               │ (第二層：             │              │
    │                             │  {returnHash}       ┌──────>│ 並發安全)            │              │
    │                             │                     │       │                        │              │
    │                             │                     └──────────[寫入 hash]         │              │
    │                             │                              │   (TTL 7天)          │              │
    │                             │                              ├──────[SAVE/UPDATE]──>│              │
```

### 2.2 退貨判斷邏輯（Channel Job 內含 Hash 計算和去重）

```javascript
// Shopee Channel Job 內部邏輯（退貨）
function processReturnList(returns) {
  for (ret of returns) {
    // *** 第一步：判斷是否需要詳情，若需要則同步呼叫詳情 API ***
    let fullReturnData = ret;  // 預設用列表資料

    if (shouldFetchReturnDetail(ret)) {
      try {
        // ⭐ 重要：同步呼叫 API 取得完整詳情（不是非同步訊息）
        fullReturnData = fetchReturnDetailSync(ret.return_id);
      } catch (error) {
        log.warn(`Failed to fetch detail for return ${ret.return_id}, using list data`, error);
        fullReturnData = ret;
      }
    }

    // *** 第二步：計算退貨內容 Hash（必須在完整資料基礎上）***
    const returnHash = calculateReturnHash({
      returnStatus: fullReturnData.return_status,
      reason: fullReturnData.reason,
      items: fullReturnData.items,        // 完整的退貨項目清單
      refundAmount: fullReturnData.refund_amount
    });

    // *** 第三步：檢查 Redis 中的舊 Hash（避免重複處理）***
    const redisKey = `return:hash:${merchantId}:${channelId}:${ret.return_id}`;
    const existingHash = redis.get(redisKey);

    if (existingHash === returnHash) {
      // Hash 相同，退貨內容未變化，跳過
      log.debug(`Return unchanged (hash match): ${ret.return_id}`);
      continue;
    }

    // *** 第四步：發送 RETURN_UPSERT 訊息（帶完整資料和 Hash）***
    sendMessage('RETURN_UPSERT', {
      returnId: ret.return_id,
      returnData: fullReturnData,  // ⭐ 必須是完整資料
      returnHash: returnHash       // ⭐ 根據完整資料計算的 Hash
    });
  }
}

function shouldFetchReturnDetail(ret) {
  // 判斷是否需要完整資料
  return ret.return_status === 'PROCESSING' ||     // 處理中需要詳細項目
         ret.return_status === 'APPROVED' ||       // 已批准需要詳細項目
         !ret.items;                               // 列表無項目資訊時必須取詳情
}

function fetchReturnDetailSync(returnId) {
  // 直接呼叫 Shopee API，非同步訊息
  const response = await shopeeApi.get(`/return/${returnId}/detail`);
  return response.data;
}
```

**平台特定判斷**

| 通路 | 判斷條件 | Hash 欄位 | API 端點 |
|------|---------|----------|----------|
| Shopee | return_status ∈ [PROCESSING, APPROVED] 或 !items | returnStatus, reason, items, refundAmount | /api/v2/returns/{returnId}/detail |
| Momo | 退貨記錄存在時 | returnStatus, reason, items, refundAmount | 包含在訂單 API |
| Yahoo | 獨立退貨系統 | returnStatus, reason, items, refundAmount | /returns/{returnId}/detail |

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

## 4. Hash 去重流程（核心機制）

⭐ **這是整個 Order / Return 處理的關鍵去重層，確保不重複處理同樣的資料**

### 4.1 兩層去重架構

```
Layer 1: Channel Job (只讀 Redis)
  ├─ 計算訂單內容 Hash
  ├─ 查詢 Redis 中的舊 Hash
  └─ 若 Hash 相同 → 跳過此訂單

Layer 2: order.process 的 OrderUpsertHandler (讀寫 Redis + DB)
  ├─ 再次檢查 Redis Hash（避免並發）
  ├─ 檢查資料庫（判斷新建或更新）
  ├─ 只在 Hash 不同時才執行 update
  └─ 更新 Redis Hash（TTL 7 天）
```

### 4.2 Hash 計算規則

#### Order Hash（根據 REDIS_DEDUPLICATION.md）
```
sha256(json({
  orderStatus: "READY_TO_SHIP",
  totalAmount: 1500.00,
  shippingStatus: "PENDING",
  paymentStatus: "PAID",
  items: [...],
  buyerInfo: {...},
  shippingInfo: {...}
}))
```

**欄位必須按字母順序排列（TreeMap）以保證一致性**

#### Return Hash
```
sha256(json({
  returnStatus: "REQUESTED",
  refundAmount: 1500.00,
  reason: "Product defect",
  items: [...]
}))
```

### 4.3 Redis Key 命名

```
訂單:  order:hash:{merchantId}:{channelId}:{channelOrderId}
       value: SHA-256 hash（不是 Order Entity 的欄位）

退貨:  return:hash:{merchantId}:{channelId}:{channelReturnId}
       value: SHA-256 hash（不是 Return Entity 的欄位）

範例：
order:hash:M001:SHOPEE_001:20240210#1234  →  "a1b2c3d4e5..."
return:hash:M001:SHOPEE_001:R20240210#5678 →  "f6g7h8i9j0..."
```

**⚠️ Hash 只在 Redis value 中，不在 DB 表**
- Redis: 存儲 hash 值用於快速查詢和去重
- Database: 訂單/退貨實體不含 hash 欄位，只含業務資料

### 4.4 去重流程示例

```
第1次拉單：
  Channel Job → 計算 hash A → Redis 無此訂單 → 發送 ORDER_UPSERT + hashA
  order.process → 檢查 Redis（無）→ 存入 DB → 存入 Redis（hashA，TTL 7天）

第2次拉單（訂單未改變）：
  Channel Job → 計算 hash A → Redis 有 hashA → 比對相同 → 跳過 ✓
  （節省不必要的消息和處理）

第2次拉單（訂單改變了）：
  Channel Job → 計算 hash B（不同） → Redis 有 hashA（不同） → 發送 ORDER_UPSERT + hashB
  order.process → 檢查 Redis hashA ≠ hashB → DB 檢查（已存在）
               → 比對 hashB ≠ DB.hash → 執行 UPDATE
               → 更新 Redis（hashB）✓
```

---

## 5. 商品同步資料流

### 5.1 商品差異同步
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

### 5.2 庫存更新流程
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

## 6. 狀態映射表

### 6.1 訂單狀態映射
| 內部狀態 | Shopee | Momo | Yahoo | PChome |
|---------|--------|------|-------|---------|
| PENDING_PAYMENT | UNPAID | WAIT_PAY | PENDING | NEW |
| READY_TO_SHIP | READY_TO_SHIP | CONFIRMED | READY | CONFIRMED |
| SHIPPED | SHIPPED | DELIVERED | SHIPPED | SHIPPING |
| COMPLETED | COMPLETED | FINISHED | DONE | COMPLETED |
| CANCELLED | CANCELLED | CANCELED | CANCEL | VOID |

### 6.2 退貨狀態映射
| 內部狀態 | Shopee | Momo | Yahoo |
|---------|--------|------|-------|
| RETURN_REQUESTED | REQUESTED | APPLY | PENDING |
| RETURN_APPROVED | APPROVED | AGREED | APPROVED |
| RETURN_REJECTED | REJECTED | REFUSED | REJECTED |
| RETURN_COMPLETED | COMPLETED | DONE | FINISHED |

## 7. 資料完整性檢查

### 7.1 必要欄位檢查
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

### 7.2 資料一致性
- 使用 correlationId 關聯 list 和 detail
- 使用 version 確保訊息相容性
- 使用 requestId 保證冪等性

## 8. 效能考量

### 8.1 批次處理建議
| 操作 | 建議批次大小 | 原因 |
|------|------------|------|
| FETCH_ORDERS | 50 | Shopee API 限制 |
| FETCH_ORDER_DETAIL | 10 | 避免超時 |
| UPDATE_INVENTORY | 20 | 平衡效能 |
| SHIP_ORDER | 1 | 需要即時回饋 |

### 8.2 並發控制
```yaml
channel-job:
  concurrency: 4  # 每個 partition

order-job:
  concurrency: 8  # 處理能力更強

backend-job:
  concurrency: 4  # 資料庫寫入