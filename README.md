# SimpleEC OMS - 簡易電商訂單管理系統

> Multi-Channel Order Management System
> 支持 7 個通路：Cyberbiz, PChome, MOMO, Shopline, Yahoo 購物中心, Shopee, Shopify

**Status**: MVP Phase 1 - 基礎設施完成，準備測試

---

## 🚀 快速開始 (3 分鐘)

### 1. 環境需求
```bash
Docker 20.10+
Docker Compose 2.0+
JDK 17+ (推薦用 sdkman)
```

### 2. 啟動系統
```bash
git clone https://github.com/tm731531/simpleec-oms.git
cd simpleec-oms
git checkout ops/production

# 啟動全部容器 (Infrastructure + Services)
docker-compose up -d

# 檢查容器狀態
docker-compose ps

# 查看日誌
docker-compose logs -f
```

### 3. 驗證系統
```bash
# API 健康檢查
curl http://localhost:8082/health

# Kafka UI (查看 topics)
# 瀏覽器: http://localhost:8088

# Grafana (監控儀表盤)
# 瀏覽器: http://localhost:3000 (admin/admin)
```

---

## 📊 系統架構 (3 層)

```
┌─────────────────────────────────────────┐
│ Scheduler Job + Heartbeat               │  <- 時間源
├─────────────────────────────────────────┤
│ Channel Jobs (8 instance)               │  <- 適配層
│ MOMO, Shopee, Yahoo, PChome (fast/slow) │
├─────────────────────────────────────────┤
│ Process Handlers                        │  <- 業務邏輯
│ ORDER_UPSERT, RETURN_UPSERT             │
└─────────────────────────────────────────┘
        ↓          ↓
    PostgreSQL  Redis
```

---

## 🔧 常用命令

### 構建服務
```bash
./gradlew clean build -x test
```

### Docker 操作
```bash
# 查看日誌 (實時)
docker-compose logs -f simpleec-api
docker-compose logs -f simpleec-order-job

# 進入數據庫
docker-compose exec postgres psql -U simpleec -d simpleec

# 進入 Redis
docker-compose exec redis redis-cli

# 停止所有容器
docker-compose down
docker-compose down -v  # 清除數據
```

### 數據庫
```bash
# 查看表
docker-compose exec postgres psql -U simpleec -d simpleec -c "\dt"

# 查看最新訂單
docker-compose exec postgres psql -U simpleec -d simpleec -c \
  "SELECT * FROM orders ORDER BY created_at DESC LIMIT 10;"
```

---

## 📈 監控與調試

### Grafana Dashboard (Port 3000)
- URL: http://localhost:3000
- 預設用戶: admin/admin

### Prometheus (Port 9090)
- URL: http://localhost:9090

### Kafka UI (Port 8088)
- URL: http://localhost:8088

### 應用日誌
```bash
docker-compose logs simpleec-api
docker-compose logs simpleec-channel-momo-fast
docker-compose logs simpleec-order-job
```

---

## 📁 文檔索引

### 快速參考
- **[ARCHITECTURE_OVERVIEW.md](./docs/ARCHITECTURE_OVERVIEW.md)** - 系統全景圖
- **[CORE_CONTRACTS.md](./docs/CORE_CONTRACTS.md)** - 16 個 Kafka Topic 定義
- **[CHANNEL_IMPLEMENTATION_GUIDE.md](./docs/CHANNEL_IMPLEMENTATION_GUIDE.md)** - 通路實作細節

### 深度學習
- **[DATA_FLOW_MAPPING.md](./docs/DATA_FLOW_MAPPING.md)** - Kafka 訊息流與 DB 映射
- **[PLATFORM_MAPPING.md](./docs/PLATFORM_MAPPING.md)** - 7 個通路 API 狀態轉換
- **[OPERATIONS_RUNBOOK.md](./docs/OPERATIONS_RUNBOOK.md)** - 營運手冊

---

## 📋 環境配置

```yaml
# Database
DB_HOST: postgres
DB_PORT: 5432
DB_NAME: simpleec
DB_USER: simpleec
DB_PASSWORD: simpleec123

# Redis
REDIS_HOST: redis
REDIS_PORT: 6379

# Kafka
KAFKA_BOOTSTRAP_SERVERS: kafka:9092
```

---

## 🌐 服務端點

| 服務 | URL | 說明 |
|------|-----|------|
| API | http://localhost:8082 | REST API |
| Gateway | http://localhost:8081 | Webhook 入口 |
| Grafana | http://localhost:3000 | 監控儀表板 |
| Kafka UI | http://localhost:8088 | Kafka 管理 |
| Prometheus | http://localhost:9090 | Metrics |
| PostgreSQL | localhost:5433 | DB |
| Redis | localhost:6379 | Cache |

---

## 🐛 故障排除

### 容器無法啟動
```bash
docker-compose logs simpleec-api
docker-compose ps  # 檢查依賴
docker-compose restart postgres kafka redis
```

### Kafka Lag 高
```bash
docker-compose logs kafka
docker-compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group channel-job-momo-fast \
  --describe
```

### 數據未進入 DB
```bash
# 檢查日誌
docker-compose logs simpleec-order-job

# 檢查去重
docker-compose exec redis redis-cli
> KEYS "dedup:order:*"
```

---

## 📝 分支說明

- **ops/production** ← 當前分支（運作環境）
- **main** - 穩定版本
- **docs-only** - 文檔專用分支

---

## 📚 完整文檔

| 文件 | 說明 |
|------|------|
| [DESIGN_v2.md](DESIGN_v2.md) | 完整系統設計 |
| [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) | 部署指南 |
| [CODE_STRUCTURE.md](CODE_STRUCTURE.md) | 代碼結構 |
| [docs/SCHEMA.md](docs/SCHEMA.md) | 資料庫 Schema |
| [docs/DOCKER_GUIDE.md](docs/DOCKER_GUIDE.md) | Docker 使用手冊 |
| [docs/](docs/) | 其他設計文件 |

---

**Last Updated**: 2026-02-20
**Version**: v0.1-MVP
**Branch**: ops/production

## License

Private — All rights reserved.
