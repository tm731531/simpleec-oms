# 商務流及隊列消息規範 v1.0

> 將事件流從「純技術」視角轉換為「商務流」視角
> 每個商務流都有：縱向（步驟流程）、橫向（多平台/多Mode並行）、具體消息格式

---

## 概述

### 設計理念
- **縱向**：一個商務流從開始到結束的完整步驟
- **橫向**：同一商務流在多個平台、多個Mode上並行進行
- **一致性**：無論哪個平台、哪個Mode，同一步驟的消息結構相同

### Queue 一致性原則
```
Header 結構：完全一致（所有消息都使用同一個 Header）
Body 結構：根據 TaskType 靈活變化
  - Fetch Order：Body 可以為空（只是觸發動作）
  - Process Order：Body 承載完整的訂單資料
  - Sync Product：Body 承載商品資料
```

---

## 商務流 1：訂單同步流（Order Fetch & Process）

### 流程概觀

```
┌─────────────────────────────────────────────────────────────────┐
│                        訂單同步商務流                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  平台 A（Cyberbiz）    平台 B（Momo）       平台 C（Yahoo）      │
│       ↓                    ↓                     ↓                │
│  Step 1: LIST API      LIST API              LIST API            │
│  (Fetch Orders)                                                   │
│       ↓                    ↓                     ↓                │
│  Topic: cyberbiz.slow  Topic: momo.slow     Topic: yahoo.slow    │
│  TaskType: FETCH_ORDERS                                          │
│       ↓                    ↓                     ↓                │
│  Step 2: DETAIL API（如需）                                       │
│  (Fetch Order Detail)                                            │
│       ↓                    ↓                     ↓                │
│  Topic: cyberbiz.slow  Topic: momo.slow     Topic: yahoo.slow    │
│  TaskType: FETCH_ORDER_DETAIL                                    │
│       ↓                    ↓                     ↓                │
│  Step 3: 匯聚到統一 Topic                                         │
│       └─────────────────────────────────────┬──────────────────┘ │
│                                              │                    │
│                                    Topic: order.process           │
│                                    TaskType: PROCESS_ORDER        │
│                                              ↓                    │
│                                    Order Job Handler              │
│                                    (INSERT/UPDATE to DB)          │
└─────────────────────────────────────────────────────────────────┘
```

### Step 1: 訂單列表拉取（LIST）

**觸發方式**：Scheduler 定時分發 `FETCH_ORDERS` 到各平台的 `.slow` topic

**Topic**：`{platform}.slow`（cyberbiz.slow, momo.slow, yahoo.slow, pchome.slow, shopee.slow, easystore.slow）

**TaskType**：`FETCH_ORDERS`

**消息格式**（統一）：

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

**Channel Job 內部邏輯**（不在消息中）：

```
讀取 header.timestamp = 2026-02-13T10:00:00Z

根據平台特性自主決策時間窗口：
  ├─ Cyberbiz：過去 7 天
  ├─ Momo：
  │  ├─ 1 小時內的新訂單（PENDING）
  │  ├─ 3 天內的待出貨（CONFIRMED）
  │  ├─ 5 天內的出貨中（SHIPPED）
  │  └─ 7 天後的已完成（COMPLETED）
  ├─ Yahoo：根據 updated_after=timestamp-1d
  ├─ PChome：根據時間範圍查詢
  └─ Easystore：過去 7 天

發送多個 API 請求（批次）

判斷差異：與 DB 中的 SellPack 比對
  └─ 只有有差異的訂單才進入 DETAIL 階段
```

**注**：Queue 中只有時間戳，不包含時間範圍。Channel Job 完全自主決策。

---

### Step 2: 訂單詳情拉取（DETAIL）

**觸發方式**：Channel Job LIST 階段判斷某訂單需詳情時

**Topic**：`{platform}.slow`

**TaskType**：`FETCH_ORDER_DETAIL`

**消息格式**：

```json
{
  "header": {
    "taskType": "FETCH_ORDER_DETAIL",
    "merchantId": "M001",
    "platformId": "momo",
    "channelId": "MOMO_001",
    "requestId": "detail_req_momo_20260213_001",
    "timestamp": "2026-02-13T10:30:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "sched-20260213-fetch-001",
    "priority": "NORMAL"
  },
  "body": {
    "orders": [
      {
        "channelOrderId": "MOMO-2026021300001"
      },
      {
        "channelOrderId": "MOMO-2026021300002"
      }
    ]
  }
}
```

**Channel Job 內部邏輯**：

```
檢查差異（vs DB 中的訂單記錄）
  ├─ 新訂單：YES → 進入 DETAIL
  ├─ 已存在訂單且數據相同：NO → 跳過
  └─ 已存在訂單但字段改變：YES → 進入 DETAIL

對每個需要 DETAIL 的訂單：
  ├─ 調用平台 DETAIL API
  ├─ 解析所有欄位（包括 items 詳情）
  └─ 實施 Rate Limiting（避免 API 限流）
```

---

### Step 3: 統一訂單處理（PROCESS_ORDER）

**來源**：Channel Job 完成 LIST/DETAIL 後，發送到 `order.process`

**Topic**：`order.process`

**TaskType**：`PROCESS_ORDER`

**消息格式**（完整訂單資料）：

```json
{
  "header": {
    "taskType": "PROCESS_ORDER",
    "merchantId": "M001",
    "platformId": "momo",
    "channelId": "MOMO_001",
    "requestId": "process_req_momo_20260213_001",
    "timestamp": "2026-02-13T10:35:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "sched-20260213-fetch-001",
    "isRollback": false
  },
  "body": {
    "orderData": {
      "orderId": "ord_abc123def456",
      "channelOrderId": "MOMO-2026021300001",
      "orderStatus": "PENDING",
      "buyerName": "[加密]",
      "buyerPhone": "[加密]",
      "buyerEmail": "[加密]",
      "shippingAddress": "[加密]",
      "shippingMethod": "HOME_DELIVERY",
      "shippingStatus": "PENDING",
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

**Order Job Handler 邏輯**：

```
1. 計算 orderData 的 hash（SHA256）
2. 查詢 DB：WHERE channel_order_id=? AND platform_id=?
3. 判斷：
   ├─ 新訂單（無記錄）→ INSERT，id = orderId
   ├─ 已存在 + Hash 不同 → UPDATE
   └─ 已存在 + Hash 相同 → 跳過（冪等性）
4. 更新 order 表
5. 可能觸發下游事件（如庫存扣減）
```

---

## 商務流 2：商品同步流（Product Sync - SYNC_PACK）

### 流程概觀

```
┌──────────────────────────────────────────────────────────┐
│              商品同步商務流（拉 - Pull）                   │
├──────────────────────────────────────────────────────────┤
│                                                            │
│  平台 A（Cyberbiz）    平台 B（Momo）                     │
│       ↓                    ↓                              │
│  Step 1: LIST API      LIST API                          │
│  (Fetch Products)      差異檢測（vs OMS SellPack）        │
│       ↓                    ↓                              │
│  差異檢測               只有差異產品進入 DETAIL           │
│       ↓                    ↓                              │
│  Topic: cyberbiz.fast  Topic: cyberbiz.slow              │
│  TaskType: SYNC_PACK_LIST                                │
│       ↓                    ↓                              │
│  Step 2: DETAIL API（只限有差異的產品）                  │
│       ↓                    ↓                              │
│  Topic: cyberbiz.slow  Topic: cyberbiz.slow              │
│  TaskType: SYNC_PACK_DETAIL                              │
│       ↓                    ↓                              │
│       └───────────────────┬──────────────────┘            │
│                            │                              │
│                  Topic: task.backend                      │
│                  TaskType: SYNC_PACK_COMPLETE             │
│                            ↓                              │
│                    Backend Job Handler                    │
│                  (更新 SellPack 表)                       │
└──────────────────────────────────────────────────────────┘
```

### Step 1: 產品列表拉取 & 差異檢測（LIST）

**觸發方式**：Scheduler 定時分發到各平台的 `.fast` topic

**Topic**：`{platform}.fast`

**TaskType**：`SYNC_PACK_LIST`

**消息格式**：

```json
{
  "header": {
    "taskType": "SYNC_PACK_LIST",
    "merchantId": "M001",
    "platformId": "cyberbiz",
    "channelId": "CYBERBIZ_001",
    "requestId": "sched-20260213-synclist-001",
    "timestamp": "2026-02-13T06:00:00Z",
    "source": "scheduler",
    "version": 1,
    "priority": "HIGH"
  },
  "body": {
    "syncSpec": {}
  }
}
```

**Channel Job 內部邏輯**：

```
呼叫平台 LIST API，獲得所有產品清單
  ├─ Cyberbiz: GET /api/v1/products
  ├─ Momo: GET /api/product/v1/products
  └─ Yahoo: GET /api/products

粒度檢測：對每個 channelProductId
  ├─ 在 OMS SellPack 表中查找
  ├─ 對比欄位（價格、庫存、狀態）
  └─ 如有變化，標記為「需要 DETAIL」

結果分類：
  ├─ 新產品：進入 DETAIL
  ├─ 現有 + 無變化：跳過
  └─ 現有 + 有變化：進入 DETAIL

生成 SYNC_PACK_DETAIL 消息（只含有差異的產品）
```

**核心設計**：
- LIST 只做「列表 + 快速檢測」，不取詳細資料
- 節省 50-70% 的 API 呼叫（只取需要的產品詳情）

---

### Step 2: 產品詳情拉取（DETAIL）

**觸發方式**：LIST 階段判斷有差異的產品

**Topic**：`{platform}.slow`

**TaskType**：`SYNC_PACK_DETAIL`

**消息格式**：

```json
{
  "header": {
    "taskType": "SYNC_PACK_DETAIL",
    "merchantId": "M001",
    "platformId": "cyberbiz",
    "channelId": "CYBERBIZ_001",
    "requestId": "detail_syncprod_20260213_001",
    "timestamp": "2026-02-13T06:15:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "sched-20260213-synclist-001",
    "priority": "HIGH"
  },
  "body": {
    "products": [
      {
        "channelProductId": "CYBER-SKU-001"
      },
      {
        "channelProductId": "CYBER-SKU-002"
      }
    ]
  }
}
```

**Channel Job 內部邏輯**：

```
對每個需要詳情的產品：
  1. 呼叫平台 DETAIL API
     └─ Cyberbiz: GET /api/v1/products/{productId}
  2. 解析完整欄位：
     ├─ 基本資訊（名稱、說明、圖片）
     ├─ 價格（現價、原價）
     ├─ 庫存
     ├─ 規格（channelSpecId）← 在此階段提取
     └─ 狀態（active/inactive/draft）
  3. 實施 Rate Limiting
     └─ Cyberbiz: 3.3 秒 / 請求
  4. 生成 SYNC_PACK_DETAIL 消息
```

---

### Step 3: 商品資料完成（COMPLETE）

**來源**：Channel Job 完成 DETAIL 後，發送到 `task.backend`

**Topic**：`task.backend`

**TaskType**：`SYNC_PACK_COMPLETE`

**消息格式**（完整商品資料）：

```json
{
  "header": {
    "taskType": "SYNC_PACK_COMPLETE",
    "merchantId": "M001",
    "platformId": "cyberbiz",
    "channelId": "CYBERBIZ_001",
    "requestId": "complete_syncprod_20260213_001",
    "timestamp": "2026-02-13T06:30:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "sched-20260213-synclist-001"
  },
  "body": {
    "products": [
      {
        "channelProductId": "CYBER-SKU-001",
        "channelSpecId": "CYBER-SPEC-001",
        "sku": "CYBER-SKU-001",
        "productName": "Samsung 55吋 QLED 電視",
        "specName": "55吋/黑色",
        "description": "...",
        "sellingPrice": 24999,
        "originalPrice": 29999,
        "quantity": 150,
        "status": "active",
        "imageUrl": "https://..."
      },
      {
        "channelProductId": "CYBER-SKU-002",
        "channelSpecId": "CYBER-SPEC-002",
        "sku": "CYBER-SKU-002",
        "productName": "Samsung 55吋 QLED 電視",
        "specName": "55吋/銀色",
        "description": "...",
        "sellingPrice": 24999,
        "originalPrice": 29999,
        "quantity": 80,
        "status": "active",
        "imageUrl": "https://..."
      }
    ]
  }
}
```

**Backend Job Handler 邏輯**：

```
1. 對每個產品：
   ├─ 檢查 SellPack 表是否存在
   ├─ 如果不存在 → INSERT
   ├─ 如果存在 + 資料相同 → 跳過
   └─ 如果存在 + 資料不同 → UPDATE
2. 更新 sell_pack 表
3. 可能觸發下游（如同步到 OMS Product 表）
```

---

## 快速參考：消息映射表

### 訂單同步流

| 步驟 | Topic | TaskType | 消息來源 | Body 特性 |
|------|-------|----------|---------|----------|
| LIST | {platform}.slow | FETCH_ORDERS | Scheduler | 空白（只是觸發） |
| DETAIL | {platform}.slow | FETCH_ORDER_DETAIL | Channel Job | 訂單編號列表 |
| PROCESS | order.process | PROCESS_ORDER | Channel Job | **完整訂單資料** |

### 商品同步流

| 步驟 | Topic | TaskType | 消息來源 | Body 特性 |
|------|-------|----------|---------|----------|
| LIST | {platform}.fast | SYNC_PACK_LIST | Scheduler | 空白（只是觸發） |
| DETAIL | {platform}.slow | SYNC_PACK_DETAIL | Channel Job | **只含有差異的產品** |
| COMPLETE | task.backend | SYNC_PACK_COMPLETE | Channel Job | **完整產品資料** |

---

## 設計重點

### 1. Body 的彈性與一致性

✅ **Header 完全一致**
```
所有消息都使用同一個 Header 結構
```

✅ **Body 根據 TaskType 靈活變化**
```
FETCH_ORDERS body: {}           （空白）
PROCESS_ORDER body: {...}       （完整訂單）
SYNC_PACK_LIST body: {}         （空白）
SYNC_PACK_DETAIL body: {...}    （產品清單）
```

### 2. 橫向並行性

```
多個平台同時進行相同的商務流
每個平台獨立處理，最後匯聚到統一的 Topic
無阻塞，無順序要求
```

### 3. 最小化 API 呼叫

```
訂單同步：
  ├─ LIST 判斷差異 → 只對有差異的訂單呼叫 DETAIL
  └─ 節省 30-50% API 呼叫

商品同步：
  ├─ LIST 判斷差異 → 只對有差異的產品呼叫 DETAIL
  └─ 節省 50-70% API 呼叫
```

---

## 更新日誌

| 日期 | 版本 | 變更 |
|------|------|------|
| 2026-02-25 | 1.0 | 初始版本：訂單同步、商品同步、詳細的消息格式 |

