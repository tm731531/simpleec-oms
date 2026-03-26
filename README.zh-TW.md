# SimpleEC OMS - 簡易電商訂單管理系統

**選擇語言**: [English](README.md) | [繁體中文](README.zh-TW.md) | [簡體中文](README.zh-CN.md) (計畫中) | [語言指南](LANGUAGE-GUIDE.md)

> 多通路訂單管理系統
> 支持 7 個通路：Cyberbiz、PChome、MOMO、Shopline、Yahoo 購物中心、Shopee、Shopify

**狀態**: ✅ **完全運作中 (FULLY OPERATIONAL)** - 系統運作、API 已修復、使用者事件流運作中 (2026 年 2 月 24 日)

> 📍 **最新信息**：[OPERATIONS_CURRENT_STATUS.md](docs/6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md) | 文檔索引 [DOCUMENTATION_INDEX.md](docs/0-START/DOCUMENTATION_INDEX.md) | 工作指示 [CLAUDE.md](CLAUDE.md)

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
bash scripts/start-all.sh

# 方式二：使用 Docker Compose
docker compose up -d

# 檢查容器狀態
docker compose ps

# 查看日誌
docker compose logs -f simpleec-api
```

### 3. 驗證系統
```bash
# 使用者應用程式登入（透過反向代理）
# 瀏覽器: http://localhost:8089
# 電子郵件: admin@a00000.com
# 密碼: pass123456

# API 直接測試
curl -X POST http://localhost:8083/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@a00000.com","password":"pass123456"}'

# Kafka UI (查看主題)
# 瀏覽器: http://localhost:8088

# 系統架構圖
# 詳見: https://localhost:8089/admin/ (管理應用程式)
```

---

## 📊 系統架構

### 前端架構（2026 年 2 月 22 日後）
```
┌──────────────────────────────────────────┐
│    Nginx 反向代理 (8089)                  │
│  路由: / → 使用者應用程式 (5173)          │
│  路由: /admin/ → 管理應用程式 (8084)      │
│  路由: /api/* → 後端 API (8083)           │
└────────────┬─────────────┬────────────────┘
             │             │
    ┌────────▼─────┐  ┌────▼──────────┐
    │ 使用者應用   │  │ 管理應用      │
    │ (5173)       │  │ (8084)        │
    │ Vue 3+Vite   │  │ Vue 3+Vite    │
    └──────────────┘  └───────────────┘
```

### 後端架構（事件驅動）
```
┌──────────────────────────────────────────┐
│ Kafka 消費者組 (8 個)                     │
├──────────────────────────────────────────┤
│ 通路任務: 通路資料同步 (5 個平台)        │
│ 訂單任務: 訂單/退貨處理                   │
│ 重試任務: 失敗消息處理 + DLT              │
│ 後端/前端/排程任務                        │
├──────────────────────────────────────────┤
│ Kafka 主題 (15 個)                        │
│ - 10 個平台 (cyberbiz/momo/.../shopee)   │
│ - 1 個訂單工作流 (order.process)         │
│ - 4 個系統 (scheduler, task.*, failed)   │
└────────┬──────────────┬──────────────────┘
         ↓              ↓
    PostgreSQL      Redis
    (16 個表)    (快取/去重)
```

---

## 🔧 常用命令

### 構建服務
```bash
./gradlew clean build -x test
```

### Docker 操作
```bash
# 查看日誌 (即時)
docker-compose logs -f simpleec-api
docker-compose logs -f simpleec-order-job

# 進入資料庫
docker-compose exec postgres psql -U simpleec -d simpleec

# 進入 Redis
docker-compose exec redis redis-cli

# 停止所有容器
docker-compose down
docker-compose down -v  # 清除資料
```

### 資料庫
```bash
# 查看表
docker-compose exec postgres psql -U simpleec -d simpleec -c "\dt"

# 查看最新訂單
docker-compose exec postgres psql -U simpleec -d simpleec -c \
  "SELECT * FROM orders ORDER BY created_at DESC LIMIT 10;"
```

---

## 📈 監控與調試

### Grafana 儀表板 (連接埠 3000)
- URL: http://localhost:3000
- 預設使用者: admin/admin

### Prometheus (連接埠 9090)
- URL: http://localhost:9090

### Kafka UI (連接埠 8088)
- URL: http://localhost:8088

### 應用程式日誌
```bash
docker-compose logs simpleec-api
docker-compose logs simpleec-channel-momo-fast
docker-compose logs simpleec-order-job
```

---

## 📁 文檔索引

### 快速參考
- **[ARCHITECTURE_OVERVIEW.md](docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md)** - 系統全景圖
- **[CORE_CONTRACTS.md](docs/3-EVENT-FLOW/CORE_CONTRACTS.md)** - 16 個 Kafka 主題定義
- **[CHANNEL_IMPLEMENTATION_GUIDE.md](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md)** - 通路實作細節

### 深度學習
- **[DATA_FLOW_MAPPING.md](docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md)** - Kafka 消息流與資料庫映射
- **[PLATFORM_MAPPING.md](docs/4-SCHEMA/PLATFORM_MAPPING.md)** - 7 個通路 API 狀態轉換
- **[OPERATIONS_RUNBOOK.md](docs/6-OPERATIONS/OPERATIONS_RUNBOOK.md)** - 營運手冊

---

## 📋 環境配置

```yaml
# 資料庫 (PostgreSQL 16)
SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/simpleec_oms
SPRING_DATASOURCE_USERNAME: postgres
SPRING_DATASOURCE_PASSWORD: postgres123

# Redis (快取與去重)
REDIS_HOST: redis
REDIS_PORT: 6379

# Kafka (KRaft 模式，無 ZooKeeper)
KAFKA_BOOTSTRAP_SERVERS: kafka:9092
KAFKA_LOG_RETENTION_HOURS: 1
KAFKA_LOG_SEGMENT_BYTES: 104857600  # 100MB

# API 認證
JWT_SECRET: (自動生成)

# 前端 API 端點偵測
# 本地: http://localhost:8083/api
# 遠端: /api (透過反向代理)
```

---

## 🌐 服務端點

### 前端（透過反向代理）
| 服務 | URL | 說明 |
|------|-----|------|
| Nginx 反向代理 | http://localhost:8089 | 統一入口 |
| 使用者應用程式 | http://localhost:8089/ | 商家端（訂單/通路管理） |
| 管理應用程式 | http://localhost:8089/admin/ | 平台端（商家/平台管理） |

### 後端（直接訪問）
| 服務 | URL | 說明 |
|------|-----|------|
| API | http://localhost:8083/api | REST API |
| Kafka UI | http://localhost:8088 | Kafka 消費組/主題監控 |
| PostgreSQL | localhost:5433 | 資料庫 |
| Redis | localhost:6379 | 快取與去重 |

### 前端開發訪問（不使用代理）
| 服務 | URL | 說明 |
|------|-----|------|
| 使用者應用程式 | http://localhost:5173 | 直接開發伺服器 |
| 管理應用程式 | http://localhost:8084 | 直接開發伺服器 |

### 外部訪問（Cloudflare）
| 服務 | URL | 說明 |
|------|-----|------|
| 使用者應用程式 | https://oms.tomting.com | 商家端公網 |
| 管理應用程式 | https://oms-admin.tomting.com | 平台端公網 |

---

## 🐛 故障排除

### 使用者應用程式登入失敗
**症狀**: 登入頁面無法登入（2026 年 2 月 23 日已修復）

**修復**:
- ✅ API 端點已更正：8082 → 8083
- ✅ 回應攔截器已修復：支援多種格式
- ✅ Docker 網路已修復：使用者應用程式連接到 simpleec-oms_default

**驗證**:
```bash
# 直接測試 API
curl -X POST http://localhost:8083/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@a00000.com","password":"pass123456"}'

# 檢查使用者應用程式網路
docker inspect simpleec-user-app | grep NetworkID
```

### Kafka 消費者組未建立
**症狀**: `kafka-consumer-groups.sh --list` 無輸出

**狀態**: ✅ 已修復（2026 年 2 月 23 日）- 8 個消費者組已運作

**根本原因** (已修復):
1. ServiceAutoConfiguration 自動載入 OrderService（非 JPA 任務失敗）
2. KafkaConfig bean 衝突（核心 + 任務的多重定義）
3. 過寬的元件掃描範圍

**驗證**:
```bash
# 檢查消費者組日誌
docker logs simpleec-channel-job | grep "groupId="

# 檢查 Kafka 主題
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

### 資料未進入資料庫
```bash
# 檢查訂單任務日誌
docker compose logs simpleec-order-job -f

# 檢查 Redis 去重
docker compose exec redis redis-cli
> KEYS "dedup:*" | head -20
```

---

## 📝 分支說明

- **ops/production** ← 目前分支（運作環境）
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
- **[docs/6-OPERATIONS/DEPLOYMENT.md](docs/6-OPERATIONS/DEPLOYMENT.md)** ⭐ - 部署指南 (2026 年 2 月 23 日已更新)
- **[docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md](docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md)** - 系統架構詳解
- **[docs/3-EVENT-FLOW/CORE_CONTRACTS.md](docs/3-EVENT-FLOW/CORE_CONTRACTS.md)** - Kafka 主題定義
- **[docs/4-SCHEMA/PLATFORM_MAPPING.md](docs/4-SCHEMA/PLATFORM_MAPPING.md)** - 7 個通路映射

### 深度學習
- **[docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md](docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md)** - 消息流與資料庫映射
- **[docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md)** - 通路實作
- **[docs/3-EVENT-FLOW/EVENT_SAMPLES.md](docs/3-EVENT-FLOW/EVENT_SAMPLES.md)** - 事件範例
- **[docs/](docs/)** - 完整技術文檔（9 大類別）

### 最新修復（2026 年 2 月 23 日）
- **[使用者應用程式登入修復](docs/6-OPERATIONS/DEPLOYMENT.md#user-app-login-fails)** - API 端點、回應格式、Docker 網路
- **[Kafka 消費者組修復](docs/6-OPERATIONS/DEPLOYMENT.md#kafka-consumer-groups-not-created)** - Bean 配置、掃描範圍調整
- **[系統保留策略](docs/6-OPERATIONS/RETENTION_CLEANUP_RUNBOOK.md)** - Kafka 1 小時、Prometheus 7 天、Loki 7 天

---

**最後更新**: 2026 年 2 月 23 日
**版本**: v0.1-MVP
**狀態**: ✅ 完全運作中
**分支**: main

## 授權

私有 — 保留所有權利。
