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

## 2. Scheduler 架構：Heartbeat + Decision System

**核心設計**：分散系統中，不同 Scheduler 實例有不同的本地時間。解決方案：
- **Heartbeat Job** (時間源)：單一權威伺服器，每秒發脈搏到 `scheduler` topic，攜帶當前 timestamp
- **Scheduler Consumer** (決策者)：所有 Scheduler 實例接收脈搏，根據 timestamp 檢查分鐘位判斷派發任務

**優勢**：
- ✅ 單一時間基準：避免分散系統時間不同步
- ✅ 反應式驅動：事件驅動而非時間觸發
- ✅ 完全可控：停止 Heartbeat = 暫停所有排程任務
- ✅ 易於測試：用任意 timestamp 的脈搏重放

**Scheduler 判斷邏輯**（基於 timestamp 的分鐘位）：
```
当分钟 % 5 == 0（:00, :05, :10...）
  → 派發 ORDERS_SLOW 到所有 {platform}.slow
  → Channel Job 根據 timestamp 自行決策是否呼叫 API

当分钟 % 5 == 1（:01, :06, :11...）
  → 派發訂單報表任務到 task.backend

当分钟 % 5 == 2（:02, :07, :12...）
  → 派發庫存報表任務到 task.backend

当分钟 % 5 == 3（:03, :08, :13...）
  → 派發銷售額報表任務到 task.backend

当分钟 % 5 == 4（:04, :09, :14...）
  → 派發退貨報表任務到 task.backend

当分钟 % 10 == 5（:05, :15, :25...）
  → 派發 Kafka 健康檢查

当分钟 == 0 或 30（:00, :30）
  → 派發日報生成任務
```

**實現細節**：
- Heartbeat 訊息格式：`{ timestamp: "2026-02-13T08:00:00Z", ... }`
- Scheduler Consumer 實作：`if (timestamp.getMinutes() % 5 === 0) { dispatch ORDERS_SLOW }`
- **重點**：NEVER 用本地時間 `new Date().getMinutes()`，ALWAYS 用 Heartbeat timestamp

## 3. Topic 定義

### 2.1 Channel Topics (通路主題)
| Topic Pattern | 用途 | 處理時間要求 | Retention |
|--------------|------|-------------|-----------|
| `{platform}.fast` | 快速任務：狀態更新、出貨 | < 5s | 1d |
| `{platform}.slow` | 慢速任務：訂單列表、批次處理 | < 5m | 1d |

平台列表：`momo`, `shopee`, `yahoo`, `pchome`, `cyberbiz`, `easystore`

### 2.2 Business Topics (業務主題 - 7 個)
| Topic | 用途 | 資料特性 | Retention |
|-------|------|---------|-----------|
| `order.process` | 訂單資料處理 | Source of Truth（核心） | 1d→2h |
| `return.process` | 退貨資料處理 | Source of Truth | 1d |
| `task.backend` | 後端非同步任務 | 商品同步、庫存更新、賣場同步、出貨等 | 1d |
| `task.frontend` | 前端非同步任務 | UI 觸發的任務（匯出、批次更新等） | 1d |
| `scheduler` | Heartbeat 脈搏 | Heartbeat Job 每秒發送當前 timestamp，所有 Scheduler Consumer 接收後判斷派發任務 | 1d |
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
    "retryCount": 0,            // 選填：重試次數
    "priority": "NORMAL",       // 選填：HIGH/NORMAL/LOW
    "correlationId": "string"   // 選填：關聯識別碼
  },
  "body": {
    // TaskType 特定資料
  }
}
```

### 3.2 Header 欄位說明
- `taskType`: 決定使用哪個 Handler Class 處理
- `source`: 訊息來源（scheduler/api/webhook/manual/channel_job 等）
- `merchantId`: 多商戶隔離，資料不互通（必填）
- `platformId`: 通路編號（shopee/momo/yahoo/pchome/cyberbiz/easystore），用於第三方 API 呼叫（必填）
- `channelId`: 通路實例，如 SHOPEE_001, SHOPEE_002（多帳號）（選填）
- `requestId`: 唯一識別碼，用於追蹤和冪等性（必填）
- `timestamp`: 訊息時間戳（ISO-8601），用於追蹤和業務判斷（如 FETCH_ORDERS 的基準時間）（必填）
- `version`: 訊息協議版本，用於向後相容判斷（必填）
- `correlationId`: 串連相關訊息，如 FETCH_ORDERS → FETCH_ORDER_DETAIL → ORDER_UPSERT 的關聯（選填）
- `retryCount`: 重試次數（選填）
- `priority`: 優先度 HIGH/NORMAL/LOW（選填）
- `isRollback`: 是否為回補訂單（遺漏的過往訂單）（選填，預設 false）
  - `true`: 過去遺漏、現在回補；業績/統計/庫存計算邏輯不同
  - `false`: 新訂單，正常計入當日營業額

## 4. 核心 TaskType 定義

### 4.0 Channel Job 角色：數據適配層 & isRollback 標籤

**isRollback 適用範圍**：
- ✅ **訂單相關**：FETCH_ORDERS, FETCH_ORDER_DETAIL, ORDER_UPSERT, SHIP_ORDER
- ✅ **退貨相關**：FETCH_RETURNS, FETCH_RETURN_DETAIL, RETURN_UPSERT, APPROVE_RETURN
- ❌ **套包/庫存/價格相關**：SYNC_PACK, UPDATE_INVENTORY, UPDATE_PRICE, SYNC_PRODUCT（無需 isRollback）

**Channel Job 角色**：

**核心責任**：Channel Job 是數據適配層，負責將各通路 API 的五花八門格式轉換為 OMS 統一的訂單結構。

- 各通路 API 特性天差地遠（數據結構、response 粒度、rate limit、費用等）
- Channel Job 封裝所有通路差異，只向上游暴露統一的 OMS orderData
- order.process Handler 無需處理多通路差異，只需專注業務邏輯（查 DB、決定新建/更新、去重）

**Example：5 個通路，5 種 API 特性**
| 平台 | API 特性 | Channel Job 責任 |
|------|---------|-----------------|
| Shopee | orders list 不完整，需打 detail API | 判斷何時打 detail，整合 items/payment/shipping |
| Momo | item-level 記錄（非訂單層級），無 detail API | 按訂單號分組聚合，自己整合完整結構 |
| Yahoo | 只有更新時間，無狀態分類 | 用時間範圍查詢，自己轉換為 OMS 狀態 |
| easystore | 有 IP 限制，但一次可抓 50 張完整訂單 | 評估 rate limit，決定是否分批 |
| PChome/Cyberbiz | ... | ... |

### 4.1 訂單相關

#### Mode A vs Mode B 處理方式

**重要**：訂單詳情是否需要另外 fetch 取決於平台 API 能力，分為兩種模式：

| 模式 | 平台特性 | 流程 | 範例 |
|------|--------|------|------|
| **Mode A** | 訂單列表 API 已包含完整資訊（items、payment、shipping 等） | FETCH_ORDERS (list) → orderData 完整 → ORDER_UPSERT | Shopify、easystore |
| **Mode B** | 訂單列表 API 只有概要資訊 | FETCH_ORDERS (list) → 判斷需要詳情 → FETCH_ORDER_DETAIL → 詳情完整 → ORDER_UPSERT | Shopee、Momo（需按訂單號聚合） |

#### TaskType 定義

| TaskType | 來源 Topic | 目標 Topic | 說明 |
|----------|-----------|------------|------|
| FETCH_ORDERS | scheduler | {platform}.slow | Scheduler 接收 Heartbeat 脈搏，根據分鐘位判斷派發，Channel Job 根據 timestamp 決策是否呼叫 API |
| FETCH_ORDER_DETAIL | {platform}.slow | order.process | **Mode B only**：Channel Job 判斷某訂單需詳情，fetch detail API 後發到 order.process。Mode A 平台無此步驟。 |
| ORDER_UPSERT | order.process | (內部消費) | Handler 查詢 DB 決定 INSERT 或 UPDATE，執行業務邏輯。orderData 必須是**完整資料**（Mode A 來自 list API，Mode B 來自 detail API） |
| SHIP_ORDER | {platform}.fast | task.backend | 出貨作業 |

### 4.2 退貨相關
| TaskType | 來源 Topic | 目標 Topic | 說明 |
|----------|-----------|------------|------|
| FETCH_RETURNS | {platform}.slow | return.process | 抓取退貨列表 |
| FETCH_RETURN_DETAIL | {platform}.slow | return.process | 抓取退貨詳情 |
| RETURN_UPSERT | return.process | (內部消費) | 退貨入庫（Handler 查詢 DB 決定 INSERT 或 UPDATE） |
| APPROVE_RETURN | {platform}.fast | return.process | 同意退貨 |

**備註**: RETURN_UPSERT 也遵循 isRollback 邏輯（與 ORDER_UPSERT 相同）

### 4.3 套包相關（來自通路）
| TaskType | 來源 Topic | 目標 Topic | 說明 |
|----------|-----------|------------|------|
| SYNC_PACK | {platform}.slow | task.backend | 同步通路上賣的套包資訊 |
| UPDATE_INVENTORY | {platform}.fast | task.backend | 更新庫存 |
| UPDATE_PRICE | {platform}.fast | task.backend | 更新價格 |

**備註**: 通路層只有「套包」(Pack) 概念——在該通路上賣的商品單位。
商品聚合 (SYNC_PRODUCT) 是 OMS 內部邏輯，BACKEND 收到 SYNC_PACK 後，建立 Pack → Product 映射（客戶不做 mapping，由系統自動建立）。

### 4.4 SYNC_PACK 完整流程：雙層檢查 + 條件派發

**UI 操作**：客戶點擊「同步套包」按鈕（source: admin_ui）

**{platform}.fast 層**（基本比對，快速）：
- 取得通路上的套包列表（平台 ID + 規格編號）
- 發送到 {platform}.slow 進行詳細處理

**{platform}.slow 層**（詳細檢查，較慢）：
- 逐個套包進行 **雙層對映檢查**：
  1. 查詢 **PRODUCT 表**：根據 SKU 檢查商品是否存在
  2. 查詢 **PACK 表**：根據「平台 ID + 規格編號」檢查套包是否存在
- 根據檢查結果決定發送哪些獨立事件到 task.backend

**決策矩陣**（派發規則）：

| Product 存在？ | Pack 存在？ | 動作 | 說明 |
|---------------|-----------|------|------|
| ✅ 有 | ✅ 有 | 不做事 | 已完整，無需任何操作 |
| ✅ 有 | ❌ 無 | SYNC_PACK | 只需建立 Pack |
| ❌ 無 | ✅ 有 | SYNC_PRODUCT → SYNC_PACK | 先建 Product，再建/更新 Pack |
| ❌ 無 | ❌ 無 | SYNC_PRODUCT → SYNC_PACK | 先建 Product，再建 Pack |

**實例**（平台 ID: AAAA，4個規格）：

| 規格 | SKU | Product | Pack | 派發事件 |
|------|-----|---------|------|---------|
| 001 | A001 | ✅ 有 | ❌ 無 | → SYNC_PACK |
| 002 | A002 | ❌ 無 | ❌ 無 | → SYNC_PRODUCT → SYNC_PACK |
| 003 | A003 | ❌ 無 | ✅ 有 | → SYNC_PRODUCT → SYNC_PACK |
| 004 | A004 | ✅ 有 | ✅ 有 | （skip，已完整） |

**重要設計原則**：
- ✅ **SYNC_PRODUCT** 和 **SYNC_PACK** 是獨立的兩個 TaskType
- ✅ 各有各的 Handler 在 task.backend 中獨立處理
- ✅ **最小粒度設計** — 可被其他流程重用
  - SYNC_PRODUCT 可由其他業務觸發（不只是 Pack 同步）
  - SYNC_PACK 可單獨發送（當 Product 已存在）
- ❌ 不串聯在一個事件中，不同時更新多個表

## 5. 訂單 fetch 流程詳解

### 5.1 FETCH_ORDERS：Heartbeat 驅動

**架構原則**：
- **Heartbeat Job** 每秒發脈搏到 `scheduler` topic，body 含整數欄位供 Scheduler Consumer 判斷派發時機：
  ```json
  {
    "header": { "taskType": "HEARTBEAT", "timestamp": "2026-02-13T09:00:00Z", ... },
    "body": {
      "minuteOfHour": 5,
      "secondOfMinute": 0
    }
  }
  ```
- **Scheduler Consumer** 接收脈搏，檢查分鐘位：
  - 當 `minuteOfHour % 5 == 0` 且 `secondOfMinute == 0` 時 → 派發 ORDERS_SLOW 到所有 {platform}.slow
- **Channel Job** 根據 timestamp + 通路 API 規則**自行決策**是否呼叫 API，包括：
  - 如何打 API（各通路規則不同）
  - 是否需要 FETCH_ORDER_DETAIL（取決於 API 能力和 rate limit）
  - 如何組成 OMS 統一結構

**Scheduler Consumer 派發到 {platform}.slow:**
```json
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "source": "scheduler",
    "timestamp": "2026-02-13T09:00:00Z",
    "merchantId": "merchant_001",
    "platformId": "shopee",
    "channelId": "SHOPEE_001",
    "requestId": "fetch_req_001",
    "version": 1
  },
  "body": {
    "fetchSpec": {}
  }
}
```

**Channel Job 的內部決策流程**（根據通路規則）：

**第 1 步：讀取 header.timestamp**
- 取得基準時間戳（BASE_TS）

**第 2 步：根據時間戳和訂單狀態，分批多次打 API（Orders Channel Job 核心職責）**

Scheduler 只傳一個 timestamp，Channel Job **自行決策**分批策略和時間窗口：

```
Channel Job 根據訂單「年齡」分層級、自行計算時間窗口：
- PENDING 訂單：BASE_TS 往前推 1 小時內 → 打 API_1
- CONFIRMED 訂單：BASE_TS 往前推 3 天內 → 打 API_2
- SHIPPED 訂單：BASE_TS 往前推 5 天內 → 打 API_3
- COMPLETED 訂單：BASE_TS 往前推 7 天內 → 打 API_4

（時間窗口完全由 Channel Job 內部決策，Queue 和 Scheduler 無關）
```

**具體實例（Channel Job 內部實現細節）：**
```
Shopee 訂單 API：
  // Channel Job 自行計算的時間窗口
  GET /api/orders?order_status=UNPAID&create_time_from=BASE_TS&create_time_to=BASE_TS+1h
  GET /api/orders?order_status=AWAITING_SHIPMENT&create_time_from=BASE_TS-3d&create_time_to=BASE_TS
  GET /api/orders?order_status=SHIPPED&create_time_from=BASE_TS-5d&create_time_to=BASE_TS
  GET /api/orders?order_status=COMPLETED&create_time_from=BASE_TS-7d&create_time_to=BASE_TS

Momo 訂單 API：
  // Momo API 無狀態分類，Channel Job 用時間範圍
  GET /api/orders?created_time_start=BASE_TS-1h&created_time_end=BASE_TS
  GET /api/orders?created_time_start=BASE_TS-3d&created_time_end=BASE_TS
  // 返回 item-level 記錄，Channel Job 自己按訂單號分組聚合

Yahoo 訂單 API：
  // Yahoo 無狀態，只有更新時間
  GET /api/orders?updated_after=BASE_TS-1d
  // Channel Job 自己評估訂單狀態

easystore 訂單 API：
  // easystore 一次可拿完整資訊
  GET /api/orders?from_date=BASE_TS-7d&to_date=BASE_TS&limit=50
  // 可視需要分頁拉取
```

**設計原則**：
- ✅ Scheduler 只提供 `timestamp`（一個時間點）
- ✅ **Queue 裡面 NO RANGE** — 只有單一時間戳
- ✅ Channel Job 根據**通路特性**和**訂單狀態生命週期**，自行計算時間窗口
- ✅ 不同狀態的訂單重要性和更新頻率不同 → 分層級拉取保證數據新鮮度

**第 3 步：判斷是否需要 FETCH_ORDER_DETAIL**

| 平台 | 判斷標準 | 結論 |
|------|---------|------|
| **Shopee** | orders list API 只回傳概要（items 不完整、無 payment/shipping 詳情） | ✅ YES，必須打 DETAIL API |
| **Momo** | API 返回 item-level，非訂單層級，detail API 根本不存在 | ❌ NO，改在 Channel Job 內自己按訂單號分組聚合 |
| **easystore** | orders API 一次回傳 50 張 + 完整資訊（items、payment、shipping 都有） | ❌ NO，直接組 OMS 結構 |
| **Yahoo** | 需評估 API response 內容是否足夠 | 視情況 |

**第 4 步：組成 OMS 統一結構並發送**

若無需 DETAIL，直接發 ORDER_UPSERT；若需 DETAIL，先發 FETCH_ORDER_DETAIL 到 {platform}.slow。

---

### 5.2 FETCH_ORDER_DETAIL：Channel Job 決定是否需要

**何時觸發**：
- Channel Job 在 FETCH_ORDERS 階段判斷某些訂單需要詳情（如 Shopee）
- 決定發送 FETCH_ORDER_DETAIL 到 {platform}.slow，背景非同步打詳情 API

**Channel Job 發送到 {platform}.slow:**
```json
{
  "header": {
    "taskType": "FETCH_ORDER_DETAIL",
    "source": "channel_job",
    "merchantId": "merchant_001",
    "platformId": "shopee",
    "channelId": "SHOPEE_001",
    "requestId": "detail_req_001",
    "timestamp": "2026-02-13T09:30:00Z",
    "version": 1,
    "isRollback": false
  },
  "body": {
    "orders": [
      {
        "channelOrderId": "2026021300001",
        "metadata": {
          "apiVersion": "v3",
          "shopId": "12345"
        }
      }
    ]
  }
}
```

**Channel Job 根據通路規則打 DETAIL API 後，發送 ORDER_UPSERT 到 order.process:**
```json
{
  "header": {
    "taskType": "ORDER_UPSERT",
    "source": "channel_job",
    "merchantId": "merchant_001",
    "platformId": "shopee",
    "channelId": "SHOPEE_001",
    "requestId": "process_req_001",
    "timestamp": "2026-02-13T09:35:00Z",
    "version": 1,
    "isRollback": false
  },
  "body": {
    "orderData": {
      "orderId": "ord_abc123def456",
      "channelOrderId": "2026021300001",
      "orderStatus": "PENDING",
      "buyerName": "顧客名稱",
      "buyerPhone": "0912345678",
      "buyerEmail": "customer@example.com",
      "shippingAddress": "台北市信義區松壽路 99 號",
      "shippingMethod": "HOME_DELIVERY",
      "paymentMethod": "CREDIT_CARD",
      "totalAmount": 2790.00,
      "shippingFee": 60.00,
      "discountAmount": 270.00,
      "channelCreatedAt": "2026-02-13T08:30:00Z",
      "paidAt": "2026-02-13T08:31:00Z",
      "items": [
        {
          "sku": "HGJ-60-12",
          "productId": "pd_xyz789",
          "channelProductId": "SHOPEE-SKU-98765",
          "channelSpecId": "SHOPEE-SPEC-98765-A",
          "channelItemId": "SHOPEE-ITEM-98765-001",
          "channelProductName": "SHOPEE養生雞精禮盒限定組",
          "channelSpecName": "60ml×12入(單盒)",
          "productName": "養生雞精禮盒限定組",
          "quantity": 2,
          "unitPrice": 1395.00,
          "subtotal": 2790.00,
          "sellPackId": "sp_abc123"
        }
      ]
    }
  }
}
```

**重要提示**：
- `orderData` 已是 **OMS 統一結構**（對應 orders 表和 items JSONB），不是通路原始格式
- **orderId**: 我們的訂單 NanoID（新訂單由 Handler 生成，更新時由 orderData 帶入）
- **channelOrderId**: 通路訂單編號（如 Shopee 的 order_id）；特殊字元（`#`, `-`, `@`）必須完整保留
- **channelItemId**: 通路項目編號（如 Momo/Shopee 的 item_id）
- **platformId** in header: 通路 ID（Shopee/Momo/Yahoo/easystore 等），用於通路 API 調用需要
- Handler INSERT/UPDATE 時直接拆解 orderData 到各欄位：
  - 訂單表: id=orderId, channel_order_id=channelOrderId, orderStatus, buyerName, buyerPhone, buyerEmail, shippingAddress, shippingMethod, paymentMethod, totalAmount, shippingFee, discountAmount, channelCreatedAt, paidAt
  - items JSONB: 整個 items[] 陣列存入 orders.items

---

### 5.3 ORDER_UPSERT：Handler 執行業務邏輯

**Handler 的責任**：
- 查詢 DB，決定是否已存在該訂單（根據 merchantId:channelId:orderId）
- 若新訂單，INSERT；若已存在，UPDATE
- 執行業務驗證（如庫存檢查、積分計算等）
- 計算 order hash 用於後續變更偵測（存入 Redis）
- **根據 `header.isRollback` 調整業務邏輯**：
  - `isRollback=false`：正常訂單
    - 業績計入當日（based on `channelCreatedAt`）
    - 庫存正常扣減
    - 統計數據正常計入
  - `isRollback=true`：回補訂單（遺漏的過往訂單）
    - 業績追溯原日期（based on `channelCreatedAt`，不是當日）
    - 庫存調整時可能需要特殊處理
    - 統計數據標記為回補，可能不計入排名
    - 發送回補專用事件通知（如重新計算日報）

**Handler 不應做的事**：
- ❌ 調用通路 API
- ❌ 處理多通路的 API 格式差異
- ❌ 解析通路特定欄位（Channel Job 已處理）

---

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
    "taskType": "FAILED_TASK",
    "source": "channel_job",
    "merchantId": "merchant_001",
    "platformId": "shopee",
    "channelId": "SHOPEE_001",
    "isRollback": false
  },
  "body": {
    "originalHeader": {
      "taskType": "ORDER_UPSERT",
      "merchantId": "merchant_001",
      "platformId": "shopee",
      "channelId": "SHOPEE_001",
      "requestId": "process_req_001",
      "timestamp": "2026-02-13T09:35:00Z",
      "isRollback": false
    },
    "originalBody": { },
    "errorInfo": {
      "errorCode": "API_TIMEOUT",
      "errorMessage": "Shopee API 連線逾時",
      "retryable": true,
      "maxRetries": 3,
      "nextRetryTime": "2026-02-13T09:15:00Z"
    }
  }
}
```

## 7. 版本管理策略

### 7.1 版本相容性
- v1 Handler 必須能處理 v1 訊息
- v2 Handler 必須能處理 v1 和 v2 訊息（向下相容）
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

---

## 9. Translation Layer Rule — Channel Job 是唯一翻譯器

**This is a non-negotiable architectural constraint.**

Channel Job is the ONLY component in the system that knows platform-specific IDs.
All Kafka messages on all topics MUST use internal OMS IDs (NanoID) only.

### 9.1 Outbound Action Messages (OMS → Channel Job → Platform)
Platform IDs MUST NOT appear in message bodies. Channel Job translates internally:

| TaskType | Message body contains | Channel Job looks up | Then calls platform with |
|---|---|---|---|
| UPDATE_INVENTORY | `sellPackId` | `sell_pack WHERE id = ?` → `channel_product_id`, `channel_spec_id` | platform variant update API |
| UPDATE_PRICE | `sellPackId` | `sell_pack WHERE id = ?` → `channel_product_id`, `channel_spec_id` | platform price update API |
| SHIP_ORDER | `orderId` | `orders WHERE id = ?` → `channel_order_id` | platform ship API |
| APPROVE_RETURN | `returnId` | `refund_orders WHERE id = ?` → `channel_order_id` | platform return approve API |
| REJECT_RETURN | `returnId` | `refund_orders WHERE id = ?` → `channel_order_id` | platform return reject API |

### 9.2 Inbound Flow Exception
ORDER_UPSERT and RETURN_UPSERT messages sent FROM Channel Job TO `order.process`/`return.process` MAY include `channelOrderId`/`channelRefundId` — because the internal OMS record does not exist yet for new inbound data. The backend handler creates the record and assigns the internal ID.

### 9.3 Why This Matters
If platform IDs leak into messages, every new channel integration requires hunting down all message senders and handlers. With this rule, adding a new channel only requires a new ChannelAdapter implementation — no message contract changes.
