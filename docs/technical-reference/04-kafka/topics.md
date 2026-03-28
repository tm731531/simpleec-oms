# Kafka Topics 參考手冊

SimpleEC OMS 使用 Apache Kafka 3.7.1，以 KRaft 模式運行（無需 ZooKeeper）。所有 topic 均由
`docker/init-kafka/create-topics.sh` 在容器啟動時建立。

---

## 1. Topic 命名慣例

```
{platform}.fast    — 快速通路任務   (目標延遲 < 5 秒)
{platform}.slow    — 慢速通路任務   (目標延遲 < 5 分鐘)
order.process      — 訂單標準串流
return.process     — 退貨標準串流
task.backend       — 內部非同步後台作業
task.frontend      — 面向用戶的非同步作業（匯出、批次操作）
scheduler          — 心跳計時串流
task.failed        — 可重試失敗佇列
task.dlt           — 死信佇列（不可重試 / 超過最大重試次數）
```

所有名稱均為小寫、以點號分隔。平台代碼一律小寫：
`momo`、`shopee`、`yahoo`、`pchome`、`cyberbiz`、`easystore`、`shopline`、`shopify`。

---

## 2. 完整 Topic 清單

### 2.1 通路 Topic（每個平台各有 fast + slow）

| Topic | 速度類別 | Task Type | 目標延遲 |
|-------|---------|-----------|---------|
| `momo.fast` | fast | SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | < 5s |
| `momo.slow` | slow | FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, FETCH_RETURN_DETAIL, SYNC_PACK | < 5 min |
| `shopee.fast` | fast | SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | < 5s |
| `shopee.slow` | slow | FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, FETCH_RETURN_DETAIL, SYNC_PACK | < 5 min |
| `yahoo.fast` | fast | SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | < 5s |
| `yahoo.slow` | slow | FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, FETCH_RETURN_DETAIL, SYNC_PACK | < 5 min |
| `pchome.fast` | fast | SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | < 5s |
| `pchome.slow` | slow | FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, FETCH_RETURN_DETAIL, SYNC_PACK | < 5 min |
| `cyberbiz.fast` | fast | SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | < 5s |
| `cyberbiz.slow` | slow | FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, FETCH_RETURN_DETAIL, SYNC_PACK | < 5 min |
| `easystore.fast` | fast | SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | < 5s |
| `easystore.slow` | slow | FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, FETCH_RETURN_DETAIL, SYNC_PACK | < 5 min |
| `shopline.fast` | fast | SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | < 5s |
| `shopline.slow` | slow | FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, FETCH_RETURN_DETAIL, SYNC_PACK | < 5 min |
| `shopify.fast` | fast | SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | < 5s |
| `shopify.slow` | slow | FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, FETCH_RETURN_DETAIL, SYNC_PACK | < 5 min |

**fast 與 slow 的差異說明**

- `.fast` — 需要近即時回應的平台回寫任務（建立出貨標籤、推送價格／庫存、批准退貨）。
  失敗會直接影響商家的日常營運。
- `.slow` — 涉及多頁 API 呼叫、速率限制合規和資料轉換的平台讀取任務。
  可容忍數分鐘的處理延遲。

**關於 SYNC_PACK 的說明**：通路只有「套包」（Listing）的概念，沒有內部商品的概念。
SYNC_PACK 處理完成後，`task.backend` 會收到 SYNC_PRODUCT，負責建立 Pack → Product 的映射關係。
Channel Job 本身絕不直接操作商品資料表。

### 2.2 業務 Topic

| Topic | 保留時間 | Partition 數 | Consumer Group | 主要 Task Type | 用途 |
|-------|---------|-------------|----------------|--------------|------|
| `order.process` | 2h | 3 | `order-job-group` | ORDER_UPSERT | 訂單寫入的唯一真相來源；對 DB 執行冪等 upsert |
| `return.process` | 1d | 3 | `return-job-group` | RETURN_UPSERT | 退貨／退款寫入的唯一真相來源 |
| `task.backend` | 1d | 1 | `backend-job-group` | SYNC_PACK, SYNC_PRODUCT, STATS_RECALC, *_REPORT | 內部非同步後台作業；訊息量少 |
| `task.frontend` | 1d | 1 | `frontend-job-group` | EXPORT_ORDERS, BATCH_SHIP, BATCH_CANCEL | 用戶發起的非同步操作 |
| `scheduler` | 1d | 1 | `scheduler-group` | HEARTBEAT | 驅動通路派發的週期性計時器 |
| `task.failed` | 1d | 1 | `retry-job-group` | （任何可重試的失敗） | 重試佇列；訊息重新路由至原始 topic |
| `task.dlt` | 30d | 1 | `dlt-group` | （任何不可重試 / 超過最大重試次數） | 死信；持久化至 `failed_task_logs` 資料表 |

**保留時間設計說明**：

- `order.process` 保留時間很短（2h），因為 DB 才是持久化儲存；Kafka 只是傳遞機制。
  重新處理應從 DB 發起，而不是重新消費舊訊息。
- `task.failed` / `task.dlt` 保留較長（1d / 30d），以便人工排查和回放。
- `scheduler` 保留 1d — 幾秒前的心跳訊息已無意義，1d 只是安全緩衝。

---

## 3. Consumer Group 並發度

| 服務 | Consumer Group ID | 預設並發度 | 環境變數覆蓋 |
|------|-----------------|----------|------------|
| `simpleec-channel-job` | 依部署設定 | 8（已優化） | `JOB_CHANNEL_CONCURRENCY` |
| `simpleec-order-job` | `order-job-group` | 3 | 硬編碼 |
| `simpleec-return-job` | `return-job-group` | 3 | 硬編碼 |
| `simpleec-backend-job` | `backend-job-group` | 3 | 硬編碼 |
| `simpleec-frontend-job` | `frontend-job-group` | 3 | 硬編碼 |
| `simpleec-retry-job` | `retry-job-group` | 3 | 硬編碼 |
| `simpleec-scheduler-job` | `scheduler-group` | 1 | 硬編碼（單一 partition） |

> **歷史說明**：Channel Job 的並發度從 3 調升至 8，原因是 consumer lag 分析顯示 Scheduler
> 的訊息產生速度超過 3 個並發 worker 的處理能力。完整診斷記錄請見 MEMORY.md Phase 8。

---

## 4. Kafka 設定

### 4.1 Bootstrap 與傳輸

```yaml
spring:
  kafka:
    bootstrap-servers: kafka:9092    # Docker 內部網路位址
    # 外部連線（從宿主機）: localhost:9094  (在 docker-compose.yml 中映射)
```

### 4.2 Producer 設定

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all          # 等待所有 ISR 副本確認（保證持久性）
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 1
```

所有訊息均序列化為純 JSON 字串。訊息 Schema 定義詳見
[message-contracts.md](message-contracts.md)。

### 4.3 Consumer 設定

```yaml
spring:
  kafka:
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false    # 處理完成後手動提交 offset
```

> **重要**：Consumer 反序列化目標型別為 `String`，而非 `ObjectNode` 或任何 POJO。
> 每個 consumer 在內部呼叫 `ObjectMapper.readTree(messageJson)` 進行解析。
> 這是過去一個 `MessageConversionException` bug 的根本原因——修復方式是將
> `JsonDeserializer` 改為 `StringDeserializer`。

### 4.4 錯誤處理

```java
// DefaultErrorHandler 零重試 — 毒藥訊息會被立即跳過
DefaultErrorHandler errorHandler = new DefaultErrorHandler(
    (record, exception) -> sendToFailedTopic(record),
    new FixedBackOff(0L, 0L)
);
```

- 可重試的錯誤 → `task.failed`（透過 `RetryJobConsumer` 最多重試 3 次）
- 不可重試或超過最大重試次數 → `task.dlt`
- DLT consumer 將訊息持久化至 `failed_task_logs` 資料表，供人工排查

### 4.5 Topic 建立預設值

所有 topic 均由 `docker/init-kafka/create-topics.sh` 以下列預設值建立：

| 設定項 | 預設值 | 覆蓋方式 |
|-------|-------|---------|
| Partition 數 | 3 | `KAFKA_PARTITIONS` 環境變數 |
| 複製因子 | 1（開發環境） | `KAFKA_REPLICATION_FACTOR` 環境變數 |
| 保留時間 | 1h（3600000ms） | `KAFKA_RETENTION_MS` 環境變數 |

注意：`scheduler` 以及所有 `task.*` topic 不受 `KAFKA_PARTITIONS` 影響，固定使用 1 個 partition。

### 4.6 執行時期 Topic 設定（application.yml）

保留時間可透過 Spring 屬性依環境調整：

```yaml
simpleec:
  kafka:
    retention:
      default: 1d
      dlt: 30d
      order-process: 2h
```

---

## 5. Topic 輔助方法（TopicConstants.java）

```java
// 動態產生平台 topic 名稱
TopicConstants.platformSlowTopic("shopee")    // → "shopee.slow"
TopicConstants.platformFastTopic("cyberbiz")  // → "cyberbiz.fast"
TopicConstants.getPlatformDetailTopic("momo") // → "momo.detail"  (保留)

// Partition 數量常數（用於 topic 建立決策）
TopicConstants.DEFAULT_PARTITIONS       = 8
TopicConstants.HIGH_VOLUME_PARTITIONS   = 16
TopicConstants.LOW_VOLUME_PARTITIONS    = 4
TopicConstants.SINGLE_PARTITION         = 1
```
