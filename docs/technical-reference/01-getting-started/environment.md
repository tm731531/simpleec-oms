# 環境變數參考手冊

所有設定均透過環境變數提供，從儲存庫根目錄的 `.env` 檔案載入。
執行 `docker compose up` 時，Docker Compose 會自動讀取此檔案。

修改前請先複製範本：

```bash
cp .env.example .env
```

「必要性」欄位標記為 **YES（正式環境）** 的變數，在部署至任何可公開存取的環境前，必須更改為非開發預設值。

---

## 核心基礎設施

這些變數控制每個 Spring Boot 服務如何連接共用基礎設施元件（PostgreSQL、Redis、Kafka）。

| 變數 | 預設值 | 必要性 | 說明 |
|---|---|---|---|
| `DB_HOST` | `simpleec-postgres` | 否 | PostgreSQL 主機名稱。預設值可在 Docker Compose 內部網路中解析。僅在使用外部資料庫時才需更改。 |
| `DB_PORT` | `5432` | 否 | PostgreSQL 連接埠。對外主機映射為 `5433 → 5432`；內部連接埠保持 `5432`。 |
| `DB_NAME` | `simpleec` | 否 | 資料庫名稱。 |
| `DB_PASSWORD` | `simpleec123` | **YES（正式環境）** | 資料庫密碼。正式環境必須更改。 |
| `REDIS_HOST` | `simpleec-redis` | 否 | Redis 主機名稱。 |
| `REDIS_PORT` | `6379` | 否 | Redis 連接埠。 |
| `KAFKA_BOOTSTRAP_SERVERS` | `simpleec-kafka:9092` | 否 | Kafka Bootstrap Server 位址。使用 Docker 內部網路主機名稱。 |

---

## API 服務（`simpleec-api`）

| 變數 | 預設值 | 必要性 | 說明 |
|---|---|---|---|
| `JWT_SECRET` | *（開發佔位符）* | **YES（正式環境）** | 用於簽署和驗證 JWT Token 的密鑰。必須至少 256 位元（32+ 個隨機位元組）。產生方式：`openssl rand -base64 64` |
| `JWT_EXPIRATION` | `604800` | 否 | Token 有效期（秒）。預設為 7 天（604800 秒）。可根據安全需求調整。 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080,...` | **YES（正式環境）** | 允許的 CORS 來源，以逗號分隔。在正式環境中請設定為前端的確切網域（例如 `https://app.example.com`）。不支援萬用字元。 |

---

## Channel Job 設定

每個 Channel Job 容器可獨立設定，允許以不同平台指派和並行設定運行多個實例。

| 變數 | 範例值 | 說明 |
|---|---|---|
| `JOB_CHANNEL_TOPICS` | `momo.fast,momo.slow` | 此 Job 實例訂閱的 Kafka Topic，以逗號分隔。每個平台有一個 `.fast` Topic（高優先級、短暫任務）和一個 `.slow` Topic（批次抓取作業）。 |
| `JOB_CHANNEL_GROUP_ID` | `channel-job-momo` | Kafka Consumer Group ID。每個平台必須唯一，確保各平台訊息獨立消費。 |
| `JOB_CHANNEL_CONCURRENCY` | `3` | 並行 Kafka 消費者執行緒數量。在平台 API 速率限制允許的情況下，可增加此值以提升吞吐量。目前正式環境設定為 `8`。 |

### 支援的 Topic 名稱

```
momo.fast      momo.slow
shopee.fast    shopee.slow
yahoo.fast     yahoo.slow
pchome.fast    pchome.slow
cyberbiz.fast  cyberbiz.slow
easystore.fast easystore.slow
```

---

## 平台 API 憑證

每個電商平台需要各自的 API 憑證。所有平台遵循相同的命名慣例：`{PLATFORM}_API_URL`、`{PLATFORM}_USERNAME`、`{PLATFORM}_SECRET`。

### Cyberbiz

| 變數 | 說明 |
|---|---|
| `CYBERBIZ_API_URL` | Cyberbiz API 基礎 URL。正式環境：`https://api.cyberbiz.co` |
| `CYBERBIZ_USERNAME` | Cyberbiz 提供的 API 帳號。示範帳號：`apidemo` |
| `CYBERBIZ_SECRET` | Cyberbiz 提供的 HMAC 簽名密鑰。示範密鑰：`apidemo` |

### Shopee

| 變數 | 說明 |
|---|---|
| `SHOPEE_API_URL` | Shopee Partner API 基礎 URL。 |
| `SHOPEE_PARTNER_ID` | Shopee Partner ID（數字格式）。 |
| `SHOPEE_PARTNER_KEY` | Shopee Partner 密鑰，用於 HMAC 簽名。 |

### Momo

| 變數 | 說明 |
|---|---|
| `MOMO_API_URL` | Momo Commerce API 基礎 URL。 |
| `MOMO_USERNAME` | API 帳號。 |
| `MOMO_SECRET` | API 密鑰。 |

### Yahoo

| 變數 | 說明 |
|---|---|
| `YAHOO_API_URL` | Yahoo Commerce API 基礎 URL。 |
| `YAHOO_CLIENT_ID` | OAuth Client ID。 |
| `YAHOO_CLIENT_SECRET` | OAuth Client Secret。 |

### Easystore

| 變數 | 說明 |
|---|---|
| `EASYSTORE_API_URL` | Easystore API 基礎 URL。 |
| `EASYSTORE_API_KEY` | 用於認證的 API 金鑰。 |

> **注意：** PChome 憑證在該平台整合實作後，將以類似方式設定。`.env.example` 中已預留對應的佔位符金鑰。

---

## Scheduler Job

| 變數 | 預設值 | 說明 |
|---|---|---|
| `SCHEDULER_CRON_FAST` | `0 */5 * * * *` | 快速通道心跳的 Cron 表達式（每 5 分鐘）。 |
| `SCHEDULER_CRON_SLOW` | `0 0 * * * *` | 慢速通道批次抓取的 Cron 表達式（每小時）。 |

---

## 可觀測性

| 變數 | 預設值 | 說明 |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://simpleec-otel:4317` | OpenTelemetry Collector gRPC 端點。追蹤、指標和日誌將送至此處，再分別轉發至 Tempo、Prometheus 和 Loki。 |
| `OTEL_SERVICE_NAME` | *（每個服務各自設定）* | 附加至所有遙測資料的服務名稱標籤。每個服務設定為自身的模組名稱（例如 `simpleec-api`）。 |

---

## Kafka Retention（進階）

Kafka Topic 的保留期間可透過 Spring 屬性映射這些變數進行調整。
開發環境使用預設值即可；正式環境可根據儲存空間需求調整。

| 變數 | 預設值 | 說明 |
|---|---|---|
| `KAFKA_RETENTION_DEFAULT` | `86400000` | 預設 Topic 保留時間（毫秒，1 天）。 |
| `KAFKA_RETENTION_DLT` | `2592000000` | 死信 Topic 保留時間（毫秒，30 天）。 |

---

## 正式環境安全性檢查清單

在部署至任何可從網際網路存取的環境前：

- [ ] `DB_PASSWORD` — 設定為強度足夠的隨機產生密碼（非預設值）
- [ ] `JWT_SECRET` — 設定為至少 256 位元的隨機字串（`openssl rand -base64 64`）
- [ ] `CORS_ALLOWED_ORIGINS` — 設定為正式前端的確切網域，不使用萬用字元
- [ ] 所有平台 API 憑證 — 設定為正式（非示範/沙盒）值
- [ ] `.env` 檔案 — 確認不在 git 歷史中（`git log --all -- .env` 顯示無結果）
- [ ] `.env` 檔案 — 權限限制為僅擁有者可讀寫（`chmod 600 .env`）
- [ ] Grafana 預設管理員密碼 — 首次登入後透過 UI 從 `admin/admin` 更改

---

## 在 Docker Compose 外部載入 .env

若需要在本機腳本或手動指令中使用 `.env` 檔案的變數：

```bash
# 將 .env 的所有變數匯出至當前 Shell 工作階段
set -a
source .env
set +a
```
