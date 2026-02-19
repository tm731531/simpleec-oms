# SimpleEC OMS — Claude Session Guide

> 多平台電商訂單管理系統。Spring Boot 3.5 + Kafka + PostgreSQL + Redis。
> 整合 Momo / Shopee / Yahoo / PChome / Cyberbiz，統一管理商品、訂單、出貨與庫存。

---

## 🔥 最新架構更新 (2024-02-19)

### 統一訊息結構 (Header/Body)
所有 Kafka 訊息採用統一結構，詳見 `docs/CORE_CONTRACTS.md`：
```json
{
  "header": {
    "taskType": "路由關鍵",
    "merchantId": "商家ID",
    "channelId": "通路實例",
    "requestId": "追蹤ID",
    "timestamp": "時間戳",
    "version": 1
  },
  "body": {
    // TaskType 特定資料
  }
}
```

### Channel Job 職責界定
- **只負責通路 API 溝通**，不存資料庫
- **智能判斷**：自主決定是否需要 detail/return
- **分頁處理**：每個通路自己知道用 cursor 或 offset
- 詳見 `docs/CHANNEL_IMPLEMENTATION_GUIDE.md`

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

### Kafka Topics (16個)
- **10 Channel Topics**: `{platform}.fast`, `{platform}.slow` × 5 platforms
- **6 Business Topics**: `order.process`, `return.process`, `product.sync`, `inventory.update`, `task.backend`, `task.frontend`, `scheduler`, `task.failed`, `task.dlt`
- **可配置 Retention**: 透過 `simpleec.kafka.retention.*` (預設 1d，DLT 30d)

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

### 1. Channel Job 單一職責
```java
✅ Channel Job 該做：
- 呼叫通路 API
- 處理分頁/游標
- 處理 rate limit
- 發送訊息到 Kafka

❌ Channel Job 不該做：
- 存取資料庫
- 業務邏輯驗證
- 訂單狀態管理
```

### 2. 統一 Header/Body 結構
- **Header** 負責路由（taskType 決定 Handler）
- **Body** 負責業務資料
- 所有 16 個 Topics 使用相同結構

### 3. Channel 自主決策
```java
// Shopee 自己決定
if (shouldFetchDetail(order)) {
    sendToDetailFetch(order);
}

// 不是外部告訴它
❌ if (message.fetchDetail) { ... }
```

### 4. 分頁策略封裝
- Shopee: Cursor-based pagination
- Momo: Offset-based pagination
- Yahoo: 特殊 CSV webhook
- 外部不需要知道差異

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
- Branch: `main`, `docs-only`（純文檔分支）
- 最近重要 commits:
  - `28a9f70` — 新增完整事件流文檔體系
  - `ee79c10` — 重構為統一 header/body 結構
  - `ba77b93` — 可配置 Kafka topic retention
  - `47d2bd0` — 移除 product.detail topic

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