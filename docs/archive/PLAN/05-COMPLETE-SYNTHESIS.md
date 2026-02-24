# SimpleEC OMS - 完整系統設計綜合參考

> **這是完整的「總一覽」文檔，綜合了 Job 類型、Kafka Topic、UI 組件、Handler 對應等所有設計細節**
>
> 最後更新：2026-02-20
> 版本：1.0.0

---

## 📋 完整導航

| 文檔 | 用途 |
|------|------|
| **00-OVERVIEW.md** | 系統定位、三層標準框架、核心概念 |
| **01-KAFKA-TOPOLOGY.md** | Producer/Consumer/Topic 關係圖、消息流、訊息結構 |
| **02-MODULE-DESIGN.md** | 11 個模組架構、職責邊界、實現順序 |
| **03-IMPLEMENTATION-ORDER.md** | 開發優先順序、里程碑、時間估算 |
| **04-INTERFACE-CONTRACTS.md** | Java 介面定義、各模組的 contract |
| **05-COMPLETE-SYNTHESIS.md** | 📍 本文：完整的「總一覽」，所有設計的總合 |

---

## 第一部分：完整的 Job 架構

SimpleEC OMS 共需實現 **11 個 Job 服務 + 8 個 Handler 類型族群**

### 1.1 所有 Job 服務（Java 應用）

| # | Job 服務 | 模組 | 職責 | 消費的 Topic | 生產的 Topic | 實現週數 |
|----|---------|------|------|-------------|------------|---------|
| 1️⃣ | **HeartbeatJob** | scheduler-job | 每秒發送時間戳 | - | scheduler | Week 1 |
| 2️⃣ | **SchedulerConsumer** | scheduler-job | 根據時間決策派發任務 | scheduler | {platform}.slow, task.backend | Week 1 |
| 3️⃣ | **Channel Job (Mode A)** | channel-job | 直接列表 API → ORDER_UPSERT | {platform}.slow | order.process, return.process, task.backend | Week 3-4 |
| 4️⃣ | **Channel Job (Mode B)** | channel-job | 列表 + 詳情 API → ORDER_UPSERT | {platform}.slow | order.process, return.process, task.backend | Week 8-9 |
| 5️⃣ | **OrderUpsertHandler** | order-job | 訂單入庫（INSERT/UPDATE + Redis） | order.process | task.backend | Week 8 |
| 6️⃣ | **ReturnUpsertHandler** | order-job | 退貨入庫（INSERT/UPDATE + Redis） | return.process | task.backend | Week 8-9 |
| 7️⃣ | **SyncProductHandler** | backend-job | 商品元數據同步 | task.backend | - | Week 5 |
| 8️⃣ | **SyncPackHandler** | backend-job | 上架配置同步 | task.backend | - | Week 5 |
| 9️⃣ | **ReportHandlers** | backend-job | 報表生成（訂單、庫存、銷售、退貨） | task.backend | Metrics/Logs | Week 6 |
| 🔟 | **RetryHandler** | retry-job | 重試失敗的消息 | task.failed | 原 topic | Week 10 |
| 1️⃣1️⃣ | **DltHandler** | retry-job | 死信隊列處理 | task.dlt | 告警系統 | Week 10 |

### 1.2 詳細 Handler 對應表（按 TaskType 分類）

#### A. 通路（Channel）Handlers — 按平台和 Mode 分組

##### Mode A 平台（直接模式）— 各有 8 個 Handler

**Shopify Channel Job（Mode A）**
| TaskType | Handler 類別 | Topic | 說明 |
|----------|------------|-------|------|
| FETCH_ORDERS | ShopifyOrderListHandler | shopify.slow | 拉訂單列表（完整資訊） |
| FETCH_RETURNS | ShopifyReturnListHandler | shopify.slow | 拉退貨列表 |
| SYNC_PACK | ShopifySyncPackHandler | shopify.slow | 同步上架配置 |
| SHIP_ORDER | ShopifyShipOrderHandler | shopify.fast | 執行出貨 |
| UPDATE_INVENTORY | ShopifyInventoryHandler | shopify.fast | 更新庫存 |
| UPDATE_PRICE | ShopifyPriceHandler | shopify.fast | 更新價格 |
| APPROVE_RETURN | ShopifyApproveReturnHandler | shopify.fast | 同意退貨 |
| CANCEL_ORDER | ShopifyCancelOrderHandler | shopify.fast | 取消訂單 |

**easystore Channel Job（Mode A）** — 結構同上

---

##### Mode B 平台（列表+詳情模式）— 各有 10-12 個 Handler

**Shopee Channel Job（Mode B）**
| TaskType | Handler 類別 | Topic | 說明 |
|----------|------------|-------|------|
| FETCH_ORDERS | ShopeeOrderListHandler | shopee.slow | 拉訂單列表（不完整） |
| FETCH_ORDER_DETAIL | ShopeeOrderDetailHandler | shopee.slow | 拉單筆訂單詳情 |
| FETCH_RETURNS | ShopeeReturnListHandler | shopee.slow | 拉退貨列表 |
| FETCH_RETURN_DETAIL | ShopeeReturnDetailHandler | shopee.slow | 拉單筆退貨詳情 |
| SYNC_PACK | ShopeeSyncPackHandler | shopee.slow | 同步上架配置 |
| SHIP_ORDER | ShopeeShipOrderHandler | shopee.fast | 執行出貨 |
| UPDATE_INVENTORY | ShopeeInventoryHandler | shopee.fast | 更新庫存 |
| UPDATE_PRICE | ShopeePriceHandler | shopee.fast | 更新價格 |
| APPROVE_RETURN | ShopeeApproveReturnHandler | shopee.fast | 同意退貨 |
| CANCEL_ORDER | ShopeeCanc elOrderHandler | shopee.fast | 取消訂單 |

**Momo, Yahoo, PChome, Cyberbiz Channel Jobs（Mode B 預測）** — 結構同上

---

#### B. 訂單處理（Order）Handlers

| TaskType | Handler 類別 | Topic | 消費者 | 說明 |
|----------|------------|-------|--------|------|
| ORDER_UPSERT | OrderUpsertHandler | order.process | order-job | 新建或更新訂單；寫 Redis hash + DB |
| RETURN_UPSERT | ReturnUpsertHandler | return.process | order-job | 新建或更新退貨；寫 Redis hash + DB |
| ORDER_STATUS_CHANGE | OrderStatusHandler | order.process | order-job | 訂單狀態變更 |

---

#### C. 後端任務（Backend）Handlers

| TaskType | Handler 類別 | Topic | 消費者 | 說明 | 觸發時機 |
|----------|------------|-------|--------|------|---------|
| SYNC_PRODUCT | SyncProductHandler | task.backend | backend-job | 商品元數據同步（INSERT/UPDATE） | 由訂單或定時 |
| SYNC_PACK | SyncPackHandler | task.backend | backend-job | 上架配置同步（INSERT/UPDATE） | 由訂單或定時 |
| ORDER_REPORT | OrderReportHandler | task.backend | backend-job | 訂單報表（統計） | % 5 == 1 分鐘 |
| INVENTORY_REPORT | InventoryReportHandler | task.backend | backend-job | 庫存報表 | % 5 == 2 分鐘 |
| SALES_REPORT | SalesReportHandler | task.backend | backend-job | 銷售報表 | % 5 == 3 分鐘 |
| RETURN_REPORT | ReturnReportHandler | task.backend | backend-job | 退貨報表 | % 5 == 4 分鐘 |
| KAFKA_HEALTH_CHECK | KafkaHealthHandler | task.backend | backend-job | Kafka 健康檢查 | % 10 == 5 分鐘 |
| DAILY_REPORT | DailyReportHandler | task.backend | backend-job | 每日報表 | :00, :30 |

---

#### D. 錯誤恢復（Retry/DLT）Handlers

| TaskType | Handler 類別 | Topic | 說明 |
|----------|------------|-------|------|
| (任意) | RetryHandler | task.failed | 重試失敗消息（最多 3 次） |
| (任意) | DltHandler | task.dlt | 死信隊列；寫入 DB + 告警 |

---

### 1.3 Handler 計數統計

```
Mode A 平台（2 個）:
  ├─ Shopify: 8 handlers
  └─ easystore: 8 handlers
  小計：16 handlers

Mode B 平台（5 個，包含 1 個待確認）:
  ├─ Shopee: 10 handlers
  ├─ Momo: 10 handlers（預測）
  ├─ Yahoo: 10 handlers（預測）
  ├─ PChome: 10 handlers（預測）
  └─ Cyberbiz: 10 handlers（待評估）
  小計：50 handlers

訂單流程（Order Job）: 3 handlers
後端任務（Backend Job）: 8 handlers
錯誤恢復（Retry/DLT）: 2 handlers
時間驅動（Scheduler）: 2 handlers（Heartbeat + Scheduler）

────────────────────────
總計：81+ handlers（+5 個新平台或待確認）
```

---

## 第二部分：完整的 Kafka Topic 設計

### 2.1 Topic 完整列表

| # | Topic | 類型 | Partitions | 說明 | 生產者 | 消費者 |
|----|-------|------|-----------|------|--------|--------|
| 1 | **scheduler** | 系統 | 1 | 時間驅動信號 | HeartbeatJob | SchedulerConsumer |
| 2-11 | **{platform}.slow** (10 個) | 通路 | 8 | 定時任務（5 分鐘一次） | SchedulerConsumer, Channel Job | Channel Job Handlers |
| 12-21 | **{platform}.fast** (10 個) | 通路 | 8 | 即時操作（UI/API 觸發） | API/UI/Gateway | Channel Job Handlers |
| 22 | **order.process** | 業務 | 16 | 訂單入庫 | Channel Job | OrderUpsertHandler |
| 23 | **return.process** | 業務 | 8 | 退貨入庫 | Channel Job | ReturnUpsertHandler |
| 24 | **task.backend** | 任務 | 4 | 報表、同步、檢查 | 多個 Job | BackendJob Handlers |
| 25 | **task.failed** | 錯誤 | 2 | 重試隊列 | ErrorHandler | RetryHandler |
| 26 | **task.dlt** | 錯誤 | 1 | 死信隊列 | ErrorHandler | DltHandler |

**總計：26 個 Topic（其中 10 個通路 slow + 10 個通路 fast = 20 個通路專用）**

---

### 2.2 Topic 生命週期與 Partition 規劃

```
高流量 Topic（Partition = 16）:
  └─ order.process（訂單量最大，需要強大併發）

中流量 Topic（Partition = 8）:
  ├─ {platform}.slow （訂單定時拉取）
  ├─ {platform}.fast （即時操作）
  └─ return.process （退貨量較低但關鍵）

低流量 Topic（Partition = 4 或更少）:
  ├─ task.backend （報表、同步，是計劃任務）
  ├─ task.failed （錯誤量應該很小）
  └─ task.dlt （死信隊列，應罕見）

特殊 Topic（Partition = 1）:
  ├─ scheduler （單一信號源，無需並發）
  └─ task.dlt （DLT 需要嚴格順序，避免亂序恢復）
```

---

## 第三部分：完整的 UI 與 API 設計

### 3.1 REST API 端點（API 模組）

**訂單相關**
| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/orders` | GET | 列出訂單（支持過濾、排序、分頁） |
| `/api/orders/{id}` | GET | 查詢單筆訂單詳情 |
| `/api/orders/{id}/items` | GET | 查詢訂單項目 |
| `/api/orders/{id}/ship` | POST | 標記出貨 |
| `/api/orders/{id}/cancel` | POST | 取消訂單 |
| `/api/orders/batch-ship` | POST | 批量出貨 |

**商品相關**
| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/products` | GET | 列出商品（支持 SKU 搜尋） |
| `/api/products/{id}` | GET | 查詢單筆商品 |
| `/api/products/{id}/price` | PATCH | 更新成本/建議售價 |
| `/api/products/{id}/inventory` | GET | 查詢庫存 |

**退貨相關**
| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/returns` | GET | 列出退貨 |
| `/api/returns/{id}` | GET | 查詢退貨詳情 |
| `/api/returns/{id}/approve` | POST | 批准退貨 |
| `/api/returns/{id}/reject` | POST | 拒絕退貨 |

**報表相關**
| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/reports/orders` | GET | 訂單報表（日/週/月） |
| `/api/reports/inventory` | GET | 庫存報表 |
| `/api/reports/sales` | GET | 銷售報表 |
| `/api/reports/returns` | GET | 退貨報表 |

### 3.2 Webhook 端點（Gateway 模組）

| 端點 | 來源 | 說明 |
|------|------|------|
| `/webhooks/shopee/order` | Shopee | 訂單推送 |
| `/webhooks/shopee/return` | Shopee | 退貨推送 |
| `/webhooks/momo/order` | Momo | 訂單推送 |
| `/webhooks/{platform}/{event}` | 多通路 | 通用 webhook 路由 |

### 3.3 前端儀表板組件（暫時保留位置）

未來實現的 UI 組件：
- **訂單管理儀表板** — 各通路訂單聚合視圖
- **商品管理頁** — SKU、定價、庫存管理
- **退貨管理頁** — 退貨流程協作
- **報表中心** — 多維度分析
- **通路設定頁** — 平台連接配置

---

## 第四部分：系統架構全景圖

```
┌─────────────────────────────────────────────────────────────┐
│                        前端（未來實現）                        │
│        API 仪表板 / Webhook 管理 / 報表查看             │
└──────────────────────┬──────────────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        │                             │
┌───────▼──────────┐      ┌──────────▼────────┐
│   simpleec-api    │      │  simpleec-gateway │
│  (REST 端點)      │      │  (Webhook 入口)   │
│                   │      │                   │
│ ├─ GET /orders   │      │ ├─ POST /webhooks │
│ ├─ POST /ship    │      │ └─ Webhook 路由   │
│ └─ ...           │      │                   │
└───────┬──────────┘      └──────────┬────────┘
        │                            │
        │   (REST / Webhook)         │
        │   ↓                        │
        └────────────┬───────────────┘
                     │
        ┌────────────▼────────────┐
        │  Kafka 消息隊列系統      │
        │   (26 個 Topics)        │
        │                         │
        │ ┌─────────────────────┐ │
        │ │ scheduler           │ │
        │ │ {platform}.slow (×10)
        │ │ {platform}.fast (×10)
        │ │ order.process       │ │
        │ │ return.process      │ │
        │ │ task.backend        │ │
        │ │ task.failed/dlt     │ │
        │ └─────────────────────┘ │
        └──────────┬──────────────┘
                   │
    ┌──────────────┼──────────────┬────────────────┐
    │              │              │                │
┌───▼────┐  ┌──────▼──────┐  ┌────▼──────┐  ┌────▼──────┐
│ Channel│  │   Order     │  │ Scheduler │  │  Backend  │
│  Jobs  │  │   Jobs      │  │   Jobs    │  │   Jobs    │
│        │  │             │  │           │  │           │
│ ├─Mode │  │ ├─ORDER_   │  │ ├─Heartbeat
│ │  A   │  │ │  UPSERT  │  │ │ Job      │  │ ├─Reports │
│ │  (2) │  │ ├─RETURN_  │  │ ├─Scheduler
│ │      │  │ │  UPSERT  │  │ │ Consumer │  │ ├─Sync    │
│ └─Mode │  │ └─...      │  │ └─...     │  │ │ Product  │
│ │  B   │  │             │  │           │  │ ├─Sync    │
│ │  (5) │  │             │  │           │  │ │ Pack     │
│ └─────┘  └─────────────┘  └───────────┘  │ └─...     │
│            (Layer 2)       (Layer 1)      │            │
└──────────────────────────────────────────┴────────────┘
          (Layer 3 Job Services)

        ┌─────────────────────────┐
        │   數據存儲層              │
        │                         │
        │ ├─ PostgreSQL 16        │
        │ │  ├─ orders           │
        │ │  ├─ products         │
        │ │  ├─ sell_packs       │
        │ │  ├─ returns          │
        │ │  ├─ reports          │
        │ │  ├─ dlt_records      │
        │ │  └─ ...              │
        │                         │
        │ ├─ Redis 7              │
        │ │  ├─ order:hash:...   │
        │ │  ├─ return:hash:...  │
        │ │  ├─ cache:...        │
        │ │  └─ ...              │
        └─────────────────────────┘
```

---

## 第五部分：實現時間表與里程碑

### 5.1 按週的交付物

| 週 | 焦點 | Handler 數 | 交付物 |
|----|------|----------|--------|
| **Week 1** | 基礎 + Shopee 驗證 | 2 (Heartbeat + Scheduler) | 驗證清單、文檔整理 |
| **Week 3-4** | Mode A（Shopify + easystore） | 16 | 訂單入庫、測試 |
| **Week 5-6** | 後端任務 | 8 (Reports + Sync) | 報表、商品同步 |
| **Week 8** | Order Job + Shopee Mode B | 13 | 完整訂單流程 |
| **Week 9-10** | 其他 Mode B + 錯誤恢復 | 40+ | 退貨、重試、DLT |
| **Week 11** | 生產準備 | 總 81+ | 完整測試、部署 |

### 5.2 關鍵里程碑

| 日期 | 里程碑 | 條件 |
|------|--------|------|
| **Feb 28** | Shopee Round 2 完成 | API 文檔整理完畢 |
| **Mar 9** | Mode A 完成 | Shopify + easystore 訂單入庫可運作 |
| **Apr 7** | Hard Tier 開始 | Order Job 和 Shopee ChannelJob 架構完成 |
| **May 2** | MVP 完成 | 所有平台訂單、退貨、報表可運作 |
| **May 16** | 生產部署 | 壓力測試通過、監控就位 |

---

## 第六部分：核心設計決策速查

### 6.1 為什麼 Channel Job 沒有數據庫存取？

✅ **原因**：
- 職責明確（拉、計算 hash、發送）
- 去重邏輯集中在 Redis（快速）
- 失敗隔離（Channel Job 失敗不影響訂單入庫）

❌ **不做什麼**：
- 不查詢 Product 是否存在
- 不查詢 SKU 映射
- 不執行 UPDATE（OrderUpsertHandler 負責）

---

### 6.2 兩層去重為什麼必要？

| 層 | 誰做 | 何時 | 目的 |
|----|------|------|------|
| **L1: Redis** | Channel Job | 拉取時 | 避免發送重複 ORDER_UPSERT，節省消息 |
| **L2: DB** | OrderUpsertHandler | 入庫時 | 並發安全、支持訂單更新（hash 變化時 UPDATE） |

範例：
```
第 1 次拉：hash A → Redis 無 → 發送 → DB 存入 + Redis 存入
第 2 次拉（未變）：hash A → Redis 有 A → 跳過 ✓
第 2 次拉（已變）：hash B → Redis 有 A（≠B） → 發送 → DB UPDATE + Redis 更新
```

---

### 6.3 Mode A vs Mode B 決策樹

```
Question 1: 列表 API 有 items[] 欄位？
  YES → Question 2
  NO  → Mode B ✓

Question 2: 列表 API 有 shippingInfo（地址、電話等）？
  YES → Question 3
  NO  → Mode B ✓

Question 3: 列表 API 有 buyerInfo（買家信息）？
  YES → Mode A ✓
  NO  → Mode B ✓
```

---

### 6.4 買家 PII 加密

```
buyerName, buyerPhone, buyerEmail
  ├─ 存儲：AES-256-GCM 密文
  ├─ 查詢：解密後比對（DB 無法直接搜尋）
  ├─ 列表：自動脫敏
  └─ API 返回：根據權限決定是否解密

shippingAddress（同上）
```

---

## 第七部分：檢查清單

實現 SimpleEC OMS 完整系統時，確保包含：

### Handler 實現
- [ ] 2 × Heartbeat + Scheduler Handlers
- [ ] 16 × Mode A Handlers（Shopify + easystore）
- [ ] 50 × Mode B Handlers（Shopee + 4 個平台）
- [ ] 3 × Order Job Handlers（Order/Return/Status）
- [ ] 8 × Backend Task Handlers（Reports + Sync）
- [ ] 2 × Error Handlers（Retry + DLT）

### Kafka 設置
- [ ] 26 個 Topics 建立（含分區配置）
- [ ] 各 Topic 的 replication factor = 3
- [ ] retention policy 設置（Log retention hours）

### 數據庫表
- [ ] orders, order_items
- [ ] returns, return_items
- [ ] products, sell_packs
- [ ] reports (訂單、庫存、銷售、退貨)
- [ ] dlt_records (死信隊列)

### API 端點
- [ ] 至少 20 個 REST 端點（訂單、商品、退貨、報表）
- [ ] Webhook 路由（各通路）
- [ ] 錯誤處理與驗證

### 測試
- [ ] 單元測試 (> 85%)
- [ ] 集成測試（訂單完整流程）
- [ ] 壓力測試（50 orders/min @ Mode B）

### 監控與可觀測性
- [ ] OpenTelemetry trace 埋點
- [ ] Prometheus metrics（請求數、延遲等）
- [ ] Loki 日誌聚合
- [ ] 告警規則（DLT/Failed message）

---

## ✅ 快速導航

- **開始開發？** → 閱讀 02-MODULE-DESIGN.md + 04-INTERFACE-CONTRACTS.md
- **需要 Kafka 細節？** → 閱讀 01-KAFKA-TOPOLOGY.md + 本文第二部分
- **需要時間規劃？** → 閱讀 03-IMPLEMENTATION-ORDER.md + 本文第五部分
- **需要 Handler 對應？** → 本文第一部分
- **需要 API 設計？** → 本文第三部分

---

**文檔終止。此為完整設計綜合參考。所有設計細節均可在相應文檔中找到。**
