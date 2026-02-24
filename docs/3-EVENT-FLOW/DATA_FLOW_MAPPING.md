# SimpleEC OMS 資料流對應表

## 0. 通路處理模式速查表

每個通路有自己的訂單列表資料完整度，決定了處理模式：

| 通路 | 模式 | 描述 | 特徵 |
|------|------|------|------|
| **Shopee** | **Mode B** | 列表 → 詳情 → 組織 | 列表 API 缺商品/物流詳情，必須逐單打詳情 API |
| **Shopify** | **Mode A** | 直接組織 | 列表 API 已含完整商品/客戶資訊 |
| **Momo** | ? | ? | 待確認 |
| **Yahoo** | ? | ? | 待確認 |
| **PChome** | ? | ? | 待確認 |
| **easystore** | ? | ? | 待確認 |
| **Cyberbiz** | ? | ? | 待確認 |

---

## 1. 訂單資料流 (Order Flow)

### 1.1 Mode B：列表 + 詳情模式（Shopee 等）

**特徵：列表 API 缺少商品清單、物流詳情等，必須逐單再打詳情 API**

```
Scheduler                    Channel Job                     Order Job                  Redis       Database
    │                             │                              │                        │              │
    ├──[FETCH_ORDERS]────────────>│ (slow queue)                │                        │              │
    │  {timeRange}                │                              │                        │              │
    │                             ├────────────────────────────────────────────────┐      │              │
    │                             │ [抓訂單列表]                 │                   │      │              │
    │                             │ (無商品/物流詳情)          │                   │      │              │
    │                             │                              │                   ▼      │              │
    │                             │ ┌──[FETCH_ORDER_DETAIL]──────────[slow queue]         │              │
    │                             │ │ (每張訂單號一個)         │                   │      │              │
    │                             │ │                            │                   │      │              │
    │                        ┌────┴─┴────┐                      │                   │      │              │
    │                        │呼叫詳情 API│                      │                   │      │              │
    │                        │組成 OMS   │                      │                   │      │              │
    │                        │計算 hash  │                      │                   │      │              │
    │                        └────┬────┘                        │                   │      │              │
    │                             │                              │                   │      │              │
    │                             ├─────────────────────────────────────[讀 hash]────────>│              │
    │                             │                                    (第一層)      │              │
    │                             │                              │                        │              │
    │                             ├──[ORDER_UPSERT]────────────>│ [檢查 hash]          │              │
    │                             │  {完整 orderData}           │ (第二層)            │              │
    │                             │  {orderHash}        ┌──────>│                        │              │
    │                             │                     └──────────[寫入 hash]         │              │
    │                             │                              │                        │              │
    │                             │                              ├──────[SAVE/UPDATE]──>│              │
```

### 1.2 Mode A：直接模式（Shopify 等）

**特徵：列表 API 已含完整商品、客戶資訊，無需詳情 API**

```
Scheduler                    Channel Job                     Order Job                  Redis       Database
    │                             │                              │                        │              │
    ├──[FETCH_ORDERS]────────────>│ (無詳情隊列)                │                        │              │
    │  {timeRange}                │                              │                        │              │
    │                             │                              │                        │              │
    │                        ┌────┴────┐                         │                        │              │
    │                        │呼叫列表 API                       │                        │              │
    │                        │(已含完整資訊)                    │                        │              │
    │                        │組成 OMS                         │                        │              │
    │                        │計算 hash                        │                        │              │
    │                        └────┬────┘                         │                        │              │
    │                             │                              │                        │              │
    │                             ├─────────────────────────────────────[讀 hash]────────>│              │
    │                             │                                    (第一層)      │              │
    │                             │                              │                        │              │
    │                             ├──[ORDER_UPSERT]────────────>│ [檢查 hash]          │              │
    │                             │  {完整 orderData}           │ (第二層)            │              │
    │                             │  {orderHash}        ┌──────>│                        │              │
    │                             │                     └──────────[寫入 hash]         │              │
    │                             │                              │                        │              │
    │                             │                              ├──────[SAVE/UPDATE]──>│              │
```

### 1.3 資料轉換與 Hash 計算規則

**⚠️ 重要：orderHash 只在記憶體和 Redis 中**
- ✓ Channel Job 內存計算（基於完整資料）
- ✓ Kafka 訊息傳遞
- ✓ Redis 存儲（value，鍵為 order:hash:...）
- ✓ OrderUpsertHandler 內存重新計算驗證
- ✗ **不存儲在 Order Entity 或資料庫表**

#### ORDER_UPSERT 統一結構
| 欄位 | 來源 | 說明 |
|------|------|------|
| orderId | 訂單號 | 通路訂單號（Shopee order_sn / Shopify order id） |
| orderData | 完整訂單資料 | **必須包含 items、shippingInfo、buyerInfo** |
| orderHash | 內存計算 | SHA-256（不存儲） |

### 1.4 Mode B 實現（需要詳情）- Shopee 示例

```javascript
// ========== Mode B: Shopee Channel Job ==========
// 第一個 Handler：FETCH_ORDERS 訊息 (slow queue)
function processFetchOrders(message) {
  const { merchantId, channelId, timeRange } = message;

  // 呼叫 Shopee List API
  const orders = shopeeApi.listOrders(timeRange);

  for (order of orders) {
    // ⭐ Mode B 特點：列表無完整資訊，必須逐單抓詳情
    sendMessage('FETCH_ORDER_DETAIL', {
      merchantId,
      channelId,
      orderId: order.order_sn,
      correlationId: generateCorrelationId()  // 用於追蹤
    });
  }
}

// 第二個 Handler：FETCH_ORDER_DETAIL 訊息 (slow queue)
function processOrderDetail(message) {
  const { merchantId, channelId, orderId, correlationId } = message;

  try {
    // *** 第一步：呼叫詳情 API 取得完整訂單 ***
    const detailResponse = shopeeApi.getOrderDetail(orderId);
    const fullOrderData = detailResponse.data;

    // *** 第二步：組織成 OMS 結構 ***
    const omsOrder = {
      orderId: fullOrderData.order_sn,
      orderData: {
        status: fullOrderData.order_status,
        totalAmount: fullOrderData.total_amount,
        items: fullOrderData.item_list,           // ✓ 來自詳情 API
        shippingInfo: fullOrderData.shipping_info, // ✓ 來自詳情 API
        buyerInfo: fullOrderData.buyer_info,      // ✓ 來自詳情 API
        createTime: fullOrderData.create_time,
        updateTime: fullOrderData.update_time
      }
    };

    // *** 第三步：計算 Hash（基於完整資料）***
    const orderHash = calculateOrderHash(omsOrder.orderData);

    // *** 第四步：檢查 Redis（第一層去重）***
    const redisKey = `order:hash:${merchantId}:${channelId}:${orderId}`;
    const existingHash = redis.get(redisKey);

    if (existingHash === orderHash) {
      log.info(`Order unchanged: ${orderId}`, { correlationId });
      return;  // 跳過
    }

    // *** 第五步：發送 ORDER_UPSERT（帶完整資料）***
    sendMessage('ORDER_UPSERT', {
      merchantId,
      channelId,
      orderId: omsOrder.orderId,
      orderData: omsOrder.orderData,
      orderHash: orderHash,
      correlationId,
      timestamp: new Date().toISOString()
    });

    log.info(`Order sent to order.process: ${orderId}`, { correlationId, hash: orderHash });

  } catch (error) {
    log.error(`Failed to process order detail: ${orderId}`, error, { correlationId });
    // TODO: 重試邏輯或死信隊列
  }
}

// Hash 計算（TreeMap 確保排序）
function calculateOrderHash(orderData) {
  const sortedData = {
    buyerInfo: orderData.buyerInfo,
    items: orderData.items,
    shippingInfo: orderData.shippingInfo,
    status: orderData.status,
    totalAmount: orderData.totalAmount,
    updateTime: orderData.updateTime
  };
  const json = JSON.stringify(sortedData);
  return sha256(json);
}
```

**Mode B 特徵**
- ✓ 兩個 Handler：FETCH_ORDERS → FETCH_ORDER_DETAIL
- ✓ 列表 API 呼叫少，但詳情 API 呼叫多（=訂單數）
- ✓ 提高資料準確性（完整的 items、shippingInfo）
- ✓ 適合：Shopee（需要商品明細）

### 1.5 Mode A 實現（直接模式）- Shopify 示例

```javascript
// ========== Mode A: Shopify Channel Job ==========
// 單一 Handler：FETCH_ORDERS 訊息 (fast queue)
function processFetchOrders(message) {
  const { merchantId, channelId, timeRange } = message;

  try {
    // *** 第一步：呼叫列表 API ***
    // ⭐ Mode A 特點：列表 API 已含完整資訊（商品、客戶、地址）
    const orders = shopifyApi.listOrders(timeRange);

    // *** 第二步：逐單處理 ***
    for (order of orders) {
      // *** 直接組織成 OMS 結構（無需詳情 API）***
      const omsOrder = {
        orderId: order.id,
        orderData: {
          status: order.status,
          totalAmount: order.total_price,
          items: order.line_items,               // ✓ 列表 API 已有
          shippingInfo: order.shipping_address,  // ✓ 列表 API 已有
          buyerInfo: order.customer,             // ✓ 列表 API 已有
          createTime: order.created_at,
          updateTime: order.updated_at
        }
      };

      // *** 第三步：計算 Hash（基於完整資料）***
      const orderHash = calculateOrderHash(omsOrder.orderData);

      // *** 第四步：檢查 Redis（第一層去重）***
      const redisKey = `order:hash:${merchantId}:${channelId}:${order.id}`;
      const existingHash = redis.get(redisKey);

      if (existingHash === orderHash) {
        log.debug(`Order unchanged: ${order.id}`);
        continue;  // 跳過
      }

      // *** 第五步：發送 ORDER_UPSERT（帶完整資料）***
      sendMessage('ORDER_UPSERT', {
        merchantId,
        channelId,
        orderId: omsOrder.orderId,
        orderData: omsOrder.orderData,
        orderHash: orderHash,
        timestamp: new Date().toISOString()
      });

      log.info(`Order sent to order.process: ${order.id}`, { hash: orderHash });
    }

  } catch (error) {
    log.error('Failed to fetch orders', error);
    // TODO: 重試邏輯
  }
}

// Hash 計算（同 Mode B）
function calculateOrderHash(orderData) {
  const sortedData = {
    buyerInfo: orderData.buyerInfo,
    items: orderData.items,
    shippingInfo: orderData.shippingInfo,
    status: orderData.status,
    totalAmount: orderData.totalAmount,
    updateTime: orderData.updateTime
  };
  const json = JSON.stringify(sortedData);
  return sha256(json);
}
```

**Mode A 特徵**
- ✓ 單一 Handler：FETCH_ORDERS 直接發送 ORDER_UPSERT
- ✓ 無詳情 API 呼叫（=快速、低 API 配額消耗）
- ✓ 但依賴列表 API 數據完整度
- ✓ 適合：Shopify、Stripe、自有平台（API 設計完整）

---

## 2. 退貨資料流 (Return Flow)

### 2.1 Mode B：列表 + 詳情模式（Shopee 等）

同訂單流程，退貨流程也有兩個 Handler：

```
Scheduler                    Channel Job                     Return Job                 Redis        Database
    │                             │                              │                        │              │
    ├──[FETCH_RETURNS]───────────>│ (slow queue)                │                        │              │
    │                             │                              │                        │              │
    │                             ├─[FETCH_RETURN_DETAIL]───────────[slow queue]         │              │
    │                             │ (每筆退貨號一個)          │                        │              │
    │                             │                              │                        │              │
    │                        ┌────┴────┐                         │                        │              │
    │                        │呼叫詳情 API│                      │                        │              │
    │                        │組成 OMS  │                       │                        │              │
    │                        │計算 hash │                       │                        │              │
    │                        └────┬────┘                         │                        │              │
    │                             │                              │                        │              │
    │                             ├─────────────────────────────────────[讀 hash]────────>│              │
    │                             │                                    (第一層)      │              │
    │                             │                              │                        │              │
    │                             ├──[RETURN_UPSERT]───────────>│ [檢查 hash]          │              │
    │                             │  {完整 returnData}          │ (第二層)            │              │
    │                             │  {returnHash}       ┌──────>│                        │              │
    │                             │                     └──────────[寫入 hash]         │              │
    │                             │                              │                        │              │
    │                             │                              ├──────[SAVE/UPDATE]──>│              │
```

### 2.2 Mode A：直接模式（Shopify 等）

```
Scheduler                    Channel Job                     Return Job                 Redis        Database
    │                             │                              │                        │              │
    ├──[FETCH_RETURNS]───────────>│ (無詳情隊列)                │                        │              │
    │                             │                              │                        │              │
    │                        ┌────┴────┐                         │                        │              │
    │                        │呼叫列表 API                       │                        │              │
    │                        │(已含完整資訊)                    │                        │              │
    │                        │組成 OMS                         │                        │              │
    │                        │計算 hash                        │                        │              │
    │                        └────┬────┘                         │                        │              │
    │                             │                              │                        │              │
    │                             ├─────────────────────────────────────[讀 hash]────────>│              │
    │                             │                                    (第一層)      │              │
    │                             │                              │                        │              │
    │                             ├──[RETURN_UPSERT]───────────>│ [檢查 hash]          │              │
    │                             │  {完整 returnData}          │ (第二層)            │              │
    │                             │  {returnHash}       ┌──────>│                        │              │
    │                             │                     └──────────[寫入 hash]         │              │
    │                             │                              │                        │              │
    │                             │                              ├──────[SAVE/UPDATE]──>│              │
```

### 2.3 Mode B 實現（需要詳情）- Shopee 示例

```javascript
// ========== Mode B: Shopee Return Job ==========
// 第一個 Handler：FETCH_RETURNS 訊息 (slow queue)
function processFetchReturns(message) {
  const { merchantId, channelId, timeRange } = message;

  // 呼叫 Shopee Returns List API
  const returns = shopeeApi.listReturns(timeRange);

  for (ret of returns) {
    // ⭐ Mode B 特點：列表無完整項目清單，必須逐筆抓詳情
    sendMessage('FETCH_RETURN_DETAIL', {
      merchantId,
      channelId,
      returnId: ret.return_id,
      correlationId: generateCorrelationId()
    });
  }
}

// 第二個 Handler：FETCH_RETURN_DETAIL 訊息 (slow queue)
function processReturnDetail(message) {
  const { merchantId, channelId, returnId, correlationId } = message;

  try {
    // *** 第一步：呼叫詳情 API ***
    const detailResponse = shopeeApi.getReturnDetail(returnId);
    const fullReturnData = detailResponse.data;

    // *** 第二步：組織成 OMS 結構 ***
    const omsReturn = {
      returnId: fullReturnData.return_id,
      returnData: {
        status: fullReturnData.return_status,
        reason: fullReturnData.reason,
        items: fullReturnData.items,              // ✓ 來自詳情 API
        refundAmount: fullReturnData.refund_amount,
        createTime: fullReturnData.create_time,
        updateTime: fullReturnData.update_time
      }
    };

    // *** 第三步：計算 Hash ***
    const returnHash = calculateReturnHash(omsReturn.returnData);

    // *** 第四步：檢查 Redis（第一層去重）***
    const redisKey = `return:hash:${merchantId}:${channelId}:${returnId}`;
    const existingHash = redis.get(redisKey);

    if (existingHash === returnHash) {
      log.info(`Return unchanged: ${returnId}`, { correlationId });
      return;
    }

    // *** 第五步：發送 RETURN_UPSERT ***
    sendMessage('RETURN_UPSERT', {
      merchantId,
      channelId,
      returnId: omsReturn.returnId,
      returnData: omsReturn.returnData,
      returnHash: returnHash,
      correlationId,
      timestamp: new Date().toISOString()
    });

    log.info(`Return sent to return.process: ${returnId}`, { correlationId, hash: returnHash });

  } catch (error) {
    log.error(`Failed to process return detail: ${returnId}`, error, { correlationId });
  }
}

function calculateReturnHash(returnData) {
  const sortedData = {
    items: returnData.items,
    reason: returnData.reason,
    refundAmount: returnData.refundAmount,
    status: returnData.status,
    updateTime: returnData.updateTime
  };
  const json = JSON.stringify(sortedData);
  return sha256(json);
}
```

### 2.4 Mode A 實現（直接模式）- Shopify 示例

```javascript
// ========== Mode A: Shopify Return Job ==========
// 單一 Handler：FETCH_RETURNS 訊息 (fast queue)
function processFetchReturns(message) {
  const { merchantId, channelId, timeRange } = message;

  try {
    // *** 列表 API 已含完整退貨資訊 ***
    const returns = shopifyApi.listReturns(timeRange);

    for (ret of returns) {
      const omsReturn = {
        returnId: ret.id,
        returnData: {
          status: ret.status,
          reason: ret.reason,
          items: ret.line_items,              // ✓ 列表 API 已有
          refundAmount: ret.refund_amount,
          createTime: ret.created_at,
          updateTime: ret.updated_at
        }
      };

      const returnHash = calculateReturnHash(omsReturn.returnData);

      const redisKey = `return:hash:${merchantId}:${channelId}:${ret.id}`;
      const existingHash = redis.get(redisKey);

      if (existingHash === returnHash) {
        log.debug(`Return unchanged: ${ret.id}`);
        continue;
      }

      sendMessage('RETURN_UPSERT', {
        merchantId,
        channelId,
        returnId: omsReturn.returnId,
        returnData: omsReturn.returnData,
        returnHash: returnHash,
        timestamp: new Date().toISOString()
      });

      log.info(`Return sent to return.process: ${ret.id}`, { hash: returnHash });
    }

  } catch (error) {
    log.error('Failed to fetch returns', error);
  }
}

function calculateReturnHash(returnData) {
  const sortedData = {
    items: returnData.items,
    reason: returnData.reason,
    refundAmount: returnData.refundAmount,
    status: returnData.status,
    updateTime: returnData.updateTime
  };
  const json = JSON.stringify(sortedData);
  return sha256(json);
}
```

**平台模式對應**

| 通路 | 模式 | 特徵 |
|------|------|------|
| Shopee | Mode B | 需詳情 API（列表無 items） |
| Shopify | Mode A | 列表已完整 |
| Momo | ? | 待確認 |
| Yahoo | ? | 待確認 |
| PChome | ? | 待確認 |
| easystore | ? | 待確認 |
| Cyberbiz | ? | 待確認 |

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