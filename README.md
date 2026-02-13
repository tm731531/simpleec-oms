# SimpleEC OMS

多平台電商訂單管理系統 — 整合 Momo、Shopee、Yahoo、PChome、Cyberbiz 等通路，統一管理商品、訂單、出貨與庫存。

## 功能特色

- **多通路整合** — 同時對接多個電商平台，統一拉取商品與訂單
- **Kafka 事件驅動** — 訂單同步透過 3-JOB 串接（ChannelJob → OrderProcessJob → BackendJob），保證有序且可重試
- **Hash Dedup** — Redis SHA-256 去重，避免重複處理已同步的訂單
- **JSONB 訂單明細** — 訂單明細直接存 `orders.items` JSONB，簡化查詢
- **NanoID 主鍵** — 所有表使用 VARCHAR(20) NanoID，由應用程式產生
- **PII 加密** — 訂單買家欄位 AES-256-GCM 加密存儲，API 列表自動遮罩，詳情解鎖明文，CSV 匯出完整明文
- **全鏈路觀測** — OpenTelemetry + Grafana（Prometheus / Loki / Tempo）
- **資料可搬遷** — bind mount 外部資料目錄，支援環境複製與平行測試

## 技術架構

| 層級 | 技術 |
|------|------|
| 語言 | Java 17 |
| 框架 | Spring Boot 3.5.0 |
| 建置 | Gradle 8.14.4 |
| 資料庫 | PostgreSQL 16 |
| 快取 | Redis 7（AOF 持久化） |
| 訊息佇列 | Apache Kafka 3.7.1（KRaft，無 ZooKeeper） |
| ORM | MyBatis-Plus |
| 觀測 | OTEL Agent + Grafana + Prometheus + Loki + Tempo |
| 容器 | Docker Compose（26 個容器） |

## 模組結構

```
simpleec-oms/
├── simpleec-common          # 共用工具、模型、設定
├── simpleec-core            # 核心業務邏輯、Entity、Mapper
├── simpleec-channel         # 通路整合層（Adapter 介面）
├── simpleec-api             # 前端 REST API（:8082）
├── simpleec-gateway         # 對外 Gateway / Webhook / ERP（:8081）
├── simpleec-channel-job     # 通路同步 JOB（×10 實例：5 平台 × fast/slow）
├── simpleec-order-job       # 訂單處理 JOB
├── simpleec-scheduler-job   # 排程引擎（HeartbeatTimer）
├── simpleec-backend-job     # 後台非同步 JOB
├── simpleec-frontend-job    # 前台事件 JOB
├── simpleec-retry-job       # 失敗重試 / DLT 路由
├── docker/                  # Dockerfile × 8 + 觀測設定
└── docs/                    # 設計文件、Schema、事件流
```

## 快速開始

### 前置需求

- Java 17（推薦 [sdkman](https://sdkman.io/) 管理）
- Docker & Docker Compose v2
- Git

### 建置

```bash
git clone git@github.com:tm731531/simpleec-oms.git
cd simpleec-oms

# 編譯所有模組
./gradlew clean build -x test
```

### 啟動

```bash
# 1. 建立環境變數檔（首次）
cp .env.example .env

# 2. 建置映像檔並啟動全部 26 個容器
docker compose up -d --build

# 3. 確認服務健康
docker compose ps
```

### 服務端點

| 服務 | URL | 說明 |
|------|-----|------|
| API | http://localhost:8082 | 前端 REST API |
| Gateway | http://localhost:8081 | 對外 Gateway |
| Grafana | http://localhost:3000 | 觀測儀表板（免登入） |
| Kafka UI | http://localhost:8088 | Kafka 管理介面 |
| Prometheus | http://localhost:9090 | Metrics 查詢 |
| PostgreSQL | localhost:5433 | `simpleec` / `simpleec123` |
| Redis | localhost:6379 | |
| Kafka | localhost:9092 | |

## 資料持久化

所有 stateful 服務資料存在 `data/` 目錄（bind mount），不使用 Docker named volumes。

```bash
# 停止再啟動 — 資料不受影響
docker compose down
docker compose up -d

# 複製現有資料做新版測試
cp -a data/ data-v2/
DATA_DIR=./data-v2 docker compose up -d

# 從零開始（重跑 init-db SQL）
rm -rf data/
docker compose up -d
```

詳見 [docs/DOCKER_GUIDE.md](docs/DOCKER_GUIDE.md)。

## Kafka Topics（16 個）

| Topic | 用途 |
|-------|------|
| `{platform}.fast` | 即時操作（出貨確認、商品列表同步、庫存更新） |
| `{platform}.slow` | 排程操作（拉單、退貨同步、商品明細抓取） |
| `order.process` | 訂單整理入庫 |
| `task.backend` | 後台任務（統計、通知） |
| `task.frontend` | 前台任務 |
| `scheduler` | 排程心跳 |
| `task.failed` | 失敗重試佇列 |
| `task.dlt` | 死信佇列（30 天保留） |

> `{platform}` = momo / shopee / yahoo / pchome / cyberbiz

### 觸發模式

| 同步類型 | 觸發方式 | Topic |
|---------|---------|-------|
| 商品同步 (`FETCH_PRODUCTS`) | **手動** — 客戶逐通路點擊 | `{platform}.fast` |
| 商品明細 (`FETCH_PRODUCT_DETAIL`) | 自動 — 由商品同步發散 | `{platform}.slow` |
| 訂單同步 (`FETCH_ORDERS`) | **排程自動** — 每 5-10 分鐘 | `{platform}.slow` |
| 退貨同步 (`FETCH_REFUND_ORDERS`) | **排程自動** — 多層時間窗 | `{platform}.slow` |

## 資料庫

19 張表，完整 DDL 見 `docker/init-db/01-schema.sql`，測試資料見 `02-seed-data.sql`。

核心表：

| 表 | 說明 |
|----|------|
| `merchant` | 商家（租戶） |
| `channel` | 通路 / 賣場（FK → platform + merchant） |
| `product` | 商品 = SKU 級別 |
| `sell_pack` | 上架對應（product × channel） |
| `orders` | 訂單（items JSONB 存明細） |
| `daily_statistics` | 日統計（分區表） |

Schema 設計文件：[docs/SCHEMA.md](docs/SCHEMA.md)

## 文件索引

| 文件 | 說明 |
|------|------|
| [DESIGN_v2.md](DESIGN_v2.md) | 完整系統設計（API、JOB、前端、Kafka） |
| [docs/ABSTRACT_DESIGN.md](docs/ABSTRACT_DESIGN.md) | 抽象設計規格（模組合約、介面定義） |
| [docs/SCHEMA.md](docs/SCHEMA.md) | 資料庫 Schema v4 設計 |
| [docs/DOCKER_GUIDE.md](docs/DOCKER_GUIDE.md) | Docker 環境使用手冊 |
| [docs/OPERATIONS_RUNBOOK.md](docs/OPERATIONS_RUNBOOK.md) | Kafka 營運手冊 |
| [docs/STATISTICS_DESIGN.md](docs/STATISTICS_DESIGN.md) | 日統計設計 |
| [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | 實作計劃 |
| [docs/STATUS.md](docs/STATUS.md) | 開發進度追蹤 |
| [docs/event-flows/](docs/event-flows/) | 事件流設計（同步商品、拉單、抓取策略） |
| [docs/plans/](docs/plans/) | 架構設計文件（多通路架構、商品差異同步） |

## 開發指引

### 只啟動基礎設施

開發時不需要跑全部 26 個容器，可以只啟動基礎設施後用 IDE 跑 Spring Boot：

```bash
docker compose up -d postgres redis kafka otel-collector tempo loki prometheus grafana
```

### 重建單一服務

```bash
docker compose up -d --build simpleec-api
```

### 查看日誌

```bash
# API 日誌
docker compose logs -f simpleec-api

# 特定 Channel JOB
docker compose logs -f simpleec-channel-momo-slow
```

### 資料庫操作

```bash
# 進入 psql
docker exec -it simpleec-postgres psql -U simpleec -d simpleec

# 列出所有表
docker exec simpleec-postgres psql -U simpleec -d simpleec -c '\dt'
```

## License

Private — All rights reserved.
