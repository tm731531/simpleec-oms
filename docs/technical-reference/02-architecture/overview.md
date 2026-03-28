# 系統架構概覽

## SimpleEC OMS 做什麼

SimpleEC OMS 是一套事件驅動的訂單管理後端，持續從 Shopee、Momo、Yahoo、PChome、Cyberbiz 與 Easystore 六大電商平台拉取訂單、退貨、商品與庫存資料，並將其整合到單一 PostgreSQL 資料庫中。商家只需登入一次統一入口，即可跨所有平台查看訂單、處理退貨、觸發出貨，並追蹤庫存，無需進入任何平台的原生後台。

本系統圍繞排程驅動的輪詢架構設計。Scheduler Job 依可設定的間隔（快速任務每 5 分鐘、慢速批次每小時）向 Kafka 發送心跳訊息。各平台的 Channel Job 消費這些訊息，呼叫對應的外部 API，將不同平台的回應正規化為統一訊息格式，再將訂單/退貨事件發布至內部 Kafka Topic。下游 Job 服務消費這些內部事件，將記錄持久化至 PostgreSQL，並透過任務管道向前端推送即時通知。

此架構使每個整合點都可獨立部署和觀測。Cyberbiz API 中斷不影響 Shopee 的處理。Momo 緩慢的批次抓取不會阻礙 Shopee 訂單的即時更新。每條無法處理的訊息都會被路由至死信 Topic（DLT），保留 30 天供調查與重放。

---

## 系統架構圖

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           SimpleEC OMS                                  │
│                                                                         │
│  ┌──────────────┐   ┌──────────────┐                                   │
│  │   user-app   │   │  admin-app   │  ← Vue 3 前端                      │
│  │  (:8090/nginx│   │  (:8089/nginx│    (SPA, JWT 認證)                 │
│  │   :5173/dev) │   │   :8084/dev) │                                   │
│  └──────┬───────┘   └──────┬───────┘                                   │
│         │                  │                                            │
│  ┌──────▼──────────────────▼──────────┐  ┌────────────────────────┐   │
│  │          simpleec-api (:8082)       │  │  simpleec-gateway       │   │
│  │   REST API + JWT Auth + CORS        │  │      (:8081)            │   │
│  │   訂單 / 退貨 / 出貨 /              │  │  平台 Webhook           │   │
│  │   庫存 / 統計 / 報表                │  │  ERP 整合               │   │
│  └────────────────┬────────────────── ┘  └──────────┬─────────────┘   │
│                   │                                  │                  │
│                   └──────────────┬───────────────────┘                  │
│                                  │  Kafka 發布                          │
│                   ┌──────────────▼──────────────────┐                  │
│                   │             Kafka                │                  │
│                   │   KRaft 模式（無 ZooKeeper）     │                  │
│                   │   17 個 Topic，分 3 層           │                  │
│                   │                                  │                  │
│                   │  Channel Topic（×10）：          │                  │
│                   │    {platform}.fast               │                  │
│                   │    {platform}.slow               │                  │
│                   │  業務 Topic（×7）：              │                  │
│                   │    order.process  return.process │                  │
│                   │    task.backend   task.frontend  │                  │
│                   │    scheduler      task.failed    │                  │
│                   │    task.dlt                      │                  │
│                   └──────┬──────────────────┬────────┘                  │
│                          │                  │                           │
│         ┌────────────────┘                  └────────────────────┐     │
│         │                                                         │     │
│  ┌──────▼───────────────────────────┐  ┌───────────────────────┐ │     │
│  │  simpleec-channel-job (×10)      │  │  simpleec-order-job    │ │     │
│  │  每個平台一個容器 ×               │  │  simpleec-backend-job  │ │     │
│  │  fast/slow Topic 組合            │  │  simpleec-frontend-job │ │     │
│  │                                  │  │  simpleec-scheduler-job│ │     │
│  │  • 呼叫平台 REST API             │  │  simpleec-retry-job    │ │     │
│  │  • 決定時間窗口                  │  └───────────┬───────────┘ │     │
│  │  • 正規化回應格式                │              │              │     │
│  │  • 發布至 order.process          │              │              │     │
│  │    或 return.process             │              │              │     │
│  └──────┬───────────────────────────┘  ┌──────────▼──────────────┘     │
│         │  HTTPS 對外連線               │                               │
│  ┌──────▼───────────────────────────┐  │  ┌───────────────────────┐   │
│  │  外部平台 API                    │  └─►│  PostgreSQL 16         │   │
│  │                                  │     │  (19 張表，AES-256     │   │
│  │  • api.cyberbiz.co               │     │   PII 加密)            │   │
│  │  • partner.shopeemobile.com      │     ├───────────────────────┤   │
│  │  • Momo Commerce API             │     │  Redis 7 (AOF)         │   │
│  │  • Yahoo Commerce API            │     │  （去重、快取、         │   │
│  │  • Easystore API                 │     │   Session 儲存）        │   │
│  └──────────────────────────────────┘     └───────────────────────┘   │
│                                                                         │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │  可觀測性堆疊                                                      │ │
│  │  OTEL Collector → Tempo（追蹤）· Loki（日誌）· Prometheus（指標）  │ │
│  │  Grafana (:3000) 統一儀表板                                        │ │
│  └────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 核心設計原則

### 1. 被動同步：接受平台給予的所有資料

SimpleEC OMS 是外部平台資料的下游消費者，而非其權威來源。
本系統不驗證訂單狀態轉換、不拒絕意外的狀態跳躍，也不強制執行可能與平台回報資料相衝突的業務規則。若 Shopee 回報某訂單從 `PENDING` 直接跳至 `COMPLETED`，系統即如實記錄。

此原則防止了最常見的整合失效模式：OMS 內部的守衛條件因狀態機未預期某次轉換，而拒絕了平台的合法更新。移除所有狀態守衛後，OMS 成為平台現實的忠實映像。

### 2. Channel Job 自主性：各平台自行決定時間窗口

Scheduler 僅發送單一時間戳記（`header.timestamp`）。Channel Job 完全負責將該時間戳記轉換為各平台 API 的查詢參數：

- **Shopee** — 依訂單生命週期階段分為四個重疊窗口：1h（UNPAID）、3d（AWAITING_SHIPMENT）、5d（SHIPPED）、7d（COMPLETED）。使用游標分頁。
- **Momo** — 項目級記錄，無狀態分類。Channel Job 按訂單號聚合。不存在 Detail API；聚合在記憶體中完成。
- **Yahoo** — 僅支援 `updated_after` 查詢。Channel Job 使用 `timestamp - 1d`。
- **Easystore** — 每頁回傳 50 筆完整訂單。Channel Job 使用 `timestamp - 7d`。
- **PChome / Cyberbiz** — 依各自 API 契約定義平台特定的時間窗口邏輯。

此自主性使 Kafka 訊息契約（`scheduler` Topic）保持簡單穩定，同時各平台的抓取策略可獨立演進。**Queue 中絕不包含 from/to 日期範圍** — 只有觸發時間戳記。

### 3. 統一 Header/Body 契約：所有 Kafka 訊息共用相同結構

每個 Topic 上的每條訊息都符合相同的封裝格式：

```json
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "merchantId": "a00000",
    "platformId": "shopee",
    "channelId": "CHANNEL_SHOPEE_001",
    "requestId": "trace-correlation-id",
    "timestamp": "2026-02-13T10:00:00Z",
    "source": "scheduler",
    "version": 1,
    "isRollback": false
  },
  "body": {
    // TaskType 特定的 Payload
  }
}
```

Header 中的 `taskType` 欄位是路由鍵。每個消費者僅依據 `header.taskType` 分派至對應的 Handler，使系統易於擴展：新增 TaskType 只需實作一個帶有 `@ChannelHandler` 註解的新 Handler 類別，無需修改消費者基礎設施。

### 4. `isRollback` 旗標：區分即時訂單與歷史補填

當商家連接新平台時，OMS 需要匯入歷史訂單，同時不影響當前業務指標。訊息 Header 中的 `isRollback` 旗標向所有 Handler 標示訊息代表的是即時事件還是補填：

- `isRollback = false` — **即時訂單**：計入今日統計、正常扣減庫存、觸發正常通知。
- `isRollback = true` — **補填訂單**：營收歸屬至 `channelCreatedAt` 日期（非今日）、統計記錄標記為補填以排除在即時儀表板之外、跳過可能破壞當前庫存數量的庫存扣減調整。

此標示貫穿 `FETCH_ORDERS`、`PROCESS_ORDER`、`SHIP_ORDER` 及所有退貨相關 TaskType 的每個 Handler。

### 5. 雙層去重：Redis 快速路徑、DB 為最終依據

平台 API 並非冪等。同一訂單可能出現在多個連續抓取窗口中（例如，兩天前建立的 Shopee 訂單會同時出現在 3d 和 5d 的窗口中）。若不進行去重，系統將建立重複的訂單記錄。

去重策略分為兩層：

1. **Redis（快速路徑）** — 在處理任何訊息前，Order Job 先檢查 Redis 金鑰 `dedup:{channelId}:{channelOrderId}`。若金鑰存在且訂單未變更，訊息直接丟棄，不進行 DB 查詢。
2. **PostgreSQL（最終依據）** — 快取未命中或偵測到變更時，Handler 執行 upsert（`INSERT ... ON CONFLICT DO UPDATE`），以 `(channel_id, channel_order_id)` 為鍵。DB 永遠是真實來源；Redis 僅作為優化手段。

此設計可優雅處理快取重啟：Redis 清除後，單次輪詢週期的 DB 負載略有增加，隨後快取重新預熱，恢復正常。

---

## 模組依賴關係圖

所有模組都依賴 `simpleec-common`，以使用共用的 Enum、工具類別和 ID 生成器。
`simpleec-core` 在此基礎上新增資料庫層（Entity、MyBatis Mapper、Kafka 生產者/消費者工具、加密服務）。應用模組同時依賴兩者。

```
simpleec-common
    │
    └── simpleec-core
            │
            ├── simpleec-channel          （平台適配器介面 + 實作）
            │
            ├── simpleec-api              （REST API :8082）
            ├── simpleec-gateway          （Webhook/ERP :8081）
            │
            ├── simpleec-channel-job      （平台抓取 Worker）
            ├── simpleec-order-job        （order.process 消費者）
            ├── simpleec-backend-job      （非同步後台任務）
            ├── simpleec-frontend-job     （前端通知任務）
            ├── simpleec-scheduler-job    （心跳計時器）
            └── simpleec-retry-job        （失敗重試 + DLT 路由）
```

**關鍵約束：** 任何模組不得有反方向的編譯時依賴（例如 `simpleec-core` 不得引入 `simpleec-api`）。跨服務通訊僅透過 Kafka 訊息進行。

---

## 技術棧摘要

| 層級 | 技術 | 說明 |
|---|---|---|
| 程式語言 | Java 17 | LTS 版本 |
| 框架 | Spring Boot 3.5.0 | Gradle 多模組專案 |
| 建置工具 | Gradle 8.14.4 | Wrapper 已提交至儲存庫 |
| ORM | MyBatis-Plus | 基於註解，無 XML Mapper |
| 主鍵 | NanoID（VARCHAR 20） | `IdGenerator.nextId()`，URL 安全格式 |
| 資料庫 | PostgreSQL 16 | 19 張表，AES-256-GCM PII 加密 |
| 快取 / Session | Redis 7（AOF） | 啟用持久化以應對崩潰恢復 |
| 訊息代理 | Kafka 3.7.1 KRaft | 無需 ZooKeeper |
| 加密 | AES-256-GCM | buyer_name、phone、email、address 欄位 |
| 追蹤 | OpenTelemetry Agent 2.10.0 | 透過 Java Agent 自動埋點 |
| 指標 | Prometheus + Grafana | 所有 Spring Boot Actuator 指標 |
| 日誌聚合 | Loki + Grafana | 透過 Logstash Encoder 輸出 JSON 結構化日誌 |
| 前端 | Vue 3 + Vite | 兩個獨立的 SPA 應用 |
| 容器 | Docker Compose | 共 26 個容器 |
