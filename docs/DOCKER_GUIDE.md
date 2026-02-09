# SimpleEC OMS — Docker 環境使用手冊

## 快速開始

```bash
# 1. 複製環境變數（首次）
cp .env.example .env

# 2. 建置 & 啟動全部服務（26 個容器）
docker compose up -d --build

# 3. 確認服務健康
docker compose ps
```

## 架構總覽

```
                        ┌─────────────────────────────────────────────┐
                        │  Infrastructure                             │
                        │  ┌──────────┐ ┌───────┐ ┌───────────────┐  │
                        │  │PostgreSQL│ │ Redis │ │ Kafka (KRaft) │  │
                        │  │  :5433   │ │ :6379 │ │    :9092      │  │
                        │  └──────────┘ └───────┘ └───────────────┘  │
                        └─────────────────────────────────────────────┘
                                           │
          ┌────────────────────────────────┼──────────────────────────┐
          │                                │                          │
   ┌──────┴──────┐                  ┌──────┴──────┐           ┌───────┴──────┐
   │  API 層     │                  │  JOB 層     │           │ Observability│
   │  api :8082  │                  │ 8 channel   │           │ Grafana:3000 │
   │  gw  :8081  │                  │ 5 indep.    │           │ Prom   :9090 │
   └─────────────┘                  └─────────────┘           │ Loki   :3100 │
                                                              │ Tempo  :3200 │
                                                              │ KafkaUI:8088 │
                                                              └──────────────┘
```

### 服務清單

| 分類 | 服務 | Port | 說明 |
|------|------|------|------|
| **基礎設施** | postgres | 5433 | PostgreSQL 16 |
| | redis | 6379 | Redis 7（AOF 持久化） |
| | kafka | 9092 | Kafka 3.7.1 KRaft |
| **API** | simpleec-api | 8082 | 前端 API |
| | simpleec-gateway | 8081 | 對外 Gateway |
| **Channel JOB** | channel-{platform}-{fast/slow} | — | 8 個實例（momo/shopee/yahoo/pchome × fast/slow） |
| **獨立 JOB** | order-job, scheduler-job, backend-job, frontend-job, retry-job | — | 5 個實例 |
| **觀測** | grafana | 3000 | 統一儀表板 |
| | prometheus | 9090 | Metrics |
| | loki | 3100 | Logs |
| | tempo | 3200 | Traces |
| | otel-collector | 4317/4318 | OTEL 收集器 |
| | kafka-ui | 8088 | Kafka 管理 UI |

## 資料持久化

所有 stateful 服務的資料以 **bind mount** 方式存在外部目錄，不使用 Docker named volumes。

### 資料目錄結構

```
data/                          ← DATA_DIR（可透過 .env 設定）
  ├── postgres/                ← PostgreSQL 資料檔
  ├── redis/                   ← Redis AOF 持久化
  ├── kafka/                   ← Kafka KRaft 日誌
  ├── prometheus/              ← Prometheus 時序資料
  ├── loki/                    ← Loki 日誌資料（30 天保留）
  ├── tempo/                   ← Tempo 分散式追蹤
  └── grafana/                 ← Grafana 儀表板 & 設定
```

### 為什麼用 bind mount？

| 操作 | Named Volume | Bind Mount（目前） |
|------|-------------|-------------------|
| `docker compose down` | 資料保留 | 資料保留 |
| `docker compose down -v` | **資料刪除** | 資料保留 |
| 複製資料到另一個環境 | 需用 `docker volume` 指令 | **直接 `cp -a`** |
| 檢查資料檔案 | 需進容器或找 volume 路徑 | **直接 `ls data/`** |

## 常用操作

### 啟動 / 停止

```bash
# 啟動全部
docker compose up -d

# 只啟動基礎設施（開發時常用）
docker compose up -d postgres redis kafka

# 停止全部（資料保留）
docker compose down

# 查看日誌
docker compose logs -f simpleec-api
docker compose logs -f simpleec-channel-momo-slow

# 重啟單一服務
docker compose restart simpleec-order-job
```

### 重新建置

```bash
# 重建全部 Java 服務（code 有改動時）
docker compose up -d --build

# 只重建特定服務
docker compose up -d --build simpleec-api
```

### 資料庫操作

```bash
# 進入 psql
docker exec -it simpleec-postgres psql -U simpleec -d simpleec

# 列出所有表
docker exec simpleec-postgres psql -U simpleec -d simpleec -c '\dt'

# 查詢訂單
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT id, channel_order_id, order_status FROM orders LIMIT 10;"
```

### Redis 操作

```bash
# 進入 redis-cli
docker exec -it simpleec-redis redis-cli

# 查看所有 key
docker exec simpleec-redis redis-cli KEYS '*'

# 查看 Hash Dedup key
docker exec simpleec-redis redis-cli KEYS 'order:hash:*'
```

## 環境切換與平行測試

這是本架構的核心設計：可以用 `DATA_DIR` 指向不同目錄，達到環境隔離。

### 場景 1：複製現有資料做新版測試

```bash
# 1. 停掉目前環境
docker compose down

# 2. 複製資料
cp -a data/ data-v2/

# 3. 用新資料目錄啟動（修改 .env 或用環境變數覆蓋）
DATA_DIR=./data-v2 docker compose up -d

# 4. 在 data-v2 上做測試...

# 5. 測試完畢，切回原版
docker compose down
DATA_DIR=./data docker compose up -d
```

### 場景 2：從零開始（重跑 init-db）

```bash
# 刪除資料目錄，PostgreSQL 會重新執行 init-db/ 下的 SQL
docker compose down
rm -rf data/
docker compose up -d
```

### 場景 3：只重置資料庫，保留其他

```bash
docker compose down
rm -rf data/postgres/
docker compose up -d
# PostgreSQL 重建，Kafka/Redis/Grafana 保持原樣
```

### 場景 4：同時跑兩個版本（不同 port）

```bash
# 環境 A（預設 port）
DATA_DIR=./data docker compose -p simpleec-prod up -d

# 環境 B（需要另一份 docker-compose.override.yml 改 port）
# 或直接用不同的 compose project name + 手動指定 port
DATA_DIR=./data-staging docker compose -p simpleec-staging \
  --env-file .env.staging up -d
```

> **注意**：同時跑兩個 compose 需要避免 port 衝突。
> 建議用 `docker-compose.override.yml` 或 `.env.staging` 設不同 port。

## 環境變數

### `.env` 檔（Docker Compose 自動讀取）

```env
# 資料目錄（必填）
DATA_DIR=./data
```

### 服務內部環境變數（docker-compose.yml 已設定）

| 變數 | 值 | 說明 |
|------|-----|------|
| `DB_HOST` | postgres | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | simpleec | 資料庫名 |
| `DB_USER` | simpleec | 資料庫帳號 |
| `DB_PASSWORD` | simpleec123 | 資料庫密碼 |
| `REDIS_HOST` | redis | Redis host |
| `REDIS_PORT` | 6379 | Redis port |
| `KAFKA_BOOTSTRAP_SERVERS` | kafka:9092 | Kafka broker |
| `SPRING_PROFILES_ACTIVE` | docker | Spring Boot profile |

## 資料庫初始化

PostgreSQL 容器首次啟動時，會自動執行 `docker/init-db/` 下的 SQL 檔案（按檔名排序）：

| 檔案 | 說明 |
|------|------|
| `01-schema.sql` | 完整 DDL（19 張表，依 FK 順序建立） |
| `02-seed-data.sql` | 測試資料（1 商家、2 通路、3 商品、3 訂單等） |

> **注意**：init-db 只在 `data/postgres/` 不存在時執行。
> 如果要重跑，需先 `rm -rf data/postgres/`。

舊版 SQL 備份在 `docker/init-db/archive/`。

## 觀測工具

| 工具 | URL | 預設帳號 |
|------|-----|---------|
| Grafana | http://localhost:3000 | 匿名免登（Admin 權限） |
| Kafka UI | http://localhost:8088 | 免登 |
| Prometheus | http://localhost:9090 | 免登 |

詳細的 Kafka 操作指令請參考 [OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md)。

## 故障排除

### PostgreSQL 啟動失敗

```bash
# 查看日誌
docker compose logs postgres

# 常見原因：data/postgres/ 權限問題
ls -la data/postgres/

# 解法：重建
docker compose down
rm -rf data/postgres/
docker compose up -d postgres
```

### Kafka 啟動慢（30 秒以上）

Kafka KRaft 首次啟動需要初始化 cluster metadata，是正常的。
其他服務設有 `depends_on: kafka: condition: service_healthy`，會等待 Kafka 就緒。

### Redis 資料想清空但不影響其他

```bash
docker exec simpleec-redis redis-cli FLUSHALL
```

或刪除持久化檔案：

```bash
docker compose stop redis
rm -rf data/redis/
docker compose start redis
```

### 某個 JOB 一直重啟

```bash
# 查看該 JOB 的日誌
docker compose logs --tail=50 simpleec-channel-momo-fast

# 常見原因：
# 1. Kafka topic 不存在 → 需要先建立 topic
# 2. DB schema 不匹配 → 重建 data/postgres/
# 3. 依賴服務未就緒 → 等 healthcheck 通過
```

### 磁碟空間不足

```bash
# 查看各服務資料大小
du -sh data/*

# Kafka 日誌最大（retention=-1 永久保留）
# 如需清理，參考 OPERATIONS_RUNBOOK.md §5「完全清空 Topic」

# Docker 系統清理（清除未使用的 image/container/network）
docker system prune -f
```
