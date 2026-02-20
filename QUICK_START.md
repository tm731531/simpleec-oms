# SimpleEC OMS - Quick Start Guide

> **一行啟動完整的開發環境**

---

## ⚡ 最快啟動 (30 秒)

### 方式 1️⃣: 使用 Shell 腳本 (推薦)

```bash
bash quick-start.sh
```

✅ 自動檢查 Docker
✅ 自動創建數據目錄
✅ 自動構建和啟動容器
✅ 自動等待服務就緒
✅ 自動顯示訪問信息

### 方式 2️⃣: 使用 Makefile

```bash
make dev-up
```

### 方式 3️⃣: 直接使用 docker-compose

```bash
docker-compose -f docker-compose.dev.yml up -d
```

---

## 🎯 啟動後可以訪問的服務

| 服務 | URL | 用途 |
|------|-----|------|
| **API** | http://localhost:8082 | 主 API 服務 |
| **Gateway** | http://localhost:8081 | 對外 Gateway / Webhook |
| **Kafka UI** | http://localhost:8088 | Kafka 消息隊列管理 |
| **PgAdmin** | http://localhost:5050 | PostgreSQL 管理工具 |

### 數據庫連接信息

```
PostgreSQL:
  Host: localhost
  Port: 5433
  Database: simpleec
  Username: simpleec
  Password: simpleec123

Redis:
  Host: localhost
  Port: 6379

Kafka:
  Host: localhost
  Port: 9092
```

---

## 📋 常用命令

### 查看容器狀態

```bash
# 使用 Makefile
make ps
make dev-status

# 或直接使用 docker-compose
docker-compose -f docker-compose.dev.yml ps
```

### 查看日誌

```bash
# 查看所有容器的日誌
make logs

# 查看特定服務日誌
make dev-logs-api     # API 服務
make dev-logs-gw      # Gateway 服務

# 或直接使用 docker-compose
docker-compose -f docker-compose.dev.yml logs -f
docker-compose -f docker-compose.dev.yml logs -f simpleec-api
```

### 停止環境

```bash
# 使用 Makefile
make down

# 或使用 docker-compose
docker-compose -f docker-compose.dev.yml down
```

### 清空數據並重新啟動

```bash
# 使用 quick-start 腳本
bash quick-start.sh clean

# 使用 Makefile
make dev-clean

# 或手動
docker-compose -f docker-compose.dev.yml down -v
rm -rf data/*
docker-compose -f docker-compose.dev.yml up -d
```

### 構建服務鏡像

```bash
# 構建所有服務
make build

# 構建特定服務
make build-api
make build-gateway
```

---

## 🐛 調試和測試

### 進入容器 Shell

```bash
# API 容器
docker exec -it simpleec-api /bin/sh

# PostgreSQL 容器
docker exec -it simpleec-postgres psql -U simpleec -d simpleec

# Redis 容器
docker exec -it simpleec-redis redis-cli
```

### 測試 API 連接

```bash
# 檢查 API 健康狀態
curl http://localhost:8082/actuator/health

# 檢查 Gateway
curl http://localhost:8081/health
```

### 查看 Kafka 主題

```bash
# 進入 Kafka 容器
docker exec -it simpleec-kafka bash

# 列出所有主題
/opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092

# 查看特定主題的消息
/opt/kafka/bin/kafka-console-consumer.sh --topic order.process --bootstrap-server localhost:9092
```

---

## 📁 項目結構

```
simpleec-oms/
├── docker-compose.dev.yml      ← 開發環境配置 (快速啟動)
├── docker-compose.yml          ← 完整生產環境配置
├── docker-compose.prod.yml     ← 生產環境備用
├── Makefile                    ← 簡化命令
├── quick-start.sh              ← 一鍵啟動腳本
├── .env.dev                    ← 開發環境變數
├── docker/                     ← Dockerfile 和配置
│   ├── Dockerfile.api          ← API 服務
│   ├── Dockerfile.gateway      ← Gateway 服務
│   ├── init-db/                ← 數據庫初始化腳本
│   ├── prometheus/             ← Prometheus 配置
│   ├── loki/                   ← Loki 配置
│   └── ...
└── simpleec-core/              ← Java 項目源碼
```

---

## 🔧 系統需求

### 最低要求

- **Docker**: 20.10+ 版本
- **docker-compose**: 1.29+ 版本 (或 Docker Desktop)
- **磁盤空間**: 至少 5GB (用於數據和鏡像)
- **內存**: 至少 4GB 分配給 Docker
- **CPU**: 2+ 核心

### 推薦配置

- **磁盤**: 10GB+
- **內存**: 8GB+ 分配給 Docker
- **CPU**: 4+ 核心

### 檢查系統

```bash
# 檢查 Docker 版本
docker --version
docker-compose --version

# 檢查 Docker 資源使用
docker stats

# 檢查可用磁盤空間
df -h
```

---

## ❌ 常見問題

### Q: 容器無法啟動？

**A**: 檢查日誌找出原因：
```bash
docker-compose -f docker-compose.dev.yml logs
```

常見原因：
- 端口被占用：更改 docker-compose.dev.yml 中的端口
- 內存不足：分配更多內存給 Docker
- 磁盤空間不足：清空 Docker 未使用的資源

### Q: 如何修改端口？

**A**: 編輯 `docker-compose.dev.yml`，找到相應的 `ports` 部分修改。

例如，將 API 端口從 8082 改為 9000：
```yaml
simpleec-api:
  ports:
    - "9000:8080"  # 改這裡
```

### Q: 如何持久化數據？

**A**: 默認數據保存在 `./data/` 目錄中。只要不刪除這個目錄，數據就會被保留。

### Q: 容器佔用太多空間？

**A**: 清理未使用的資源：
```bash
# 清空容器和鏡像
docker system prune -a

# 清空所有卷
docker volume prune

# 完全重置
bash quick-start.sh clean
```

### Q: 如何查看詳細日誌？

**A**:
```bash
# 查看所有日誌（帶時間戳）
docker-compose -f docker-compose.dev.yml logs -f --timestamps

# 查看最後 100 行
docker-compose -f docker-compose.dev.yml logs --tail=100

# 查看特定時間段
docker-compose -f docker-compose.dev.yml logs --since 10m
```

### Q: 如何在本機訪問數據庫？

**A**: 使用本機的 PostgreSQL 客戶端或 PgAdmin：

```bash
# 使用 psql (需要安裝 PostgreSQL)
psql -h localhost -p 5433 -U simpleec -d simpleec

# 或訪問 PgAdmin
http://localhost:5050
# 郵箱: admin@simpleec.local
# 密碼: admin
```

---

## 📚 進階用法

### 修改 Java 應用配置

編輯 `.env.dev` 或在啟動時設置環境變數：

```bash
SPRING_PROFILES_ACTIVE=dev docker-compose -f docker-compose.dev.yml up -d
```

### 開啟 DEBUG 模式

```bash
# 編輯 .env.dev，設置
DEBUG=true
LOGGING_LEVEL_COM_SIMPLEEC=DEBUG

# 重啟容器
make restart
```

### 使用外部數據庫

編輯 `docker-compose.dev.yml`，設置 `DB_HOST` 為外部 IP：

```yaml
environment:
  DB_HOST: 192.168.1.100  # 外部數據庫 IP
  DB_PORT: 5432
```

### 監控應用性能

訪問 Prometheus 指標：
```
http://localhost:9090
```

查看 API 指標：
```
curl http://localhost:8082/actuator/prometheus
```

---

## 🎯 快速檢查清單

啟動後，確保以下服務都在運行：

- [ ] PostgreSQL 已就緒 (port 5433)
- [ ] Redis 已就緒 (port 6379)
- [ ] Kafka 已就緒 (port 9092)
- [ ] API 服務已就緒 (port 8082)
- [ ] Gateway 已就緒 (port 8081)
- [ ] Kafka UI 可訪問 (port 8088)
- [ ] PgAdmin 可訪問 (port 5050)

檢查所有服務：
```bash
curl http://localhost:8082/actuator/health
curl http://localhost:8081/health
docker-compose -f docker-compose.dev.yml ps
```

---

## 📖 完整命令參考

| 命令 | 說明 |
|------|------|
| `bash quick-start.sh` | 一鍵啟動開發環境 |
| `bash quick-start.sh stop` | 停止環境 |
| `bash quick-start.sh clean` | 清空數據並重啟 |
| `make help` | 查看所有 Makefile 命令 |
| `make dev-up` | 啟動環境 |
| `make dev-down` | 停止環境 |
| `make dev-logs` | 查看日誌 |
| `make dev-ps` | 查看容器狀態 |
| `make build` | 構建所有服務 |
| `docker-compose -f docker-compose.dev.yml up -d` | 直接啟動 |

---

## 🚀 下一步

1. **啟動開發環境**: `bash quick-start.sh`
2. **訪問 API**: http://localhost:8082
3. **查看 Kafka 消息**: http://localhost:8088
4. **管理數據庫**: http://localhost:5050
5. **開始開發**! 📝

---

## 📞 需要幫助？

```bash
# 查看所有可用命令
make help

# 查看詳細日誌
docker-compose -f docker-compose.dev.yml logs -f

# 檢查容器狀態
docker-compose -f docker-compose.dev.yml ps

# 進入應用容器調試
docker exec -it simpleec-api /bin/sh
```

---

**Happy Coding!** 🎉

祝你開發順利！如有問題，查看日誌或檢查網絡連接。
