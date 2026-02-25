# SimpleEC OMS — Claude Session Guide

> 多平台電商訂單管理系統。Spring Boot 3.5 + Kafka + PostgreSQL + Redis。
> 整合 Momo / Shopee / Yahoo / PChome / Cyberbiz，統一管理商品、訂單、出貨與庫存。

---

## 🚀 最近修復和文檔整理 (Feb 24, 2026)

### ✅⚡ 快速重啟腳本 (新增 - v1.1)
- **`./quick-redeploy.sh`** — 快速重啟單個或多個 Docker 服務
  - 單個服務：30-60 秒（vs 5 分鐘的完整重啟）
  - 多個服務：1-2 分鐘（解決連帶修改的問題）
- **用法**：`./quick-redeploy.sh simpleec-channel-job` 或 `./quick-redeploy.sh svc1 svc2 svc3`
- **文件**：`quick-redeploy.sh`, `QUICK_REDEPLOY_GUIDE.md`, `QUICK_REDEPLOY_CHEATSHEET.txt`
- **特色**：自動依賴分析、自動去重、彩色輸出、清晰摘要

### ✅ API 路由完全修復
- 所有11個Spring控制器的`@RequestMapping`已修正（添加`/api`前綴）
- **13/13 API端點全部運行** ✅
  - 6個用戶端點 (需JWT認證)
  - 2個管理端點 (需JWT認證)
  - 3個公開端點 (無需認證)
  - 2個後端端點 (內部使用)

### ✅ 系統重啟部署修復
- `start-on-boot.sh` 現在會自動rebuild Docker images
- 防止系統重啟後運行舊版本代碼

### 📚 新文檔索引
使用新的**文檔導航系統**，快速找到所需文檔：

| 文檔 | 用途 |
|------|------|
| **[OPERATIONS_CURRENT_STATUS.md](docs/6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md)** ⭐ | 當前系統狀態、最新修復、故障排除 |
| **[DOCUMENTATION_INDEX.md](docs/0-START/DOCUMENTATION_INDEX.md)** | 30+個文檔的完整導航指南 |
| **[QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md)** | 可複製貼上的常用命令 |

### 協作建議
與Claude合作時：
1. 查看 **[OPERATIONS_CURRENT_STATUS.md](docs/6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md)** 了解當前系統狀態
2. 用 **[QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md)** 中的命令快速診斷問題
3. 查看 **[DOCUMENTATION_INDEX.md](docs/0-START/DOCUMENTATION_INDEX.md)** 找相關設計文檔
4. 重大修復後更新 OPERATIONS_CURRENT_STATUS.md 中的狀態

---

## 🔥 最新架構更新 (2026-02-19)

### 統一訊息結構 (Header/Body)
所有 Kafka 訊息採用統一結構，詳見 `docs/3-EVENT-FLOW/CORE_CONTRACTS.md`：
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
- **詳見** `docs/3-EVENT-FLOW/CORE_CONTRACTS.md` §4.0 和 `docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md`

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

### 🆕 新人必讀（Feb 24開始）
| 你要做什麼 | 讀哪份文件 |
|-----------|-----------|
| **⭐ 系統當前狀態** | [OPERATIONS_CURRENT_STATUS.md](docs/6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md) — 最新修復、API狀態、故障排除 |
| **⭐ 找文檔導航** | [DOCUMENTATION_INDEX.md](docs/0-START/DOCUMENTATION_INDEX.md) — 30+文檔的完整指南 |
| **⭐ 快速複製命令** | [QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md) — Docker、API測試、Kafka操作 |

### 📖 架構和設計
| 你要做什麼 | 讀哪份文件 |
|-----------|-----------|
| **⭐ 理解事件流架構** | `docs/3-EVENT-FLOW/CORE_CONTRACTS.md` — 核心契約定義 |
| **⭐ 看資料流對應** | `docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md` — API→Message→Entity |
| **⭐ 實作新 Channel** | `docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md` |
| **⭐ 查 TaskType Handler** | `docs/3-EVENT-FLOW/HANDLER_REGISTRY.md` |
| 看訊息範例 | `docs/3-EVENT-FLOW/EVENT_SAMPLES.md` — 所有 Topic 訊息範例 |
| 理解系統全貌 | `docs/1-ARCHITECTURE/DESIGN_v2.md` §0（現狀）→ §1-§15（完整設計） |
| 看 DB Schema | `docs/4-SCHEMA/SCHEMA.md`（19 張表 DDL） |
| 看詳細修改歷史 | 見 `docs/archive/` 中的歷史文檔 |
| 看事件流設計 | `docs/3-EVENT-FLOW/event-flows/FETCH_ORDERS.md`, `FETCH_PRODUCTS.md` |
| 看各平台抓取策略 | `docs/3-EVENT-FLOW/event-flows/FETCH_STRATEGY.md` |
| 看 Entity↔Schema 差異 | `docs/3-EVENT-FLOW/event-flows/DB_ENTITY_GAPS.md` |
| 看統計設計 | `docs/archive/STATISTICS_DESIGN.md`（多角色統計 + 退貨流程） |

### 🛠️ 操作和維運
| 你要做什麼 | 讀哪份文件 |
|-----------|-----------|
| **⭐ 快速重啟服務（開發用）** | **[QUICK_REDEPLOY_GUIDE.md](docs/0-START/QUICK_REDEPLOY_GUIDE.md)** — 只需 30-60 秒！支持多服務 |
| 快速命令速查表 | [QUICK_REDEPLOY_CHEATSHEET.txt](QUICK_REDEPLOY_CHEATSHEET.txt) — 常用命令速查 |
| 系統啟動和重啟 | [QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md) 或 [SCRIPTS_GUIDE.md](docs/0-START/SCRIPTS_GUIDE.md) |
| 腳本使用指南 | [SCRIPTS_GUIDE.md](docs/0-START/SCRIPTS_GUIDE.md) — 所有腳本說明與用法 |
| API 測試 | [QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md) 的 "API 測試" 部分 |
| Kafka 操作 | [QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md) 或執行 `bash kafka-topic-manager.sh` |
| systemd 服務修復 | `docs/6-OPERATIONS/SYSTEMD_SERVICE_FIX.md` |
| Docker 操作 | [QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md) 或 `docs/6-OPERATIONS/DOCKER_GUIDE.md` |

---

## 建構與部署

### 快速部署（推薦用於開發）⭐ NEW

```bash
cd /home/tom/ONEEC/simpleec-oms

# 編譯
./gradlew clean build -x test

# 快速重啟單個服務（只需 30-60 秒！）
./quick-redeploy.sh simpleec-channel-job
./quick-redeploy.sh simpleec-api

# 一次性重啟多個服務（連帶修改時使用）
./quick-redeploy.sh simpleec-channel-job simpleec-order-job simpleec-api

# 查看所有可用服務
./quick-redeploy.sh --list

# 查看幫助
./quick-redeploy.sh --help
```

**使用場景**：
- 修改單個 JOB 後快速測試
- 修改了多個相關服務的代碼
- 避免每次都重啟整個系統（原本需要 5 分鐘）

詳見 `docs/0-START/QUICK_REDEPLOY_GUIDE.md` 和 `QUICK_REDEPLOY_CHEATSHEET.txt`

### 完整部署

```bash
cd /home/tom/ONEEC/simpleec-oms

# 編譯（跳過測試，目前 0 測試檔）
./gradlew clean build -x test

# 啟動全部 26 個容器（3-5 分鐘）
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

### Kafka Topics (10個 + 7業務主題)
- **10 Channel Topics**: `{platform}.fast`, `{platform}.slow` × 6 platforms (momo, shopee, yahoo, pchome, cyberbiz, easystore)
  - `.fast`: 快速任務（SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN）< 5s
  - `.slow`: 慢速任務（FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, FETCH_RETURN_DETAIL, SYNC_PACK）< 5m
  - **重點**: 通路只有「套包」(Pack)，沒有「商品」概念；BACKEND 自動建立 Pack → Product 映射
- **7 Business Topics**: `order.process`, `return.process`, `task.backend`, `task.frontend`, `scheduler`, `task.failed`, `task.dlt`
  - 其中 `order.process` 和 `return.process` 是 Source of Truth
  - `task.backend` 包含所有內部邏輯任務（SYNC_PACK 的結果處理、SYNC_PRODUCT、Pack→Product 映射建立等）
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
- SYNC_PACK：同步通路套包（在通路上賣的商品套包資訊，BACKEND 自動建立 Pack → Product 映射）
- 發送到 Kafka

❌ **Channel Job 不該做**:
- 存取資料庫
- 業務邏輯驗證（庫存、積分等）
- 訂單狀態管理
- 接受 Queue 裡面的時間 range（應該自己計算）
- 同步商品 (SYNC_PRODUCT) 或建立 Pack→Product 映射 — 這是 OMS 內部邏輯，由 BACKEND 根據 SYNC_PACK 自動建立

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
- **統一 Header/Body 結構**（見 `docs/3-EVENT-FLOW/CORE_CONTRACTS.md`）
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

### ★ 外鍵原則：數據庫和 Queue 只傳 ID，不傳名稱
**核心目的：⚡ 快速、🔗 統一性、🔒 資訊安全**

#### 設計規則
- **數據庫中存外表的東西 → 必須存 ID**（外鍵）
  - ❌ 不要存名稱、代碼、字符串
  - ✅ 存真實的 NanoID（如 platformId, merchantId, channelId）
- **Kafka Queue 中傳遞 → 也必須傳 ID**
  - ❌ 不要傳 platformCode, platformName 這類字符串
  - ✅ 傳 platformId（從 header 中直接提取，無需轉換）

#### 三大核心目的

**⚡ 快速 (Performance)**
- **消除名稱→ID 的往返查詢**
  - ❌ 舊做法：Queue 傳 platformCode("cyberbiz") → Consumer 接收 → 查詢 Platform 表得到 platformId → 再查業務數據
  - ✅ 新做法：Queue 直接傳 platformId → Consumer 直接用 → O(1) 查表
- **減少 N+1 查詢問題**
  - 若傳名稱，每條記錄都要轉換一次：單個查詢消費者、批量拉取時則變成 N+1 問題
  - 直接傳 ID，無轉換層，直接用索引查詢
- **實際效果**
  - 消費 Kafka 消息速度：原本需要 2 次查詢，現在只需 1 次直接 ID 索引
  - 批量操作時，效能提升明顯（減少 50% 的 DB 查詢）

**🔗 統一性 (Consistency)**
- **數據庫自動強制一致性（外鍵約束）**
  - 存 ID：PostgreSQL 通過 FK 自動驗證 `platformId` 存在於 platform 表
  - 存名稱：無法設置 FK，若平台名稱被改或刪除，舊記錄變成孤立數據
- **系統範圍內的單一真實源 (Single Source of Truth)**
  - 平台資訊改了（如狀態、配置），所有使用 ID 的記錄自動正確參照最新數據
  - 若存名稱，舊名稱和新名稱不同步，導致數據混亂
- **多語言友好**
  - ID 全球通用（1，23，abc123 等），不受語言影響
  - 存名稱很容易遇到 i18n 問題（"cyberbiz" vs "網家" 誰是唯一標識？）

**🔒 資訊安全 (Security)**
- **不傳敏感信息**
  - platformCode("cyberbiz", "shopee", "momo") 暴露平台配置資訊
  - platformId(NanoID) 是不可預測的雜亂字符，無法猜測系統的平台清單
- **防止意外洩露**
  - Queue 消息可能被日誌記錄、監控工具截獲
  - 傳 ID：即使被截獲，攻擊者只看到 `platformId: "abc123def456"`
  - 傳名稱：攻擊者立刻知道 `"cyberbiz"` 是一個已接入的平台，可能進一步攻擊
- **外鍵約束的防護**
  - 直接存 ID 且有 FK 約束，無法插入虛假 platformId（約束會拒絕）
  - 存名稱時無約束，容易被應用邏輯漏洞利用

#### 實際案例

**反例 - 舊做法**：
```
Scheduler → platformCode("cyberbiz")
  ↓
Queue 消息：{header: {platformCode: "cyberbiz"}}
  ↓
Consumer 接收 → Service 查詢：SELECT * FROM platform WHERE platform_code = 'cyberbiz'
  ↓
Service 獲得 platformId = "id_123abc"
  ↓
DB INSERT：channel_sync_logs (platform_id = "id_123abc")

❌ 問題：
- 多一次 DB 查詢（効率降低）
- Queue 中暴露 platformCode（安全隱患）
- 若 platform_code 值改了，系統可能混亂（一致性破裂）
```

**正例 - 新做法**：
```
Scheduler → platformId("id_123abc")
  ↓
Queue 消息：{header: {platformId: "id_123abc"}}
  ↓
Consumer 直接接收 platformId
  ↓
Service 直接用：DB INSERT：channel_sync_logs (platform_id = "id_123abc")

✅ 好處：
- 無需額外查詢（效能最優）
- Queue 中只傳加密的 ID（安全）
- 數據庫 FK 自動保證一致性（若 platform 被刪，FK 約束會防止）
```

#### 應用範圍
- **所有外表參照**：platformId, merchantId, channelId, productId, orderId 等
- **所有傳遞層**：
  - 數據庫：使用 FK 約束
  - Kafka Queue：header 和 body 中傳 ID，不傳名稱
  - API：接收 ID 參數，不接受模糊的名稱查詢（除非特意設計）
- **例外**：用戶搜索介面（如「搜索平台名稱」），需要名稱查詢；但內部系統流程一律傳 ID

### ★ 抓取策略：Channel 自主
- **Channel 內部決定**：時間欄位、狀態過濾、分頁策略
- **外部只給時間範圍**：不指定細節
- 詳見 `docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md` §1.3

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

完整對應表見 `docs/3-EVENT-FLOW/HANDLER_REGISTRY.md`

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

詳見相關文檔在 `docs/3-EVENT-FLOW/` 和 `docs/archive/`

---

## 與 Claude 協作指南

### 工作流程
1. **描述問題或需求**
   - 參考 [OPERATIONS_CURRENT_STATUS.md](docs/6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md) 了解當前狀態
   - 用 [QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md) 中的命令快速診斷

2. **規劃修復**
   - Claude 會查看相關代碼和文檔
   - 提出實施計畫（如需要）

3. **實施和測試**
   - 修改代碼
   - 本地測試（使用 [QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md)）
   - 查看日誌驗證

4. **更新文檔**
   - 修復完成後，更新 [OPERATIONS_CURRENT_STATUS.md](docs/6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md)
   - 提交時使用清晰的 commit 訊息

### 提交規範
所有 commit 訊息應包含：
- 簡短描述 (修了什麼)
- 修復的原因或背景
- 驗證方式 (如何測試)
- 相關文檔更新

### 系統級修復檢查清單
修復後務必驗證：
- [ ] 代碼編譯無誤
- [ ] Docker 容器啟動成功
- [ ] 相關 API 端點可用
- [ ] 日誌中無錯誤或警告
- [ ] 文檔已更新
- [ ] commit 訊息清晰

---

## Git 資訊

- GitHub: `tm731531/simpleec-oms` (SSH)
- Branch:
  - `main` — 主分支
  - `fix/admin-app-api-routing-and-nginx-proxy` — 當前工作分支 (API 路由修復)
  - `docs-only` — 純文檔分支（詳見架構/設計文檔）

### 最近重要 commits (當前分支 - Feb 24)
- `37488e4` — docs: Organize and consolidate documentation (OPERATIONS_CURRENT_STATUS, DOCUMENTATION_INDEX, QUICK_COMMANDS)
- `34d5805` — fix: API version deployment issue - rebuild images on system restart
- `7f594a7` — fix: All user and backend API endpoint routing - add /api prefix
- `0ed6853` — fix: API endpoint routing and authentication flow

### 文檔分支 (docs-only) 的最近 commits
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

### 文檔結構說明
- **main 分支** — 完整代碼 + 最新文檔
  - 包含 OPERATIONS_CURRENT_STATUS.md（當前狀態）
  - 包含 QUICK_COMMANDS.md（操作命令）
  - 新人開發者應該從這裡開始

- **docs-only 分支** — 純文檔分支（無程式碼）
  ```bash
  git checkout docs-only
  ```
  適合：
  - 分享架構設計（不涉及程式碼細節）
  - 討論系統流程
  - 外部評審或文檔維護

### 文檔維護責任
| 文檔 | 維護頻率 | 責任 |
|------|---------|------|
| OPERATIONS_CURRENT_STATUS.md | 每週或每次修復後 | 記錄系統狀態、最新修復 |
| DOCUMENTATION_INDEX.md | 每季或新增文檔時 | 更新文檔導航和分類 |
| QUICK_COMMANDS.md | 每次添加新功能時 | 更新常用命令 |
| DESIGN_v2.md | 架構變更時 | 更新設計文檔 |

---

## 🎯 下一步工作

### 立即事項（優先）
- [ ] 1. **測試系統重啟** — 驗證 rebuild 機制是否正常工作
  ```bash
  sudo reboot && sleep 120 && curl http://localhost:8082/api/health
  ```

- [ ] 2. **診斷 Kafka 剩餘問題** — 用戶提到還有一點點 Kafka 問題
  ```bash
  docker logs simpleec-kafka 2>&1 | tail -50
  ```

- [ ] 3. **更新 systemd 服務**（可選但推薦）
  - 查看 `docker/SYSTEMD_SERVICE_FIX.md`
  - 手動更新 `/etc/systemd/system/simpleec-oms.service`

### 中期工作（本週）
- [ ] 4. **驗證用戶事件流**
  - 用户登录 → 查看商品 → 瀏覽通路 → 完整流程測試
  - 檢查 Kafka 事件是否正確流轉

- [ ] 5. **定期更新文檔**
  - 每週檢查 OPERATIONS_CURRENT_STATUS.md
  - 記錄系統狀態、新增的功能

### 長期計畫（後續）
詳見 `docs/STATUS.md` 和 `REWRITE_PLAN.md`