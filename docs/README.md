# SimpleEC OMS 文檔指南 v2.3

本目錄包含 SimpleEC OMS 多通路訂單管理系統的完整設計文檔。
+ **上層**：[PLAN/](../PLAN/) 資料夾（架構規劃與實施指南）
+ **本層**：docs/ 資料夾（詳細設計與執行參考）

> **最後更新**：2026-02-20
> **版本**：2.3（PLAN/ 資料夾新增，包含 5 份架構規劃文檔）

---

## 🎯 三層架構

```
┌─────────────────────────────────────┐
│   第一層：設計契約（必讀）          │
│  (CORE_CONTRACTS + EVENT_SAMPLES)    │
└────────────┬────────────────────────┘
             │
┌────────────▼──────────────────────────┐
│   第二層：執行層設計（實施指南）      │
│  (CHANNEL_GUIDE + HANDLER_REGISTRY)   │
└────────────┬───────────────────────────┘
             │
┌────────────▼───────────────────────────┐
│   第三層：支撐設施（部署 & 維運）      │
│  (DOCKER + OPERATIONS + SCHEMA)        │
└────────────────────────────────────────┘
```

---

## 📚 文檔導覽

### ⭐ **第零層：架構規劃與實施指南 (PLAN)**

#### **[../PLAN/](../PLAN/)** — 從零開始的系統設計藍圖

新開發者 / 架構師必讀。5 份設計文檔涵蓋從概念到上線的完整規劃：

| 文檔 | 用途 | 讀者 |
|------|------|------|
| **[00-OVERVIEW.md](../PLAN/00-OVERVIEW.md)** | 5 分鐘快速理解系統定位、設計原則、11 個模組、16 個 Topic | 所有新人 |
| **[01-KAFKA-TOPOLOGY.md](../PLAN/01-KAFKA-TOPOLOGY.md)** | Producer/Consumer/Topic 完整拓撲圖、Consumer Group 分配、故障排查 | 架構師、後端 |
| **[02-MODULE-DESIGN.md](../PLAN/02-MODULE-DESIGN.md)** | 11 個 Gradle 模組的詳細設計、職責邊界、依賴關係、關鍵類別 | 後端開發 |
| **[03-IMPLEMENTATION-ORDER.md](../PLAN/03-IMPLEMENTATION-ORDER.md)** | 5 個開發階段 (Phase I-V)、4-5 個月交付計劃、里程碑與測試計劃 | PM、架構師 |
| **[04-INTERFACE-CONTRACTS.md](../PLAN/04-INTERFACE-CONTRACTS.md)** | 9 個 Consumer 介面定義、Platform Adapter 介面、Kafka 訊息 Schema、異常處理 | 後端開發 |

**推薦閱讀順序**：
1. 所有新人：PLAN/00-OVERVIEW.md（5 分鐘）
2. 架構師：PLAN/01-KAFKA-TOPOLOGY.md → 02-MODULE-DESIGN.md
3. 後端開發：PLAN/02-MODULE-DESIGN.md → 04-INTERFACE-CONTRACTS.md
4. 專案經理：PLAN/03-IMPLEMENTATION-ORDER.md

---

### 🔴 **第一層：設計契約（必讀所有）**

#### 1. **[CORE_CONTRACTS.md](CORE_CONTRACTS.md)** ⭐⭐⭐
   最重要的文檔。定義整個系統的基礎：
   - 16 個 Kafka Topic（10 個 Channel + 6 個 Business）
   - 統一的 Header/Body 訊息結構
   - 7 個 TaskType 分類（訂單、退貨、商品、錯誤、死信等）
   - Channel Job 數據轉換原則
   - 冪等性保證設計

   **何時讀**：開發前必讀 | **讀完後**：了解系統骨架

#### 2. **[EVENT_SAMPLES.md](EVENT_SAMPLES.md)** ⭐⭐⭐
   每個 Topic 的具體 JSON 訊息範例：
   - scheduler 的 Heartbeat 脈搏示例（時間源）
   - {platform}.fast/slow 的 TaskType 操作範例
   - order.process/return.process 的完整訊息格式
   - task.backend/task.frontend 的處理樣本
   - TaskType 路由對應表

   **何時讀**：開發、測試、整合時參考 | **讀完後**：知道每個訊息長什麼樣

#### 3. **[QUEUE_CONSUMER_DESIGN.md](QUEUE_CONSUMER_DESIGN.md)** ⭐⭐
   16 個 Consumer Group 的完整設計：
   - 10 個 Channel Consumer Groups（5 平台 × fast/slow 分流）
   - 6 個 Business Consumer Groups（訂單/退貨/後端/錯誤/死信）
   - 每個 Consumer 的詳細行為邏輯與並發設定
   - **Heartbeat + Scheduler 架構**（時間源 + 決策層）
   - **Mode B 平台的 FETCH_ORDER_DETAIL 流程**（動態決定是否需要詳情 API）
   - 監控指標與告警規則

   **何時讀**：理解數據流向時讀 | **讀完後**：知道訊息如何被處理

#### 4. **[DATA_FLOW_MAPPING.md](DATA_FLOW_MAPPING.md)** ⭐⭐
   訂單數據完整生命週期（新增 Mode A/B 區分）：
   - §0: Mode A/B 平台分類與區別
   - §1: Mode A/B 訂單流程圖與 JavaScript 實作範例
   - §2: Mode A/B 退貨流程圖與範例
   - 完整的 orderData 統一結構定義
   - Shopee/Shopify 具體實作對照

   **何時讀**：追蹤訂單數據流、理解 Mode A/B 差異時 | **讀完後**：能快速定位數據問題

---

### 🟡 **第二層：執行層設計（根據角色選讀）**

#### 5. **[CHANNEL_IMPLEMENTATION_GUIDE.md](CHANNEL_IMPLEMENTATION_GUIDE.md)** ⭐⭐
   Channel Job 開發者的完整指南：
   - **§2.0: Mode A/B 架構模式** — 決定平台需要幾個 Handler
   - Channel Job = 數據適配層（Shopee/Momo/Yahoo... → OMS 統一格式）
   - Adapter 實作模式與 FETCH_ORDER_DETAIL 的重要性
   - 分頁、Rate Limit、重試策略的代碼範例
   - Docker Compose 配置（獨立 fast/slow Consumer Groups）
   - 整合測試與監控

   **何時讀**：開發 Channel Job 時 | **讀完後**：能寫出規範的 Adapter

#### 6. **[HANDLER_REGISTRY.md](HANDLER_REGISTRY.md)** ⭐
   Process Job 與 Backend Job 開發者的指南：
   - 所有 Handler 的註冊與路由
   - PROCESS_ORDER → DB INSERT/UPDATE 邏輯
   - PROCESS_RETURN 的去重判定
   - task.backend 的各類操作

   **何時讀**：開發 Handler 時 | **讀完後**：能實作各類 Handler

#### 7. **[REDIS_DEDUPLICATION.md](REDIS_DEDUPLICATION.md)** ⭐
   訂單去重的核心設計：
   - Key 格式：`order:hash:{merchantId}:{channelId}:{orderId}`
   - **特殊字元完整保留**（如 `#`, `@`）— 不做任何轉換
   - Channel Job = 讀 Redis（判斷是否變更）
   - Process Job = 寫 Redis（確保同步）
   - 被動恢復策略（無全量同步）

   **何時讀**：實施去重邏輯時 | **讀完後**：明白訂單去重如何保證冪等

---

### 🟢 **第三層：支撐設施（部署 & 維運）**

#### 8. **[DOCKER_GUIDE.md](DOCKER_GUIDE.md)**
   生產環境部署參考：
   - 所有服務的 Docker 容器配置
   - Kafka、PostgreSQL、Redis 的網路設定
   - 數據卷與環境變數

   **何時讀**：準備部署時 | **讀完後**：能一鍵啟動整個系統

#### 9. **[OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md)**
   運維人員的操作手冊：
   - Kafka Topic 監控與 Consumer Lag 追蹤
   - 常見故障排查流程
   - 告警設定與應急響應

   **何時讀**：系統上線後 | **讀完後**：能獨立排查運維問題

#### 10. **[SCHEMA.md](SCHEMA.md)**
   數據庫 DDL 與欄位定義：
   - 19 張表的完整結構
   - 索引與約束
   - 分區策略

   **何時讀**：開發 DB 操作或優化查詢時 | **讀完後**：了解數據存儲結構

#### 11. **[STATISTICS_DESIGN.md](STATISTICS_DESIGN.md)**
   報表與統計設計：
   - 業務視角 vs 財務視角的統計口徑
   - 退款與退貨的處理規則
   - daily_statistics 表的聚合邏輯

   **何時讀**：開發統計功能時 | **讀完後**：能正確計算各類報表

---

### 🏛️ **系統架構全景（推薦先讀）**

#### **[ARCHITECTURE_OVERVIEW.md](ARCHITECTURE_OVERVIEW.md)** ⭐⭐⭐ 新增
   統整所有文檔的系統整體架構：
   - 5 分鐘快速理解核心概念
   - Mode A/B 訂單流程完整對比（端到端）
   - 11 份文檔地圖與依賴關係
   - 角色與職責對應（新人 / 架構師 / 運維）
   - Heartbeat + Scheduler 時間驅動邏輯
   - Redis 二層去重機制解釋
   - 常見問題 + 推薦學習路徑

   **何時讀**：**所有新人上手首讀** | **讀完後**：能快速定位各文檔用途

---

### 📂 **詳細流程設計（進階參考）**

| 文檔 | 所在位置 | 用途 |
|------|---------|------|
| FETCH_STRATEGY.md | event-flows/ | Scheduler 怎樣決定訂單時間窗口 |
| FETCH_ORDERS.md | event-flows/ | 訂單拉取的端到端流程 |
| FETCH_PRODUCTS.md | event-flows/ | 商品同步的兩階段 API |
| DB_ENTITY_GAPS.md | event-flows/ | Entity ↔ Schema 對應檢查表 |

---

## 🚀 快速查找（按角色）

### 👨‍💻 **我是 Channel Job 開發者**
```
0️⃣ ARCHITECTURE_OVERVIEW.md (§2.3) — 角色視圖：Channel Job 開發者路線圖
1️⃣ CORE_CONTRACTS.md (4.0-4.1 章) — 了解 Mode A/B 和 Channel Job 數據轉換責任
2️⃣ EVENT_SAMPLES.md (Channel Topics) — 看 FETCH_ORDERS/SHIP_ORDER 樣本
3️⃣ CHANNEL_IMPLEMENTATION_GUIDE.md (§2.0 開始) — Mode A/B 架構決策 + 完整開發指南
4️⃣ DATA_FLOW_MAPPING.md (§1 開始) — Mode A/B 訂單流程圖與實作範例
5️⃣ event-flows/FETCH_STRATEGY.md — 深入理解拉單策略
6️⃣ DOCKER_GUIDE.md — 部署獨立的 fast/slow Consumer
```

### 🔧 **我是 Handler 開發者**
```
0️⃣ ARCHITECTURE_OVERVIEW.md (§2.4) — 角色視圖：Handler 開發者路線圖
1️⃣ CORE_CONTRACTS.md (4.1-4.3 章) — 了解各 TaskType 職責與 Mode A/B
2️⃣ EVENT_SAMPLES.md (Business Topics) — 看 PROCESS_ORDER/SYNC_PRODUCT 樣本
3️⃣ QUEUE_CONSUMER_DESIGN.md (Channel Job - Slow Consumer) — 了解 Mode B 的 FETCH_ORDER_DETAIL
4️⃣ HANDLER_REGISTRY.md — Handler 實作框架
5️⃣ REDIS_DEDUPLICATION.md — 訂單去重邏輯
6️⃣ SCHEMA.md — 了解 DB 結構
```

### 🏗️ **我要從頭理解系統**
```
0️⃣ ARCHITECTURE_OVERVIEW.md (全讀) — 系統整體架構 + 數據流 + 11 份文檔地圖
1️⃣ CORE_CONTRACTS.md (全讀) — 契約、Topic、TaskType、Mode A/B
2️⃣ QUEUE_CONSUMER_DESIGN.md (全讀) — Consumer 如何處理、Mode B FETCH_ORDER_DETAIL
3️⃣ DATA_FLOW_MAPPING.md (§0-§1) — Mode A/B 訂單流程、完整 orderData 結構
4️⃣ EVENT_SAMPLES.md (快速掃) — 看訊息格式
5️⃣ CHANNEL_IMPLEMENTATION_GUIDE.md (§2.0 開始) — Channel 職責與 Mode A/B 決策
```

### 🚀 **我要部署到生產**
```
0️⃣ ARCHITECTURE_OVERVIEW.md (§2.5) — 角色視圖：DevOps 檢查清單
1️⃣ DOCKER_GUIDE.md — 容器配置與啟動
2️⃣ OPERATIONS_RUNBOOK.md — 監控與故障排查
3️⃣ SCHEMA.md — DB 初始化確認
4️⃣ CHANNEL_IMPLEMENTATION_GUIDE.md (10.2) — Consumer Group 配置驗證
```

### 🐛 **我要排查運維問題**
```
0️⃣ ARCHITECTURE_OVERVIEW.md (§2.5) — 整體架構快速回憶
1️⃣ OPERATIONS_RUNBOOK.md — 故障排查流程
2️⃣ QUEUE_CONSUMER_DESIGN.md (監控指標) — Consumer Lag 檢查
3️⃣ DATA_FLOW_MAPPING.md — 追蹤具體訊息流向
4️⃣ SCHEMA.md — DB 查詢驗證數據
```

---

## ⚙️ 核心概念速查

### 訊息結構（所有 Topic 統一）
```json
{
  "header": {
    "taskType": "FETCH_ORDERS",      // 路由關鍵字
    "merchantId": "M001",             // 商家隔離
    "channelId": "SHOPEE_001",        // 通路實例
    "requestId": "uuid",              // 唯一追蹤
    "timestamp": "2026-02-19T...",   // ISO 8601
    "source": "scheduler",            // 來源識別
    "version": 1                      // 版本控制
  },
  "body": {
    // TaskType 特定資料
  }
}
```

### 16 個 Topic（4 層級別）
- **Channel Topics** (10): `{platform}.fast/slow` × 5 platforms
- **Order Topic** (1): `order.process` — 訂單 Source of Truth
- **Return Topic** (1): `return.process` — 退貨 Source of Truth
- **Task Topics** (3): `task.backend`, `task.frontend`, `scheduler`
- **Error Topics** (2): `task.failed`, `task.dlt`

### Channel Job 數據轉換原則
```
Shopee/Momo/Yahoo API 格式  (五花八門)
         │
         ↓ [Channel Job 轉換]
         │
    OMS 統一結構  (orderData)
         │
         ↓ [發到 order.process]
         │
    Handler 業務邏輯 (新建/更新/去重)
```

### Redis 去重（冪等性保證）
```
Key: order:hash:{merchantId}:{channelId}:{orderId}

讀寫職責分離：
├─ Channel Job: 讀 Redis → 判斷 hash 變化 → 決定是否送 order.process
└─ Process Job: 入庫成功 → 寫 Redis（7 天 TTL）

特殊字元保留：order:hash:M001:SHOPEE_001:ORD#2026-001
             （不能改成 ORD_2026_001）
```

---

## 📝 文檔維護政策

### 修改文檔時的檢查清單

- [ ] 修改 CORE_CONTRACTS.md？→ 同步 EVENT_SAMPLES.md + QUEUE_CONSUMER_DESIGN.md
- [ ] 修改 EVENT_SAMPLES.md？→ 檢查 CHANNEL_IMPLEMENTATION_GUIDE.md 範例
- [ ] 新增 TaskType？→ 更新 HANDLER_REGISTRY.md + TaskType 路由表
- [ ] 修改 Topic 結構？→ 更新 DATA_FLOW_MAPPING.md
- [ ] 修改 Redis Key？→ 檢查 REDIS_DEDUPLICATION.md 的 key 格式
- [ ] 修改 DB Schema？→ 同步 SCHEMA.md + DATA_FLOW_MAPPING.md

### 文檔提交慣例

```bash
git add docs/
git commit -m "Updated CORE_CONTRACTS & EVENT_SAMPLES for PROCESS_ORDER design"
```

---

## 📊 版本記錄

| 版本 | 日期 | 主要變更 |
|------|------|---------|
| **2.3** | 2026-02-20 | **新增 PLAN/ 資料夾（第零層）** — 5 份架構規劃文檔：00-OVERVIEW、01-KAFKA-TOPOLOGY、02-MODULE-DESIGN、03-IMPLEMENTATION-ORDER、04-INTERFACE-CONTRACTS；包含 Heartbeat+Scheduler、Mode A/B、兩層 Redis 去重、11 模組設計、5 階段實施計劃 |
| **2.2** | 2026-02-20 | **新增 ARCHITECTURE_OVERVIEW.md** 統整全系統；HANDLER_REGISTRY.md Mode A/B 平台分類；所有角色路線圖更新；12 份文檔完全同步 |
| **2.1** | 2026-02-20 | Mode A/B 架構模式新增至 CORE_CONTRACTS + CHANNEL_IMPLEMENTATION_GUIDE + QUEUE_CONSUMER_DESIGN；DATA_FLOW_MAPPING.md 升至第一層；文檔同步一致性確認 |
| **2.0** | 2026-02-19 | 刪除過時文檔（STATUS/ABSTRACT_DESIGN/IMPLEMENTATION_PLAN）；重整文檔層級；強調 Channel Job 數據轉換責任；PROCESS_ORDER 統一設計 |
| 1.0 | 2026-02-09 | 初版：完整事件流設計；Redis 去重；16 個 Consumer Groups；fast/slow 分流 |

---

## 🔗 文檔依賴圖

```
CORE_CONTRACTS.md (基礎)
    │
    ├─→ EVENT_SAMPLES.md (具體範例)
    │       │
    │       └─→ QUEUE_CONSUMER_DESIGN.md (執行邏輯 + Mode B FETCH_ORDER_DETAIL)
    │               │
    │               ├─→ DATA_FLOW_MAPPING.md (Mode A/B 訂單流程)
    │               │
    │               ├─→ CHANNEL_IMPLEMENTATION_GUIDE.md (Mode A/B 架構決策)
    │               │
    │               ├─→ HANDLER_REGISTRY.md
    │               │
    │               └─→ event-flows/* (詳細流程)
    │
    ├─→ REDIS_DEDUPLICATION.md (去重邏輯)
    │
    └─→ SCHEMA.md (資料庫)

部署層：
    ├─→ DOCKER_GUIDE.md
    ├─→ OPERATIONS_RUNBOOK.md
    └─→ STATISTICS_DESIGN.md
```

---

## 🔴 **系統可靠性 & 運維 (NEW - Feb 21)**

### Retention & Cleanup 相關文檔

由於 **Feb 21 磁碟爆炸事件** (Kafka broker crash at 12:36:49)，已實施系統級 retention 及自動清理策略：

| 文檔 | 內容 | 讀者 |
|------|------|------|
| **[RETENTION_CLEANUP_RUNBOOK.md](RETENTION_CLEANUP_RUNBOOK.md)** | 日常運維手冊：監控、故障排除、手動操作 | DevOps / SRE |
| **[DOCKER_LOG_ROTATION_SETUP.md](DOCKER_LOG_ROTATION_SETUP.md)** | Docker daemon.json 部署指南 | DevOps / 基礎設施 |
| **[plans/2026-02-21-system-retention-cleanup-design.md](plans/2026-02-21-system-retention-cleanup-design.md)** | 架構設計與 12 層決策 | 架構師 / Tech Lead |
| **[plans/2026-02-21-system-retention-cleanup-implementation.md](plans/2026-02-21-system-retention-cleanup-implementation.md)** | 實施計劃 12 個任務 (已全數完成) | 開發 / 部署 |

**快速了解**：
- **Kafka**: 1 小時 retention (vs 無限)
- **Prometheus**: 7 天或 5GB (whichever first)
- **Loki**: 7 天 retention
- **Docker 容器日誌**: 100MB × 5 per container (自動輪轉)
- **自動清理**: cron 每 30 分鐘檢查；80% 告警，85% 觸發清理

✅ **已完成**：
- ✓ 13 個 git commits，所有測試通過 (27 unit tests)
- ✓ 清理腳本已部署，cron 已啟用
- ✓ Docker log rotation 配置已就位

---

**有疑問？** 先查 [快速查找](#🚀-快速查找按角色) 對應的閱讀清單，然後按順序讀。