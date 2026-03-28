# SimpleEC OMS — 技術參考手冊

> **多平台電商訂單管理系統**

## SimpleEC OMS 是什麼？

SimpleEC OMS 是一套多租戶電商訂單管理系統，整合 Shopee、Momo、Yahoo、PChome、Cyberbiz 與 Easystore 六大電商平台的訂單、退貨、出貨與庫存資訊，統一納入單一後台管理。商家只需登入一次，即可透過統一儀表板管理所有業務，無需在各平台後台之間來回切換。

本系統採事件驅動架構：排程器按固定間隔觸發各平台的 Channel Job，每個 Job 從對應平台 API 抓取資料，將回應正規化為統一格式，再發布至 Kafka。下游 Job 服務消費這些事件，將訂單持久化到 PostgreSQL，並透過任務管道即時推送通知至前端。此架構確保系統可靠性（失敗的 Job 透過專屬重試管道自動重試）與服務解耦（每個服務可獨立部署和擴展）。

SimpleEC OMS 從一開始即以生產環境標準建構：所有買家個資欄位使用 AES-256-GCM 加密，透過 OpenTelemetry 實現分散式追蹤，結構化 JSON 日誌送至 Loki，以及指標暴露至 Prometheus/Grafana。

---

## 文件索引

| 章節 | 檔案 | 說明 |
|---|---|---|
| **快速入門** | [01-getting-started/prerequisites.md](01-getting-started/prerequisites.md) | 硬體規格、軟體需求、連接埠說明 |
| **快速開始** | [01-getting-started/quick-start.md](01-getting-started/quick-start.md) | 30 分鐘完成環境建置 |
| **環境變數** | [01-getting-started/environment.md](01-getting-started/environment.md) | 所有環境變數、預設值、正式環境檢查清單 |
| **系統架構概覽** | [02-architecture/overview.md](02-architecture/overview.md) | 系統設計、模組結構、核心原則 |
| **事件流程與契約** | ../../3-EVENT-FLOW/CORE_CONTRACTS.md | Kafka 訊息結構、Topic 路由 |
| **Handler 註冊表** | ../../3-EVENT-FLOW/HANDLER_REGISTRY.md | TaskType → Handler 對應表 |
| **資料庫 Schema** | ../../4-SCHEMA/SCHEMA.md | 全部 19 張資料表 DDL |
| **Channel 實作指南** | ../../7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md | 如何新增一個平台 |
| **維運狀態** | ../../6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md | 當前系統狀態、已知問題 |

---

## 快速參考

### 連接埠對照

| 服務 | 對外連接埠 | 說明 |
|---|---|---|
| `simpleec-api` | **8082** | REST API，需 JWT 認證 |
| `simpleec-gateway` | **8081** | Webhook 與 ERP 整合 |
| `user-app` (nginx) | **8090** | 商家前端（Vue 3） |
| `user-app` (dev server) | **5173** | Vite 開發伺服器（僅本機） |
| `admin-app` (nginx) | **8089** | 管理後台前端（Vue 3） |
| `admin-app` (dev server) | **8084** | Vite 開發伺服器（僅本機） |
| Kafka UI | **8088** | 瀏覽 Topic、訊息、Consumer Lag |
| Grafana | **3000** | 指標、日誌、追蹤儀表板 |
| Prometheus | **9090** | 原始指標擷取端點 |
| PostgreSQL | **5433** | 對外映射（內部：5432） |
| Redis | **6379** | 快取與 Session 儲存 |
| Kafka broker | **9092** | 僅內部使用（KRaft 模式，無 ZooKeeper） |

### 常用 URL

```
http://localhost:8082/api/health          # API 健康檢查
http://localhost:8088                     # Kafka UI
http://localhost:3000                     # Grafana
http://localhost:8090                     # 商家前端
http://localhost:8089                     # 管理後台
```

### 常用指令

```bash
# 編譯所有模組（跳過測試）
./gradlew clean build -x test

# 啟動全部 26 個容器
docker compose up -d --build

# 快速重啟單一服務（30-60 秒）
./quick-redeploy.sh <service-name>

# 一次重啟多個服務
./quick-redeploy.sh simpleec-channel-job simpleec-order-job

# 持續追蹤服務日誌
docker compose logs -f simpleec-api

# 查看所有容器狀態
docker compose ps

# API 健康檢查
curl http://localhost:8082/api/health
```

### 模組名稱（供 quick-redeploy 使用）

```
simpleec-api           simpleec-gateway        simpleec-channel-job
simpleec-order-job     simpleec-return-job     simpleec-backend-job
simpleec-frontend-job  simpleec-scheduler-job  simpleec-retry-job
```
