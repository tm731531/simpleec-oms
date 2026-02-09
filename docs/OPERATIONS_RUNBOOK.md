# SimpleEC OMS — Kafka 營運手冊

## 工具一覽

| 工具 | URL | 用途 |
|------|-----|------|
| Grafana | http://localhost:3000 | 統一儀表板（Metrics + Logs + Traces） |
| Kafka UI | http://localhost:8088 | Topic 瀏覽、Consumer Group 管理、訊息查看 |
| Prometheus | http://localhost:9090 | 直接查詢 Metrics |
| Tempo | http://localhost:3200 | Trace 查詢（通常透過 Grafana） |
| Loki | http://localhost:3100 | Log 查詢（通常透過 Grafana） |

## Topic 清單

| Topic | Partitions | 用途 | Retention |
|-------|-----------|------|-----------|
| `momo.fast` | 8 | Momo 即時操作 | 永久 |
| `momo.slow` | 8 | Momo 排程操作 | 永久 |
| `shopee.fast` | 8 | Shopee 即時操作 | 永久 |
| `shopee.slow` | 8 | Shopee 排程操作 | 永久 |
| `yahoo.fast` | 8 | Yahoo 即時操作 | 永久 |
| `yahoo.slow` | 8 | Yahoo 排程操作 | 永久 |
| `pchome.fast` | 8 | PCHome 即時操作 | 永久 |
| `pchome.slow` | 8 | PCHome 排程操作 | 永久 |
| `order.process` | 8 | 訂單處理 | 永久 |
| `task.backend` | 8 | 後台任務 | 永久 |
| `task.frontend` | 8 | 前台任務 | 永久 |
| `scheduler` | 4 | 排程心跳 | 永久 |
| `task.failed` | 4 | 失敗重打佇列 | 永久 |
| `task.dlt` | 4 | 死信佇列（最終站） | 30 天 |

## 常用操作

### 1. 查看 Consumer Group Lag

```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group channel-job-momo-fast \
  --describe
```

或直接到 Kafka UI → Consumer Groups 頁面查看。

### 2. 清理垃圾訊息（跳過到最新）

**場景**：Topic 裡有大量無效訊息，想全部跳過。

```bash
# 1. 停止 consumer
docker compose stop simpleec-channel-momo-fast

# 2. Reset offset 到最新
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group channel-job-momo-fast \
  --topic momo.fast \
  --reset-offsets --to-latest --execute

# 3. 重啟 consumer
docker compose start simpleec-channel-momo-fast
```

### 3. 跳過 N 則訊息

```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group channel-job-momo-fast \
  --topic momo.fast \
  --reset-offsets --shift-by 100 --execute
```

### 4. 從特定時間點重播

```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group channel-job-momo-fast \
  --topic momo.fast \
  --reset-offsets --to-datetime 2026-02-09T00:00:00.000 --execute
```

### 5. 完全清空 Topic

```bash
# 暫時設 retention 為 1 秒
docker exec simpleec-kafka /opt/kafka/bin/kafka-configs.sh \
  --bootstrap-server localhost:9092 \
  --alter --entity-type topics --entity-name momo.fast \
  --add-config retention.ms=1000

# 等待清理
sleep 5

# 還原 retention
docker exec simpleec-kafka /opt/kafka/bin/kafka-configs.sh \
  --bootstrap-server localhost:9092 \
  --alter --entity-type topics --entity-name momo.fast \
  --delete-config retention.ms
```

### 6. Consumer Group 卡住 / 無限 Rebalance

```bash
# 1. 停止所有該 group 的 consumers
docker compose stop simpleec-channel-momo-fast

# 2. 刪除 consumer group
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --delete --group channel-job-momo-fast

# 3. 重啟（group 會自動重建）
docker compose start simpleec-channel-momo-fast
```

### 7. 查看失敗/死信訊息

**方法 A — Kafka UI**：
1. 打開 http://localhost:8088
2. 到 Topics → `task.failed` 或 `task.dlt`
3. 瀏覽訊息內容

**方法 B — CLI**：
```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic task.dlt \
  --from-beginning --max-messages 10
```

## Grafana Loki 常用查詢

```logql
# 所有 ERROR 日誌
{service_name=~"simpleec-.*"} |= "ERROR"

# 特定 trace 的所有日誌
{service_name=~"simpleec-.*"} | json | trace_id="<your-trace-id>"

# 特定商家的日誌
{service_name=~"simpleec-.*"} | json | merchantId="M001"

# Channel Job 失敗
{service_name="simpleec-channel-momo-fast"} |= "FAILED"

# Retry 活動
{service_name="simpleec-retry-job"} | json | taskAction="RETRY_DISPATCH"

# Poison pill 事件
{service_name=~"simpleec-.*"} |= "Poison pill"

# DLT 路由事件
{service_name=~"simpleec-.*"} |= "routing to DLT"
```

## 訊息流向與失敗處理

```
正常流程:
  API → momo.fast → ChannelJob → SUCCESS → ack

失敗流程:
  ChannelJob → FAILED → task.failed → RetryDispatchJob
    ├── Fast topic (鐵則) → task.dlt (永不重打)
    ├── 超過 maxRetry → task.dlt
    ├── 不可重打的 action → task.dlt
    └── 可重打 → retryCount++ → 原 topic

Schema 不支援:
  任何 JOB 收到 schemaVersion 不支援 → task.dlt → ack
```
