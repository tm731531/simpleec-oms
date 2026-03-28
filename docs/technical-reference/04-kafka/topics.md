# Kafka Topics Reference

SimpleEC OMS uses Apache Kafka 3.7.1 in KRaft mode (no ZooKeeper). All topics are created by
`docker/init-kafka/create-topics.sh` at container startup.

---

## 1. Topic Naming Convention

```
{platform}.fast    — fast channel tasks   (target latency < 5 seconds)
{platform}.slow    — slow channel tasks   (target latency < 5 minutes)
order.process      — canonical order stream
return.process     — canonical return stream
task.backend       — internal async backend work
task.frontend      — user-facing async work (exports, batch ops)
scheduler          — heartbeat tick stream
task.failed        — retryable failure queue
task.dlt           — dead-letter queue (non-retryable / max retries exceeded)
```

All names are lowercase, dot-separated. Platform codes are always lowercase:
`momo`, `shopee`, `yahoo`, `pchome`, `cyberbiz`, `easystore`, `shopline`, `shopify`.

---

## 2. Complete Topic Inventory

### 2.1 Channel Topics (fast + slow per platform)

| Topic | Speed Class | Task Types | Target Latency |
|-------|------------|------------|----------------|
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

**Distinction: fast vs slow**

- `.fast` — platform write-back tasks requiring near-real-time response (shipping label creation,
  price / inventory push, return approval). Failure impacts the merchant operationally.
- `.slow` — platform read tasks that involve multi-page API calls, rate-limit compliance, and
  data transformation. Acceptable to be processed with a lag of several minutes.

**Note on SYNC_PACK**: Channels only have "packs" (listings). They have no concept of internal
products. After SYNC_PACK is processed, `task.backend` receives SYNC_PRODUCT to build the
Pack → Product mapping. The channel job never touches the product table directly.

### 2.2 Business Topics

| Topic | Retention | Partitions | Consumer Group | Primary Task Types | Purpose |
|-------|-----------|------------|----------------|--------------------|---------|
| `order.process` | 2h | 3 | `order-job-group` | ORDER_UPSERT | Source of truth for order ingest; idempotent upsert to DB |
| `return.process` | 1d | 3 | `return-job-group` | RETURN_UPSERT | Source of truth for return/refund ingest |
| `task.backend` | 1d | 1 | `backend-job-group` | SYNC_PACK, SYNC_PRODUCT, STATS_RECALC, *_REPORT | Internal async backend work; low volume |
| `task.frontend` | 1d | 1 | `frontend-job-group` | EXPORT_ORDERS, BATCH_SHIP, BATCH_CANCEL | User-initiated async operations |
| `scheduler` | 1d | 1 | `scheduler-group` | HEARTBEAT | Periodic tick driving channel dispatch |
| `task.failed` | 1d | 1 | `retry-job-group` | (any retryable failure) | Retry queue; messages re-routed to original topic |
| `task.dlt` | 30d | 1 | `dlt-group` | (any non-retryable / max retries exceeded) | Dead-letter; persisted to `failed_task_logs` table |

**Retention rationale**:

- `order.process` is short (2h) because the DB is the durable store; Kafka is just the delivery
  mechanism. Reprocessing comes from the DB, not from re-consuming old messages.
- `task.failed` / `task.dlt` are longer (1d / 30d) to support manual investigation and replay.
- `scheduler` is 1d — heartbeats older than a few seconds are irrelevant, 1d is a safety buffer.

---

## 3. Consumer Group Concurrency

| Service | Consumer Group ID | Default Concurrency | Env Var Override |
|---------|-------------------|---------------------|------------------|
| `simpleec-channel-job` | configurable per deploy | 8 (optimized) | `JOB_CHANNEL_CONCURRENCY` |
| `simpleec-order-job` | `order-job-group` | 3 | hardcoded |
| `simpleec-return-job` | `return-job-group` | 3 | hardcoded |
| `simpleec-backend-job` | `backend-job-group` | 3 | hardcoded |
| `simpleec-frontend-job` | `frontend-job-group` | 3 | hardcoded |
| `simpleec-retry-job` | `retry-job-group` | 3 | hardcoded |
| `simpleec-scheduler-job` | `scheduler-group` | 1 | hardcoded (single partition) |

> **Historical note**: Channel job concurrency was raised from 3 → 8 after consumer lag analysis
> showed that Scheduler was generating messages faster than 3 concurrent workers could process them.
> See MEMORY.md Phase 8 for full diagnosis.

---

## 4. Kafka Configuration

### 4.1 Bootstrap & Transport

```yaml
spring:
  kafka:
    bootstrap-servers: kafka:9092    # Internal Docker network address
    # External (from host): localhost:9094  (mapped in docker-compose.yml)
```

### 4.2 Producer Configuration

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all          # Wait for all ISR replicas (durability)
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 1
```

All messages are serialized as plain JSON strings. The message schema is defined in
[message-contracts.md](message-contracts.md).

### 4.3 Consumer Configuration

```yaml
spring:
  kafka:
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false    # Manual offset commit after processing
```

> **Important**: The consumer deserializes to `String`, not to `ObjectNode` or any POJO. Each
> consumer calls `ObjectMapper.readTree(messageJson)` internally. This was the root cause of a
> past `MessageConversionException` bug — the fix was switching from `JsonDeserializer` to
> `StringDeserializer`.

### 4.4 Error Handling

```java
// DefaultErrorHandler with zero retries — poison pills are immediately skipped
DefaultErrorHandler errorHandler = new DefaultErrorHandler(
    (record, exception) -> sendToFailedTopic(record),
    new FixedBackOff(0L, 0L)
);
```

- Retryable errors → `task.failed` (up to 3 retries via `RetryJobConsumer`)
- Non-retryable or max-retries exceeded → `task.dlt`
- DLT consumer persists messages to `failed_task_logs` table for manual review

### 4.5 Topic Creation Defaults

All topics are created by `docker/init-kafka/create-topics.sh` with these defaults:

| Setting | Default | Override |
|---------|---------|----------|
| Partitions | 3 | `KAFKA_PARTITIONS` env var |
| Replication Factor | 1 (dev) | `KAFKA_REPLICATION_FACTOR` env var |
| Retention | 1h (3600000ms) | `KAFKA_RETENTION_MS` env var |

Note: `scheduler` and all `task.*` topics use 1 partition regardless of `KAFKA_PARTITIONS`.

### 4.6 Runtime Topic Configuration (application.yml)

Retention can be tuned per-environment via Spring properties:

```yaml
simpleec:
  kafka:
    retention:
      default: 1d
      dlt: 30d
      order-process: 2h
```

---

## 5. Topic Helper Methods (TopicConstants.java)

```java
// Dynamic platform topic generation
TopicConstants.platformSlowTopic("shopee")    // → "shopee.slow"
TopicConstants.platformFastTopic("cyberbiz")  // → "cyberbiz.fast"
TopicConstants.getPlatformDetailTopic("momo") // → "momo.detail"  (reserved)

// Partition size constants (for topic creation decisions)
TopicConstants.DEFAULT_PARTITIONS       = 8
TopicConstants.HIGH_VOLUME_PARTITIONS   = 16
TopicConstants.LOW_VOLUME_PARTITIONS    = 4
TopicConstants.SINGLE_PARTITION         = 1
```
