# Architect Review Report

**Reviewer:** Senior Architect (automated)
**Date:** 2026-04-05
**Branch:** `feature/shipment-workflow`
**Scope:** All rule documents (`docs/rules/`) vs. implemented source code

---

## Overall Assessment

The architecture is fundamentally sound: the header/body Kafka envelope, the three-layer separation (Scheduler / Channel Job / Handler), the NanoID primary key strategy, PII encryption via `EncryptedAttributeConverter`, and the capabilities-driven model are all well-designed and correctly documented. However, the implementation lags behind the rules in several critical areas. Four handlers in `simpleec-channel-job` contain hardcoded `"cyberbiz".equalsIgnoreCase(platformCode)` checks -- a direct violation of the capabilities model. The Channel Job module has extensive DB access via JPA repositories, contradicting the "Channel Job must not access DB" rule. The `UpdateInventoryHandler` has no multi-location logic despite V4 migration and entity being in place. Several backend handlers documented in the rules (e.g., `ShipOrderConfirmedHandler`, `ReturnActionConfirmedHandler`) do not exist in the codebase. The `OrderUpsertConsumer` does not write `order_status_logs`, violating the order processing flow rules. These issues are tractable but represent real gaps between documented architecture and running code.

---

## Confirmed Compliant

1. **Kafka envelope structure** -- `SchemaVersionHandler.validate()` exists in `simpleec-common/kafka/SchemaVersionHandler.java` and correctly validates `header.version` against `SUPPORTED_VERSIONS = Set.of(1)`. The `OrderUpsertConsumer` calls it before processing and routes unsupported versions to `task.dlt` (lines 60-65).

2. **TaskMdcHelper usage** -- `TaskMdcHelper.set(json)` and `TaskMdcHelper.clear()` are correctly called in `OrderUpsertConsumer.consumeOrderUpsert()` (lines 67, 138) with proper try/finally structure.

3. **Translation Layer Rule for SHIP_ORDER** -- `ShipOrderHandler` reads `body.orderId` (OMS NanoID) and resolves `channelOrderId` via `OrderRefRepository.findById(orderId)` (line 39). No platform IDs leak into the Kafka body for this handler.

4. **Translation Layer Rule for APPROVE_RETURN / REJECT_RETURN** -- Both handlers read `body.returnId` (OMS NanoID) and resolve `channelReturnId` via `ReturnOrderRefRepository` then `OrderRefRepository` (ApproveReturnHandler lines 39-48).

5. **Translation Layer Rule for UPDATE_INVENTORY** -- `UpdateInventoryHandler` reads `body.sellPackId` and resolves `channelProductId + channelSpecId` via `ChannelService.getSellPackChannelIds()` (line 40). Compliant with the NanoID-only rule.

6. **Inbound exception for ORDER_UPSERT** -- `OrderUpsertConsumer` correctly reads `channelOrderId` from the Kafka body (line 89), which is the documented inbound exception to the translation rule.

7. **PII encryption in OrderUpsertConsumer** -- `EncryptionContext.setMerchantId(merchantId)` is correctly set before processing (line 108) and `EncryptionContext.clear()` in the finally block (line 113). The `Order` entity has `@Convert(converter = EncryptedAttributeConverter.class)` on all four PII fields (Order.java lines 113, 120, 127, 133).

8. **Redis dedup with graceful degradation** -- `OrderUpsertConsumer` wraps Redis calls in try/catch (lines 158-166) and falls through to DB check on Redis failure. Matches `tech/cache.md` Section 7 exactly.

9. **isRollback flag handling** -- `OrderUpsertConsumer.populateOrderFromData()` reads `isRollback` from the header (line 94) and sets `order.setRollback(isRollback)` (line 417).

10. **Error routing** -- `OrderUpsertConsumer` routes `UnsupportedSchemaVersionException` to `task.dlt` (line 63) and runtime exceptions to `task.failed` with `errorInfo` envelope (lines 122-134). Compliant with `tech/kafka-envelope.md` Section 4.4.

11. **DB migration structure** -- V1 through V5 are sequential, correctly named with double underscores. V4 (`channel_location_and_inventory`) and V5 (`platform_capabilities`) align with the DB conventions and capabilities model documents.

12. **Platform entity** -- `Platform.java` has `capabilities` typed as `JsonNode` with `@JdbcTypeCode(Types.OTHER)` and `columnDefinition = "jsonb"` (lines 95-96). Correct for the capabilities model.

13. **SYNC_PACK Channel Job** -- `SyncPackChannelHandler` correctly publishes to `TopicConstants.TASK_BACKEND` (line 162) and does not write to DB directly. Channel Job converts platform data to OMS structure with header/body envelope (lines 124-161).

14. **SyncPackHandler (Backend Job)** -- Upserts `sell_pack` by `(channelId, channelProductId, channelSpecId)` (line 123). Auto-creates `product` records when SKU is first seen (lines 107-119). Compliant with `flows/product-sync.md` Section 5.1.

15. **ReturnUpsertHandler** -- Resolves `orderId` from `channelOrderId` via `orderRepository.findByChannelIdAndChannelOrderId()` (line 138). Handles missing order gracefully with warn log (lines 142-143), keeping `order_id = null`. Compliant with `flows/return-flow.md` Section 5.1.

---

## Critical Issues (must fix before production)

### CRIT-1: Hardcoded platform name checks in 4 Channel Job handlers

**Rule violated:** `tech/capabilities-model.md` Section 6 -- "The following patterns are forbidden in all modules."

**Locations:**
- `ShipOrderHandler.java` line 62: `if ("cyberbiz".equalsIgnoreCase(platformCode))`
- `ApproveReturnHandler.java` line 51: `if ("cyberbiz".equalsIgnoreCase(platformCode))`
- `RejectReturnHandler.java` line 52: `if ("cyberbiz".equalsIgnoreCase(platformCode))`
- `FetchReturnsHandler.java` line 50: `if ("cyberbiz".equalsIgnoreCase(platformCode))`

**Impact:** Adding a new platform (e.g., Shopee, Shopify) to these handlers requires modifying every `if/else` chain. This is the exact anti-pattern the capabilities model was designed to prevent.

**Recommended fix:** Introduce a `ChannelAdapterRegistry` (keyed by `platformCode`) that resolves the correct `ChannelAdapter` implementation at runtime. Each handler calls `registry.getAdapter(platformCode)` instead of casting to `CyberbizAdapter`. The registry is populated by Spring bean scanning. Platform-specific behavior differences (e.g., Shopee requires `get_shipping_parameter` before ship) should be driven by `platform.capabilities` JSONB flags (e.g., `"shipment.requiresPreStep": true`), not by name checks.

---

### CRIT-2: Channel Job directly accesses DB via JPA repositories

**Rule violated:** `tech/rest-api.md` Section 7 -- "Channel Job must NOT call REST APIs or access the database directly." Also `INDEX.md` key principle 4 and `CLAUDE.md` Channel Job section.

**Locations (repositories in `simpleec-channel-job`):**
- `ChannelRepository` -- reads `channel` table (credentials, platform info)
- `OrderRefRepository` -- reads `orders` table for `channelOrderId` resolution
- `ReturnOrderRefRepository` -- reads `refund_orders` table for `channelReturnId` resolution
- `ChannelSyncLogRepository` -- writes sync log entries
- `ChannelService` uses `JdbcTemplate` and `ChannelRepository`

**Impact:** Violates the isolation principle that allows Channel Job to scale independently. DB access couples Channel Job to the core database schema. If the DB is slow, Channel Job is slow. If Channel Job scales to N instances, DB connection pool is exhausted faster.

**Context:** The rules doc `flows/shipment.md` Section 4.2 shows `orderRepository.findById(message.body.orderId)` in Channel Job -- this is inconsistent with the "no DB access" rule. The rules themselves have an internal contradiction: outbound actions (SHIP_ORDER, APPROVE_RETURN, UPDATE_INVENTORY) need the Channel Job to translate OMS NanoIDs to platform IDs, which requires a DB lookup.

**Recommended fix:** Two options:
1. **Accept the pragmatic exception:** Outbound action handlers in Channel Job are allowed read-only DB access for ID translation (the `OrderRef`, `ReturnOrderRef`, `SellPack` lookups). Document this as an explicit exception in the rules. This is the current implementation's approach.
2. **Pure isolation:** Include all necessary platform IDs in the Kafka message body for outbound actions (e.g., `SHIP_ORDER` body carries `channelOrderId` alongside `orderId`). This requires the API layer to look up `channelOrderId` before publishing. This removes DB dependency from Channel Job but violates the Translation Layer Rule.

**Recommendation:** Option 1 is pragmatic and correct. Update the rules to explicitly carve out "read-only ID translation lookups" as an allowed Channel Job DB access pattern. The `ChannelSyncLogRepository` (write operations for sync logging) should move to a backend job or be done via Kafka events.

---

### CRIT-3: OrderUpsertConsumer does not write order_status_logs

**Rule violated:** `flows/order-processing.md` Section 1.5 -- "Every order status change must insert a record into `order_status_logs`, never overwriting old records." Section 5.5 shows explicit `orderStatusLogRepo.insert()` logic.

**Location:** `OrderUpsertConsumer.java` -- the entire file has no reference to `OrderStatusLog`, `order_status_logs`, or any status log repository.

**Impact:** No audit trail of order status changes. When an order transitions from PENDING to SHIPPED via `ORDER_UPSERT`, the transition is silently overwritten. Debugging order status issues becomes impossible without this log.

**Recommended fix:** In `OrderUpsertConsumer.handleOrderUpsert()`, after the `updateOrderFromData()` call (around line 184), compare `order.getOrderStatus()` before and after update. If changed, insert a new `OrderStatusLog` record. The entity (`OrderStatusLog.java`) and repository (`OrderStatusLogRepository.java`) already exist in `simpleec-core`.

---

### CRIT-4: UpdateInventoryHandler ignores multi-location and async inventory

**Rule violated:** `flows/inventory-sync.md` Sections 4.3-4.5 (multiLocation and asyncInventory handling). `tech/capabilities-model.md` Section 9 (relationship to sell_pack_inventory).

**Location:** `UpdateInventoryHandler.java` -- the entire file:
- No reference to `platform.getCapabilities()`
- No `multiLocation` check
- No `channel_location` lookup
- No `inventory_item_id` resolution from `platform_metadata`
- No `asyncInventory` handling
- No `sell_pack_inventory` snapshot update after successful sync
- Hardwired to a single `cyberbizAdapter` (line 27)

**Impact:** Shopify multi-location inventory pushes will not work. Shopee async inventory updates will not be tracked. No platform gets `sell_pack_inventory` snapshot updates after sync. The handler is effectively a stub that only works for Cyberbiz.

**Recommended fix:** Rewrite `UpdateInventoryHandler` following the pseudocode in `flows/inventory-sync.md` Section 4.3:
1. Look up `Platform` for the channel, read `capabilities`
2. If `multiLocation == true`: query `ChannelLocation` for sync target, read `platform_metadata.inventory_item_id`, call platform API with location
3. If `multiLocation == false`: use `channelProductId + channelSpecId` directly
4. If `asyncInventory == true`: handle `task_id` polling pattern
5. After success: upsert `sell_pack_inventory` snapshot
6. Use adapter registry instead of hardcoded `cyberbizAdapter`

---

### CRIT-5: Redis TTL set to 7 days instead of 24 hours

**Rule violated:** `tech/cache.md` Section 2 -- "TTL: 24 hours. Set on write. Do not extend TTL on read."

**Locations:**
- `OrderUpsertConsumer.java` line 194: `redisTemplate.opsForValue().set(redisKey, orderHash, Duration.ofDays(7))`
- `OrderUpsertConsumer.java` line 222: `redisTemplate.opsForValue().set(redisKey, orderHash, Duration.ofDays(7))`
- `ReturnUpsertHandler.java` line 84: `redisTemplate.opsForValue().set(redisKey, returnHash, Duration.ofDays(7))`
- `ReturnUpsertHandler.java` line 97: `redisTemplate.opsForValue().set(redisKey, returnHash, Duration.ofDays(7))`

**Impact:** Redis memory usage is 7x higher than designed. Stale dedup keys linger for a week, which is unlikely to cause incorrect behavior but wastes memory on a shared Redis instance.

**Recommended fix:** Change `Duration.ofDays(7)` to `Duration.ofHours(24)` at all four locations.

---

## Moderate Issues (should fix)

### MOD-1: SellPackSyncService uses sellPackId as Kafka partition key

**Rule violated:** `tech/kafka-envelope.md` Section 4.7 -- "Use `channelId` as the Kafka message key for most messages (ensures ordering within a channel)."

**Location:** `SellPackSyncService.java` line 77: `kafkaTemplate.send(topic, sellPack.getId(), message)`

**Impact:** Messages for the same channel may be distributed across different Kafka partitions, breaking per-channel ordering guarantees.

**Recommended fix:** Change to `kafkaTemplate.send(topic, sellPack.getChannelId(), message)`.

---

### MOD-2: SyncPackChannelHandler method signature couples to CyberbizAdapter

**Location:** `SyncPackChannelHandler.java` line 48: `public void handleSyncPack(String platformCode, String channelId, String merchantId, CyberbizAdapter adapter)`

**Impact:** The method parameter is typed as `CyberbizAdapter`, not the `ChannelAdapter` interface. Adding Shopee or Shopify SYNC_PACK support requires changing this signature.

**Recommended fix:** Change parameter type to `ChannelAdapter` and ensure `ChannelAdapter` interface declares a `fetchProducts(String channelId)` method.

---

### MOD-3: ShipOrderHandler reads channelItemId from body -- potential Translation Layer leak

**Location:** `ShipOrderHandler.java` lines 52-59: reads `body.lineItems[].channelItemId` / `body.lineItems[].channel_item_id`

**Rule:** `tech/kafka-envelope.md` Section 4.2 -- SHIP_ORDER body should use `orderId` (NanoID) and `items[].orderItemId` + `items[].sellPackId` (NanoIDs). Channel Job resolves platform IDs.

**Impact:** If `channelItemId` leaks into the Kafka body from the API layer, it violates the Translation Layer Rule. The API should send `orderItemId` (NanoID), and Channel Job should resolve `channelItemId`.

**Recommended fix:** Verify that the API controller for SHIP_ORDER sends `orderItemId` (NanoID) in `lineItems`, not `channelItemId`. Update `ShipOrderHandler` to resolve channel item IDs from the DB via `orderItemId`.

---

### MOD-4: ReturnUpsertHandler dedup uses channelRefundId only, not (channelId + channelRefundId)

**Location:** `ReturnUpsertHandler.java` line 65: `returnOrderService.findByChannelRefundId(channelRefundId)`

**Rule:** `flows/return-flow.md` Section 5.1 -- "by (channelId + channelReturnId) query refund_orders"

**Impact:** If two different channels have the same `channelRefundId` string (unlikely but possible across platforms), the wrong record could be matched.

**Recommended fix:** Change to `findByChannelIdAndChannelRefundId(channelId, channelRefundId)` as the rules specify.

---

### MOD-5: SyncPackHandler does not trigger SYNC_PRODUCT event

**Rule violated:** `flows/product-sync.md` Section 5.1 -- "SyncPackHandler completes upsert, then automatically triggers SYNC_PRODUCT to task.backend."

**Location:** `SyncPackHandler.java` -- the file creates products inline (lines 107-119) rather than emitting a `SYNC_PRODUCT` event to `task.backend`. No `KafkaTemplate` is injected; no message is published.

**Impact:** The two-step process (SYNC_PACK upserts sell_pack, then SYNC_PRODUCT upserts product) is collapsed into one handler. This works for now but violates the documented separation. A dedicated `SyncProductHandler` cannot evolve independently.

**Recommended fix:** After upserting `sell_pack`, publish a `SYNC_PRODUCT` event to `task.backend` with `sellPackId`, `productId`, `sku`, etc. Implement `SyncProductHandler` to handle product upsert logic. Move product creation out of `SyncPackHandler`.

---

### MOD-6: SellPackInventory entity missing channel_id column

**Location:** `SellPackInventory.java` -- only has `sellPackId` and `channelLocationId`, no `channelId`.

**Rule:** `flows/inventory-sync.md` Section 5.1 shows the INSERT statement with `channel_id` column. The V4 migration however does NOT include `channel_id` in the `sell_pack_inventory` table.

**Impact:** The rules document is inconsistent with the actual migration. The current schema derives `channel_id` through `sell_pack.channel_id`. Querying inventory by channel requires a JOIN. This is acceptable but the rules document should be updated to match reality, or `channel_id` should be added as a denormalized column for query efficiency.

**Recommended fix:** Update `flows/inventory-sync.md` Section 5.1 to match the actual V4 schema (no `channel_id` column). Alternatively, add a denormalized `channel_id` to V4 if direct queries are needed.

---

### MOD-7: Order entity missing revenue_date and is_backfill columns

**Location:** `Order.java` has `isRollback` (line 166) but no `revenueDate` field.

**Rule:** `flows/order-processing.md` Section 5.4 -- `order.setRevenueDate(...)` and `order.setIsBackfill(true)`.

**Impact:** The isRollback-based revenue attribution logic documented in the rules cannot be implemented without `revenue_date` and potentially a separate `is_backfill` flag (currently `isRollback` serves as backfill indicator, but `revenue_date` is missing for date attribution).

**Recommended fix:** Add `revenue_date DATE` column to `orders` table via V6 migration. Add corresponding field to `Order.java`. Implement the revenue date logic in `OrderUpsertConsumer`.

---

## Gaps (documented in rules, not yet implemented)

### GAP-1: ShipOrderConfirmedHandler (Backend Job)

**Rule:** `flows/shipment.md` Section 5.1 -- listens to `task.backend` for `SHIP_ORDER_CONFIRMED`, updates `shipment.status` and computes `fulfillment_status`.

**Status:** Does not exist. No file found in `simpleec-backend-job` or any other module. `ShipOrderHandler` in Channel Job throws on failure and logs success, but never publishes `SHIP_ORDER_CONFIRMED` to `task.backend`.

---

### GAP-2: ShipOrderFailedHandler (Backend Job)

**Rule:** `flows/shipment.md` Section 5.3 -- updates `shipment.status = FAILED` and inserts `shipment_status_logs`.

**Status:** Does not exist.

---

### GAP-3: ReturnActionConfirmedHandler (Backend Job)

**Rule:** `flows/return-flow.md` Section 5.2 -- listens to `task.backend` for `RETURN_ACTION_CONFIRMED`, updates `refund_orders.status`.

**Status:** Does not exist. `ApproveReturnHandler` and `RejectReturnHandler` in Channel Job call platform APIs but never publish confirmation events back to `task.backend`.

---

### GAP-4: CANCEL_ORDER and CANCEL_ORDER_INTERNAL handlers

**Rule:** `tech/kafka-envelope.md` Sections 2.2 and 2.4 define `CANCEL_ORDER` and `CANCEL_ORDER_INTERNAL` contracts.

**Status:** No handler exists in any module.

---

### GAP-5: ORDER_STATUS_CHANGE handler

**Rule:** `tech/kafka-envelope.md` Section 2.4 defines `ORDER_STATUS_CHANGE` contract for `order.process` topic.

**Status:** No handler exists.

---

### GAP-6: FETCH_ORDER_DETAIL handler

**Rule:** `tech/kafka-envelope.md` Section 2.1 -- used for Mode B platforms (Shopee) where list API is incomplete.

**Status:** `ModeBOrderDetailHandler.java` exists but is currently only used by Cyberbiz (based on grep results showing `CyberbizAdapter` usage). Shopee FETCH_ORDER_DETAIL is documented in rules but has no Shopee adapter.

---

### GAP-7: Webhook receiver (`simpleec-gateway`)

**Rule:** `tech/platform-api.md` Section 9 -- respond HTTP 200 immediately, process via Kafka.

**Status:** The `simpleec-gateway` module exists in Gradle structure but webhook endpoints are not reviewed here. Shopify and Shopline webhook support (`platform.capabilities.webhook = true`) has no visible implementation.

---

### GAP-8: Async inventory task tracking (Shopee)

**Rule:** `tech/platform-api.md` Section 8 -- persist `task_id`, poll `get_task_result`, check `failure_list`.

**Status:** No `channel_task` table or entity exists. `UpdateInventoryHandler` has no async handling logic.

---

### GAP-9: Token refresh logic

**Rule:** `tech/platform-api.md` Section 3 -- proactive refresh at <20% remaining TTL, persist new token before use, handle rotating refresh tokens.

**Status:** Not reviewed in detail, but no `TokenRefreshService` or similar was found in the Channel Job module during this review.

---

### GAP-10: Multi-platform adapter registry

**Rule:** `CLAUDE.md` Handler Registry section describes `@ChannelHandler` annotation for auto-registration.

**Status:** `UpdateInventoryHandler` injects a single `cyberbizAdapter`. `ShipOrderHandler` casts to `CyberbizAdapter`. No `@ChannelHandler` annotation or registry mechanism exists in the reviewed code.

---

## Architecture Suggestions

### SUGG-1: Reconcile the "no DB access" rule with outbound action reality

The rules state Channel Job must not access DB, but outbound actions (SHIP_ORDER, APPROVE_RETURN, UPDATE_INVENTORY) inherently need ID translation that requires DB lookups. The current `OrderRef` / `ReturnOrderRef` lightweight entities in Channel Job are a reasonable compromise. **Formalize this as an explicit exception** in the rules rather than leaving it as a silent contradiction. Define exactly which tables Channel Job may read (read-only): `orders` (for `channelOrderId`), `refund_orders` (for `channelReturnId`), `sell_pack` (for `channelProductId/channelSpecId`), `channel` (for credentials), `channel_location` (for sync targets). No writes except sync logs (which should arguably move to Kafka events).

### SUGG-2: Create an adapter resolution layer

Replace all `(CyberbizAdapter) cyberbizAdapter` casts with a `ChannelAdapterFactory` or `ChannelAdapterRegistry`:

```java
@Component
public class ChannelAdapterRegistry {
    private final Map<String, ChannelAdapter> adapters;
    
    public ChannelAdapterRegistry(List<ChannelAdapter> adapterBeans) {
        this.adapters = adapterBeans.stream()
            .collect(Collectors.toMap(ChannelAdapter::getPlatformCode, a -> a));
    }
    
    public ChannelAdapter getAdapter(String platformCode) {
        return adapters.get(platformCode.toLowerCase());
    }
}
```

This eliminates all `if ("cyberbiz".equalsIgnoreCase(...))` checks and all `(CyberbizAdapter)` casts in one refactoring pass.

### SUGG-3: Add `order_hash` column to `orders` table

`OrderUpsertConsumer` computes hashes in memory (`calculateOrderHash`) but never persists them to the DB. The rules (`flows/order-processing.md` Section 6.2) show `order_hash VARCHAR(64)` as a column. Without a persisted hash, the DB fallback dedup (Layer 2) cannot compare hashes -- it can only check existence, not content changes.

Currently the code re-derives the hash from the existing DB record fields (line 179), which is fragile: if the hash algorithm or included fields change, old records produce different hashes and trigger spurious updates.

**Fix:** Add `order_hash` column via migration. Persist the incoming `orderHash` on INSERT and UPDATE. Use the persisted hash for DB-layer dedup comparison instead of re-deriving.

### SUGG-4: Consider idempotency for sell_pack_inventory writes

The rules (`flows/inventory-sync.md` Section 6.3) prescribe `INSERT ... ON CONFLICT DO UPDATE` for `sell_pack_inventory`. Currently no code writes to this table at all (see CRIT-4). When implementing, use the prescribed upsert pattern rather than JPA `save()` to avoid race conditions under concurrent inventory pushes.

### SUGG-5: Standardize version field type

`SellPackSyncService.java` line 64 sets `header.put("version", 1)` (integer), while `flows/inventory-sync.md` Section 3.3 shows `"version": "1.0"` (string). `SchemaVersionHandler.validate()` calls `versionNode.asInt(-1)` which works for integers but would return -1 for the string `"1.0"`. Standardize on integer `1` everywhere as `SchemaVersionHandler` expects.
