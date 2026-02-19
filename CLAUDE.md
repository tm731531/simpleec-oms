# SimpleEC OMS — Claude Session Guide

> 多平台電商訂單管理系統。Spring Boot 3.5 + Kafka + PostgreSQL + Redis。
> 整合 Momo / Shopee / Yahoo / PChome / Cyberbiz，統一管理商品、訂單、出貨與庫存。

---

## 🔥 最新架構更新 (2026-02-19)

### 統一訊息結構 (Header/Body)
所有 Kafka 訊息採用統一結構，詳見 `docs/CORE_CONTRACTS.md`：
```json
{
  "header": {
    "taskType": "路由關鍵",
    "merchantId": "商家ID",
    "platformId": "通路編號 (shopee/momo/yahoo/pchome/cyberbiz/easystore)",
    "channelId": "通路實例",
    "requestId": "追蹤ID",
    "timestamp": "時間戳 (ISO-8601)",
    "source": "來源 (scheduler/api/webhook/channel_job)",
    "version": 1,
    "isRollback": false  // 回補訂單標籤
  },
  "body": {
    // TaskType 特定資料
  }
}
```

### Channel Job 職責界定：數據適配層 (Data Adapter Layer)
- **不只是 API 溝通** — 是數據格式轉換層
- **將 5 種不同的通路 API 格式** 轉換為統一 OMS 結構
- **自主決策**：
  - 時間窗口（1h, 3d, 5d, 7d 等）是 Channel Job 內部邏輯，不在 Queue 裡
  - 是否需要 detail/return API（根據通路能力和 rate limit）
  - 分頁策略（Shopee cursor, Momo offset 等）
- **詳見** `docs/CORE_CONTRACTS.md` §4.0 和 `docs/CHANNEL_IMPLEMENTATION_GUIDE.md`

### 核心設計：Scheduler 只傳時間戳，Queue 不含 Range
```
Scheduler: "給我 2026-02-13T10:00:00Z 的訂單"
Queue: { timestamp: "2026-02-13T10:00:00Z" }  ← 只有一個時間點

Channel Job 內部決策:
- Shopee: BASE_TS ~ BASE_TS+1h (新訂單)，BASE_TS-3d ~ BASE_TS (待出貨)...
- Momo: BASE_TS-1h ~ BASE_TS, BASE_TS-3d ~ BASE_TS... (自己決定)
- Yahoo: updated_after=BASE_TS-1d (自己決定時間窗口)
- easystore: BASE_TS-7d ~ BASE_TS (自己決定)

❌ Queue 裡面不要 from/to 或任何 range 參數
✅ Queue 只有 timestamp，Channel Job 自行判斷時間窗口
```

---

## 快速定位

| 你要做什麼 | 讀哪份文件 |
|-----------|-----------|
| **⭐ 理解事件流架構** | `docs/CORE_CONTRACTS.md` — 核心契約定義 |
| **⭐ 看資料流對應** | `docs/DATA_FLOW_MAPPING.md` — API→Message→Entity |
| **⭐ 實作新 Channel** | `docs/CHANNEL_IMPLEMENTATION_GUIDE.md` |
| **⭐ 查 TaskType Handler** | `docs/HANDLER_REGISTRY.md` |
| 看訊息範例 | `docs/EVENT_SAMPLES.md` — 所有 Topic 訊息範例 |
| 理解系統全貌 | `DESIGN_v2.md` §0（現狀）→ §1-§15（完整設計） |
| 看 DB Schema | `docs/SCHEMA.md`（19 張表 DDL） |
| 看目前完成到哪 | `docs/STATUS.md`（唯一進度真相來源） |
| 看詳細修改歷史 | `REWRITE_PLAN.md`（16 輪演進） |
| 看事件流設計 | `docs/event-flows/FETCH_ORDERS.md`, `FETCH_PRODUCTS.md` |
| 看各平台抓取策略 | `docs/event-flows/FETCH_STRATEGY.md` |
| 看 Entity↔Schema 差異 | `docs/event-flows/DB_ENTITY_GAPS.md` |
| 看統計設計 | `docs/STATISTICS_DESIGN.md`（多角色統計 + 退貨流程） |
| Docker 操作 | `docs/DOCKER_GUIDE.md` |
| Kafka 維運 | `docs/OPERATIONS_RUNBOOK.md` |

---

## 建構與部署

```bash
cd /home/tom/ONEEC/simpleec-oms

# 編譯（跳過測試，目前 0 測試檔）
./gradlew clean build -x test

# 啟動全部 26 個容器
docker compose up -d --build

# 只啟動基礎設施（開發用）
docker compose up -d postgres redis kafka otel-collector tempo loki prometheus grafana

# Kafka UI
http://localhost:8088
```

---

## 技術棧

- Java 17, Spring Boot 3.5.0, Gradle 8.14.4
- PostgreSQL 16, Redis 7 (AOF), Kafka 3.7.1 (KRaft 模式，無 ZooKeeper)
- MyBatis-Plus (ORM), NanoID (PK), AES-256-GCM (PII 加密)
- OTEL Agent v2.10.0 + Grafana (Prometheus + Loki + Tempo)
- 11 Gradle 模組, 26 Docker 容器, **16 Kafka Topics**

### Kafka Topics (10個)
- **10 Channel Topics**: `{platform}.fast`, `{platform}.slow` × 6 platforms (momo, shopee, yahoo, pchome, cyberbiz, easystore)
  - `.fast`: 快速任務（SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN）< 5s
  - `.slow`: 慢速任務（FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, FETCH_RETURN_DETAIL, SYNC_PRODUCT, SYNC_STORE）< 5m
- **7 Business Topics**: `order.process`, `return.process`, `task.backend`, `task.frontend`, `scheduler`, `task.failed`, `task.dlt`
  - 其中 `order.process` 和 `return.process` 是 Source of Truth
- **可配置 Retention**: 透過 `simpleec.kafka.retention.*` (預設 1d，DLT 30d)

### 多平台 Channel Job 實現模式
```
FETCH_ORDERS 通路對應表
───────────────────────────────────────
Shopee:
  • orders list 不完整（缺 items, payment, shipping）→ 需打 detail API
  • 分批邏輯：1h(UNPAID) + 3d(AWAITING_SHIPMENT) + 5d(SHIPPED) + 7d(COMPLETED)
  • 使用 create_time_from/to 時間範圍查詢

Momo:
  • 無狀態分類，item-level 記錄（每行 1 item）
  • 無 detail API（根本不存在）→ 在 Channel Job 內按訂單號自己聚合
  • 分批邏輯：1h(新訂單) + 3d(待出貨) 等
  • 使用 created_time_start/end 時間範圍查詢

Yahoo:
  • 只有「更新時間」(updated_after)，無狀態分類
  • 返回最近 1d 更新的所有訂單
  • Channel Job 自己評估訂單狀態

easystore:
  • 優點：一次可回傳 50 張完整訂單 + 所有欄位
  • 缺點：有 IP 限制，rate limit 嚴格
  • 評估 rate limit，可視需要分頁拉取
  • 使用 from_date/to_date，預設 7d 窗口

PChome/Cyberbiz:
  • TBD（後續實裝）

❌ Queue/Scheduler 不決定上述邏輯，完全由 Channel Job 自主決策
✅ Scheduler 只傳 timestamp，Channel Job 自行計算所有時間窗口
```

---

## 模組結構

```
simpleec-oms/
├── simpleec-common        # 共用：Enum, Model, Util (IdGenerator, PiiMasker)
├── simpleec-core          # 核心：Entity, Mapper, Service, Kafka, Crypto, KafkaRetentionProperties
├── simpleec-channel       # 通路：ChannelAdapter 介面 + 平台實作
├── simpleec-api           # REST API (:8082) — Controller, VO
├── simpleec-gateway       # 對外 Gateway (:8081) — Webhook, ERP
├── simpleec-channel-job   # 通路同步 JOB (×10: 5 平台 × fast/slow)
├── simpleec-order-job     # 訂單處理 JOB (order.process consumer)
├── simpleec-scheduler-job # 排程引擎 (HeartbeatTimer)
├── simpleec-backend-job   # 後台非同步 JOB
├── simpleec-frontend-job  # 前台事件 JOB
└── simpleec-retry-job     # 失敗重試 / DLT 路由
```

---

## 🏗️ 核心設計原則

### 1. Channel Job = 數據適配層（三層職責分離）
```
┌─────────────┬──────────────────┬──────────────────┐
│ Scheduler   │ Channel Job      │ Handler          │
├─────────────┼──────────────────┼──────────────────┤
│ 何時拉取    │ 怎麼拉取         │ 怎麼存資料庫     │
│ (WHAT)      │ (HOW to fetch)   │ (HOW to process) │
├─────────────┼──────────────────┼──────────────────┤
│ 時間戳:     │ • API 呼叫       │ • 查詢 DB        │
│ 2026-02-13  │ • 時間窗口計算   │ • INSERT/UPDATE  │
│ T10:00:00Z  │ • 格式轉換       │ • 業務驗證       │
│             │ • 分頁/游標      │ • isRollback 計算│
│             │ • rate limit     │ • 狀態轉遷       │
│             │ • detail API決策 │                  │
└─────────────┴──────────────────┴──────────────────┘
```

✅ **Channel Job 該做**:
- 呼叫通路 API，每個通路 API 特性都不同
- **自主決策時間窗口**（不由 Scheduler 或 Queue 決定）
  - Shopee: 1h(PENDING) + 3d(CONFIRMED) + 5d(SHIPPED) + 7d(COMPLETED)
  - Momo: item-level 聚合（無狀態分類），按訂單號分組
  - Yahoo: 時間範圍查詢（無狀態），自己評估狀態
  - easystore: 7d 窗口（一次取 50 筆完整訂單）
- 判斷是否需要 detail API（根據平台能力和 rate limit）
- 格式轉換成統一 OMS 結構（orderId, channelOrderId, items[].channelItemId）
- 發送到 Kafka

❌ **Channel Job 不該做**:
- 存取資料庫
- 業務邏輯驗證（庫存、積分等）
- 訂單狀態管理
- 接受 Queue 裡面的時間 range（應該自己計算）

### 2. 統一 Header/Body 結構 & 路由
- **Header** 負責路由（taskType 決定 Handler）
  - platformId: 通路編號（通路 API 呼叫需要）
  - isRollback: 回補訂單標籤（Handler 業務邏輯需要）
  - version: 訊息版本（向後相容）
- **Body** 負責業務資料（對齐數據庫 schema）
  - orderData.orderId: 我們的 NanoID
  - orderData.channelOrderId: 通路訂單編號
  - items[].channelItemId: 通路項目 ID
- 所有 10 Kafka Topics 使用相同結構

### 3. isRollback 標籤：回補訂單追蹤
```
isRollback=false (新訂單)：
  ✓ 業績計入當日（based on channelCreatedAt）
  ✓ 庫存正常扣減
  ✓ 統計數據正常計入

isRollback=true (回補訂單)：
  ✓ 業績追溯原日期（based on channelCreatedAt，不是當日）
  ✓ 庫存調整可能需要特殊處理
  ✓ 統計數據標記為回補，可能不計入排名
  ✓ 發送回補專用事件通知（如重新計算日報）
```
適用於：FETCH_ORDERS, FETCH_ORDER_DETAIL, PROCESS_ORDER, SHIP_ORDER 及所有退貨相關 TaskType

### 4. 分頁策略封裝（完全隱藏於 Channel Job）
- Shopee: Cursor-based pagination
- Momo: Offset-based pagination + item-level 聚合
- Yahoo: 時間範圍查詢（無分頁概念）
- easystore: 可視需要多次翻頁
- 外部（Scheduler, Queue, Handler）完全不知道差異

---

## 編碼慣例

### PK / ID
- **所有表 PK 都是 `VARCHAR(20)` NanoID**，程式端用 `IdGenerator.nextId()` 產生
- Entity 用 `@TableId(type = IdType.ASSIGN_UUID)` + `String id`
- 所有 FK（merchantId, channelId 等）也是 `String`

### PII 加密
- `buyer_name`, `buyer_phone`, `buyer_email`, `shipping_address` — AES-256-GCM
- 透過 `EncryptedFieldTypeHandler` 在 MyBatis 層透明加解密
- 任何讀取加密欄位的程式碼必須包在 `EncryptionContext.setMerchantId()` / `clear()` 之間

### Kafka 訊息
- **統一 Header/Body 結構**（見 `docs/CORE_CONTRACTS.md`）
- `SchemaVersionHandler.normalize()` 版本驗證；不支援版本 → `task.dlt`
- `TaskMdcHelper.set(msg)` / `clear()` 在所有 JOB handle() 方法中使用

### 日誌
- `logback-spring.xml`：local=pattern, docker=JSON (LogstashEncoder 8.0)
- MDC 自動帶 traceId, spanId, merchantId, taskType

### 錯誤處理
- `DefaultErrorHandler` + `FixedBackOff(0L, 0L)` 跳過 poison pill
- 可重試 → `task.failed` (保留 1d)
- 不可重試 → `task.dlt` (保留 30d)

### ★ 核心原則：被動同步方
- **我們是被動同步方** — 平台給什麼就收什麼
- **不做狀態轉換驗證** — 接受任何狀態跳轉
- **所有邏輯都必須容錯** — 訂單可能從任何狀態跳到任何狀態

### ★ 抓取策略：Channel 自主
- **Channel 內部決定**：時間欄位、狀態過濾、分頁策略
- **外部只給時間範圍**：不指定細節
- 詳見 `docs/DATA_FLOW_MAPPING.md` §1.3

---

## Handler 註冊機制

### TaskType → Handler 對應
透過 `@ChannelHandler` 註解自動註冊：

```java
@ChannelHandler(
    platform = "shopee",
    taskTypes = {"FETCH_ORDERS", "FETCH_ORDER_DETAIL"}
)
public class ShopeeOrderHandler {
    // Spring 自動掃描註冊
}
```

完整對應表見 `docs/HANDLER_REGISTRY.md`

---

## 下一步工作

基於新的 Header/Body 架構：

### Phase 1: 實作核心流程（優先）
1. 實作 `BaseChannelJob` 框架（dispatch 機制）
2. 實作 `ShopeeOrderListHandler`（FETCH_ORDERS）
3. 實作 `NewOrderHandler`（order.process）
4. End-to-end 測試

### Phase 2: 擴展 Handler
- 加入其他 TaskType Handler
- 加入 Momo, Yahoo Channel
- 加入 Return, Shipping 流程

詳見 `docs/STATUS.md`

---

## Git 資訊

- GitHub: `tm731531/simpleec-oms` (SSH)
- Branch: `main`, `docs-only`（純文檔分支，詳見架構/設計文檔）
- 最近重要 commits (docs-only 分支):
  - `1727a52` — fix: FETCH_ORDERS routing table - {platform}.fast → {platform}.slow
  - `d725025` — fix: Clarify that Queue contains NO RANGE - only timestamp
  - `3c773c2` — feat: Complete isRollback coverage across all order/return TaskTypes
  - `ba8cce6` — feat: Add isRollback flag to header for handling backfill orders
  - `308b1be` — fix: Align EVENT_SAMPLES.md with updated CORE_CONTRACTS.md
  - `a2a2063` — clarify: Orders Channel Job fetches in multiple batches by order status lifecycle
  - `1a47db7` — refactor: Restructure message body - move channelOrderId to orderData, add platformId, channelItemId
  - `112cda8` — refactor: Align orderData structure with actual DB schema

---

## 文檔分支

純文檔分支（無程式碼）：
```bash
git checkout docs-only
```

適合：
- 分享架構設計
- 討論系統流程
- 不暴露實作細節