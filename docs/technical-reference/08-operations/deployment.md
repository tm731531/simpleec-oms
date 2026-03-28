# 部署指南

SimpleEC OMS 以 Docker Compose 管理 26 個 Docker 容器。本指南涵蓋全新部署、開發期間的單服務重建，以及系統拆除程序。

---

## 前置需求

- Docker Engine 24+ 及 Docker Compose v2
- Java 17（用於建置 Java 服務）
- Gradle 8.14.4（已含 wrapper — `./gradlew`）
- 支援 submodule 的 Git
- 建議最低 8 GB RAM（含可觀測性服務的完整堆疊建議 16 GB）

---

## 完整系統部署（全新安裝）

```bash
# 1. 複製儲存庫並初始化 submodule
git clone git@github.com:tm731531/simpleec-oms.git
cd simpleec-oms
git submodule update --init --recursive

# 2. 設定環境變數
cp .env.example .env
# 編輯 .env — 至少需設定：
#   DB_PASSWORD          PostgreSQL 密碼（預設：simpleec123）
#   CYBERBIZ_API_TOKEN   Cyberbiz HMAC 密鑰
#   WEBHOOK_SECRET_SHOPIFY, WEBHOOK_SECRET_SHOPEE, WEBHOOK_SECRET_EASYSTORE

# 3. 建置所有 Java 服務（跳過測試 — 目前尚無測試）
./gradlew clean build -x test
# 預期輸出：BUILD SUCCESSFUL

# 4. 啟動所有容器
docker compose up -d --build
# Kafka KRaft 初始化需約 30 秒；kafka-init 在此之後執行主題建立
# 請等待 2-3 分鐘讓整個堆疊達到健康狀態

# 5. 確認系統已啟動
docker compose ps                        # 所有容器應顯示「Up」或「Up (healthy)」
curl http://localhost:8082/api/health    # 預期：{"status":"UP"}
```

### 首次開機檢查清單

- [ ] `simpleec-postgres` 處於健康狀態（`pg_isready` 通過）
- [ ] `simpleec-kafka` 處於健康狀態（broker API versions 端點有回應）
- [ ] `simpleec-kafka-init` 以代碼 0 結束（主題已建立）
- [ ] `simpleec-api` 在連接埠 8082 通過健康檢查
- [ ] `simpleec-kafka-ui` 可透過 http://localhost:8088 存取

---

## 單服務重建（開發用途）

修改單一服務後，請使用 `quick-redeploy.sh`。它只會重建受影響的 Docker 映像並重啟容器，全程僅需 30–60 秒，而非完整堆疊重啟所需的 3–5 分鐘。

### quick-redeploy.sh 的運作方式

1. 確認服務名稱存在於 `docker-compose.yml`
2. 執行 `docker compose build --no-cache <service>` 重建映像
3. 停止並移除舊容器
4. 以 `docker compose up -d` 啟動新容器
5. 印出日誌追蹤指令

### 單一服務重建

```bash
# 修改 simpleec-api 原始碼後
./gradlew :simpleec-api:build -x test
./quick-redeploy.sh simpleec-api

# 修改 simpleec-order-job 原始碼後
./gradlew :simpleec-order-job:build -x test
./quick-redeploy.sh simpleec-order-job

# 修改 simpleec-channel-job 原始碼後（影響所有 14 個 channel 容器）
./gradlew :simpleec-channel-job:build -x test
./quick-redeploy.sh simpleec-channel-cyberbiz-slow
```

### 同時重建多個服務

```bash
# 共用模組變更影響多個服務時
./gradlew clean build -x test
./quick-redeploy.sh simpleec-channel-cyberbiz-fast simpleec-channel-cyberbiz-slow

# simpleec-core 變更同時影響 order job 與 backend job 時
./quick-redeploy.sh simpleec-order-job simpleec-backend-job
```

### 輔助旗標

```bash
./quick-redeploy.sh --list              # 依類別列出所有 26+ 個服務
./quick-redeploy.sh --deps simpleec-api # 顯示此服務的依賴項目
./quick-redeploy.sh --all               # 完整拆除並重建（需互動式確認）
./quick-redeploy.sh --help              # 完整使用說明
```

### 何時應使用 --all

以下情況請使用 `--all`：
- 修改了 `docker-compose.yml` 本身（環境變數、連接埠映射）
- 修改了基礎設施初始化腳本（`docker/init-db/`、`docker/init-kafka/`）
- 更新了基礎映像
- 系統重開機後（若容器未自動重啟）

---

## 僅啟動基礎設施

開發過程中若只需要資料層與可觀測性服務，可跳過 Java 應用程式容器：

```bash
docker compose up -d \
  postgres \
  redis \
  kafka \
  kafka-init \
  simpleec-kafka-ui \
  simpleec-prometheus \
  simpleec-grafana \
  simpleec-loki \
  simpleec-otel-collector \
  simpleec-tempo
```

再依需要個別啟動 Java 服務：

```bash
docker compose up -d simpleec-api
docker compose up -d simpleec-order-job
```

---

## 停止與清理

```bash
# 停止所有容器，保留 volumes（資料不會遺失）
docker compose down

# 停止所有容器並移除所有 volumes（警告：所有資料將遺失）
docker compose down -v

# 停止單一服務但不移除
docker compose stop simpleec-order-job

# 移除懸空的建置快取與映像
docker image prune -f
docker builder prune -f
```

---

## 容器清單（全部 26 個容器）

### 基礎設施層

| 容器 | 連接埠 | 用途 |
|-----------|------|---------|
| `simpleec-postgres` | 5433:5432 | PostgreSQL 16 — 主資料庫。對外公開於主機連接埠 5433，避免與本機 Postgres 衝突。 |
| `simpleec-redis` | 6379:6379 | Redis 7，啟用 AOF 持久化 — 訂單去重快取、統計髒集合、會話儲存。 |
| `simpleec-kafka` | 9092:9092 | Apache Kafka 3.7.1 KRaft 模式（無 ZooKeeper）。開發環境使用單一 broker。 |
| `simpleec-kafka-init` | — | 一次性容器，在首次開機時建立所有 Kafka 主題。成功後以代碼 0 結束。 |

### 可觀測性層

| 容器 | 連接埠 | 用途 |
|-----------|------|---------|
| `simpleec-kafka-ui` | 8088:8080 | Kafka UI — 瀏覽主題、consumer group 及訊息偏移量。 |
| `simpleec-prometheus` | 9090:9090 | Prometheus — 從 Spring Boot Actuator 端點抓取指標。 |
| `simpleec-grafana` | 3000:3000 | Grafana — JVM 指標、Kafka lag 及 API 延遲的儀表板。 |
| `simpleec-loki` | 3100:3100 | Loki — 日誌聚合，接收所有 Java 服務的 JSON 格式日誌。 |
| `simpleec-otel-collector` | 4317:4317 | OpenTelemetry Collector（OTLP gRPC）— 接收來自 OTEL agent 的追蹤資料。 |
| `simpleec-tempo` | 3200:3200 | Grafana Tempo — 分散式追蹤資料的儲存與查詢。 |

### Channel Job 層（14 個容器 — 7 個平台 × fast/slow）

每個平台運行兩個 consumer 實例：`fast`（動作類，預期 < 5 秒）和 `slow`（抓取/同步類，預期 < 5 分鐘）。

| 容器 | 消費的主題 | 並發數 |
|-----------|---------------|-------------|
| `simpleec-channel-momo-fast` | `momo.fast` | 3 |
| `simpleec-channel-momo-slow` | `momo.slow` | 3 |
| `simpleec-channel-shopee-fast` | `shopee.fast` | 3 |
| `simpleec-channel-shopee-slow` | `shopee.slow` | 3 |
| `simpleec-channel-yahoo-fast` | `yahoo.fast` | 3 |
| `simpleec-channel-yahoo-slow` | `yahoo.slow` | 3 |
| `simpleec-channel-pchome-fast` | `pchome.fast` | 3 |
| `simpleec-channel-pchome-slow` | `pchome.slow` | 3 |
| `simpleec-channel-cyberbiz-fast` | `cyberbiz.fast` | 3 |
| `simpleec-channel-cyberbiz-slow` | `cyberbiz.slow` | 3 |
| `simpleec-channel-shopline-fast` | `shopline.fast` | 3 |
| `simpleec-channel-shopline-slow` | `shopline.slow` | 3 |
| `simpleec-channel-shopify-fast` | `shopify.fast` | 3 |
| `simpleec-channel-shopify-slow` | `shopify.slow` | 3 |

所有 channel job 共用同一映像（`simpleec-channel-job`），透過 `JOB_CHANNEL_TOPICS` 和 `JOB_CHANNEL_GROUP_ID` 環境變數加以區分。

若要在 consumer lag 期間提升吞吐量，可在 `docker-compose.yml` 中提高 `JOB_CHANNEL_CONCURRENCY`（例如從 3 提高到 8），並重新部署受影響的容器。

### 系統 Job 層

| 容器 | 消費的主題 | 記憶體限制 | 用途 |
|-----------|---------------|-------------|---------|
| `simpleec-order-job` | `order.process` | 384 MB | 以雙層去重（Redis + DB）將訂單 upsert 至 PostgreSQL。 |
| `simpleec-scheduler-job` | `scheduler.heartbeat` | 192 MB | 心跳驅動的排程器 — 每 5 分鐘發布 FETCH_ORDERS、FETCH_RETURNS 及報表任務。 |
| `simpleec-backend-job` | `task.backend` | 384 MB | 內部非同步任務：SYNC_PRODUCT、STATS_RECALC、報表生成。 |
| `simpleec-frontend-job` | `task.frontend` | 192 MB | 由 API 路由的商家發起操作。 |
| `simpleec-retry-job` | `task.failed`、`task.dlt` | 192 MB | 重試失敗訊息，並將無法恢復的失敗路由至 DLT。 |

### API 與前端層

| 容器 | 連接埠 | 用途 |
|-----------|------|---------|
| `simpleec-api` | 8082:8080 | Spring Boot REST API — 商家與管理員端點，需要 JWT 認證。 |
| `simpleec-gateway` | 8081:8081 | Webhook 接收器，用於平台推送事件（Shopify、Shopee、Easystore），直接發布至 Kafka。 |
| `simpleec-user-app` | 5173:5173 | Vue 3 商家端 UI。 |
| `simpleec-admin-app` | 8084:8084 | Vue 3 管理員端 UI。 |
| `simpleec-nginx` | 8089:80、8090:443 | Nginx 反向代理 — 將 `/api/*` 路由至 simpleec-api，並提供兩個應用程式的靜態資源。 |
| `simpleec-test-seeder` | — | Python 測試資料生成器（profile：`test` — 預設不啟動）。 |

---

## 環境變數參考

這些變數由 Docker Compose 從 `.env` 讀取。`:-` 之後的值為未設定時使用的預設值。

| 變數 | 預設值 | 使用方 |
|----------|---------|---------|
| `DB_PASSWORD` | `simpleec123` | 所有 Java 服務 + postgres |
| `DATA_DIR` | `./data` | postgres、redis、kafka、prometheus、grafana、loki、tempo |
| `CYBERBIZ_API_TOKEN` | _（必填）_ | simpleec-channel-cyberbiz-fast/slow |
| `CYBERBIZ_API_BASE_URL` | `https://api.cyberbiz.co` | simpleec-channel-cyberbiz-fast/slow |
| `WEBHOOK_SECRET_SHOPIFY` | _（空白）_ | simpleec-gateway |
| `WEBHOOK_SECRET_SHOPEE` | _（空白）_ | simpleec-gateway |
| `WEBHOOK_SECRET_EASYSTORE` | _（空白）_ | simpleec-gateway |

敏感資訊絕對不可提交至版本控制，`.env` 檔案已加入 `.gitignore`。
