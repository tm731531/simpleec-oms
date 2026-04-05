# Kafka Envelope

> Defines the mandatory header/body structure for every Kafka message in SimpleEC OMS, the per-TaskType body contract, topic routing, and error-handling rules. Read this before writing any producer or consumer.

---

## 1. Header Schema

Every message on every topic — fast, slow, order.process, task.backend, all of them — carries this header exactly. No exceptions.

| Field | Type | Required | Description |
|---|---|---|---|
| `taskType` | String (TaskTypeEnum) | YES | Routing key. Determines which handler processes the message. See `TaskTypeEnum.java`. |
| `merchantId` | String (NanoID-20) | YES | Tenant identifier. Always an OMS internal NanoID. |
| `platformId` | String | YES | Platform code in lowercase: `shopee`, `shopify`, `cyberbiz`, `shopline`, `momo`, `yahoo`, `pchome`, `easystore`. |
| `channelId` | String (NanoID-20) | YES | Channel instance ID. OMS internal NanoID. Used as partition key for ordering. |
| `requestId` | String (UUID) | YES | Deduplication and distributed trace ID. Use `UUID.randomUUID().toString()`. |
| `timestamp` | String (ISO-8601) | YES | Message creation time. Use `Instant.now().toString()`. This is the anchor for Channel Job time-window calculations. |
| `source` | String (enum) | YES | Originator: `api`, `scheduler`, `webhook`, `channel_job`. |
| `version` | Integer | YES | Message schema version. Currently always `1`. |
| `isRollback` | Boolean | YES | `false` = live order; `true` = backfill. Affects revenue attribution and statistics. Never omit. |

**Minimal valid header (Java):**
```java
Map<String, Object> header = new HashMap<>();
header.put("taskType", "FETCH_ORDERS");
header.put("merchantId", merchantId);
header.put("platformId", "shopee");
header.put("channelId", channelId);
header.put("requestId", UUID.randomUUID().toString());
header.put("timestamp", Instant.now().toString());
header.put("source", "scheduler");
header.put("version", 1);
header.put("isRollback", false);
```

---

## 2. Body Contracts by TaskType

The body is always a JSON object. Fields not listed here are not part of the contract and must not be relied upon by handlers.

### 2.1 Scheduler-Driven (isSchedulerDriven = true in TaskTypeEnum)

#### `FETCH_ORDERS`
```json
{
  "timestamp": "2026-03-17T10:00:00Z"
}
```
- `timestamp` is the anchor time only. Channel Job decides all time windows internally.
- No `from`/`to`, no date range, no status filter. These are Channel Job's responsibility.

#### `FETCH_ORDER_DETAIL`
```json
{
  "orderId": "<OMS NanoID>"
}
```
- `orderId` is the OMS internal NanoID. Channel Job resolves `channelOrderId` from the DB (via its internal lookup).
- Used in Mode B platforms (e.g., Shopee) where list API doesn't return full detail.

#### `FETCH_RETURNS`
```json
{
  "timestamp": "2026-03-17T10:00:00Z"
}
```
- Same timestamp-only contract as `FETCH_ORDERS`.

#### `FETCH_RETURN_DETAIL`
```json
{
  "returnId": "<OMS NanoID>"
}
```

#### `HEARTBEAT`
```json
{}
```
- Body is intentionally empty. Header fields are sufficient.

---

### 2.2 Channel Actions (Outbound: OMS → Platform)

#### `UPDATE_INVENTORY`
```json
{
  "sellPackId": "<OMS NanoID>",
  "operation": "SET | INCREMENT | DECREMENT",
  "oldValue": 10,
  "newValue": 8
}
```
- `oldValue` is **required** — some platforms (e.g., Shopee) need the delta, not the absolute value.
- Channel Job resolves `channelProductId` + `channelSpecId` (or `inventory_item_id` for Shopify) from `sell_pack` using `sellPackId`.
- For Shopify multi-location channels, Channel Job also reads `platform_metadata.shopify.inventory_item_id`.

#### `UPDATE_PRICE`
```json
{
  "sellPackId": "<OMS NanoID>",
  "newPrice": 299.00
}
```
- Channel Job resolves `channelProductId` + `channelSpecId` from `sell_pack`.

#### `SHIP_ORDER`
```json
{
  "orderId": "<OMS NanoID>",
  "shipmentData": {
    "trackingNumber": "...",
    "carrier": "..."
  }
}
```
- Channel Job resolves `channelOrderId` from `orders` table using `orderId`.
- Do NOT put `channelOrderId` directly in the body (translation rule).

#### `SYNC_PACK`
```json
{
  "sellPackId": "<OMS NanoID>"
}
```
- Triggers Channel Job to sync listing configuration from the platform.
- Channel Job looks up `channelProductId` + `channelSpecId` and calls the platform listing API.

#### `APPROVE_RETURN`
```json
{
  "returnId": "<OMS NanoID>"
}
```
- Channel Job resolves `channel_refund_id` from `refund_orders` using `returnId`.

#### `REJECT_RETURN`
```json
{
  "returnId": "<OMS NanoID>"
}
```
- Same resolution pattern as `APPROVE_RETURN`.

#### `CANCEL_ORDER`
```json
{
  "orderId": "<OMS NanoID>",
  "reason": "..."
}
```
- Outbound cancel pushed to platform via Channel Job.

---

### 2.3 Inbound Processing (Platform → OMS)

#### `ORDER_UPSERT`
```json
{
  "channelOrderId": "platform-native-order-id",
  "channelOrderNumber": "human-readable-order-number",
  "orderHash": "sha256hex",
  "orderData": {
    "orderStatus": "PENDING",
    "totalAmount": 1200.00,
    "shippingFee": 60.00,
    "discountAmount": 0.00,
    "paymentMethod": "credit_card",
    "shippingMethod": "standard",
    "buyerName": "...",
    "buyerPhone": "...",
    "buyerEmail": "...",
    "shippingAddress": "...",
    "channelCreatedAt": "2026-03-17T08:00:00Z",
    "paidAt": "2026-03-17T08:05:00Z",
    "items": [
      {
        "channelItemId": "platform-item-id",
        "channelProductId": "platform-product-id",
        "channelSpecId": "platform-spec-id",
        "sellPackId": "OMS-NanoID-or-null",
        "productName": "...",
        "specName": "...",
        "quantity": 2,
        "unitPrice": 600.00
      }
    ]
  },
  "isRollback": false
}
```
- `channelOrderId` is an **inbound exception** to the translation rule: the internal `orderId` (NanoID) does not exist yet at this stage.
- `isRollback` in body mirrors `header.isRollback`. Both must be set consistently.
- Handler writes to `orders` table, generating a new NanoID via `NanoIdUtil.generate()`.

#### `RETURN_UPSERT`
```json
{
  "returnData": {
    "returnId": "<OMS NanoID or null if new>",
    "channelOrderId": "platform-native-order-id",
    "channelRefundId": "platform-refund-id",
    "refundStatus": "PENDING",
    "refundAmount": 600.00,
    "reason": "...",
    "requestedAt": "2026-03-17T09:00:00Z",
    "items": [...]
  }
}
```
- `channelOrderId` is also an inbound exception here (same reason as ORDER_UPSERT).

---

### 2.4 Internal Processing

#### `ORDER_STATUS_CHANGE`
```json
{
  "orderId": "<OMS NanoID>",
  "newStatus": "SHIPPED",
  "operator": "system|userId",
  "remark": "..."
}
```

#### `CANCEL_ORDER_INTERNAL`
```json
{
  "orderId": "<OMS NanoID>",
  "reason": "..."
}
```
- Published to `order.process` (not the platform fast topic).

#### `APPROVE_RETURN_INTERNAL`
```json
{
  "returnId": "<OMS NanoID>"
}
```

---

## 3. Topic Routing Table

| TaskType | Topic | Speed |
|---|---|---|
| `FETCH_ORDERS` | `{platform}.slow` | Slow (<5m) |
| `FETCH_ORDER_DETAIL` | `{platform}.slow` | Slow (<5m) |
| `FETCH_RETURNS` | `{platform}.slow` | Slow (<5m) |
| `FETCH_RETURN_DETAIL` | `{platform}.slow` | Slow (<5m) |
| `SYNC_PACK` | `{platform}.slow` | Slow (<5m) |
| `UPDATE_INVENTORY` | `{platform}.fast` | Fast (<5s) |
| `UPDATE_PRICE` | `{platform}.fast` | Fast (<5s) |
| `SHIP_ORDER` | `{platform}.fast` | Fast (<5s) |
| `APPROVE_RETURN` | `{platform}.fast` | Fast (<5s) |
| `REJECT_RETURN` | `{platform}.fast` | Fast (<5s) |
| `CANCEL_ORDER` | `{platform}.fast` | Fast (<5s) |
| `ORDER_UPSERT` | `order.process` | Processing |
| `ORDER_STATUS_CHANGE` | `order.process` | Processing |
| `CANCEL_ORDER_INTERNAL` | `order.process` | Processing |
| `RETURN_UPSERT` | `return.process` | Processing |
| `APPROVE_RETURN_INTERNAL` | `return.process` | Processing |
| `SYNC_PRODUCT`, `ORDER_REPORT`, `STATS_RECALC`, etc. | `task.backend` | Background |
| Frontend notifications | `task.frontend` | Background |
| `FETCH_ORDERS`, `FETCH_RETURNS`, `HEARTBEAT` | `scheduler` | Trigger |

**Topic name construction:**
```java
// TopicConstants.java
TopicConstants.platformFastTopic("shopee")  // → "shopee.fast"
TopicConstants.platformSlowTopic("shopee")  // → "shopee.slow"
TopicConstants.ORDER_PROCESS                // → "order.process"
```

---

## 4. Rules

### 4.1 Scheduler sends timestamp only — no time ranges in messages
```
CORRECT:   body = { "timestamp": "2026-03-17T10:00:00Z" }
INCORRECT: body = { "from": "2026-03-17T09:00:00Z", "to": "2026-03-17T10:00:00Z" }
```
Channel Job is the sole authority on time windows. It calculates them internally based on platform characteristics:
- Shopee: `1h` (UNPAID) + `3d` (AWAITING_SHIPMENT) + `5d` (SHIPPED) + `7d` (COMPLETED)
- Momo: `1h` (new) + `3d` (pending shipment), item-level aggregation by order number
- Yahoo: `updated_after = BASE_TS - 1d` (all statuses)
- easystore: `7d` sliding window

### 4.2 No platform IDs in message bodies (Translation Layer Rule)
All Kafka messages use OMS internal NanoIDs. Channel Job is the only component that knows platform-native IDs.

```
CORRECT:   body.sellPackId = "OMS-NanoID"   (Channel Job resolves channelProductId)
INCORRECT: body.channelProductId = "123456"
```

**Inbound exceptions** (internal ID does not exist yet):
- `ORDER_UPSERT.channelOrderId` — order record not created yet
- `RETURN_UPSERT.returnData.channelOrderId` — same reason

### 4.3 isRollback flag
| Value | Meaning |
|---|---|
| `false` | Live order. Revenue attributed to today. Inventory deducted normally. |
| `true` | Backfill. Revenue traced to `channelCreatedAt`, not today. Statistics marked as backfill. |

Set in both `header.isRollback` and `body.isRollback` (for ORDER_UPSERT) consistently. Handlers read from header; the body copy is for audit.

### 4.4 Error handling — task.failed vs task.dlt

| Error Class | ErrorType | Target Topic | Retention |
|---|---|---|---|
| HTTP 5xx | `SERVER_ERROR_5XX` (retry ≤3) | `task.failed` | 1 day |
| Network timeout | `NETWORK_ERROR` (retry ≤5) | `task.failed` | 1 day |
| HTTP 4xx | `CLIENT_ERROR_4XX` (no retry) | `task.dlt` | 30 days |
| Schema/parse error | `FORMAT_ERROR` (no retry) | `task.dlt` | 30 days |
| Unsupported version | n/a | `task.dlt` | 30 days |

See `ErrorType.java` for retry counts. `task.failed` messages are periodically replayed by `simpleec-retry-job`. `task.dlt` messages require manual intervention.

### 4.5 Schema version validation
Every consumer must call `SchemaVersionHandler.validate(message)` before dispatching:

```java
// In every Kafka @KafkaListener handle() method:
SchemaVersionHandler.validate(message); // throws UnsupportedSchemaVersionException if bad
```

- Currently `SUPPORTED_VERSIONS = Set.of(1)` (see `SchemaVersionHandler.java`).
- `UnsupportedSchemaVersionException` → route to `task.dlt`. Do NOT route to `task.failed`.
- When adding a new schema version: add it to `SUPPORTED_VERSIONS` during the transition window, then remove the old version after all producers are upgraded.

### 4.6 MDC tracing
Every consumer must call `TaskMdcHelper.set(message)` and `TaskMdcHelper.clear()` for trace correlation:

```java
TaskMdcHelper.set(message);  // sets MDC: traceId, spanId, merchantId, taskType, channelId
try {
    // handle
} finally {
    TaskMdcHelper.clear();
}
```

### 4.7 Partition key
- Use `channelId` as the Kafka message key for most messages (ensures ordering within a channel).
- Use `orderId` as the key for order/return processing messages (ensures order-level ordering).

```java
// Channel topic:
kafkaTemplate.send(topic, channelId, message);

// order.process / return.process:
kafkaTemplate.send(TopicConstants.ORDER_PROCESS, orderId, message);
```

### 4.8 Processing time budget
Consumers must satisfy:

```
max(concurrentMessages × avg_processing_time) < session.timeout.ms
```

Current config: `factory.setConcurrency(8)` and `AckMode.MANUAL_IMMEDIATE`. Fast topics must process within 5 seconds per message; slow topics within 5 minutes. If a handler exceeds this, it will trigger a rebalance and reprocess the same message.

### 4.9 Producer reliability
The `KafkaConfig` producer is configured with `acks=all` and `retries=3`. Do not override these for business message producers.

---

## 5. Common Mistakes

| Mistake | Correct Pattern |
|---|---|
| Putting `from`/`to` date range in FETCH_ORDERS body | Body has only `{ "timestamp": "..." }` |
| Using `channelOrderId` in SHIP_ORDER body | Use `orderId` (OMS NanoID); Channel Job resolves the rest |
| Omitting `isRollback` from header | Always set `isRollback`, default `false` |
| Routing `UnsupportedSchemaVersionException` to `task.failed` | Must go to `task.dlt` |
| Missing `TaskMdcHelper.set()` in consumer | Every handler must call set/clear |
| Sending to `{platform}.fast` for FETCH_ORDERS | FETCH_ORDERS goes to `{platform}.slow` |
