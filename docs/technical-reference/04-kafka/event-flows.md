# Event Flows

This document traces complete message flows end-to-end through the Kafka topology for the most
important business scenarios. Each flow shows the exact Kafka messages exchanged and the
processing steps taken by each service.

---

## 1. Order Ingestion — Mode B (Shopee: List + Detail)

Shopee's list API returns incomplete orders (no items, no payment, no shipping info). The Channel
Job must call a separate detail API for each order. This is the "Mode B" flow.

```
                    ┌─────────────┐
                    │  Scheduler  │  (HeartbeatTimer, fires every second)
                    └──────┬──────┘
                           │ Every 5 minutes: dispatch FETCH_ORDERS
                           │ for each active channel
                           ▼
                    ┌─────────────┐
                    │  scheduler  │  Kafka topic
                    │    topic    │
                    └──────┬──────┘
                           │
                    ┌──────▼──────────────┐
                    │ SchedulerEventHandler│  (simpleec-scheduler-job)
                    └──────┬──────────────┘
                           │ Publishes FETCH_ORDERS to shopee.slow
                           ▼
```

**Message 1 → `shopee.slow`**:
```json
{
  "header": {
    "taskType":   "FETCH_ORDERS",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-sched-001",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "scheduler",
    "version":    1,
    "isRollback": false
  },
  "body": {}
}
```

```
                    ┌──────────────────────┐
                    │  ChannelJobConsumer  │  (simpleec-channel-job, shopee.slow)
                    │  ModeBOrderList      │
                    │  Handler             │
                    └──────┬───────────────┘
                           │
                           │  Channel Job calculates time windows from header.timestamp:
                           │  - PENDING:              timestamp-1h  → timestamp
                           │  - AWAITING_SHIPMENT:    timestamp-3d  → timestamp
                           │  - SHIPPED:              timestamp-5d  → timestamp
                           │  - COMPLETED:            timestamp-7d  → timestamp
                           │
                           │  Calls Shopee list API (4 separate requests)
                           │  Receives order IDs with incomplete data
                           │
                           │  For each order needing detail:
                           │  Publishes FETCH_ORDER_DETAIL to shopee.slow
                           ▼
```

**Message 2 → `shopee.slow`** (one per order):
```json
{
  "header": {
    "taskType":   "FETCH_ORDER_DETAIL",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-detail-002",
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

```
                    ┌──────────────────────┐
                    │  ModeBOrderDetail    │
                    │  Handler             │  (shopee.slow consumer)
                    └──────┬───────────────┘
                           │
                           │  Calls Shopee detail API for order 2503281234567890
                           │  Receives full order data (items, payment, shipping)
                           │  Normalizes to OMS format
                           │  Publishes ORDER_UPSERT to order.process
                           ▼
```

**Message 3 → `order.process`**:
```json
{
  "header": {
    "taskType":   "ORDER_UPSERT",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-detail-002",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "orderHash": "a3f8b2c1d4e5f6a7b8c9d0e1f2a3b4c5",
    "orderData": {
      "orderId":          "V9kMnPqRsT2uWxYz",
      "channelOrderId":   "2503281234567890",
      "channelCreatedAt": "2026-03-28T08:30:00Z",
      "status":           "confirmed",
      "totalAmount":      1580.00,
      "shippingFee":      60.00,
      "buyerName":        "張三",
      "buyerPhone":       "0912345678",
      "shippingAddress":  "台北市信義區信義路五段7號",
      "items": [
        { "channelItemId": "shopee-item-99988877", "sku": "BT-HEADPHONE-BLK", "quantity": 2, "unitPrice": 790.00 }
      ]
    }
  }
}
```

```
                    ┌────────────────────┐
                    │ OrderUpsertConsumer│  (simpleec-order-job, order.process)
                    └──────┬─────────────┘
                           │
                           │  1. SchemaVersionHandler.validate()
                           │  2. Redis: GET orderHash → hit? skip. miss? continue.
                           │  3. DB: SELECT by (channel_id, channel_order_id)
                           │        → not found: INSERT new order
                           │        → found: UPDATE if data changed
                           │  4. EncryptionContext.setMerchantId(merchantId)
                           │     Save order (PII fields encrypted by converter)
                           │     EncryptionContext.clear()
                           │  5. Redis: SET orderHash (TTL 24h)
                           │  6. Redis ZSet: ZADD stats:dirty {score=now} {key=merchantId:channelId:date}
                           │
                           ▼
                    ┌────────────────────────┐
                    │ DailyStatisticsService │  (scheduled, reads Redis dirty markers)
                    └──────┬─────────────────┘
                           │
                           │  Recalculates daily_statistics for merchant/channel/date
                           │  Upserts row in daily_statistics (partitioned table)
                           ▼
                         (done)
```

---

## 2. Order Ingestion — Mode A (Cyberbiz: Complete List)

Cyberbiz returns complete order data in the list response — no detail API needed. This is the
"Mode A" flow and is simpler.

```
Scheduler → FETCH_ORDERS → cyberbiz.slow

                    ┌──────────────────────┐
                    │  ModeAOrderList      │
                    │  Handler             │  (cyberbiz.slow consumer)
                    └──────┬───────────────┘
                           │
                           │  Channel Job calculates 7-day window from header.timestamp
                           │  Calls Cyberbiz /v1/orders API (paginated, up to 50/page)
                           │  Each order is already complete — no detail call needed
                           │  For each order: publishes ORDER_UPSERT to order.process
                           ▼

→ ORDER_UPSERT → order.process → OrderUpsertConsumer (same as Mode B from here)
```

---

## 3. Return Processing Flow

```
Scheduler → FETCH_RETURNS → {platform}.slow

                    ┌──────────────────────┐
                    │  FetchReturnsHandler │  (channel-job)
                    └──────┬───────────────┘
                           │
                           │  Calls platform return API
                           │  For platforms needing detail: publishes FETCH_RETURN_DETAIL
                           │  Once complete: publishes RETURN_UPSERT to return.process
                           ▼
                    ┌──────────────────────┐
                    │ ReturnUpsertConsumer │  (simpleec-order-job or return-job,
                    │                      │   return.process)
                    └──────┬───────────────┘
                           │
                           │  1. SchemaVersionHandler.validate()
                           │  2. DB: SELECT refund_orders by (order_id, channel_refund_id)
                           │        → not found: INSERT refund_order
                           │        → found: UPDATE refund status
                           │  3. UPDATE orders SET has_refund=true, refund_amount=...
                           │     WHERE id = orderId
                           │  4. Redis ZSet: ZADD stats:dirty (marks date for recalculation)
                           ▼
                    DailyStatisticsService recalculates refund_count, refund_amount, net_amount
```

---

## 4. Shipment Update Flow

Triggered when a merchant marks an order as shipped in the OMS UI.

```
[Merchant clicks "Ship" in UI]
        │
        ▼
┌───────────────┐
│  simpleec-api │  POST /api/orders/{id}/ship
└───────┬───────┘
        │  Publishes SHIP_ORDER to {platform}.fast
        ▼
{platform}.fast (e.g., shopee.fast)

        │
        ▼
┌──────────────────────┐
│  ShipOrderHandler    │  (channel-job, fast consumer)
└──────┬───────────────┘
        │
        │  Calls platform shipping API with tracking number
        │  On success: publishes ORDER_UPSERT to order.process
        │             with status=shipped, trackingNumber=...
        │  On failure: publishes to task.failed
        ▼
order.process → OrderUpsertConsumer → DB UPDATE orders SET order_status='shipped', shipped_at=now()
```

---

## 5. Failed Message Retry Flow

```
Any consumer throws exception
        │
        ▼
DefaultErrorHandler (FixedBackOff 0, 0 — no in-process retries)
        │
        │  Determines: retryable or not?
        │
        ├─ Retryable (network error, timeout, transient DB error)
        │       │
        │       ▼
        │   task.failed
        │       │
        │       ▼
        │  ┌────────────────────┐
        │  │  RetryJobConsumer  │  (simpleec-retry-job)
        │  └────────┬───────────┘
        │           │
        │           │  1. Extract errorInfo.retryCount from message
        │           │  2. retryCount < maxRetries (3)?
        │           │       Yes: increment retryCount, re-publish to originalTopic
        │           │       No:  publish to task.dlt
        │           ▼
        │
        └─ Non-retryable (UNSUPPORTED_VERSION, MALFORMED_MESSAGE, business logic error)
                │
                ▼
            task.dlt
                │
                ▼
        ┌────────────────────┐
        │    DltConsumer     │  (simpleec-retry-job)
        └────────┬───────────┘
                │
                │  Persists to failed_task_logs table:
                │    - original_topic, task_type, merchant_id
                │    - error_message, reason, retry_count
                │    - full payload (JSONB)
                │
                ▼
        Manual review via admin console or direct DB query
```

**Retry message envelope** (message in `task.failed`):

```json
{
  "header": {
    "taskType":   "ORDER_UPSERT",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-original",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "order_job",
    "version":    1,
    "isRollback": false
  },
  "body": { "...": "original body preserved" },
  "errorInfo": {
    "originalTopic": "order.process",
    "retryCount":    1,
    "errorMessage":  "Connection timeout to PostgreSQL",
    "failedAt":      "2026-03-28T10:00:05Z"
  }
}
```

---

## 6. Stats Recalculation Flow

`DailyStatisticsService` uses Redis as a dirty-marking system to avoid full table scans:

```
Order/Return saved to DB
        │
        ▼
Redis ZADD stats:dirty {score=epochMs} {member="merchantId:channelId:YYYY-MM-DD"}

        │  (async, scheduled every 30 seconds)
        ▼
DailyStatisticsService.recalculateDirtyStats()
        │
        │  1. Redis ZRANGEBYSCORE stats:dirty 0 now → get all dirty keys
        │  2. For each key (merchantId, channelId, date):
        │       SELECT COUNT(*), SUM(total_amount), ... FROM orders
        │         WHERE merchant_id=? AND channel_id=?
        │           AND channel_created_at >= date AND channel_created_at < date+1day
        │           AND order_status != 'cancelled'
        │       → UPDATE daily_statistics (UPSERT by unique index)
        │  3. Redis ZREM stats:dirty {processed keys}
        ▼
daily_statistics table updated (partition-aware query)
```

**Why dirty markers instead of synchronous recalc?**

- Orders arrive in bursts (scheduler dispatches many at once)
- Synchronous recalc would cause N DB writes for N orders about the same date
- Dirty markers coalesce many order writes into one stats recalc
- Trade-off: stats lag by up to 30 seconds from order write

---

## 7. Pack Sync Flow

Channels sell "packs" (listings). OMS maps these to internal products.

```
Scheduler → SYNC_PACK → {platform}.slow

        │
        ▼
┌──────────────────────┐
│  SyncPackHandler     │  (channel-job)
└──────┬───────────────┘
        │  Calls platform product listing API
        │  Returns normalized pack data
        │  Publishes SYNC_PACK result to task.backend
        ▼
task.backend

        │
        ▼
┌──────────────────────┐
│  BackendJobConsumer  │  (simpleec-backend-job)
└──────┬───────────────┘
        │
        │  1. Upsert sell_pack rows (channel listing data)
        │  2. Publish SYNC_PRODUCT to task.backend
        ▼
SYNC_PRODUCT → BackendJobConsumer
        │
        │  1. Match pack.sku → product.sku (within merchant scope)
        │  2. If product found: link sell_pack.product_id = product.id
        │  3. If product not found: create product stub
        ▼
sell_pack and product tables updated
```
