# Kafka / Backend Review Report

**Review date:** 2026-04-05
**Reviewer:** Senior Backend Engineer (Agent)
**Scope:** All Kafka producers, consumers, and channel/backend/order-job handlers

---

## 修復狀態摘要

**Date fixed:** 2026-04-06
| 狀態 | 數量 | 項目 |
|------|------|------|
| ✅ FIXED | 5 | K-C1, K-C3, M-1, M-2, M-3 |
| ✅ FALSE POSITIVE | 1 | K-C2 |
| ⚠️ OPEN | 0 | — |

---

## Overall Assessment

The implementation is solid in its core structure. Header building, schema validation, MDC tracing, DLT routing, and the translation layer (NanoID-only in message bodies) are all present and largely correct. The main issues are:

1. **Critical**: Scheduler `FETCH_ORDERS`/`FETCH_RETURNS` messages are missing `merchantId` and `isRollback` from the header, and the body contains `fetchSpec: {}` instead of `{ "timestamp": "..." }`.
2. **Critical**: `SchedulerConsumer` parses the heartbeat body timestamp with `body.get("timestamp").asLong()` — but the scheduler writes `timestamp` as an ISO-8601 string, not a Unix long, causing a silent `0` value.
3. **Moderate**: `ModeBOrderListHandler` produces `FETCH_ORDER_DETAIL` messages with an incomplete header (missing `isRollback`, `source`, `requestId`).
4. **Moderate**: `ReturnUpsertConsumer` routes `FORMAT_ERROR` messages to `task.failed` instead of `task.dlt`.
5. **Moderate**: `TaskBackendListener` (backend-job) does not call `SchemaVersionHandler.validate()` or `TaskMdcHelper.set/clear()`.
6. **Gap**: `SyncPackHandler` does not publish `SYNC_PRODUCT` to `task.backend` after upsert — the rule says it must.

---

## Confirmed Compliant

- **SellPackSyncService** (`UPDATE_INVENTORY`, `UPDATE_PRICE`) — All 9 required header fields present (lines 56-65). Body contains `sellPackId`, `operation`, `oldValue`, `newValue`. Partition key is `sellPack.getId()` (channelId semantics). Topic is `{platform}.fast`. Compliant.

- **UPDATE_INVENTORY body `oldValue`** — `SellPackSyncService` line 70 puts `oldValue` from the caller. The `UPDATE_INVENTORY` body contract is satisfied.

- **SHIP_ORDER translation** — `ShipOrderHandler` (line 39) reads `body.orderId` and resolves `channelOrderId` from `OrderRefRepository`. No platform ID leaks into Kafka bodies. Compliant.

- **APPROVE_RETURN / REJECT_RETURN translation** — Both handlers read `returnId` from the body, look up `ReturnOrderRef`, then `OrderRef` to get `channelOrderId`. Correct NanoID-only approach.

- **OrderUpsertConsumer** — `SchemaVersionHandler.validate()` called at line 60, `TaskMdcHelper.set()` at line 67, cleared in `finally` at line 138. `UnsupportedSchemaVersionException` routed to `task.dlt` at line 63. Compliant.

- **ReturnUpsertConsumer** — `SchemaVersionHandler.validate()` at line 63, `TaskMdcHelper.set()` at line 70, cleared in `finally` at line 172. `UnsupportedSchemaVersionException` routed to `task.dlt` at line 66. Compliant.

- **ChannelJobConsumer** — `SchemaVersionHandler.validate()` at line 186, `TaskMdcHelper.set()` at line 193, cleared in `finally` at line 273. `UnsupportedSchemaVersionException` routed to `task.dlt` at line 189. Compliant.

- **SchedulerConsumer** — `SchemaVersionHandler.validate()` at line 57, `TaskMdcHelper.set()` at line 64, cleared in `finally` at line 74. `UnsupportedSchemaVersionException` routed to `task.dlt` at line 60. Compliant.

- **RetryJobConsumer** — Error type routing is correct: `CLIENT_ERROR_4XX` and `FORMAT_ERROR` → `task.dlt` immediately (line 81), `SERVER_ERROR_5XX` max 3, `NETWORK_ERROR` max 5, unknown `taskType` → DLT (line 65). Matches `ErrorType.java` and kafka-envelope §4.4. Compliant.

- **DltConsumer** — Logs, persists to `failed_task_logs`, and fires alert. Correct.

- **SchemaVersionHandler** — `SUPPORTED_VERSIONS = Set.of(1)`. Correct.

- **TaskMdcHelper** — Sets `merchantId`, `taskType`, `requestId`, `platformId`, `channelId`. Clears all in `clear()`. Correct.

- **ErrorType** — Retry counts match the rule: `SERVER_ERROR_5XX` = 3, `NETWORK_ERROR` = 5, non-retryable = 0. Correct.

- **SyncPackChannelHandler** — Header has all 9 required fields (lines 129-137). Publishes to `TopicConstants.TASK_BACKEND`. Body has `channelProductId`, `channelSpecId`, platform-native IDs are in the body because this is a Channel Job publishing results to backend — this is the allowed inbound-results exception pattern (same as ORDER_UPSERT). Compliant.

- **FetchReturnsHandler** — Publishes `RETURN_UPSERT` to `return.process` (line 146). Header has all required fields. `channelOrderId` in `returnData` is the correct inbound-exception field. Compliant.

- **ModeAOrderListHandler / ModeBOrderDetailHandler** — `ORDER_UPSERT` messages have full headers and route to `order.process` with `channelOrderId` as partition key. Compliant.

- **RetryJobConsumer topic routing** (`routeTaskToTopic`) — `FETCH_ORDERS`, `FETCH_RETURNS`, `FETCH_RETURN_DETAIL` → `{platform}.slow`; `SHIP_ORDER`, `UPDATE_INVENTORY`, etc. → `{platform}.fast`; `ORDER_UPSERT` → `order.process`; `RETURN_UPSERT` → `return.process`. Correct.

---

## Critical Issues (message contract violations, data loss risk)

### C-1: Scheduler FETCH_ORDERS / FETCH_RETURNS header missing `merchantId` and `isRollback`

**狀態**: ✅ FIXED — `merchantId` and `isRollback` added to header; non-standard `priority` field removed.

**File:** `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/handler/SchedulerEventHandler.java`
**Method:** `buildFetchOrdersMessage()` — lines 220-239

**Problem:** The header built for `FETCH_ORDERS` and `FETCH_RETURNS` is:
```java
header.put("taskType", ...)
header.put("platformId", channel.getPlatformId())
header.put("channelId", channel.getId())
header.put("requestId", ...)
header.put("timestamp", ...)
header.put("source", "scheduler")
header.put("version", 1)
header.put("priority", "NORMAL")   // ← non-standard field
// MISSING: merchantId
// MISSING: isRollback
```

`merchantId` and `isRollback` are both mandatory per kafka-envelope §1. Although `ChannelJobConsumer` falls back to DB lookup for `merchantId` (line 231), the header is formally incomplete and breaks downstream MDC tracing (`TaskMdcHelper.set()` will not populate `merchantId`).

`isRollback` is entirely absent. When Channel Job forwards the ORDER_UPSERT it propagates `isRollback=false` (hardcoded — see ModeAOrderListHandler line 365), but the scheduler message itself still violates the schema contract.

**Impact:** `TaskMdcHelper` will silently skip `merchantId` in MDC, making trace correlation incomplete for all scheduler-driven fetches. Any future consumer that reads `header.isRollback` will always see `false` (missing field default), which is safe for now but hides the schema violation.

**Fix:**
```java
header.put("merchantId", channel.getMerchantId());   // add
header.put("isRollback", false);                      // add
header.remove("priority");                            // remove non-standard field
```

---

### C-2: Scheduler heartbeat body uses ISO-8601 string but `SchedulerConsumer` reads it as `asLong()`

**狀態**: ✅ FALSE POSITIVE — `HeartbeatJob` sends epoch millis as a long; `asLong()` works correctly. No fix needed.

**File:** `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/consumer/SchedulerConsumer.java`
**Line:** 68

**Problem:**
```java
long timestamp = body.get("timestamp").asLong();
```

`asLong()` on a JSON string like `"2026-04-05T10:00:00Z"` returns `0` silently (Jackson's default). The `HeartbeatJob` almost certainly publishes the heartbeat with an ISO-8601 string (matching all other messages). This means every heartbeat is processed with `timestamp=0`, causing all time-window calculations in Channel Job to compute relative to the Unix epoch (`1970-01-01`).

**Impact:** `FETCH_ORDERS` and `FETCH_RETURNS` will generate requests with time windows anchored at `1970-01-01T...`, causing either empty API results (platform rejects old dates) or, worse, flooding the platform with a very large historical date window.

**Fix:**
```java
// Option A: parse ISO-8601 string to epoch seconds
String tsStr = body.get("timestamp").asText();
long timestamp = Instant.parse(tsStr).getEpochSecond();

// Option B: if HeartbeatJob sends epoch millis, use asLong() — verify HeartbeatJob output first
```

Also verify `HeartbeatJob.java` to confirm what format it publishes in the heartbeat body.

---

### C-3: Scheduler FETCH_ORDERS / FETCH_RETURNS body violates the "timestamp-only" contract

**狀態**: ✅ FIXED — Body now contains `{"timestamp":"..."}` ISO-8601 string; empty `fetchSpec` object removed.

**File:** `SchedulerEventHandler.java` — `buildFetchOrdersMessage()`, lines 232-234

**Problem:**
```java
ObjectNode body = objectMapper.createObjectNode();
body.set("fetchSpec", objectMapper.createObjectNode());   // ← empty object, not timestamp
```

The body must be `{ "timestamp": "2026-04-05T08:00:00Z" }` per kafka-envelope §2.1 and §4.1.

Instead the body contains an empty `fetchSpec` object and no `timestamp` field. `ChannelJobConsumer` reads `body.path("timeRange").asText("last_5_minutes")` (line 287) as a fallback, meaning it silently ignores the message contract and runs with a hardcoded `"last_5_minutes"` string instead of the correct anchor timestamp.

**Impact:** Channel Job cannot derive correct time windows. All scheduler-driven fetches operate with a non-standard body that breaks the documented contract and any future handler that parses `body.timestamp` correctly.

**Fix:**
```java
ObjectNode body = objectMapper.createObjectNode();
body.put("timestamp", DateUtil.toIsoString(timestamp));   // ISO-8601 string
```

---

## Moderate Issues

### M-1: `ModeBOrderListHandler` produces incomplete `FETCH_ORDER_DETAIL` headers

**狀態**: ✅ FIXED — Header now uses integer `version: 1`; `requestId`, `source`, `isRollback`, `platformId` added; `messageId` removed; `Instant.now()` replaced with correct timestamp.

**File:** `simpleec-channel-job/src/main/java/com/simpleec/channeljob/handler/ModeBOrderListHandler.java`
**Method:** `sendFetchDetailMessage()` — lines 123-131

**Problem:** The header built for `FETCH_ORDER_DETAIL` is missing:
- `requestId` — only `messageId` is set (non-standard, should be `requestId`)
- `source` — missing
- `isRollback` — missing
- `platformId` — missing
- `version` — set as string `"1.0"` but kafka-envelope specifies integer `1`

```java
header.put("messageId", "msg_" + NanoIdUtil.generate());
header.put("taskType", TaskTypeEnum.FETCH_ORDER_DETAIL.getCode());
header.put("channelId", channelId);
header.put("merchantId", merchantId);
header.put("timestamp", Instant.now().toString());
header.put("version", "1.0");  // ← string, not integer
// MISSING: requestId, source, isRollback, platformId
```

`SchemaVersionHandler.validate()` will fail on this message because `"1.0"` as a string parses to `asInt(-1)` = `-1` which is not in `SUPPORTED_VERSIONS = Set.of(1)`. This means **every FETCH_ORDER_DETAIL message produced by ModeBOrderListHandler will be routed to `task.dlt` by ChannelJobConsumer** (line 189).

**Impact:** Mode B order detail fetching (Shopee, etc.) is effectively broken — all detail messages will be dead-lettered rather than processed.

**Fix:**
```java
header.put("requestId", "req_" + NanoIdUtil.generate());
header.put("platformId", adapter.getPlatformCode());
header.put("source", "channel_job");
header.put("version", 1);          // integer, not string
header.put("isRollback", false);
// keep messageId as a non-standard but harmless extra field, or remove it
```

---

### M-2: `ReturnUpsertConsumer` routes `FORMAT_ERROR` to `task.failed` instead of `task.dlt`

**狀態**: ✅ FIXED — `FORMAT_ERROR` catch block now sends to `TopicConstants.TASK_DLT` directly.

**File:** `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/ReturnUpsertConsumer.java`
**Lines:** 141-148

**Problem:**
```java
// catches IllegalArgumentException (missing/empty required fields → FORMAT_ERROR)
errorInfo.put("errorType", "FORMAT_ERROR");
...
kafkaTemplate.send("task.failed", "ReturnUpsert", wrappedMessage.toString());  // ← wrong
```

Per kafka-envelope §4.4: `FORMAT_ERROR` is non-retryable and must go directly to `task.dlt` (30-day retention). Sending to `task.failed` causes `RetryJobConsumer` to immediately re-route it to DLT anyway (since `FORMAT_ERROR.isRetryable() == false`), but it adds unnecessary latency and an extra round-trip through the retry system.

The same pattern exists in `OrderUpsertConsumer` lines 126-134 for general errors (always classifies as `SERVER_ERROR_5XX` and sends to `task.failed`) — this is acceptable for `SERVER_ERROR_5XX`, but `FORMAT_ERROR` must not go to `task.failed`.

**Fix:**
```java
// In ReturnUpsertConsumer catch(IllegalArgumentException e):
kafkaTemplate.send(TopicConstants.TASK_DLT, "ReturnUpsert", wrappedMessage.toString());
```

---

### M-3: `TaskBackendListener` missing `SchemaVersionHandler` and `TaskMdcHelper`

**狀態**: ✅ FIXED — `SchemaVersionHandler.validate()` now called before dispatch; `TaskMdcHelper.set()/clear()` added with proper try/finally structure; unsupported schema version routed to `task.dlt`.

**File:** `simpleec-backend-job/src/main/java/com/simpleec/backendJob/consumer/TaskBackendListener.java`

**Problem:** The `consume()` method (line 33) never calls:
- `SchemaVersionHandler.validate(event)` — required per kafka-envelope §4.5
- `TaskMdcHelper.set(event)` / `TaskMdcHelper.clear()` — required per kafka-envelope §4.6

All other consumers (OrderUpsertConsumer, ReturnUpsertConsumer, ChannelJobConsumer, SchedulerConsumer) correctly call both. `TaskBackendListener` is the only consumer that skips them.

**Impact:**
1. Malformed `version` field in backend task messages will pass through silently instead of being dead-lettered.
2. No `merchantId`, `taskType`, `requestId` in MDC for backend job logs — trace correlation is broken for all `task.backend` processing.

**Fix:**
```java
public void consume(String message) {
    JsonNode event = objectMapper.readTree(message);
    try {
        SchemaVersionHandler.validate(event);
    } catch (UnsupportedSchemaVersionException e) {
        kafkaTemplate.send(TopicConstants.TASK_DLT, "BackendJob", message);
        return;
    }
    TaskMdcHelper.set(event);
    try {
        // ... existing handler dispatch logic ...
    } finally {
        TaskMdcHelper.clear();
    }
}
```

---

### M-4: `SchedulerConsumer` — heartbeat body field access without null check

**File:** `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/consumer/SchedulerConsumer.java`
**Line:** 68

```java
long timestamp = body.get("timestamp").asLong();
```

`body.get("timestamp")` returns `null` if the field is absent, causing a `NullPointerException` that is swallowed by the outer catch at line 77. The scheduler heartbeat silently fails every time if the field name doesn't match.

**Fix:** Use `path()` instead of `get()`:
```java
long timestamp = body.path("timestamp").asLong();
```
And add null validation before proceeding (or resolve C-2 to parse correctly).

---

### M-5: `ChannelJobConsumer.handleFetchOrders()` reads `body.timeRange` — non-standard field

**File:** `simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelJobConsumer.java`
**Line:** 287

```java
String timeRange = body.path("timeRange").asText("last_5_minutes");
```

The `timeRange` field is not part of the Kafka message contract and the value is never actually used by `ModeAOrderListHandler` or `ModeBOrderListHandler` (neither reads it from the body). The `baseTimestamp` passed to those handlers comes from `header.timestamp` (line 237), which is correct per the rules.

This dead read adds confusion — it implies that `body.timeRange` is intended, but it is never populated by any producer. The comment about "Mode A" using `baseTimestamp` is accurate.

**Impact:** No functional bug, but the stale code suggests confusion about the contract. Removing it prevents future misuse.

**Fix:** Remove line 287-288. The `timeRange` variable is never passed to the adapter calls.

---

## Gaps (rules say should exist, not yet implemented)

### G-1: `SyncPackHandler` does not publish `SYNC_PRODUCT` after upsert

**File:** `simpleec-backend-job/src/main/java/com/simpleec/backendJob/handler/impl/SyncPackHandler.java`

**Rule:** product-sync.md §5.1 step 5 says: "自動發送 SYNC_PRODUCT 訊息至 task.backend" after completing the sell_pack upsert.

**Current behavior:** `SyncPackHandler.processReport()` upserts `sell_pack` and auto-creates a `Product` record inline (lines 101-119) if the SKU is new, but never publishes a `SYNC_PRODUCT` event. The flow from `SyncPackHandler → SYNC_PRODUCT → SyncProductHandler` is defined in the rules but the Kafka publish step is missing.

`SyncProductHandler` exists and is registered (responds to `"SYNC_PRODUCT"` task type), but it is never triggered because no producer sends to it.

**Impact:** `SyncProductHandler` is dead code. The two-step pipeline (SyncPack → SyncProduct) is collapsed into one step with inline Product creation. This is functionally equivalent for simple cases but violates the intended decoupling — `SyncProductHandler`'s update path (name/price refresh for existing products) will never run.

**Fix:** After saving the sell_pack, publish a `SYNC_PRODUCT` message:
```java
// After sellPackRepository.save(pack):
publishSyncProductEvent(merchantId, channelId, sellPackId, sku, channelProductName, sellingPrice, header);
```
Where `publishSyncProductEvent` builds a `task.backend` message with `taskType=SYNC_PRODUCT` per product-sync.md §3.4.

---

### G-2: `FETCH_ORDER_DETAIL` body contract uses `channelOrderId` but rule specifies `orderId`

**File:** `ModeBOrderListHandler.java` — `sendFetchDetailMessage()`, line 135

**Rule:** kafka-envelope §2.1 defines `FETCH_ORDER_DETAIL` body as:
```json
{ "orderId": "<OMS NanoID>" }
```
The `orderId` is the OMS internal NanoID; Channel Job resolves `channelOrderId` from DB.

**Current behavior:** The body contains:
```java
body.put("channelOrderId", channelOrderId);
```
This puts the platform-native order ID directly in the body, violating the translation rule (kafka-envelope §4.2). `ChannelJobConsumer` then reads it at line 245:
```java
String channelOrderId = body.path("channelOrderId").asText();
```
And passes it directly to `handleFetchOrderDetail` which calls `ModeBOrderDetailHandler.handleModeBOrderDetail(merchantId, channelId, channelOrderId, adapter)`.

This is internally consistent (the consumer reads what the producer writes), but breaks the rule that Kafka bodies must use OMS NanoIDs. The `channelOrderId` is a platform-native string, not a NanoID.

Note: There is a valid argument that `FETCH_ORDER_DETAIL` is a Mode B internal re-trigger and the OMS orderId doesn't exist yet at list stage. This might be a deliberate exception. If so, it needs to be documented as an exception alongside `ORDER_UPSERT.channelOrderId`.

**Recommended action:** Either align to the rule (pass OMS orderId if it exists, or a pending-fetch token), or document this as a justified exception in kafka-envelope §4.2.

---

### G-3: No `CANCEL_ORDER` producer/consumer implemented

**Rule:** kafka-envelope §2.2 defines `CANCEL_ORDER` body and §3 routes it to `{platform}.fast`. The topic routing table lists it. No producer or handler for `CANCEL_ORDER` exists in the codebase.

**Impact:** Gap only — feature not yet built. No existing code is broken.

---

### G-4: `ApproveReturnHandler` / `RejectReturnHandler` do not publish `RETURN_ACTION_CONFIRMED`

**Rule:** return-flow.md §3.4 and §4.3 step 4 require Channel Job to publish `RETURN_ACTION_CONFIRMED` to `task.backend` after successfully calling the platform approve/reject API.

**Current behavior:** Both handlers call the platform API and log success, but do not publish any confirmation message (no `kafkaTemplate.send()` for `RETURN_ACTION_CONFIRMED`). The `ReturnActionConfirmedHandler` in backend-job (if it exists) will never be triggered.

**Impact:** Return status in the OMS database is never updated after approve/reject completes. The `refund_orders.status` stays stale after a successful platform action.

---

### G-5: No `SHIP_ORDER_CONFIRMED` / `SHIP_ORDER_FAILED` publish in `ShipOrderHandler`

**Rule:** shipment.md §3.3 and §4.1 step 5-6 require `ShipOrderHandler` to publish `SHIP_ORDER_CONFIRMED` or `SHIP_ORDER_FAILED` to `task.backend` after the platform call.

**Current behavior:** `ShipOrderHandler` calls `adapter.shipOrder()` and logs success/failure. It throws a `RuntimeException` on failure (causing `ChannelJobConsumer` to propagate the exception, which is caught by the outer catch at line 276 and logged, but no Kafka message is sent). No confirmation event is published on either success or failure.

**Impact:** `shipments.status` is never updated from `PENDING` to `SHIPPED` or `FAILED`. The fulfillment_status calculation in `ShipOrderConfirmedHandler` never runs. Shipment state in the OMS DB is permanently `PENDING` after being submitted.

---

## Suggestions

### S-1: Consumer group name for `ReturnUpsertConsumer` is semantically misnamed

**File:** `ReturnUpsertConsumer.java` line 53: `groupId = "return-job-group"`

The consumer lives in `simpleec-order-job` module but uses `"return-job-group"`. While not technically wrong, it's inconsistent. Consider renaming to `"order-job-return-group"` to make the module boundary clear and avoid confusion if a dedicated `simpleec-return-job` module is added later.

---

### S-2: `OrderUpsertConsumer` partition key uses `"OrderUpsert"` string literal instead of `orderId`

**File:** `OrderUpsertConsumer.java` lines 63, 74, 133

`kafkaTemplate.send(TopicConstants.TASK_DLT, "OrderUpsert", ...)` — using a static string literal as the partition key means all DLT messages from this consumer go to the same partition, which is acceptable for DLT. But for `task.failed` (line 132), using a fixed key `"OrderUpsert"` removes the ordering guarantee. This is unlikely to matter for `task.failed` (no ordering needed for retry), so this is a low-priority suggestion.

---

### S-3: `ChannelJobConsumer` has dead `@Autowired(required = false)` fields

**File:** `ChannelJobConsumer.java` lines 78-81

```java
@Autowired(required = false)
private org.springframework.kafka.listener.ContainerProperties.AckMode ackMode;
```

`AckMode` is an enum and will never be autowired as a bean. This is dead configuration that adds noise. Remove it.

---

### S-4: `SyncPackHandler` reads `merchantId` from `body.merchantId` via `AbstractEventHandler.extractMerchantId()` — but the rule says header

`AbstractEventHandler.extractMerchantId()` reads from `body.merchantId` (line 19-26). `SyncPackChannelHandler` does include `merchantId` in both header and body (line 140: `body.put("merchantId", merchantId)`), so this works. However, the canonical source per the rules is `header.merchantId`. The AbstractEventHandler should read from `header.merchantId` for consistency, with `body.merchantId` as a fallback. This protects against the case where a future producer omits `merchantId` from the body.

---

### S-5: `SchedulerEventHandler` dispatches `FETCH_ORDERS` directly to `{platform}.slow` — bypassing `scheduler` topic

**Rule:** The topic routing table (kafka-envelope §3) lists `FETCH_ORDERS` on both `{platform}.slow` (the channel topic) and `scheduler` (trigger). Looking at the flow, the scheduler sends to `scheduler` topic → `SchedulerConsumer` reads → `SchedulerEventHandler.dispatchFetchOrders()` sends to `{platform}.slow`.

This is correct — the final destination for `FETCH_ORDERS` is `{platform}.slow` and the scheduler sends there directly. No issue.

---

### S-6: Hash mismatch risk between `ModeAOrderListHandler` and `OrderUpsertConsumer`

`ModeAOrderListHandler.calculateOrderHash()` (line 317) uses key `"status"` for the order status. `OrderUpsertConsumer.calculateOrderHash()` (line 429) also uses key `"status"`. These are consistent.

However, `ModeAOrderListHandler` puts `shippingInfo` in the hash, while `OrderUpsertConsumer` also puts `shippingInfo`. The `totalAmount` serialization differs: Mode A stores a `double` (`omsData.put("totalAmount", calculatedTotal)`), OrderUpsertConsumer stores a `BigDecimal`. When `asText()` is called on each, `1200.0` vs `1200` might produce different strings. This is a subtle hash instability that will cause false UPDATE triggers on every fetch even when the order hasn't changed.

**Fix:** Normalize `totalAmount` to a consistent string representation in both hash calculators (e.g., use `new BigDecimal(value).toPlainString()`).
