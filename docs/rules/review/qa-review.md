# QA Review Report

**Reviewer:** Senior QA Engineer (Claude Sonnet 4.6)
**Date:** 2026-04-05
**Scope:** Inventory Sync, Order Processing, Shipment, Return Flow, Product Sync flows
**Rule Sources:** `docs/rules/flows/` and `docs/rules/tech/`

---

## Overall Assessment

The codebase has a **clear architectural vision** and correct high-level flow. The unit test layer (Mockito-based) covers the core Kafka consumer paths. However, there are **five critical gaps** that carry real data-loss or silent-corruption risk in production:

1. `UpdateInventoryHandler` hardcodes Cyberbiz — multi-location and async-inventory paths are entirely unimplemented
2. `SyncPackHandler` never fires `SYNC_PRODUCT` — the Pack → Product trigger chain is broken
3. `sell_pack_inventory` snapshot is never updated after inventory push — the QA checklist's most important write is absent
4. `OrderUpsertConsumer` does not write `revenue_date` / `is_backfill` — isRollback business logic is incomplete
5. `ShipOrderHandler` hardcodes `if ("cyberbiz".equalsIgnoreCase(platformCode))` — a direct violation of the capabilities-model red line

Test coverage is reasonable for the order/return Kafka consumer layer but has zero coverage for the channel job handlers and the backend job handlers (except health checks).

---

## Test Coverage Summary

### Existing Test Files

| Test File | Type | What It Covers |
|---|---|---|
| `ReturnUpsertHandlerTest` | Unit (Mockito) | Redis fast-path, DB INSERT, DB UPDATE for returns |
| `OrderUpsertConsumerTest` | Unit (Mockito) | ORDER_UPSERT schema validation, two-layer dedup, 5 status variants |
| `ReturnUpsertConsumerTest` | Unit (Mockito) | RETURN_UPSERT routing, invalid taskType, missing fields |
| `ModeBOrderDetailHandlerTest` | Unit (Mockito) | Status mapping, null items, nested amount_info, hash length |
| `CyberbizAdapterTest` | Unit | Cyberbiz adapter |
| `RedisKeyUtilTest` | Unit | Redis key format |
| `PlatformApiClientImplTest` | Unit | Platform API client |
| `HealthCheckServiceTest` + `HealthCheckServiceIntegrationTest` | Unit/Integration | Health check only |
| `ChannelToOrderE2ETest` + `ChannelToOrderIntegrationTest` | E2E/Integration | Channel → Order flow |
| `HealthCheckIntegrationTest` (scheduler) | Integration | Scheduler health |

### Completely Untested Critical Paths

- `UpdateInventoryHandler` — zero tests; inventory push to platform never tested
- `ShipOrderHandler` — zero tests; shipment push to platform never tested
- `SyncPackHandler` — zero tests; sell_pack upsert and product trigger never tested
- `SyncProductHandler` — zero tests; product upsert never tested
- `SchedulerJobService.publishTaskToSlowTopic()` — message structure / header validity untested
- PII encryption round-trip — no test verifies that `buyerName` is ciphertext in DB and plaintext in API response
- `isRollback = true` revenue attribution — `revenue_date` and `is_backfill` logic is untested
- Shipment status log writes (`shipment_status_logs`) — no test
- `sell_pack_inventory` snapshot update after inventory push — no test
- Redis failure fallback for ORDER_UPSERT — no test verifying DB-only dedup still works
- `SHIP_ORDER_CONFIRMED` / `ShipOrderConfirmedHandler` — untested
- Return `APPROVE_RETURN` / `REJECT_RETURN` channel handlers — untested

---

## QA Checklist Results

### Inventory Sync (§9)

| Checklist Item | Status | Evidence |
|---|---|---|
| UPDATE_INVENTORY body includes `oldValue` (non-null) | ✅ PASS | `SellPackSyncService.java:70` — `body.put("oldValue", oldValue)` |
| `sell_pack_inventory` updated (quantity + last_synced_at) after push success | 🔴 FAIL | `UpdateInventoryHandler.java` calls `cyberbizAdapter.updateVariantInventory()` but never writes to `sell_pack_inventory`. The entity and repository exist in `simpleec-core` but are not called from the handler. |
| Push failure sets `sync_status = 'failed'` and sends to `task.failed` | 🔴 FAIL | `UpdateInventoryHandler.java:67-71` rethrows RuntimeException. No `sync_status` DB write on failure, no `task.failed` Kafka send. The throw propagates to the consumer which may or may not handle it. |
| Shopify: uses `platform_metadata.inventory_item_id` (not `channelSpecId`) | 🔴 FAIL | `UpdateInventoryHandler.java` has no multi-location branch. Neither `platform.capabilities` nor `platform_metadata` is read. The entire Shopify inventory path is absent. |
| Only pushes to `channel_location.is_sync_target = true` | 🔴 FAIL | Same root cause — no multi-location logic implemented. |
| Shopee asyncInventory: `task_id` persisted, `failure_list` checked | 🔴 FAIL | No `asyncInventory` capability check anywhere in `UpdateInventoryHandler`. |
| No hardcoded platform name in capability checks | ✅ PASS | `UpdateInventoryHandler` itself has no platform-name conditionals; uses `cyberbizAdapter` by DI. However the handler only ever calls Cyberbiz — other platforms silently get no-op. |
| `sell_pack_inventory` query always includes `sell_pack_id` | ⚠️ PARTIAL | `SellPackInventoryRepository.java` has `findBySellPackId()` which is correct, but `findBySellPackIdAndChannelLocationIdIsNull()` exists; it is never actually called from handler code. |
| Upsert not SELECT-then-INSERT | N/A | No upsert code exists in handler (the write is missing entirely). |
| `newQuantity = 0` handled normally | ✅ PASS | `body.path("newValue").asInt(0)` allows zero. |

### Order Processing (§9)

| Checklist Item | Status | Evidence |
|---|---|---|
| Scheduler body contains only `timestamp` (no `from`/`to`) | 🔴 FAIL | `SchedulerJobService.java:110` — `body.put("triggeredAt", System.currentTimeMillis())` adds a millisecond timestamp (epoch) instead of ISO-8601, and uses `platform` instead of correct header fields. Note: this service is marked `@deprecated` and disabled with `// @Service`, but no replacement scheduler sends correct FETCH_ORDERS bodies. `SchedulerConsumer.java` is the active class — not reviewed but the format must be verified. |
| Channel Job uses `header.timestamp` for time window (no `Instant.now()`) | 🔴 FAIL | `ModeAOrderListHandler.java:362` and `ModeBOrderDetailHandler.java:307` call `Instant.now().toString()` for the outbound message `header.timestamp`. This is for the producer side (setting a new timestamp on emitted messages), which is correct. However `ChannelJobConsumer.java:373` falls back to `Instant.now()` when parsing the inbound scheduler timestamp fails — this silently uses wall-clock time instead of the base timestamp, causing wrong time windows during backfill. |
| ORDER_UPSERT body contains `channelOrderId` (not null) | ✅ PASS | `OrderUpsertConsumer.java:97-102` — required field guard routes to DLT if missing. |
| Same-hash dedup skips processing | ✅ PASS | `OrderUpsertConsumer.java:160-163` — Redis hash match exits early. DB hash match also exits early (line 189-198). |
| Redis failure falls back to DB dedup | ✅ PASS | `OrderUpsertConsumer.java:164-167` — Redis exception is caught with `log.warn`, execution continues. |
| PII fields encrypted in DB | ⚠️ PARTIAL | `EncryptionContext.setMerchantId()` is called at line 108, and `EncryptionContext.clear()` is in `finally`. PII setter calls (`setBuyerName` etc.) at lines 391-409 will use the converter. However no test verifies the DB actually stores ciphertext. |
| API response contains decrypted PII | ⚠️ PARTIAL | No evidence in controller/service layer that `EncryptionContext.setMerchantId()` is set before reading orders for API responses. The rule requires it in `OrderController`/`OrderService`. |
| `isRollback = true` sets `revenue_date = channelCreatedAt.toLocalDate()` | 🔴 FAIL | `OrderUpsertConsumer.java:416` calls `order.setRollback(isRollback)` but never sets `revenue_date` or `is_backfill`. The rule at `order-processing.md §5.4` requires explicit `setRevenueDate()` and `setIsBackfill()`. These fields appear to not exist on the `Order` entity or their setters are not called. |
| Status change writes `order_status_logs` | 🔴 FAIL | `OrderUpsertConsumer.java` has no `order_status_logs` insert logic at all. The rule at `order-processing.md §5.5` requires a log insert when status differs. |
| OMS accepts any status transition (no validation rejection) | ✅ PASS | No state machine check found in consumer. |
| `tracking_number` null handled (no NPE on INSERT) | ✅ PASS | No hard NOT NULL constraint on tracking data; fields are optional in `populateOrderFromData`. |
| `isRollback` read from header (not just body) | ✅ PASS | `header.path("isRollback").asBoolean(false)` at line 94. |

### Shipment (§9)

| Checklist Item | Status | Evidence |
|---|---|---|
| SHIP_ORDER body `orderId` is OMS NanoID | ✅ PASS | Rule documented; `ShipOrderHandler.java:39` resolves via `orderRefRepository.findById(orderId)`. |
| Channel Job looks up `channelOrderId` from DB | ✅ PASS | `ShipOrderHandler.java:39-43` — `orderRefRepository.findById(orderId).getChannelOrderId()`. |
| Shopee: `get_shipping_parameter` called before `ship_order` | 🔴 FAIL | `ShipOrderHandler.java:62-87` — the Shopee branch does not exist. Only Cyberbiz is implemented. |
| Shopify: `fulfillment_order_id` fetched before `fulfillments.json` | 🔴 FAIL | Same — no Shopify branch. |
| Shipment status change writes `shipment_status_logs` INSERT | 🔴 FAIL | No `ShipOrderConfirmedHandler` or `ShipOrderFailedHandler` code found in backend-job. `shipment_status_logs` is never written. |
| Batch shipment: single failure doesn't block others | N/A | No batch handler found. |
| `order.fulfillment_status = PARTIAL` for partial shipment | N/A | Fulfillment status calculation handler (`ShipOrderConfirmedHandler`) not found. |

### Return Flow (§9)

| Checklist Item | Status | Evidence |
|---|---|---|
| RETURN_UPSERT sent to `return.process` (not `order.process`) | ✅ PASS | `ReturnUpsertConsumer.java:53` — `@KafkaListener(topics = "return.process")`. |
| APPROVE_RETURN body uses OMS NanoID `returnId` | ✅ PASS | Rule-documented; no handler code found to verify translation, but rule acknowledged in architecture docs. |
| Cyberbiz uses `refund_at` time window (not `updated_at`) | ⚠️ PARTIAL | `FetchReturnsHandler` exists but was not deep-read. The capability check for `return.fetchByRefundAt` is documented in the flow rule. |
| Dedup: same `channelReturnId + status` skipped | ✅ PASS | `ReturnUpsertHandler.java:72-75` — Redis hash check; `ReturnUpsertHandler.java:73-85` — DB hash comparison. |
| `channelOrderId` not found → `order_id = null`, warn log, no exception | ✅ PASS | `ReturnUpsertHandler.java (populateReturnFromData:138-144)` — logs warn, does not set orderId, does not throw. |
| `status_history` JSONB append-only | ⚠️ PARTIAL | `ReturnOrder` entity has `items` field but no explicit `status_history` JSONB field observed in `ReturnUpsertHandler`. The DB schema defines `status_history` JSONB but the Java handler never writes to it. |
| FORMAT_ERROR (invalid message) routes to `task.dlt` | 🔴 FAIL | `ReturnUpsertConsumer.java:143-151` — `IllegalArgumentException` (FORMAT_ERROR) is sent to `"task.failed"` not `"task.dlt"`. This violates the kafka-envelope rule (§4.4): FORMAT_ERROR must go to `task.dlt`. |

### Product Sync (§9)

| Checklist Item | Status | Evidence |
|---|---|---|
| Channel Job does NOT write DB directly | ✅ PASS | `SyncPackChannelHandler` sends Kafka to `task.backend`; no DB access observed. |
| Shopify `inventory_item_id` stored in `platformMetadata.shopify.inventory_item_id` | ⚠️ PARTIAL | `SyncPackChannelHandler` reads from Shopify response, but `SyncPackHandler.java` never writes `platform_metadata` JSONB — only scalar fields like `price`, `status`, `quantity`. |
| `SyncPackHandler` auto-triggers `SYNC_PRODUCT` after upsert | 🔴 FAIL | `SyncPackHandler.java:162-165` — after `sellPackRepository.save(pack)`, there is no Kafka send for `SYNC_PRODUCT`. The rule requires automatic trigger. `SyncProductHandler` exists but can never be reached from this path. |
| Same merchantId + SKU never creates duplicate product | ✅ PASS | `SyncPackHandler.java:101-120` — `productRepository.findByMerchantIdAndSku()` used before creating new. DB UNIQUE constraint on `(merchant_id, sku)` as backup. |
| Upsert uses `ON CONFLICT (channel_id, channel_product_id, channel_spec_id)` | ⚠️ PARTIAL | `SyncPackHandler.java:123-163` — uses `findByChannelIdAndChannelProductIdAndChannelSpecId()` then save; this is SELECT-then-INSERT/UPDATE, not a true SQL upsert. Under concurrent calls, two threads could both find "not present" and both INSERT — race condition. |
| Duplicate SYNC_PACK execution doesn't create duplicate sell_pack | ⚠️ PARTIAL | Depends on DB unique index. If the index exists it will throw on concurrent INSERT; but the handler would throw, not upsert cleanly. |
| `platform_metadata` merged with `||` (not overwritten) | 🔴 FAIL | `SyncPackHandler.java` does not set `platform_metadata` at all on either INSERT or UPDATE path. |

---

## 🔴 Critical Quality Issues

### CRIT-1: `UpdateInventoryHandler` — sell_pack_inventory never updated after push
**File:** `simpleec-channel-job/src/main/java/com/simpleec/channeljob/handler/UpdateInventoryHandler.java:61-71`

After calling `cyberbizAdapter.updateVariantInventory()`, the handler logs success and returns. There is no write to `sell_pack_inventory` (quantity + last_synced_at + sync_status). The inventory snapshot is permanently stale.

**Impact:** The OMS snapshot diverges from platform actual inventory after every push. Inventory reporting is wrong. The QA checklist's top item explicitly requires this write.

**Fix:** After successful adapter call, upsert `sell_pack_inventory`:
```java
SellPackInventory snapshot = sellPackInventoryRepo
    .findBySellPackIdAndChannelLocationIdIsNull(sellPackId)
    .orElse(new SellPackInventory());
snapshot.setSellPackId(sellPackId);
snapshot.setChannelId(channelId);
snapshot.setQuantity(quantity);
snapshot.setLastSyncedAt(Instant.now());
snapshot.setSyncStatus("synced");
sellPackInventoryRepo.save(snapshot);
```

---

### CRIT-2: `UpdateInventoryHandler` — failure does not set `sync_status = 'failed'` or send `task.failed`
**File:** `simpleec-channel-job/src/main/java/com/simpleec/channeljob/handler/UpdateInventoryHandler.java:67-71`

On exception, the handler wraps and rethrows. There is no explicit `sync_status = 'failed'` write to DB and no `kafkaTemplate.send("task.failed", ...)`. The rule requires both.

**Impact:** Failed inventory pushes are silently lost. Merchants have no visibility into which syncs failed and there is no retry mechanism triggered.

**Fix:** In the catch block:
```java
} catch (Exception e) {
    log.error("UPDATE_INVENTORY failed: ...", e);
    sellPackInventoryRepo.updateSyncStatus(sellPackId, channelId, "failed");
    kafkaTemplate.send("task.failed", buildRetryMessage(originalMessage));
    throw new RuntimeException(...);
}
```

---

### CRIT-3: `ShipOrderHandler` — hardcoded `if ("cyberbiz".equalsIgnoreCase(platformCode))`
**File:** `simpleec-channel-job/src/main/java/com/simpleec/channeljob/handler/ShipOrderHandler.java:62`

```java
if ("cyberbiz".equalsIgnoreCase(platformCode)) {
    CyberbizAdapter adapter = (CyberbizAdapter) cyberbizAdapter;  // unsafe cast too
```

This is a direct violation of the capabilities-model red line (§6 Forbidden Patterns). All other platforms fall into the `else` branch which only logs a warning — silently discarding the shipment request.

**Impact:** Shipments for Shopee, Shopify, Shopline, Yahoo, etc. are silently no-ops. Orders appear "shipped" in OMS but the platform is never notified.

**Fix:** Use platform adapter registry keyed by `platformCode`, and use `platform.capabilities` for platform-specific pre-steps (Shopee's `get_shipping_parameter`, Shopify's `fulfillment_order_id`).

---

### CRIT-4: `SyncPackHandler` never fires `SYNC_PRODUCT`
**File:** `simpleec-backend-job/src/main/java/com/simpleec/backendJob/handler/impl/SyncPackHandler.java:162-165`

The handler saves the `SellPack` and returns. There is no `kafkaTemplate.send(TASK_BACKEND, SYNC_PRODUCT, ...)` call. `SyncProductHandler` exists but can never be reached via normal flow.

**Impact:** Product records are never created or updated via the sync pipeline. `sell_pack.product_id` may point to an auto-created stub product from `SyncPackHandler` (lines 105-120) but no `SYNC_PRODUCT` event is emitted, and `SyncProductHandler` is dead code.

**Fix:** After `sellPackRepository.save(pack)`, emit SYNC_PRODUCT:
```java
kafkaTemplate.send(TopicConstants.TASK_BACKEND, productId,
    buildSyncProductMessage(header, productId, pack));
```

---

### CRIT-5: `OrderUpsertConsumer` — `revenue_date` and `is_backfill` never set
**File:** `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/OrderUpsertConsumer.java:284-417`

`isRollback` is read from the header (line 94) and passed into `populateOrderFromData()`, which calls `order.setRollback(isRollback)` (line 416). However the rule (`order-processing.md §5.4`) requires:
- `isRollback=true` → `setRevenueDate(channelCreatedAt.toLocalDate())`, `setIsBackfill(true)`
- `isRollback=false` → `setRevenueDate(LocalDate.now())`, `setIsBackfill(false)`

Neither `setRevenueDate()` nor `setIsBackfill()` is called anywhere in the consumer.

**Impact:** Revenue attribution for backfill orders is wrong. Daily revenue statistics are inflated on the backfill day and correct historical dates are unaffected (null).

**Fix:** In `populateOrderFromData()`, add:
```java
if (isRollback && order.getChannelCreatedAt() != null) {
    order.setRevenueDate(order.getChannelCreatedAt().toLocalDate());
    order.setIsBackfill(true);
} else {
    order.setRevenueDate(LocalDate.now());
    order.setIsBackfill(false);
}
```

---

### CRIT-6: `OrderUpsertConsumer` — `order_status_logs` never written on status change
**File:** `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/OrderUpsertConsumer.java:172-205`

The UPDATE branch (line 182) compares hashes and calls `updateOrderFromData()`. Nowhere in this path is `order_status_logs` written. The rule requires inserting a log row when `status` changes.

**Impact:** Complete loss of order status history. Audit, compliance, and customer-facing tracking rely on this log. Data is permanently unrecoverable once orders advance past initial status.

**Fix:** In the UPDATE branch, after status comparison:
```java
if (!existing.getStatus().equals(newStatus)) {
    orderStatusLogRepo.insert(OrderStatusLog.builder()
        .orderId(order.getId())
        .fromStatus(existing.getStatus().getCode())
        .toStatus(newStatus.getCode())
        .changedAt(Instant.now())
        .source("ORDER_UPSERT")
        .build());
}
```

---

### CRIT-7: `ReturnUpsertConsumer` — FORMAT_ERROR routed to `task.failed` instead of `task.dlt`
**File:** `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/ReturnUpsertConsumer.java:143-151`

```java
errorInfo.put("errorType", "FORMAT_ERROR");
// ...
kafkaTemplate.send("task.failed", "ReturnUpsert", wrappedMessage.toString());
```

The kafka-envelope rule (§4.4) explicitly states FORMAT_ERROR must go to `task.dlt` (30-day retention, no retry). Sending it to `task.failed` causes the retry job to repeatedly reprocess an unfixable message.

**Fix:**
```java
kafkaTemplate.send(TopicConstants.TASK_DLT, "ReturnUpsert", wrappedMessage.toString());
```

---

## 🟡 Moderate Issues

### MOD-1: `UpdateInventoryHandler` — NullPointerException risk on `channelSpecId.isBlank()`
**File:** `UpdateInventoryHandler.java:49`

`ids.get("channelSpecId")` could return `null` if the sell_pack has no spec (products without variants). Calling `.isBlank()` on null throws NPE.

**Fix:**
```java
if (channelProductId == null || channelProductId.isBlank()
    || channelSpecId == null || channelSpecId.isBlank()) {
```

---

### MOD-2: `ShipOrderHandler` — unsafe cast `(CyberbizAdapter) cyberbizAdapter`
**File:** `ShipOrderHandler.java:69`

```java
CyberbizAdapter adapter = (CyberbizAdapter) cyberbizAdapter;
```

This defeats the purpose of the `ChannelAdapter` interface. Any future refactor that injects a different adapter type will cause `ClassCastException` at runtime, not compile time.

**Fix:** Move `shipOrder()` to the `ChannelAdapter` interface, or use the adapter registry.

---

### MOD-3: `SyncPackHandler` — SELECT-then-INSERT race condition
**File:** `SyncPackHandler.java:123-163`

Two concurrent `SYNC_PACK` events for the same `(channelId, channelProductId, channelSpecId)` could both find "not present" and both attempt `INSERT`, causing a unique constraint violation. The application throws rather than upserts.

**Fix:** Use a native SQL upsert (`INSERT ... ON CONFLICT DO UPDATE`) via `@Query` with `nativeQuery = true`, or wrap in `@Transactional` with serializable isolation + retry on constraint violation.

---

### MOD-4: `SchedulerJobService` — deprecated but may emit malformed messages if re-enabled
**File:** `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/service/SchedulerJobService.java:92-123`

Though disabled (`// @Service`), the message structure in `publishTaskToSlowTopic()` is wrong:
- `body.put("platform", platform)` — platform belongs in header, not body
- `body.put("triggeredAt", System.currentTimeMillis())` — should be `"timestamp": ISO-8601` matching contract
- Header is missing `merchantId`, `channelId`, `requestId`, `isRollback`, `source` — all required per kafka-envelope.md §1

If this service is accidentally re-enabled, it will emit schema-invalid messages that fail `SchemaVersionHandler.validate()` and go to DLT.

---

### MOD-5: `SyncPackHandler` — `platform_metadata` never written
**File:** `SyncPackHandler.java:127-163`

Neither the INSERT nor the UPDATE path writes `platform_metadata`. For Shopify, `inventory_item_id` (required for inventory sync) will never be stored. The inventory sync flow (`UpdateInventoryHandler`) looks for `sell_pack.platform_metadata.inventory_item_id` — if it is null, Shopify inventory pushes will fail.

---

### MOD-6: `ChannelJobConsumer.parseTimestamp()` falls back to `Instant.now()`
**File:** `simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelJobConsumer.java:369-374`

When the inbound scheduler timestamp fails to parse, the fallback is `Instant.now()`. During backfill (where the scheduler timestamp is a past date), this silently fetches the wrong time window. The rule states "Channel Job uses header.timestamp (not Instant.now())".

**Fix:** Log an error and return without processing if the timestamp cannot be parsed. Do not silently fall back to wall-clock time.

---

### MOD-7: `ReturnUpsertHandler` — `status_history` JSONB never written
**File:** `ReturnUpsertHandler.java`

The `refund_orders.status_history` JSONB column (append-only audit trail) is defined in the DB schema but never populated in `populateReturnFromData()`. Status transitions are silently lost.

---

## 🔵 Missing Test Cases (High Priority)

1. **`UpdateInventoryHandler` — inventory push + snapshot write**
   - Test: push succeeds → `sell_pack_inventory.quantity` updated, `sync_status = 'synced'`
   - Test: push fails → `sync_status = 'failed'`, `task.failed` message sent
   - Test: `channelSpecId` is null → no NPE, handler logs warn and returns

2. **`ShipOrderHandler` — order not found**
   - Test: `orderId` in body does not exist in DB → logs error, returns, no platform API call

3. **`OrderUpsertConsumer` — isRollback revenue attribution**
   - Test: `isRollback = true` → `order.revenueDate == channelCreatedAt.toLocalDate()`, `isBackfill == true`
   - Test: `isRollback = false` → `order.revenueDate == LocalDate.now()`, `isBackfill == false`

4. **`OrderUpsertConsumer` — order_status_logs**
   - Test: UPDATE with status change → `orderStatusLogRepository.save()` called with correct `fromStatus`/`toStatus`
   - Test: UPDATE with same status → `orderStatusLogRepository` never called

5. **`SyncPackHandler` — SYNC_PRODUCT trigger**
   - Test: after saving sell_pack → `kafkaTemplate.send()` called with `taskType = SYNC_PRODUCT`
   - Test: concurrent inserts for same `(channelId, channelProductId, channelSpecId)` → only one product created

6. **PII encryption round-trip**
   - Test: `buyerName` value stored in DB after ORDER_UPSERT is not equal to plaintext input (is encrypted)
   - Test: API response `buyer.name` equals plaintext input after decryption

7. **Redis dedup fallback**
   - Test: Redis throws `RedisConnectionFailureException` → order is still processed via DB-only path
   - Test: DB dedup works correctly when Redis is unavailable

8. **`ReturnUpsertConsumer` — FORMAT_ERROR routing**
   - Test: message with missing `channelRefundId` → message goes to `task.dlt` (not `task.failed`)

9. **`ChannelJobConsumer.parseTimestamp()` — bad timestamp**
   - Test: malformed timestamp in body → processing stops, does not use `Instant.now()` as fallback

10. **`sell_pack_inventory` — multiLocation path**
    - Test: `platform.capabilities.multiLocation = true` → uses `channel_location.is_sync_target` location
    - Test: `platform.capabilities.multiLocation = false` → uses `channel_location_id = NULL` row

---

## 💡 Quality Improvement Suggestions

1. **Introduce platform adapter registry**: Replace the `if (platformCode.equals("cyberbiz"))` pattern in `ShipOrderHandler` and `UpdateInventoryHandler` with a `Map<String, ChannelAdapter>` registry. Adapters self-register by platform code. This removes all remaining platform-name checks and satisfies the capabilities-model red line.

2. **Extract `SellPackInventoryUpdater` service**: The inventory snapshot write is needed after every successful platform push. Encapsulate it in a shared service to avoid duplicate logic across multiple handlers.

3. **Add `@Transactional` to `SyncPackHandler.processReport()`**: The product auto-creation + sell_pack upsert must be atomic. Without a transaction, a crash between product INSERT and sell_pack INSERT leaves an orphan product.

4. **Add `OrderStatusLog` repository and entity**: The entity and repository for `order_status_logs` appear to be missing entirely (only the DB table is defined). This is required before the status-log fix in CRIT-6 can be implemented.

5. **Add integration test with embedded Kafka (Testcontainers)**: The existing unit tests mock Kafka, meaning topic routing errors (e.g., RETURN_UPSERT to wrong topic) go undetected. One integration test per consumer with embedded Kafka would catch these.

6. **Guard `SellPackSyncService` against missing channel or platform**: `SellPackSyncService.java:48-51` silently swallows Kafka publish failures when `channelOpt.isEmpty()` or `platformOpt.isEmpty()`. The merchant's inventory update is accepted (200 OK) but never propagated to the platform. This should be logged as a warn or surfaced as an error.

7. **Unify test message builders**: `OrderUpsertConsumerTest` and `ReturnUpsertConsumerTest` each have private `buildXxxMessage()` helpers. Extract to a `TestMessageFactory` so contract changes are updated in one place.
