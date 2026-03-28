# Kafka Message Contracts

Every message in SimpleEC OMS — across all 17+ Kafka topics — uses the same top-level
`header` / `body` envelope. The `header` is the routing layer; the `body` is the payload.

---

## 1. Universal Envelope

```json
{
  "header": {
    "taskType":   "ORDER_UPSERT",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-a1b2c3d4-e5f6",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    // varies by taskType — see sections below
  }
}
```

### Header Field Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `taskType` | String | Yes | Routes the message to the correct handler. See full list below. |
| `merchantId` | String (NanoID 20) | Yes | Tenant isolation. All DB writes scope to this merchant. |
| `platformId` | String | Yes | Platform code: `momo`, `shopee`, `yahoo`, `pchome`, `cyberbiz`, `easystore`, `shopline`, `shopify` |
| `channelId` | String (NanoID 20) | Yes | Specific channel instance ID (e.g., a merchant's Shopee store). Foreign key to `channel.id`. |
| `requestId` | String (UUID) | Yes | Distributed tracing ID. Propagated through MDC via `TaskMdcHelper.set(msg)`. |
| `timestamp` | String (ISO-8601 UTC) | Yes | The scheduler's heartbeat timestamp. **Channel Job uses this to calculate its own time windows** — it does NOT receive a from/to range. |
| `source` | String | Yes | Origin of the message: `scheduler`, `api`, `webhook`, `channel_job` |
| `version` | Integer | Yes | Schema version. Currently `1`. Messages with unsupported versions are routed to `task.dlt`. |
| `isRollback` | Boolean | Yes | `false` = live order; `true` = historical backfill. Affects stats attribution and inventory handling. See Section 4. |

### All Task Types

| Task Type | Topic | Direction | Description |
|-----------|-------|-----------|-------------|
| `FETCH_ORDERS` | `{platform}.slow` | Scheduler → Channel Job | Trigger order list pull from platform |
| `FETCH_ORDER_DETAIL` | `{platform}.slow` | Channel Job → Channel Job | Trigger detail pull for a specific order |
| `FETCH_RETURNS` | `{platform}.slow` | Scheduler → Channel Job | Trigger return list pull |
| `FETCH_RETURN_DETAIL` | `{platform}.slow` | Channel Job → Channel Job | Trigger detail pull for a specific return |
| `SYNC_PACK` | `{platform}.slow` | Scheduler → Channel Job | Trigger pack/listing sync |
| `SHIP_ORDER` | `{platform}.fast` | API → Channel Job | Push shipping info to platform |
| `UPDATE_PRICE` | `{platform}.fast` | API → Channel Job | Update listing price on platform |
| `UPDATE_INVENTORY` | `{platform}.fast` | API → Channel Job | Push inventory level to platform |
| `APPROVE_RETURN` | `{platform}.fast` | API → Channel Job | Approve a return on platform |
| `ORDER_UPSERT` | `order.process` | Channel Job → Order Job | Insert or update an order in DB |
| `RETURN_UPSERT` | `return.process` | Channel Job → Return Job | Insert or update a return/refund in DB |
| `SYNC_PRODUCT` | `task.backend` | Backend Job → Backend Job | Build Pack → Product mapping |
| `STATS_RECALC` | `task.backend` | Order/Return Job → Backend Job | Trigger daily stats recalculation |
| `HEARTBEAT` | `scheduler` | Scheduler → Scheduler | Periodic tick (every second) |
| `EXPORT_ORDERS` | `task.frontend` | API → Frontend Job | Async order export to CSV/Excel |
| `BATCH_SHIP` | `task.frontend` | API → Frontend Job | Batch shipment update |
| `BATCH_CANCEL` | `task.frontend` | API → Frontend Job | Batch order cancellation |

---

## 2. Body Schemas by Task Type

### 2.1 ORDER_UPSERT

Published to `order.process`. This is the most important message in the system — it is the
canonical representation of a platform order normalized into OMS format.

```json
{
  "header": {
    "taskType":   "ORDER_UPSERT",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-a1b2c3d4-e5f6",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "orderHash": "sha256-of-normalized-order-content",
    "orderData": {
      "orderId":           "nano-id-20chars-here",
      "channelOrderId":    "2503281234567890",
      "channelOrderNumber": "SHP-2503-1234",
      "channelCreatedAt":  "2026-03-28T08:30:00Z",
      "channelUpdatedAt":  "2026-03-28T09:00:00Z",
      "status":            "confirmed",
      "totalAmount":       1580.00,
      "shippingFee":       60.00,
      "discountAmount":    0.00,
      "currency":          "TWD",
      "buyerName":         "張三",
      "buyerPhone":        "0912345678",
      "buyerEmail":        "buyer@example.com",
      "shippingAddress":   "台北市信義區信義路五段7號10樓",
      "shippingMethod":    "711",
      "paymentMethod":     "credit_card",
      "paidAt":            "2026-03-28T08:31:00Z",
      "items": [
        {
          "channelItemId":  "shopee-item-99988877",
          "productName":    "無線藍牙耳機 黑色",
          "sku":            "BT-HEADPHONE-BLK",
          "quantity":       2,
          "unitPrice":      790.00,
          "totalPrice":     1580.00
        }
      ]
    }
  }
}
```

**`orderHash`**: SHA-256 of the normalized order content (excluding timestamps). Used by
`OrderUpsertConsumer` to deduplicate — if `Redis.get(orderHash)` hits, the message is skipped
without touching the DB. If the hash differs from a previously stored one, the order is updated.

**`orderId`**: OMS-internal NanoID generated by Channel Job before publishing. This ensures the
same logical order always has the same `orderId` even if the Kafka message is redelivered.

### 2.2 RETURN_UPSERT

Published to `return.process`.

```json
{
  "header": {
    "taskType":   "RETURN_UPSERT",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-b9c8d7e6-f5a4",
    "timestamp":  "2026-03-28T11:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "returnData": {
      "orderId":          "oms-order-nano-id",
      "channelRefundId":  "REFUND-99887766",
      "refundStatus":     "REQUESTED",
      "refundAmount":     790.00,
      "reason":           "商品瑕疵",
      "requestedAt":      "2026-03-28T10:55:00Z",
      "items": [
        {
          "channelItemId": "shopee-item-99988877",
          "productName":   "無線藍牙耳機 黑色",
          "sku":           "BT-HEADPHONE-BLK",
          "quantity":      1,
          "refundAmount":  790.00
        }
      ]
    }
  }
}
```

### 2.3 FETCH_ORDERS

Published to `{platform}.slow` by SchedulerEventHandler every 5 minutes per active channel.

```json
{
  "header": {
    "taskType":   "FETCH_ORDERS",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-heartbeat-derived",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "scheduler",
    "version":    1,
    "isRollback": false
  },
  "body": {}
}
```

**Body is intentionally empty.** The Channel Job is responsible for determining:
- Which time windows to query (e.g., Shopee: 1h for PENDING, 3d for AWAITING_SHIPMENT, etc.)
- Whether a detail API call is needed
- Pagination strategy

The `header.timestamp` is the only temporal input. The Channel Job derives all ranges from it
internally.

### 2.4 FETCH_ORDER_DETAIL

Published to `{platform}.slow` by Channel Job when a platform's list API returns incomplete data
(e.g., Shopee list API omits items, payment, and shipping info).

```json
{
  "header": {
    "taskType":   "FETCH_ORDER_DETAIL",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "channelOrderId": "2503281234567890"
  }
}
```

### 2.5 SHIP_ORDER

Published to `{platform}.fast` by the API after a merchant confirms shipment.

```json
{
  "header": {
    "taskType":   "SHIP_ORDER",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-api-generated",
    "timestamp":  "2026-03-28T14:00:00Z",
    "source":     "api",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "orderId":         "oms-order-nano-id",
    "channelOrderId":  "2503281234567890",
    "trackingNumber":  "123456789012",
    "shippingMethod":  "711",
    "logisticsCompany": "7-ELEVEN"
  }
}
```

### 2.6 STATS_RECALC

Published to `task.backend` after an order or return is written to DB. Triggers recalculation of
`daily_statistics` for the affected merchant/channel/date.

```json
{
  "header": {
    "taskType":   "STATS_RECALC",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-derived-from-order",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "order_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "statDate": "2026-03-28"
  }
}
```

**Note**: Rather than publishing this synchronously, `OrderUpsertConsumer` writes a "dirty marker"
to a Redis ZSet after each order upsert. `DailyStatisticsService` reads the dirty markers on a
schedule and recalculates stats in batch. The STATS_RECALC task type may be used for explicit
on-demand recalculation triggered via the admin API.

---

## 3. Schema Version Handling

Every consumer calls `SchemaVersionHandler.validate(json)` before processing. This is a
defensive check against future schema evolution.

**Current version**: `1`

**Handling rules**:

| Condition | Action |
|-----------|--------|
| `header.version == 1` | Process normally |
| `header.version` field missing | Treat as version 1 (backward compat) |
| `header.version > 1` (unknown future) | Publish to `task.dlt` with reason `UNSUPPORTED_VERSION` |
| `header` field missing entirely | Publish to `task.dlt` with reason `MALFORMED_MESSAGE` |

**Future migration**: When introducing a breaking change, bump `version` to `2` and add a version
handler that converts v1 → v2 shape before dispatching. Old consumers continue to work until
all producers are updated.

---

## 4. The isRollback Flag

`header.isRollback` is a first-class citizen in the message contract. It is set by the Channel
Job when it detects it is fetching orders from a historical window (i.e., performing a backfill).

### isRollback = false (normal live order)

```
Stats:  attributed to today (channel_created_at date used as stat_date)
Stock:  normal inventory deduction
Events: normal order lifecycle notifications
Rank:   counts towards merchant's daily ranking
```

### isRollback = true (historical backfill)

```
Stats:  attributed to channelCreatedAt date (not today)
         → A March order backfilled in April adds to March stats, not April
Stock:  inventory adjustment may require special handling
Events: backfill-specific events (e.g., trigger daily report regeneration)
Rank:   may be excluded from current-day ranking calculations
```

**Which task types carry isRollback**:

- `FETCH_ORDERS` — set by scheduler when dispatching a historical date range
- `FETCH_ORDER_DETAIL` — propagated from the parent FETCH_ORDERS message
- `ORDER_UPSERT` — propagated from FETCH_ORDER_DETAIL
- `RETURN_UPSERT` — set analogously for historical return backfills
- `SHIP_ORDER` — typically false (ships happen in real time)

**Detection logic in Channel Job**:

```java
// Example: Cyberbiz Channel Job
boolean isRollback = requestTimestamp.isBefore(Instant.now().minus(2, HOURS));
// If the scheduler's timestamp is older than 2 hours, this is backfill
```

---

## 5. PII Fields in Message Body

The following fields in `orderData` contain buyer PII. They travel in plaintext inside Kafka
messages (encrypted in transit via TLS in production). They are encrypted at rest in PostgreSQL
using AES-256-GCM via `EncryptedAttributeConverter`.

| Field | PII Class | DB Encryption |
|-------|-----------|---------------|
| `buyerName` | Name | Yes — `buyer_name` column |
| `buyerPhone` | Phone number | Yes — `buyer_phone` column |
| `buyerEmail` | Email address | Yes — `buyer_email` column |
| `shippingAddress` | Physical address | Yes — `shipping_address` column |

Any code that reads these DB columns must wrap the operation in:

```java
EncryptionContext.setMerchantId(merchantId);
try {
    // DB read/write with PII fields
} finally {
    EncryptionContext.clear();
}
```

---

## 6. Message Tracing

Every message carries a `requestId` in the header. This ID is set into MDC by `TaskMdcHelper`:

```java
// In every consumer's handle() method:
TaskMdcHelper.set(msg);
try {
    // processing
} finally {
    TaskMdcHelper.clear();
}
```

This propagates `requestId`, `merchantId`, and `taskType` into all log lines for the duration of
message processing. Logs are correlated in Grafana Loki by filtering on `requestId`.
