# SimpleEC OMS 系統架構全景圖

> **目的**：統整所有文檔，呈現系統整體架構、數據流、角色分工，幫助新開發者快速理解全貌。
>
> **最後更新**：2026-04-07
> **版本**：2.0（新增 Platform Capabilities + Channel Health 架構）

---

## 🎯 5 分鐘快速理解

### 核心概念（3 層）

```
┌────────────────────────────────────────────────┐
│  Scheduler + Heartbeat（時間源 + 決策層）      │
│  - 每秒一個脈搏，所有決策基於一個一致的時間   │
├────────────────────────────────────────────────┤
│  Channel Job（數據適配層）                     │
│  Mode A: 直接產生完整訂單 orderData            │
│  Mode B: 列表 → 詳情 → 完整 orderData         │
├────────────────────────────────────────────────┤
│  Process Handler（業務邏輯層）                │
│  - 新建/更新訂單（讀寫 DB + Redis 去重）      │
│  - 計算統計報表（每 5 分鐘批量）             │
└────────────────────────────────────────────────┘
```

### Mode A vs Mode B（關鍵區別）

| 面向 | Mode A（Shopify、easystore） | Mode B（Shopee、Momo） |
|------|------|------|
| **列表 API** | ✅ 已含 items、payment、shipping | ❌ 缺少這些欄位 |
| **流程** | `FETCH_ORDERS → PROCESS_ORDER`（1 步） | `FETCH_ORDERS → FETCH_ORDER_DETAIL → PROCESS_ORDER`（2 步） |
| **Handler 數** | 1 個 | 2 個 |
| **API 呼叫** | 1 次/訂單 | 2 次/訂單 |

---

## 📊 文檔地圖（150+ 份文檔）

### 第一層：基礎設計契約（必讀）

```
CORE_CONTRACTS.md ⭐⭐⭐
  ├─ 20 個 Kafka Topic 定義（含 platform fast/slow）
  ├─ 統一的 Header/Body 訊息結構
  ├─ 7 個 TaskType 分類
  └─ Mode A/B 處理方式 (§4.1 新增)
         ↓
EVENT_SAMPLES.md ⭐⭐⭐
  ├─ 每個 Topic 的 JSON 樣本
  └─ 開發/測試時參考的真實數據結構
         ↓
QUEUE_CONSUMER_DESIGN.md ⭐⭐
  ├─ Consumer Group 設計
  ├─ Heartbeat + Scheduler 架構詳解
  └─ Mode B FETCH_ORDER_DETAIL 流程 (§2 更新)
         ↓
DATA_FLOW_MAPPING.md ⭐⭐ (新升至第一層)
  ├─ Mode A/B 訂單完整生命週期
  ├─ 統一的 orderData 結構
  └─ Shopee/Shopify 對比實作
```

### 第二層：執行設計（按角色選讀）

```
CHANNEL_IMPLEMENTATION_GUIDE.md ⭐⭐
  ├─ Mode A/B 架構決策 (§2.0 新增)
  ├─ Adapter 實作模式
  ├─ 分頁、Rate Limit、重試策略
  └─ Docker Compose 配置
         ↓
HANDLER_REGISTRY.md ⭐
  ├─ Handler 註冊與路由
  ├─ Mode A/B 平台分類 (§0 更新)
  ├─ TaskType → Handler 對應表
  └─ Hash 計算範例
         ↓
REDIS_DEDUPLICATION.md ⭐
  ├─ Key 格式與特殊字元保留
  ├─ Channel Job 讀、Process Job 寫 的分工
  └─ 7 天 TTL 被動恢復策略
```

### 第三層：Platform & Channel 管理（新增）

```
PLATFORM_CAPABILITIES_GUIDE.md ⭐⭐⭐ (新增)
  ├─ capabilities JSONB 設計
  ├─ OAuth 類型設定（shopee_oauth / 無）
  ├─ Token Labels 自訂（token1~token5）
  └─ 平台能力旗標（multiLocation/webhook/asyncInventory）
         ↓
CHANNEL_HEALTH_MONITORING.md ⭐⭐ (新增)
  ├─ 健康檢查機制
  ├─ Redis 快取策略（TTL 10 分鐘）
  ├─ 同步日誌 API
  └─ ChannelHealthOverview 元件
         ↓
SHOPEE_OAUTH_GUIDE.md ⭐⭐
  ├─ Shopee OAuth 2.0 授權流程
  ├─ Token 生命週期管理
  ├─ 分散式鎖設計
  └─ Popup 授權流程
```

### 第四層：支撐設施（部署 & 維運）

```
DOCKER_GUIDE.md
  ├─ 容器配置（Kafka、PostgreSQL、Redis）
  └─ 獨立的 fast/slow Consumer Groups
         ↓
OPERATIONS_RUNBOOK.md
  ├─ Consumer Lag 監控
  ├─ 常見故障排查
  └─ 告警與應急響應
         ↓
SCHEMA.md
  ├─ 20+ 張表的 DDL 定義
  ├─ 索引與分區策略
  └─ Entity ↔ API 對應
         ↓
STATISTICS_DESIGN.md
  ├─ 業務視角 vs 財務視角
  ├─ 退款與退貨規則
  └─ daily_statistics 聚合邏輯
```

---

## 🔄 完整數據流（端到端）

### Mode A 訂單流程（Shopify 示例）

```
時間源（Heartbeat Job 每秒發脈搏）
    ↓ timestamp = 08:00:00
Scheduler Consumer（檢查分鐘位）
    ↓ :00 % 5 == 0 → 派發
{platform}.slow topic
    ↓ FETCH_ORDERS message
ShopifyOrderListHandler（Mode A，單一 Handler）
    ├─ 呼叫 Shopify API（已含完整訊息）
    ├─ 構建 orderData（items、shipping 都有）
    ├─ 計算 Hash（TreeMap 排序）
    ├─ 讀 Redis 第一層檢查（優化，非強制）
    └─ 發送 PROCESS_ORDER
        ↓
order.process topic
    ↓ ORDER_UPSERT message（已含完整 orderData）
OrderUpsertHandler（Process Handler，通用）
    ├─ 再次檢查 Redis（並發安全）
    ├─ 查詢 DB：是否存在該訂單？
    ├─ 是 → UPDATE；否 → INSERT
    ├─ 寫入 Redis（hash + 7天 TTL）
    └─ 觸發後續流程（庫存、統計等）
        ↓
Database + Redis 持久化
```

### Mode B 訂單流程（Shopee 示例）

```
時間源（Heartbeat Job 每秒發脈搏）
    ↓ timestamp = 08:00:00
Scheduler Consumer（檢查分鐘位）
    ↓ :00 % 5 == 0 → 派發
shopee.slow topic
    ↓ FETCH_ORDERS message
ShopeeOrderListHandler（Mode B，第一個 Handler）
    ├─ 呼叫 Shopee API list（不完整：無 items、payment 等）
    ├─ 判斷需要詳情？（金額 > 10000、待出貨、有退貨 等）
    ├─ YES → 發送 FETCH_ORDER_DETAIL 到 shopee.slow
    └─ list 級訊息暫存或丟棄（因為不完整）
        ↓
shopee.slow topic
    ↓ FETCH_ORDER_DETAIL message（per order）
ShopeeOrderDetailHandler（Mode B，第二個 Handler）
    ├─ 逐筆呼叫 Shopee detail API
    ├─ 取得完整資訊（items、payment、shipping）
    ├─ 構建完整 orderData
    ├─ 計算 Hash
    ├─ 讀 Redis 第一層檢查
    └─ 發送 PROCESS_ORDER
        ↓
order.process topic
    ↓ ORDER_UPSERT message（現在 orderData 完整，與 Mode A 格式相同）
OrderUpsertHandler（Process Handler，通用）
    ├─ 再次檢查 Redis
    ├─ 查詢 DB → INSERT/UPDATE
    ├─ 寫入 Redis
    └─ 觸發後續流程
        ↓
Database + Redis 持久化
```

---

## 💼 角色與職責對應

### 👨‍💻 **Channel Job 開發者**
「我要實作 Shopee/Momo/Yahoo 的數據適配」

```
閱讀順序：
1️⃣ CORE_CONTRACTS.md §4.1 — Mode A/B 區別
2️⃣ CHANNEL_IMPLEMENTATION_GUIDE.md §2.0 — 如何判定平台 Mode
3️⃣ DATA_FLOW_MAPPING.md §1 — 訂單流程圖
4️⃣ HANDLER_REGISTRY.md §0 — Mode A/B Handler 架構
5️⃣ 具體開發：CHANNEL_IMPLEMENTATION_GUIDE.md §2.1+ (Adapter 實作)
6️⃣ 測試：EVENT_SAMPLES.md 驗證訊息格式

核心技能：
- 理解 Mode A：直接列表 → PROCESS_ORDER
- 理解 Mode B：列表 → [判斷] → 詳情 → PROCESS_ORDER
- 實作 Adapter：Pagination、Rate Limit、重試邏輯
- 組織 orderData：統一結構，特殊字元保留
```

### 🔧 **Handler / Process 開發者**
「我要實作 PROCESS_ORDER / SYNC_PRODUCT / SYNC_PACK 邏輯」

```
閱讀順序：
1️⃣ CORE_CONTRACTS.md §4.1-4.3 — TaskType 職責
2️⃣ QUEUE_CONSUMER_DESIGN.md §4 — 各 Handler 的消費邏輯
3️⃣ HANDLER_REGISTRY.md §2-3 — Handler 架構 + 實作範例
4️⃣ REDIS_DEDUPLICATION.md — Hash 計算與去重邏輯
5️⃣ SCHEMA.md — 了解資料庫結構

核心技能：
- INSERT/UPDATE 訂單（根據 channelOrderId 存在性）
- 計算 Hash（TreeMap 排序，確保一致性）
- 讀寫 Redis（第二層並發安全去重）
- 觸發下游流程（庫存、統計等）
```

### 🏗️ **系統架構師/新人理解者**
「我要從頭理解系統全貌」

```
閱讀順序（深度優先）：
1️⃣ **本文檔**（ARCHITECTURE_OVERVIEW.md）— 整體架構
2️⃣ CORE_CONTRACTS.md（全讀）— 契約基礎
3️⃣ QUEUE_CONSUMER_DESIGN.md（全讀）— 消費者設計
4️⃣ DATA_FLOW_MAPPING.md §0-1 — Mode A/B 流程
5️⃣ CHANNEL_IMPLEMENTATION_GUIDE.md §2.0 — 決策邏輯
6️⃣ HANDLER_REGISTRY.md §0 — 架構對應
7️⃣ EVENT_SAMPLES.md（掃過）— 看訊息格式

學習重點：
- Heartbeat + Scheduler 如何驅動所有任務
- Mode A/B 如何影響 Channel Job Handler 數量
- Redis 二層去重（Layer 1 讀，Layer 2 寫）
- orderData 統一結構如何簡化 Process Handler
```

### 🚀 **DevOps / 運維**
「我要部署系統到生產環境」

```
閱讀順序：
1️⃣ DOCKER_GUIDE.md — 容器配置 + 啟動
2️⃣ SCHEMA.md — 資料庫初始化
3️⃣ OPERATIONS_RUNBOOK.md — 監控 + 故障排查
4️⃣ QUEUE_CONSUMER_DESIGN.md（部分）— Consumer Group 配置確認

部署檢查清單：
- [ ] Kafka Topic 14 個已建立
- [ ] Consumer Groups 16 個已建立（fast/slow 分流）
- [ ] PostgreSQL 19 張表已建立
- [ ] Redis 連線正常（TTL 設定 7 天）
- [ ] 環境變數正確（API Key、Rate Limit 等）
- [ ] 監控告警已開啟（Consumer Lag、錯誤率等）
```

---

## 🔀 數據轉換流（Shopee 與 Shopify 對比）

### Shopee 訂單（Mode B）

```json
[Shopee API List Response]
{
  "shop_order_id": 123456,
  "order_status": "READY_TO_SHIP",
  "total_amount": 3000,
  "items": [
    { "item_id": "abc" }  // ⚠️ 不完整，缺 sku、price
  ]
}
         ↓ [Mode B: 需要 FETCH_ORDER_DETAIL]
         ↓
[Shopee API Detail Response]
{
  "shop_order_id": 123456,
  "items": [
    {
      "item_id": "abc",
      "sku": "SKU-123",
      "item_price": 1500,
      "quantity": 2
    }
  ],
  "payment": { "status": "PAID" },
  "shipping": { ... }
}
         ↓ [Channel Job 組織成統一結構]
         ↓
[OMS 統一 orderData]
{
  "orderId": "nanoid_xyz",
  "channelOrderId": "123456",
  "items": [
    {
      "sku": "SKU-123",
      "productId": "prod_abc",
      "quantity": 2,
      "unitPrice": 1500
    }
  ],
  "paymentMethod": "PAID",
  "totalAmount": 3000,
  ...
}
         ↓
[Process Handler 入庫 + Redis 去重]
```

### Shopify 訂單（Mode A）

```json
[Shopify API List Response]
{
  "id": 987654,
  "order_number": "ORD-001",
  "line_items": [
    {
      "sku": "SKU-456",
      "quantity": 1,
      "price": 2000
    }
  ],
  "payment_status": "paid",
  "shipping_address": { ... }
}
         ↓ [Mode A: 無需詳情，直接用]
         ↓
[OMS 統一 orderData]
{
  "orderId": "nanoid_123",
  "channelOrderId": "987654",
  "items": [
    {
      "sku": "SKU-456",
      "productId": "prod_def",
      "quantity": 1,
      "unitPrice": 2000
    }
  ],
  "paymentMethod": "paid",
  "totalAmount": 2000,
  ...
}
         ↓
[Process Handler 入庫 + Redis 去重]
```

**關鍵觀察**：
- Shopee（Mode B）需要 2 次 API 呼叫才能拿到完整 orderData
- Shopify（Mode A）1 次 API 呼叫就有完整 orderData
- 兩者最終的 orderData 格式完全相同 → 同一個 OrderUpsertHandler 處理

---

## 🎪 Scheduler 驅動規則（時間邏輯）

### Heartbeat 時間基準

```
Heartbeat Job（中央時間源）
  每秒發一個脈搏到 scheduler topic
  帶著當前 server timestamp
         ↓
Scheduler Consumer（分鐘位判斷）
  根據 timestamp 檢查「分鐘」
  決定派發哪些任務
         ↓
時間驅動任務派發
```

### 分鐘位判斷規則（% 5 % 10）

| 分鐘位 | 派發目標 | Handler | 用途 |
|--------|---------|--------|------|
| :00, :05, :10... (% 5 == 0) | {platform}.slow | Channel Job | FETCH_ORDERS, FETCH_RETURNS |
| :01, :06, :11... (% 5 == 1) | task.backend | Backend Handler | 訂單報表（批量） |
| :02, :07, :12... (% 5 == 2) | task.backend | Backend Handler | 庫存報表（批量） |
| :03, :08, :13... (% 5 == 3) | task.backend | Backend Handler | 銷售報表（批量） |
| :04, :09, :14... (% 5 == 4) | task.backend | Backend Handler | 退貨報表（批量） |
| :05, :15, :25... (% 10 == 5) | task.backend | Backend Handler | Kafka 健康檢查 |
| :00, :30 | task.backend | Backend Handler | 日報生成 |

---

## 🔐 Redis 去重機制（兩層設計）

### 第一層：Channel Job 讀（優化層）

```
Channel Job（ShopeeOrderDetailHandler）
    ↓ [計算 hash]
    ↓ [讀 Redis: order:hash:M001:SHOPEE_001:123456]
    ├─ Redis HIT（hash 相同）→ [放棄發送，已處理]
    └─ Redis MISS 或不同 → [發送 PROCESS_ORDER]
```

**用途**：優化，避免通路 API 無謂的訊息發送

### 第二層：Process Handler 寫（安全層）

```
OrderUpsertHandler（Process Handler）
    ↓ [再次檢查 Redis: order:hash:M001:SHOPEE_001:123456]
    ├─ Redis HIT（hash 相同）→ [跳過 INSERT/UPDATE]
    └─ Redis MISS 或不同 → [執行 INSERT/UPDATE]
    ↓ [寫 Redis: order:hash = newHash, TTL 7天]
```

**用途**：並發安全，確保即使 Kafka 訊息重複也能冪等

---

## 🌳 文檔依賴關係樹

```
ARCHITECTURE_OVERVIEW.md（本文）← 開始點

   ↓ 想深入了解契約？

CORE_CONTRACTS.md
  ├─ QUEUE_CONSUMER_DESIGN.md
  ├─ EVENT_SAMPLES.md
  └─ DATA_FLOW_MAPPING.md

   ↓ 想開發 Channel Job？

CHANNEL_IMPLEMENTATION_GUIDE.md
  ├─ docs/rules/tech/platform-api.md
  └─ docs/rules/tech/kafka-envelope.md

   ↓ 想了解 Platform Capabilities？

PLATFORM_CAPABILITIES_GUIDE.md (新增)
  ├─ docs/rules/tech/capabilities-model.md
  └─ docs/7-IMPLEMENTATION/SHOPEE_OAUTH_GUIDE.md

   ↓ 想了解 Channel Health？

CHANNEL_HEALTH_MONITORING.md (新增)
  ├─ HealthCheckService.java
  └─ ChannelHealthOverview.vue

   ↓ 想開發 Handler？

HANDLER_REGISTRY.md → REDIS_DEDUPLICATION.md

   ↓ 想了解 DB？

SCHEMA.md

   ↓ 想部署？

DOCKER_GUIDE.md → OPERATIONS_RUNBOOK.md

   ↓ 想理解報表邏輯？

STATISTICS_DESIGN.md
```

---

## ⚙️ 常見問題（FAQ）

### Q: 為什麼 Mode A 和 Mode B 要分開處理？

**A**: 因為 API 能力不同：
- Mode A（Shopify）：列表 API 一次就有完整訊息，效率高
- Mode B（Shopee）：列表 API 需補充詳情，但如果都用詳情 API 會浪費（對 Mode A 平台會多呼叫）

分開實作可以各自最優化。

### Q: orderData 為什麼要轉成統一結構？

**A**:
1. **簡化 Process Handler**：同一個 OrderUpsertHandler 處理所有通路
2. **易於維護**：增加新通路時只需新增 Channel Job Adapter，不需改 Handler
3. **一致性**：避免 Handler 處理 Shopee、Shopify 等不同格式的複雜邏輯

### Q: Redis hash 7 天是怎樣設定的？

**A**:
- 訂單完成週期通常 5-7 天（下單 → 出貨 → 送達 → 簽收）
- 超過 7 天基本不會再更新
- 被動恢復：如果超期訂單再收到，Redis miss 時會重新入庫

### Q: Channel Job 何時發 FETCH_ORDER_DETAIL？

**A**: Mode B 平台的 Channel Job 決定，常見規則：
- 訂單金額 > 10000 元（高價訂單需確認）
- 狀態 = 待出貨（需要物流詳情）
- 有退貨標記（需要完整訂單才能處理退貨）

規則由各平台自己定義。

---

## 📚 推薦學習路徑

### 新人上手（1-2 週）

```
Day 1-2:  CORE_CONTRACTS.md（全讀）
Day 3:    QUEUE_CONSUMER_DESIGN.md（全讀）
Day 4:    DATA_FLOW_MAPPING.md §0-1（了解 Mode A/B）
Day 5:    CHANNEL_IMPLEMENTATION_GUIDE.md §2.0-2.1（了解 Adapter 概念）
Day 6:    HANDLER_REGISTRY.md §0-1（了解 Handler 架構）
Day 7:    挑一個平台（如 Shopee），完整看一遍流程
Day 8-10: 實作簡單 Handler（SYNC_PRODUCT 或小 TaskType）
```

### 系統架構師（2-3 天深度）

```
Day 1: 本文檔 + CORE_CONTRACTS.md + QUEUE_CONSUMER_DESIGN.md（全讀）
Day 2:
  - CHANNEL_IMPLEMENTATION_GUIDE.md（全讀）
  - HANDLER_REGISTRY.md（全讀）
  - REDIS_DEDUPLICATION.md（全讀）
Day 3:
  - DATA_FLOW_MAPPING.md（全讀）
  - SCHEMA.md（快速掃）
  - STATISTICS_DESIGN.md（快速掃）
```

### 運維 / DevOps（1 天）

```
Morning:  DOCKER_GUIDE.md（部署）
Afternoon: OPERATIONS_RUNBOOK.md（監控 + 故障排查）
```

---

## 🎓 核心學習點（檢查清單）

完成以下，代表已掌握系統核心：

- [ ] 理解 Heartbeat + Scheduler 如何驅動所有任務
- [ ] 分清 Mode A（直接） vs Mode B（列表+詳情）
- [ ] 能畫出完整的訂單流程圖（Scheduler → API → Message → DB）
- [ ] 理解 Redis 二層去重（Layer 1 讀優化，Layer 2 寫安全）
- [ ] 知道為什麼 orderData 要統一格式
- [ ] 能解釋一個新通路如何被加入系統（決定 Mode → 實作 Adapter → 加 Handler）
- [ ] 理解分鐘位判斷邏輯（% 5 == 0, 1, 2... 派發不同任務）

---

## 🔗 外部參考

- [iDempiere REST API Pattern](./idempiere-rest-api.md)（在 idempiere-module-ui 項目）
- [Kafka 最佳實踐](https://kafka.apache.org/documentation/)
- [Redis 鍵設計指南](https://redis.io/commands)

---

**本文檔最後目標**：讓任何新人讀完後，能快速定位到需要深入的部分，而不會迷失在 11 份文檔的細節中。
