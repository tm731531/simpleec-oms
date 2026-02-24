# SimpleEC OMS - 簡易電商訂單管理系統

> Multi-Channel Order Management System
> 支持 7 個通路：Cyberbiz, PChome, MOMO, Shopline, Yahoo 購物中心, Shopee, Shopify

**Status**: ✅ **完全操作中 (FULLY OPERATIONAL)** - All systems running, API fixed, User event flow working (Feb 24, 2026)

> 📍 **最新信息**：[OPERATIONS_CURRENT_STATUS.md](docs/6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md) | 文档索引 [DOCUMENTATION_INDEX.md](docs/0-START/DOCUMENTATION_INDEX.md) | 工作指示 [CLAUDE.md](CLAUDE.md)

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

# 方式一：使用啟動腳本（推薦）
bash start-all.sh

# 方式二：使用 Docker Compose
docker compose up -d

# 檢查容器狀態
docker compose ps

# 查看日誌
docker compose logs -f simpleec-api
```

### 3. 驗證系統
```bash
# User App 登入（經由反向代理）
# 瀏覽器: http://localhost:8089
# Email: admin@a00000.com
# Password: pass123456

# API 直接測試
curl -X POST http://localhost:8083/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@a00000.com","password":"pass123456"}'

# Kafka UI (查看 topics)
# 瀏覽器: http://localhost:8088

# 系統架構圖
# 詳見: https://localhost:8089/admin/ (Admin App)
```

---

## 📊 系統架構

### 前端架構（Feb 22+）
```
┌──────────────────────────────────────────┐
│    Nginx 反向代理 (8089)                  │
│  Route: / → User App (5173)               │
│  Route: /admin/ → Admin App (8084)        │
│  Route: /api/* → Backend API (8083)       │
└────────────┬─────────────┬────────────────┘
             │             │
    ┌────────▼─────┐  ┌────▼──────────┐
    │ User App     │  │ Admin App     │
    │ (5173)       │  │ (8084)        │
    │ Vue 3+Vite   │  │ Vue 3+Vite    │
    └──────────────┘  └───────────────┘
```

### 後端架構（消費者驅動）
```
┌──────────────────────────────────────────┐
│ Kafka 消費者組 (8 groups)                 │
├──────────────────────────────────────────┤
│ ChannelJob: 通路資料同步 (5 platform)    │
│ OrderJob: 訂單/退貨處理                   │
│ RetryJob: 失敗消息處理 + DLT              │
│ BackendJob, FrontendJob, SchedulerJob    │
├──────────────────────────────────────────┤
│ Kafka Topics (15 個)                      │
│ - 10 platform (cyberbiz/momo/.../shopee) │
│ - 1 order workflow (order.process)       │
│ - 4 system (scheduler, task.*, failed)   │
└────────┬──────────────┬──────────────────┘
         ↓              ↓
    PostgreSQL      Redis
    (16 tables)   (cache/dedup)
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
- **[ARCHITECTURE_OVERVIEW.md](docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md)** - 系統全景圖
- **[CORE_CONTRACTS.md](docs/3-EVENT-FLOW/CORE_CONTRACTS.md)** - 16 個 Kafka Topic 定義
- **[CHANNEL_IMPLEMENTATION_GUIDE.md](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md)** - 通路實作細節

### 深度學習
- **[DATA_FLOW_MAPPING.md](docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md)** - Kafka 訊息流與 DB 映射
- **[PLATFORM_MAPPING.md](docs/4-SCHEMA/PLATFORM_MAPPING.md)** - 7 個通路 API 狀態轉換
- **[OPERATIONS_RUNBOOK.md](docs/6-OPERATIONS/OPERATIONS_RUNBOOK.md)** - 營運手冊

---

## 📋 環境配置

```yaml
# Database (PostgreSQL 16)
SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/simpleec_oms
SPRING_DATASOURCE_USERNAME: postgres
SPRING_DATASOURCE_PASSWORD: postgres123

# Redis (Cache & Dedup)
REDIS_HOST: redis
REDIS_PORT: 6379

# Kafka (KRaft mode, no ZooKeeper)
KAFKA_BOOTSTRAP_SERVERS: kafka:9092
KAFKA_LOG_RETENTION_HOURS: 1
KAFKA_LOG_SEGMENT_BYTES: 104857600  # 100MB

# API 認證
JWT_SECRET: (auto-generated)

# 前端 API 端點偵測
# 本地: http://localhost:8083/api
# 遠端: /api (經由反向代理)
```

---

## 🌐 服務端點

### 前端（通過反向代理）
| 服務 | URL | 說明 |
|------|-----|------|
| Nginx 反向代理 | http://localhost:8089 | 統一入口 |
| User App | http://localhost:8089/ | 商家端（訂單/通路管理） |
| Admin App | http://localhost:8089/admin/ | 平台端（商家/平台管理） |

### 後端（直接訪問）
| 服務 | URL | 說明 |
|------|-----|------|
| API | http://localhost:8083/api | REST API |
| Kafka UI | http://localhost:8088 | Kafka 消費組/Topics 監控 |
| PostgreSQL | localhost:5433 | 數據庫 |
| Redis | localhost:6379 | Cache 與去重 |

### 前端開發訪問（不使用代理）
| 服務 | URL | 說明 |
|------|-----|------|
| User App | http://localhost:5173 | 直接開發伺服器 |
| Admin App | http://localhost:8084 | 直接開發伺服器 |

### 外部訪問（Cloudflare）
| 服務 | URL | 說明 |
|------|-----|------|
| User App | https://oms.tomting.com | 商家端公網 |
| Admin App | https://oms-admin.tomting.com | 平台端公網 |

---

## 🐛 故障排除

### User App 登入失敗
**症狀**: 登入頁面無法登入（Feb 23 已修復）

**修復**:
- ✅ API 端點已更正：8082 → 8083
- ✅ Response interceptor 已修復：支援多種格式
- ✅ Docker 網路已修復：User App 連接到 simpleec-oms_default

**驗證**:
```bash
# 直接測試 API
curl -X POST http://localhost:8083/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@a00000.com","password":"pass123456"}'

# 檢查 User App 網路
docker inspect simpleec-user-app | grep NetworkID
```

### Kafka 消費者組未建立
**症狀**: `kafka-consumer-groups.sh --list` 無輸出

**狀態**: ✅ 已修復（Feb 23）- 8 個消費者組已運作

**根本原因** (已修復):
1. ServiceAutoConfiguration 自動載入 OrderService（非 JPA jobs 失敗）
2. KafkaConfig bean 衝突（core + job 的多重定義）
3. 過寬的組件掃描範圍

**驗證**:
```bash
# 檢查消費者組日誌
docker logs simpleec-channel-job | grep "groupId="

# 檢查 Kafka topics
docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

### 容器無法啟動
```bash
# 查看詳細日誌
docker compose logs simpleec-api --tail 100

# 檢查依賴服務
docker compose ps

# 重新啟動基礎設施
docker compose restart postgres kafka redis
```

### 數據未進入 DB
```bash
# 檢查 OrderJob 日誌
docker compose logs simpleec-order-job -f

# 檢查 Redis 去重
docker compose exec redis redis-cli
> KEYS "dedup:*" | head -20
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
| [docs/1-ARCHITECTURE/DESIGN_v2.md](docs/1-ARCHITECTURE/DESIGN_v2.md) | 完整系統設計 |
| [docs/6-OPERATIONS/DEPLOYMENT_GUIDE.md](docs/6-OPERATIONS/DEPLOYMENT_GUIDE.md) | 部署指南 |
| [docs/1-ARCHITECTURE/CODE_STRUCTURE.md](docs/1-ARCHITECTURE/CODE_STRUCTURE.md) | 代碼結構 |
| [docs/4-SCHEMA/SCHEMA.md](docs/4-SCHEMA/SCHEMA.md) | 資料庫 Schema |
| [docs/6-OPERATIONS/DOCKER_GUIDE.md](docs/6-OPERATIONS/DOCKER_GUIDE.md) | Docker 使用手冊 |
| [docs/](docs/) | 其他設計文件 |

---

## 📖 詳細文檔

### 快速參考
- **[docs/6-OPERATIONS/DEPLOYMENT.md](docs/6-OPERATIONS/DEPLOYMENT.md)** ⭐ - 部署指南 (Feb 23 已更新)
- **[docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md](docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md)** - 系統架構詳解
- **[docs/3-EVENT-FLOW/CORE_CONTRACTS.md](docs/3-EVENT-FLOW/CORE_CONTRACTS.md)** - Kafka Topics 定義
- **[docs/4-SCHEMA/PLATFORM_MAPPING.md](docs/4-SCHEMA/PLATFORM_MAPPING.md)** - 7 個通路映射

### 深度學習
- **[docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md](docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md)** - 消息流與 DB 映射
- **[docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md)** - 通路實作
- **[docs/3-EVENT-FLOW/EVENT_SAMPLES.md](docs/3-EVENT-FLOW/EVENT_SAMPLES.md)** - 事件範例
- **[docs/](docs/)** - 完整技術文檔（9 大類別）

### 最新修復（Feb 23）
- **[User App 登入修復](docs/6-OPERATIONS/DEPLOYMENT.md#user-app-login-fails)** - API 端點、Response 格式、Docker 網路
- **[Kafka 消費者組修復](docs/6-OPERATIONS/DEPLOYMENT.md#kafka-consumer-groups-not-created)** - Bean 配置、掃描範圍調整
- **[系統保留策略](docs/6-OPERATIONS/RETENTION_CLEANUP_RUNBOOK.md)** - Kafka 1h, Prometheus 7d, Loki 7d

---

**Last Updated**: 2026-02-23
**Version**: v0.1-MVP
**Status**: ✅ Fully Operational
**Branch**: main

## License

Private — All rights reserved.
