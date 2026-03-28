# 前置需求

在建置 SimpleEC OMS 之前，請確認您的機器符合以下所有需求。

---

## 必要軟體

| 工具 | 最低版本 | 用途 |
|---|---|---|
| **Docker** | 24.0+ | 容器執行環境 |
| **Docker Compose** | v2.20+（外掛版，非獨立執行檔） | 多容器編排 |
| **Java (JDK)** | 17 | 使用 Gradle 編譯 Spring Boot 模組 |
| **Git** | 2.30+ | 複製儲存庫與子模組管理 |
| **Node.js** | 18+ | 僅前端開發伺服器使用（純 Docker 部署可不需要） |

### 驗證已安裝的版本

```bash
docker --version
# Docker version 24.x 或更新版本

docker compose version
# Docker Compose version v2.x

java -version
# openjdk version "17.x.x" 或更新版本

git --version
# git version 2.x

node --version    # 僅前端開發時需要
# v18.x.x 或更新版本
```

> **注意：** Docker Compose v2 以 Docker CLI 外掛形式提供（`docker compose`），而非獨立執行檔（`docker-compose`）。本專案使用 `docker compose`（無連字號）。如果您只有獨立執行檔，請安裝外掛版本。

---

## 硬體需求

| 資源 | 最低 | 建議 |
|---|---|---|
| RAM | 12 GB | **16 GB** |
| 磁碟 | 10 GB 可用空間 | 20 GB 可用空間 |
| CPU | 4 核心 | 8 核心 |

**為什麼需要 16 GB？**
完整堆疊同時運行 26 個 Docker 容器，包含：
- PostgreSQL 16 + Redis 7（持久化儲存）
- Kafka 3.7.1 KRaft 模式（無 ZooKeeper，但仍佔用大量記憶體）
- 6 個 Spring Boot Job 服務（每個 JVM heap 約 300–500 MB）
- simpleec-api + simpleec-gateway（REST 層）
- OpenTelemetry Collector、Tempo、Loki、Prometheus、Grafana（可觀測性堆疊）
- Kafka UI

一般負載下 Docker 引擎使用 6–10 GB。12 GB 的機器可以運行，但可能偶爾使用 Swap；16 GB 可為開發與測試提供充裕的記憶體空間。

---

## 網路：必須釋放的連接埠

啟動堆疊前，以下連接埠必須在主機上未被佔用。
可用 `lsof -i :<port>` 或 `ss -tlnp | grep <port>` 進行確認。

| 連接埠 | 服務 |
|---|---|
| 5433 | PostgreSQL（對外映射，內部為 5432） |
| 6379 | Redis |
| 9092 | Kafka broker |
| 8081 | simpleec-gateway |
| 8082 | simpleec-api |
| 8088 | Kafka UI |
| 8089 | admin-app（nginx） |
| 8090 | user-app（nginx） |
| 5173 | user-app Vite 開發伺服器 |
| 8084 | admin-app Vite 開發伺服器 |
| 3000 | Grafana |
| 9090 | Prometheus |

---

## Docker 設定

Docker 必須設定為允許同時運行至少 **26 個容器**。大多數系統的 Docker Desktop 預設值即已足夠，但請確認：

1. Docker Desktop → Settings → Resources → Memory：設定為至少 **12 GB**
2. Docker Desktop → Settings → Resources → CPUs：設定為至少 **4**

在 Linux（不含 Docker Desktop 的 Docker Engine）上，容器限制由 cgroups 管控，預設通常無限制。

---

## .env 檔案

專案需要在儲存庫根目錄建立 `.env` 檔案，堆疊才能啟動。
專案已提供 `.env.example` 檔案，包含所有必要的金鑰與安全的開發預設值。

```bash
cp .env.example .env
# 然後編輯 .env 並填入必要的密鑰
```

在正式環境部署前**必須**更改的變數，已在 `.env.example` 中標記。
完整變數說明請參閱 [environment.md](environment.md)。

---

## 網路存取（平台整合用）

如果您打算測試實際平台 API 整合（非本機模擬資料），運行堆疊的機器必須能對外進行 HTTPS 連線至：

| 平台 | API 網域 |
|---|---|
| Cyberbiz | `api.cyberbiz.co` |
| Shopee | `partner.shopeemobile.com` |
| Momo | *（內部系統 — 請與 Momo 技術團隊協調）* |
| Yahoo | *（請與 Yahoo Commerce 團隊協調）* |
| PChome | *（請與 PChome API 團隊協調）* |
| Easystore | `api.easystore.co` |

初期本機開發時，使用示範/沙盒憑證即已足夠，各平台可在其沙盒環境中進行測試。
