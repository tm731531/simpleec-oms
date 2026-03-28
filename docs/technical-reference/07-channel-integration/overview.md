# Channel Integration Overview

A "Channel" in SimpleEC OMS is one integration instance connecting a merchant to a single e-commerce platform (e.g., one Shopee shop, one Momo store). This document explains the architecture that keeps all platform integrations uniform from the outside while accommodating each platform's unique API behavior internally.

---

## 1. What a Channel Job Does

A Channel Job is a Spring Boot application that runs as a Docker container. It:

1. **Consumes** task messages from a platform-specific Kafka topic (e.g., `shopee.slow`)
2. **Calls** the external platform API to fetch orders, returns, or to push shipment/inventory updates
3. **Transforms** the platform-specific response format into the unified OMS format
4. **Publishes** normalized messages to `order.process` or `return.process`

Channel Jobs never touch the OMS database directly. They are pure data adapters.

```
Kafka (shopee.slow)
    → ChannelJobConsumer
        → routes to handler by taskType
            → handler calls ChannelAdapter (Shopee API)
                → handler publishes to order.process
                    → OrderUpsertConsumer writes to DB
```

---

## 2. Docker Deployment — 10 Channel Job Containers

One shared JAR image (`simpleec-channel-job`) is reused across all containers. Each container is configured by environment variables to serve one platform and one speed tier:

| Container name | Topics consumed | Group ID |
|---------------|-----------------|----------|
| `simpleec-channel-momo-fast` | `momo.fast` | `channel-job-momo` |
| `simpleec-channel-momo-slow` | `momo.slow` | `channel-job-momo` |
| `simpleec-channel-shopee-fast` | `shopee.fast` | `channel-job-shopee` |
| `simpleec-channel-shopee-slow` | `shopee.slow` | `channel-job-shopee` |
| `simpleec-channel-yahoo-fast` | `yahoo.fast` | `channel-job-yahoo` |
| `simpleec-channel-yahoo-slow` | `yahoo.slow` | `channel-job-yahoo` |
| `simpleec-channel-pchome-fast` | `pchome.fast` | `channel-job-pchome` |
| `simpleec-channel-pchome-slow` | `pchome.slow` | `channel-job-pchome` |
| `simpleec-channel-cyberbiz-fast` | `cyberbiz.fast` | `channel-job-cyberbiz` |
| `simpleec-channel-cyberbiz-slow` | `cyberbiz.slow` | `channel-job-cyberbiz` |

**fast topics:** Short-duration push operations — `SHIP_ORDER`, `UPDATE_PRICE`, `UPDATE_INVENTORY`, `APPROVE_RETURN`, `REJECT_RETURN`. Expected to complete in under 5 seconds.

**slow topics:** Long-duration fetch operations — `FETCH_ORDERS`, `FETCH_ORDER_DETAIL`, `FETCH_RETURNS`. Can take minutes due to pagination and API rate limits.

The container knows which platform it serves by parsing `JOB_CHANNEL_GROUP_ID` (e.g., `channel-job-cyberbiz` → `cyberbiz`).

---

## 3. Message Routing (taskType Dispatch)

`ChannelJobConsumer.consumeChannelMessage()` routes each incoming Kafka message to the correct handler based on `header.taskType`:

| `taskType` | Handler | Topic tier |
|-----------|---------|-----------|
| `FETCH_ORDERS` | `ModeAOrderListHandler` or `ModeBOrderListHandler` (based on adapter mode) | slow |
| `FETCH_ORDER_DETAIL` | `ModeBOrderDetailHandler` | slow |
| `FETCH_RETURNS` | `FetchReturnsHandler` | slow |
| `SHIP_ORDER` | `ShipOrderHandler` | fast |
| `UPDATE_INVENTORY` | `UpdateInventoryHandler` | fast |
| `UPDATE_PRICE` | `UpdatePriceHandler` | fast |
| `APPROVE_RETURN` | `ApproveReturnHandler` | fast |
| `REJECT_RETURN` | `RejectReturnHandler` | fast |
| `CHECK_HEALTH` | `HealthCheckService` | fast |
| `CHECK_HEALTH_PLATFORM` | `HealthCheckService` | fast |

After routing, the handler selects the correct `ChannelAdapter` based on the platform code extracted from `JOB_CHANNEL_GROUP_ID`.

---

## 4. Mode A vs Mode B

Platforms differ in how much data their list API returns. This determines whether one or two API calls are needed per order.

| Dimension | Mode A | Mode B |
|-----------|--------|--------|
| Examples | Shopify, Easystore | Shopee, Momo, Yahoo, PChome, Cyberbiz |
| List API | Returns complete order (all fields, all line items) | Returns only order IDs or a summary |
| Detail API needed? | No | Yes — one call per order ID |
| Flow | `FETCH_ORDERS` → `ORDER_UPSERT` | `FETCH_ORDERS` → N × `FETCH_ORDER_DETAIL` → N × `ORDER_UPSERT` |
| Kafka messages per order | 1 | 2 |

`ChannelAdapter.getMode()` returns `ModeEnum.A` or `ModeEnum.B`. `ChannelJobConsumer.handleFetchOrders()` checks this and routes to the appropriate handler.

### Mode A flow
```
FETCH_ORDERS message
  → ModeAOrderListHandler.handleModeAOrders()
      → adapter.fetchOrdersByTimestamp(channelId, baseTimestamp)
          → returns List<Map> (complete orders)
      → for each order:
          → calculate SHA-256 hash of business fields
          → Redis dedup check (skip if unchanged)
          → publish ORDER_UPSERT to order.process
```

### Mode B flow
```
FETCH_ORDERS message
  → ModeBOrderListHandler.handleModeBOrderList()
      → adapter.fetchOrderListByTimestamp(channelId, baseTimestamp)
          → returns List<String> (order IDs only)
      → for each order ID:
          → publish FETCH_ORDER_DETAIL to {platform}.slow

FETCH_ORDER_DETAIL message (one per order ID)
  → ModeBOrderDetailHandler.handleModeBOrderDetail()
      → adapter.fetchOrderDetail(channelId, orderId)
          → returns Map (complete order)
      → publish ORDER_UPSERT to order.process
```

---

## 5. Deduplication (Redis Hash Cache)

Before publishing `ORDER_UPSERT`, Mode A checks Redis to avoid sending unchanged orders:

1. Hash the order's business fields (status, totalAmount, items, shippingInfo, buyerInfo) using SHA-256
2. Check `order:hash:{merchantId}:{channelId}:{channelOrderId}` in Redis
3. If hash matches → skip (no change)
4. If hash differs or key missing → publish `ORDER_UPSERT` and update the Redis key

Redis failures are non-fatal: if Redis is unavailable the dedup check is skipped with a warning and the message is published anyway. This means the downstream `OrderUpsertConsumer` may receive a duplicate, but it handles that via DB upsert semantics.

---

## 6. Time Window Decisions — Channel Job Autonomy

**The Scheduler only sends a single timestamp.** The Channel Job is fully responsible for deciding what time range to query. This is intentional — each platform has different API semantics.

```
Scheduler message body:
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "channelId": "CHANNEL_SHOPEE_001",
    "timestamp": "2026-03-28T10:00:00Z"   ← single point in time
  },
  "body": {}   ← no fromTime, no toTime
}
```

The Channel Job handler calls `adapter.fetchOrdersByTimestamp(channelId, baseTimestamp)` and the adapter decides the window internally:

| Platform | Strategy | Time window logic |
|----------|----------|-------------------|
| Shopee | Mode B, status-based batches | PENDING: `[ts-1h, ts]`, AWAITING_SHIPMENT: `[ts-3d, ts]`, SHIPPED: `[ts-5d, ts]`, COMPLETED: `[ts-7d, ts]` |
| Momo | Mode B, aggregated | New orders: `[ts-1h, ts]`; backfill: `[ts-3d, ts]` |
| Yahoo | Mode B, update-time query | `updated_after: ts-1d` (no status split) |
| Easystore | Mode A, 7-day window | `from_date: ts-7d`, `to_date: ts` (50 complete orders/page) |
| Cyberbiz | Mode B, dual-query | created: `[ts-7d, ts]`, updated: `[ts-1d, ts]`, merged + deduped |
| Shopify | Mode A, dual-query | new: `[ts-7d, ts]`, updated: `[ts-1d, ts]`, merged + deduped |

---

## 7. ChannelAdapter Interface

Every platform adapter implements `com.simpleec.channel.adapter.ChannelAdapter`:

```java
public interface ChannelAdapter {
    String getPlatformCode();     // e.g., "shopee"
    ModeEnum getMode();           // ModeEnum.A or ModeEnum.B

    // Mode A: full orders by timestamp
    List<Map<String, Object>> fetchOrdersByTimestamp(String channelId, long baseTimestamp) throws Exception;

    // Mode B: order IDs by timestamp
    List<String> fetchOrderListByTimestamp(String channelId, long baseTimestamp) throws Exception;

    // Mode B: single order detail
    Map<String, Object> fetchOrderDetail(String channelId, String orderId) throws Exception;

    // Returns
    List<Map<String, Object>> fetchReturns(String channelId, String timeRange) throws Exception;

    // Push operations
    void shipOrder(String orderId, Map<String, Object> shippingInfo) throws Exception;
    void updateInventory(String productId, int quantity) throws Exception;

    // Health check
    boolean testConnection() throws Exception;
}
```

Adapters live in `simpleec-channel/src/main/java/com/simpleec/channel/adapter/`.

---

## 8. ORDER_UPSERT Message Format

Both Mode A and Mode B handlers produce this message on `order.process`:

```json
{
  "header": {
    "messageId": "msg_NANOID",
    "requestId": "req_NANOID",
    "taskType": "ORDER_UPSERT",
    "platformId": "cyberbiz",
    "channelId": "CHANNEL_CYBERBIZ_001",
    "merchantId": "mrc_NANOID",
    "timestamp": "2026-03-28T10:00:00Z",
    "source": "channel_job",
    "version": 1,
    "isRollback": false
  },
  "body": {
    "channelOrderId": "49004286",
    "channelOrderNumber": "#1001",
    "orderHash": "a3f9c2...",
    "orderData": {
      "orderStatus": "PENDING",
      "totalAmount": 1299.0,
      "shippingFee": 60.0,
      "discountAmount": 0.0,
      "channelCreatedAt": "2026-03-28T09:30:00Z",
      "items": [...],
      "buyerName": "王小明",
      "buyerPhone": "0912345678",
      "buyerEmail": "user@example.com",
      "shippingAddress": "台北市信義區...",
      "paymentMethod": "credit_card",
      "shippingMethod": "7-11 便利店取貨"
    }
  }
}
```

`channelOrderId` is used as the Kafka partition key to ensure sequential processing for the same order.

---

## 9. Error Handling

- Per-order failures inside a handler do not abort the entire batch. Errors are logged and processing continues to the next order.
- Unrecoverable consumer errors are caught at the top-level try/catch in `consumeChannelMessage` and logged.
- Messages with unsupported schema versions are routed to `task.dlt`.
- Messages missing `header` or `body` are routed to `task.dlt`.
- Unknown `taskType` values are logged as warnings and dropped (not retried).
