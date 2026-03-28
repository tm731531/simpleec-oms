# 監控與可觀測性

SimpleEC OMS 使用完整的 Grafana LGTM 堆疊（Loki、Grafana、Tempo、Mimir 相容的 Prometheus），以及用於佇列健康狀態的 Kafka UI。每個 Java 服務都搭載 OpenTelemetry Java agent，能自動對 Spring Boot、Kafka clients 及 JDBC 進行埋點。

---

## 可觀測性工具清單

| 工具 | URL | 憑證 | 用途 |
|------|-----|-------------|---------|
| Grafana | http://localhost:3000 | admin / admin | 統一儀表板 — JVM 指標、Kafka lag、API 延遲、日誌探索、追蹤搜尋 |
| Prometheus | http://localhost:9090 | 無 | 原始指標抓取與 PromQL 查詢 |
| Loki | http://localhost:3100 | 無 | 日誌聚合（透過 Grafana → Explore → Loki 查詢） |
| Tempo | http://localhost:3200 | 無 | 分散式追蹤資料儲存（透過 Grafana → Explore → Tempo 查詢） |
| Kafka UI | http://localhost:8088 | 無 | Kafka 主題瀏覽器、consumer group lag、偏移量管理 |

---

## 關鍵監控指標

### Consumer Lag（最重要）

Consumer lag 是最主要的健康訊號。任何 lag > 0 且未持續下降的情況，均代表存在處理問題。

開啟 Kafka UI → **Consumer Groups** 即可查看所有 group 及其每個分區的 lag。

預期 consumer group 及其正常狀態：

| Consumer Group | 消費的主題 | 健康 lag |
|---------------|-----------------|-------------|
| `order-job-group` | `order.process` | 0 |
| `channel-job-momo` | `momo.fast`、`momo.slow` | 0 |
| `channel-job-shopee` | `shopee.fast`、`shopee.slow` | 0 |
| `channel-job-yahoo` | `yahoo.fast`、`yahoo.slow` | 0 |
| `channel-job-pchome` | `pchome.fast`、`pchome.slow` | 0 |
| `channel-job-cyberbiz` | `cyberbiz.fast`、`cyberbiz.slow` | 0 |
| `channel-job-shopline` | `shopline.fast`、`shopline.slow` | 0 |
| `channel-job-shopify` | `shopify.fast`、`shopify.slow` | 0 |
| `scheduler-dispatcher-group-v3` | `scheduler.heartbeat` | 0 |

若 lag 持續累積，請參閱[故障排除指南](troubleshooting.md)。

### JVM 記憶體（注意 OOM 風險）

所有 Java 服務均使用 `-XX:+ExitOnOutOfMemoryError`，容器遇到記憶體不足時會直接重啟，而非在降級狀態下繼續運行。各服務的記憶體限制如下：

| 服務 | Heap 限制 |
|---------|-----------|
| `simpleec-api` | 512 MB |
| `simpleec-order-job` | 384 MB |
| `simpleec-backend-job` | 384 MB |
| `simpleec-channel-*` | 各 256 MB |
| `simpleec-gateway` | 256 MB |
| `simpleec-scheduler-job` | 192 MB |
| `simpleec-frontend-job` | 192 MB |
| `simpleec-retry-job` | 192 MB |

在 Grafana → JVM 儀表板中，若 heap 使用率超過 85% 且持續超過 5 分鐘，應發出告警。

### API 回應時間

在 Grafana → Spring Boot Actuator 儀表板中，監控以下指標：
- 依 URI 和狀態碼分類的 `http_server_requests_seconds_count`
- 任何端點的 P99 延遲 > 2 秒值得關注
- 5xx 錯誤率 > 0 應立即調查

### 日誌錯誤率

```
# Loki 查詢：過去 5 分鐘任何服務的 ERROR 等級日誌
{job=~"simpleec-.*"} | json | level="ERROR"
```

---

## 使用 Loki 查詢日誌

所有日誌在 Docker 環境（profile `docker`）中以 JSON 格式輸出。透過 Grafana → Explore → 選擇 **Loki** 資料來源進行查詢。

### 常用查詢

```logql
# 特定商家的所有錯誤
{container="simpleec-order-job"} | json | level="ERROR" | merchantId="a00000"

# 特定通路的所有 ORDER_UPSERT 處理訊息
{container="simpleec-order-job"} | json | taskType="ORDER_UPSERT" | channelId="SHOPEE_001"

# 死信訊息（需要調查）
{container="simpleec-retry-job"} |= "task.dlt"

# 含錯誤詳情的失敗訊息
{container=~"simpleec-.*-job"} | json | level="ERROR" | line_format "{{.ts}} [{{.traceId}}] {{.msg}}"

# Cyberbiz API 呼叫（成功與失敗）
{container=~"simpleec-channel-cyberbiz-.*"} |= "Calling Cyberbiz API"

# 慢速 Kafka 處理警告（> 10 秒）
{container=~"simpleec-channel-.*"} |= "processing completed" | json | duration > 10000

# 今日所有被路由至 DLT 的訊息
{container=~"simpleec-.*"} |= "task.dlt" | json | __error__=""
```

### 每條日誌記錄中可用的 MDC 欄位

所有 Java 服務透過 `TaskMdcHelper.set()` 填充以下 MDC 欄位。在處理訊息期間，這些欄位會包含在每一行結構化日誌中：

| 欄位 | 範例 | 說明 |
|-------|---------|-------------|
| `traceId` | `4b3f2a1c8d...` | OpenTelemetry trace ID — 對應至 Tempo |
| `spanId` | `7e9a3b...` | OpenTelemetry span ID |
| `merchantId` | `a00000` | 正在處理的商家 |
| `taskType` | `ORDER_UPSERT` | 正在處理的 Kafka 任務類型 |
| `channelId` | `SHOPEE_001` | 通路實例 ID |

使用這些欄位可跨多個容器關聯同一業務操作的日誌。

---

## 檢查 Consumer Lag

### 透過 Kafka UI（推薦）

1. 開啟 http://localhost:8088
2. 點擊左側欄的 **Consumer Groups**
3. 點擊 group 名稱查看每個分區的 lag
4. 任何 lag > 0 且未持續下降的分區都需要注意

### 透過 CLI

```bash
# 列出所有 consumer group
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --list

# 檢查特定 group 的 lag
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group order-job-group \
  --describe

# 一次檢查所有 channel job group 的 lag
for group in momo shopee yahoo pchome cyberbiz shopline shopify; do
  echo "=== channel-job-${group} ==="
  docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --group "channel-job-${group}" \
    --describe 2>/dev/null | grep -v "^$"
done

# 查詢主題中的訊息數量
docker exec simpleec-kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic order.process
```

---

## 分散式追蹤

每個入站 HTTP 請求和每個 Kafka 訊息處理 span 都會由 OTEL Java agent 自動賦予 `traceId`。追蹤流程貫穿整個端到端路徑：

```
HTTP 請求 → simpleec-api
  → 發布至 Kafka (order.process)
    → simpleec-order-job 消費
      → 寫入 PostgreSQL
```

所有這些 span 共用同一個 `traceId`，讓你能夠完整重建任意請求的處理過程。

### 查找追蹤記錄

1. 從日誌行中取得 `traceId`（使用上方的 Loki 查詢，找尋 `"traceId":"..."`）
2. 開啟 Grafana → Explore → 選擇 **Tempo** 資料來源
3. 將 `traceId` 貼入搜尋框
4. 查看所有 span 的完整瀑布圖

### 在 Grafana 中關聯日誌與追蹤

Grafana 11 支援追蹤到日誌的關聯。若已設定：
1. 在 Tempo 中找到緩慢或失敗的追蹤記錄
2. 點擊任意 span
3. 點擊「Logs for this span」直接跳至相關的 Loki 日誌行

---

## Kafka 主題概覽

主題由 `simpleec-kafka-init` 依據 `docker/init-kafka/create-topics.sh` 中的設定預先建立。大多數主題的預設保留時間為 1 小時；DLT 保留 30 天。

| 主題 | 生產者 | 消費者 | 保留時間 |
|-------|-----------|-----------|-----------|
| `scheduler.heartbeat` | scheduler-job（HeartbeatTimer） | scheduler-job（SchedulerEventHandler） | 1 小時 |
| `scheduler` | scheduler-job | scheduler-job | 1 小時 |
| `{platform}.fast` | scheduler-job、API | channel-job-{platform} | 1 小時 |
| `{platform}.slow` | scheduler-job | channel-job-{platform} | 1 小時 |
| `order.process` | channel-job（ORDER_UPSERT） | order-job | 1 小時 |
| `return.process` | channel-job（RETURN_UPSERT） | order-job | 1 小時 |
| `task.backend` | scheduler-job、order-job | backend-job | 1 小時 |
| `task.frontend` | API | frontend-job | 1 小時 |
| `task.failed` | 任何 consumer 遇到可重試錯誤時 | retry-job | 1 天 |
| `task.dlt` | retry-job 遇到終態失敗時 | retry-job（持久化至 DB） | 30 天 |

---

## 告警建議

以下情況在正式環境應觸發告警：

| 條件 | 閾值 | 建議行動 |
|-----------|-----------|-----------------|
| `order.process` 的 consumer lag 持續累積 | Lag > 100 超過 2 分鐘 | 檢查 order-job 日誌；考慮提高並發數 |
| 容器在 10 分鐘內重啟次數 > 0 | 任何服務 | 檢查日誌確認是否為 OOM 或啟動失敗 |
| `task.dlt` 訊息數量持續增加 | > 5 條新訊息/小時 | 檢查 DLT 訊息；修復根本原因 |
| `simpleec-api` 5xx 錯誤率 | > 1% 的請求 | 檢查 API 日誌中的異常追蹤 |
| PostgreSQL 連線數 | > 180（上限：200） | 檢查是否有連線洩漏或慢查詢 |
| Kafka broker 離線 | 任何情況 | 系統停止運作 — 調查 kafka 容器 |
