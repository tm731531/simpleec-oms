# 故障排除指南

以下各節從可觀察的症狀出發，逐步引導完成診斷與解決。請從與你所見症狀相符的部分開始。

---

## 容器持續重啟

```bash
# 首先，確認崩潰原因
docker compose logs --tail=100 <service-name>

# 檢查退出代碼
docker inspect <container-name> --format='{{.State.ExitCode}}'
# 退出代碼 137 = OOM kill（調高 -Xmx 或修復記憶體洩漏）
# 退出代碼 1   = JVM 啟動錯誤（仔細檢查日誌）
```

**常見原因與修復方式：**

| 原因 | 日誌訊號 | 修復方式 |
|-------|-----------|-----|
| 依賴服務未就緒 | `Connection refused` 連接 postgres/redis/kafka | 等待 — depends_on 健康檢查應處理此情況；若持續發生，請檢查依賴服務的容器 |
| 環境變數缺失 | 啟動時出現 `NullPointerException` 或 `Could not resolve placeholder` | 確認 `.env` 檔案存在且包含所需的變數 |
| 與主機進程埠號衝突 | `Address already in use: 5433` | 停止本機 PostgreSQL：`sudo systemctl stop postgresql` |
| 啟動時 OOM | 容器立即以代碼 137 結束 | 在 `docker-compose.yml` 中為該服務的 `JAVA_TOOL_OPTIONS` 調高 `-Xmx` |
| DB migration 失敗 | 日誌中出現 `Flyway` 或 schema 錯誤 | 確認 schema 是否不同步；檢查 `docker/init-db/01-schema.sql` |

---

## API 回傳 401 Unauthorized

```bash
# 測試認證端點
curl -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin@example.com","password":"yourpassword"}'

# 使用回傳的 token
curl http://localhost:8082/api/orders \
  -H "Authorization: Bearer <token>"
```

**可能原因：**

1. **Token 過期** — JWT token 有 7 天有效期。請重新登入取得新 token。

2. **Header 格式錯誤** — Header 必須為 `Authorization: Bearer <token>`。不含 `Bearer ` 前綴的 token 會被拒絕。

3. **JWT_SECRET 已更換** — 若密鑰已輪換，所有現有 token 均失效。請重新登入。

4. **存取受保護端點時未附帶認證資訊** — `/api/admin/**` 和 `/api/user/**` 下的端點需要有效的 JWT。公開端點（`/api/auth/login`、`/api/health`）無此要求。

5. **CORS preflight 失敗** — 若從不同來源的瀏覽器發出請求，請確認 API 設定中的 `CORS_ALLOWED_ORIGINS` 與前端 URL 一致。系統不使用萬用字元來源（`*`），來源必須明確列出。

---

## Kafka Consumer Lag 持續累積

當訊息的生產速度超過 consumer 的處理速度時，lag 就會累積。

```bash
# 步驟 1：識別哪些 group 有 lag
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --list

# 步驟 2：檢查有 lag 的 group
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group channel-job-cyberbiz \
  --describe

# 步驟 3：確認 consumer 容器正在運行且健康
docker compose ps simpleec-channel-cyberbiz-slow

# 步驟 4：檢查 consumer 日誌中的錯誤
docker compose logs simpleec-channel-cyberbiz-slow --tail=50
```

**診斷流程：**

```
Lag 持續累積？
├── 容器正在重啟 → 先修復啟動錯誤（見上方）
├── 容器運行中但未消費訊息
│   ├── 日誌中出現 MessageConversionException → StringDeserializer 設定問題
│   ├── 日誌中出現 ClassNotFoundException → scanBasePackages 缺少某個模組
│   └── 日誌中出現平台 API 認證錯誤
└── 容器在消費訊息但速度過慢
    ├── 平台 API 速率限制 → 正常現象，會自行恢復
    ├── 平台 API 逾時 → 檢查網路；新增重試機制
    └── 處理速度確實過慢 → 提高 JOB_CHANNEL_CONCURRENCY
```

**提高並發數（用於消耗 lag 積壓的暫時方案）：**

```bash
# 在 docker-compose.yml 中修改受影響服務的設定，例如：
#   JOB_CHANNEL_CONCURRENCY: '8'   （原為 '3'）

# 接著只重新部署該服務
./quick-redeploy.sh simpleec-channel-cyberbiz-slow

# 監控：lag 應在數分鐘內下降
# Kafka UI → Consumer Groups → channel-job-cyberbiz
```

注意：提高並發數會增加對平台 API 的請求量，請勿超出速率限制。

---

## 訂單未出現在儀表板

從平台到資料庫，逐步追蹤整個處理流程。

**步驟 1：channel job 是否有抓取訂單？**

```bash
docker compose logs simpleec-channel-cyberbiz-slow --tail=100 | grep -E "FETCH_ORDERS|ORDER_UPSERT|order list"
# 尋找：「Mode A order list processing completed」或「Successfully sent ORDER_UPSERT」
```

**步驟 2：order.process 是否收到訊息？**

在 Kafka UI → Topics → `order.process` → Messages 分頁，尋找符合預期通路的最新 ORDER_UPSERT 訊息。

**步驟 3：order-job 是否處理了訊息？**

```bash
docker compose logs simpleec-order-job --tail=100 | grep -E "ORDER_UPSERT|channelOrderId"
# 尋找：「Processing ORDER_UPSERT: <channelOrderId>」
# 以及：「Successfully processed ORDER_UPSERT: <channelOrderId>」
# 以及：「VERIFIED: Order ... successfully persisted to database」
```

**步驟 4：訂單是否在資料庫中？**

```bash
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT id, channel_order_id, order_status, total_amount, created_at
   FROM orders
   WHERE channel_order_id = 'YOUR_ORDER_ID';"
```

**步驟 5：Redis 去重 — 訂單是否被視為重複而跳過？**

```bash
docker compose logs simpleec-order-job --tail=200 | grep "already processed"
# 若看到「Order already processed (Redis hash match)」，表示自上次抓取以來訂單內容未變更
# 這對於未更動的訂單屬於正常現象
```

**步驟 6：PII 加密上下文**

若訂單已儲存但買家姓名/電話/Email 欄位為 null 或亂碼，請確認儲存前是否有呼叫 `EncryptionContext.setMerchantId()`：

```bash
docker compose logs simpleec-order-job --tail=200 | grep -i "encrypt\|cipher\|pii"
```

---

## BUILD FAILED

```bash
# 確認 Java 版本 — 必須是 17
java -version
# 預期：openjdk version "17.x.x"

# 確認 Gradle 版本
./gradlew --version
# 預期：Gradle 8.14.4

# 清除快取的建置狀態並重試
./gradlew clean build -x test

# 建置特定模組以縮小錯誤範圍
./gradlew :simpleec-api:build -x test
./gradlew :simpleec-order-job:build -x test
```

**常見建置錯誤：**

| 錯誤 | 原因 | 修復方式 |
|-------|-------|-----|
| `cannot find symbol: IdGenerator` | Lombok 未執行 | 清除 `.gradle/` 目錄並重新建置 |
| `error: package com.simpleec.common does not exist` | 模組依賴缺失 | 確認失敗模組的 `build.gradle` 是否包含 `implementation project(':simpleec-common')` |
| `Address already in use: 5432`（測試階段） | 本機 Postgres 在預設埠運行 | 停止本機 Postgres，或使用 `-x test` 跳過測試 |
| `Execution failed for task ':module:compileJava'` | 修改檔案中有語法錯誤 | 閱讀完整編譯器輸出 — 它會指出檔案名稱與行號 |

---

## 資料庫連線被拒絕

```bash
# 確認 postgres 容器正在運行且健康
docker compose ps simpleec-postgres
# 預期：Up (healthy)

# 從主機測試連線（注意：主機埠是 5433，不是 5432）
psql -h localhost -p 5433 -U simpleec -d simpleec
# 密碼：.env 中 DB_PASSWORD 的值（預設：simpleec123）

# 從 Java 服務容器內部測試連線
docker exec simpleec-api curl -s http://localhost:8080/actuator/health | python3 -m json.tool
# 查看 "db" 元件狀態

# 查看 postgres 日誌中的連線錯誤
docker compose logs simpleec-postgres --tail=50
```

若 postgres 本身健康但 Java 服務無法連線，請確認是否設定了 `DB_HOST=postgres`（而非 `localhost`）— 容器之間透過 Docker 內部 DNS 通訊，不使用主機網路。

---

## task.dlt 訊息持續堆積

當重試次數耗盡，或錯誤被判定為不可重試（例如：schema 版本不符、訊息格式錯誤），訊息就會進入 `task.dlt`。

```bash
# 查看 DLT 訊息
docker exec simpleec-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic task.dlt \
  --from-beginning \
  --max-messages 20

# 檢查 failed_task_logs 資料表（DLT consumer 會將訊息持久化至此）
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT task_type, error_type, error_message, created_at
   FROM failed_task_logs
   ORDER BY created_at DESC
   LIMIT 20;"

# 查看 retry-job 日誌中的路由決策
docker compose logs simpleec-retry-job --tail=100 | grep -E "DLT|dlt|terminal|non-retryable"
```

**DLT 常見根本原因：**

| `error_type` | 含義 | 修復方式 |
|-------------|---------|-----|
| `UNSUPPORTED_SCHEMA_VERSION` | 訊息的 schema 版本不被 consumer 支援 | 更新 consumer 以處理新版本，或重新發布正確版本的訊息 |
| `MALFORMED_MESSAGE` | 缺少必要的 header 或 body 欄位 | 檢查生產者 — 它發送了不完整的訊息 |
| `FORMAT_ERROR` | JSON 解析失敗 | 確認生產者發送的是有效的 JSON |
| `SERVER_ERROR_5XX` 超過最大重試次數後 | 持續性的下游失敗 | 修復底層服務錯誤，然後考慮手動重放 |

修復根本原因後，可透過 Kafka UI 的「Produce Message」功能或自訂的重播腳本，將 DLT 訊息重新發布至原始主題。

---

## 統計資料未更新

每日統計由 `DailyStatisticsService` 計算，由 `STATS_RECALC` 任務觸發。整個流程使用 Redis 髒集合追蹤哪些（商家、平台、通路、日期）組合需要重新計算。

```bash
# 確認 stats 髒標記是否有被寫入
docker compose logs simpleec-order-job --tail=100 | grep "stats dirty"
# 預期：「Marked stats dirty: a00000:SHOPEE:SHOPEE_001:2026-03-28」

# 確認 STATS_RECALC 是否有被派送
docker compose logs simpleec-scheduler-job --tail=100 | grep STATS_RECALC

# 確認 backend-job 是否有處理重新計算
docker compose logs simpleec-backend-job --tail=100 | grep -E "STATS_RECALC|recalculate|Stats upserted"

# 驗證 daily_statistics 資料表中的資料
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT merchant_id, platform_id, channel_id, stat_date,
          new_order_count, gross_amount, net_amount
   FROM daily_statistics
   ORDER BY stat_date DESC
   LIMIT 10;"
```

若 `daily_statistics` 資料表為空但訂單存在，可能的原因為：
1. `simpleec-backend-job` 未運行
2. Redis 髒集合未被填充（檢查 order-job 日誌）
3. `STATS_RECALC` 未被 scheduler-job 派送

---

## Cyberbiz API 認證失敗

```bash
# 在 channel job 日誌中尋找 401 回應
docker compose logs simpleec-channel-cyberbiz-slow --tail=100 | grep -E "401|authentication|HMAC|signature"
```

關於 Cyberbiz 認證的重要事項：
- API base URL 必須是 `https://api.cyberbiz.co`（不是 `.io`）
- HMAC 簽章字串格式：`x-date: {date}\n{METHOD} {PATH} HTTP/1.1` — 注意是字面換行符號，且不含 `request-line:` 前綴
- `.env` 中必須設定 `CYBERBIZ_API_TOKEN` 環境變數

若 API token 本身正確但請求仍回傳 401，請確認簽章格式是否完全符合 Cyberbiz 文件。

---

## Submodule（user-app）不在最新版本

```bash
# 確認 submodule 狀態
git submodule status

# 更新至父儲存庫中記錄的 commit
git submodule update --init --recursive

# 若需要拉取 submodule 遠端的最新 commit
git submodule update --remote user-app

# 更新後，重新建置 user-app 容器
./quick-redeploy.sh simpleec-user-app
```

---

## 快速確認整體系統健康狀態

執行以下命令序列，在 2 分鐘內取得完整的健康狀態快照：

```bash
# 1. 所有容器是否正常運行？
docker compose ps | grep -v " Up "
# 無輸出 = 所有容器健康

# 2. API 是否有回應？
curl -s http://localhost:8082/api/health | python3 -m json.tool

# 3. 是否有 consumer lag？
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --all-groups \
  --describe 2>/dev/null | awk '$6 > 0 {print $0}'
# 有任何輸出 = lag 存在（第 6 欄為 LAG）

# 4. order-job 是否有最新的錯誤？
docker compose logs simpleec-order-job --since 10m 2>/dev/null | grep ERROR | tail -20

# 5. 過去一小時是否有 DLT 訊息？
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT count(*) FROM failed_task_logs WHERE created_at > now() - interval '1 hour';"
```
