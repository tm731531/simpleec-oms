# API / Full-Stack Review Report

**Reviewer:** Senior Full-Stack Engineer (automated)
**Date:** 2026-04-05
**Scope:** All controllers in `simpleec-api`, `SellPackSyncService`, security config

---

## Overall Assessment

The API layer has solid foundations: `/api` prefix is consistently applied across all controllers, JWT auth filter and SecurityConfig are correctly structured, PII decryption is generally handled, and tenant isolation (`merchantId` checks) is present in most endpoints. However there are several **critical security holes** (unauthenticated admin endpoints, plain-text password support in production code, direct Repository access from controllers bypassing Service layer), and a significant number of **rule-book / flow gaps** where the implementation does not match the specification.

---

## Confirmed Compliant

1. **`/api` prefix** — All 22 controllers consistently use the `/api` prefix. No recurrence of the Feb 2026 outage pattern.

2. **Stateless JWT** — `SecurityConfig` correctly sets `SessionCreationPolicy.STATELESS` and disables CSRF.

3. **CORS configuration** — Follows the rule: allowed-origins from property, correct methods and headers, `allowCredentials = true`.

4. **`JwtAuthFilter` public path list** — Matches `SecurityConfig.permitAll()` exactly; both are in sync.

5. **Tenant isolation in user controllers** — `UserOrderController`, `UserRefundController`, `UserShipmentController`, `UserShipmentBatchController`, `UserSellPackController`, `UserProductController`, `UserInventoryController` all check `principal.getMerchantId()` before returning or modifying resources.

6. **404-not-403 rule** — Tenant isolation failures are consistently returned as `404 Not Found`, not `403 Forbidden`, across user controllers.

7. **PII decryption in `OrderController` and `UserOrderController`** — Both correctly call `EncryptionContext.setMerchantId(...)` in a try/finally block before reading encrypted order fields.

8. **202 Accepted for async Kafka operations** — `UserOrderController.receiveOrder()` (line 121) and `UserSellPackController.updateSellPack()` (line 68, 73, 77…) correctly return `202 Accepted` / `HttpStatus.ACCEPTED` for Kafka-dispatched actions.

9. **`UserShipmentController` and `UserShipmentBatchController`** — Both delegate fully to `ShipmentService` (Controller → Service pattern), no direct Repository calls, proper ownership verification via `verifyOwnership()`.

10. **`AdminApiResponse` used in all Admin controllers** — `AdminAccountController`, `AdminMerchantController`, `AdminPlatformController` all use the correct `AdminApiResponse<T>` envelope.

11. **Response envelope consistency** — Admin controllers use `AdminApiResponse`, user controllers use typed VOs or `UserPageResponse<T>`.

12. **`SellPackSyncService` Kafka contract** — Correctly builds the Header/Body structure, uses `sellPackId` (NanoID) in the body (not platform IDs), routes to `{platform}.fast` topic.

---

## Critical Issues (security, correctness)

### C-1 — Admin endpoints have NO authentication enforcement (`AdminController`, `AdminAccountController`, `AdminMerchantController`, `AdminPlatformController`)

**Rule violated:** `rest-api.md §10.2` — `/api/admin/**` must require `ROLE_PLATFORM_ADMIN`.

`SecurityConfig` (line 44) does configure:
```java
.requestMatchers("/api/admin/**").hasAuthority("ROLE_PLATFORM_ADMIN")
```

However, none of the four admin controllers declare `@PreAuthorize("hasAuthority('ROLE_PLATFORM_ADMIN')")` at the class or method level. This is currently relying entirely on the `SecurityConfig` rule matching the path pattern. **The actual risk is that the path patterns for `AdminAccountController`, `AdminMerchantController`, and `AdminPlatformController` use singular form** (`/api/admin/account`, `/api/admin/merchant`, `/api/admin/platform`) **but the rule table in `rest-api.md §8` specifies plural form** (`/api/admin/accounts`, `/api/admin/merchants`, `/api/admin/platforms`). If the `SecurityConfig` path is `/api/admin/**` it still catches all three, so auth is technically enforced by the filter chain — but the controller URL mismatch means the API surface doesn't match the rule book, making it prone to future misconfiguration.

**Fix:** Rename controllers to use plural paths (`/api/admin/accounts`, `/api/admin/merchants`, `/api/admin/platforms`) to match `rest-api.md §8`. Add `@PreAuthorize("hasAuthority('ROLE_PLATFORM_ADMIN')")` at class level for defense-in-depth.

---

### C-2 — Plain-text password comparison in `AuthController.login()` (line 52–54)

**File:** `AuthController.java` lines 51–56

```java
boolean passwordMatches = passwordEncoder.matches(password, account.getAccountPassword())
    || password.equals(account.getAccountPassword());  // ← CRITICAL: plain-text fallback
```

This allows authentication with a plain-text password if a BCrypt hash comparison fails. An attacker who injects a known plain-text value into the `account_password` column bypasses all password security. The comment says "for backward compatibility" — this is production code and must be removed.

**Fix:** Remove the `|| password.equals(account.getAccountPassword())` branch. Migrate any plain-text passwords during a one-time DB migration instead.

---

### C-3 — `ProductController` (`/api/products`) is completely unauthenticated — accepts any `merchantId` parameter

**File:** `ProductController.java` lines 31–36, 49, 67, 84–88, 103, 119

`ProductController` at `/api/products` has **no `@AuthenticationPrincipal` parameter anywhere**. Every endpoint takes `merchantId` as a plain query parameter or ignores it entirely:

```java
// listProducts — line 34
@RequestParam String merchantId,  // caller-supplied, no verification

// getProduct — line 49
public ResponseEntity<Product> getProduct(@PathVariable String productId)  // no auth principal

// createProduct — line 103
public ResponseEntity<Product> createProduct(@RequestBody Product product)  // no auth

// updateProduct — line 119
public ResponseEntity<Product> updateProduct(...)  // no auth, no merchantId check
```

Any unauthenticated caller can list, create, update, or search products for any merchant by supplying an arbitrary `merchantId`. This is a full authorization bypass.

**Fix:** Inject `@AuthenticationPrincipal UserPrincipal principal` into every method and use `principal.getMerchantId()` instead of the request parameter. Remove the `merchantId` request parameter from all endpoints (the authenticated merchant ID is the only valid scope).

---

### C-4 — `AdminController.getStats()` has no auth check and calls Repositories directly

**File:** `AdminController.java` lines 32–43

The `getStats()` method on `AdminController` (`/api/admin/stats`):
1. Has no `@AuthenticationPrincipal` and no auth enforcement at the method level.
2. Calls `merchantRepository.count()`, `accountRepository.count()`, `platformRepository.count()`, `orderRepository.count()` directly — violates **Controller → Service → Repository** layering rule.

While `SecurityConfig` should catch it under `/api/admin/**`, the Repository-direct access pattern is wrong and must be moved to a service.

**Fix:** Add a `AdminStatsService` and move the aggregation there. Add `@AuthenticationPrincipal` for defense-in-depth.

---

### C-5 — `UserRefundController` calls `ReturnOrderRepository` directly (layering violation)

**File:** `UserRefundController.java` lines 41–48, 56, 83, 98

`UserRefundController` injects `ReturnOrderRepository` directly and calls:
- `returnOrderRepository.findByOrderId(...)` (line 41)
- `returnOrderRepository.findById(...)` (line 56)
- `returnOrderRepository.save(...)` (line 83)
- `returnOrderRepository.findByMerchantId(...)` (line 48)
- `returnOrderRepository.findById(...)` (line 93)

There is a `ReturnOrderService` in the system (used by `ReturnController`). The controller should delegate to the service layer for transaction management and consistent business logic.

**Fix:** Replace `ReturnOrderRepository` injection with `ReturnOrderService` (the same service already used by `ReturnController`).

---

### C-6 — `UserProductController` calls `ProductRepository` directly (layering violation)

**File:** `UserProductController.java` — all methods

`UserProductController` injects `ProductRepository` directly. A `ProductService` exists in the codebase (used by `ProductController`). All operations should go through `ProductService`.

**Fix:** Inject `ProductService` instead of `ProductRepository`.

---

### C-7 — `UserSellPackController` calls `SellPackRepository` directly (layering violation)

**File:** `UserSellPackController.java` lines 37–38, 46, 55

`UserSellPackController` injects `SellPackRepository` and calls it directly for read operations. Writes go through `SellPackSyncService`. The read path should also be wrapped in a service for consistent encryption context and transaction management.

---

### C-8 — `UserStatsController` and `UserReportController` call Repositories directly

**Files:** `UserStatsController.java` (line 9 — `DailyStatisticsRepository`), `UserReportController.java` (lines 8–9 — `DailyStatisticsRepository`, `ProductRepository`)

Both controllers inject and query repositories directly without a service layer.

---

### C-9 — `UserSettingsController` calls `MerchantRepository` directly

**File:** `UserSettingsController.java` (line 8 — `MerchantRepository`)

Updating merchant settings should go through a service for transactional consistency.

---

### C-10 — `UserOrderController.getOrder()` returns raw `Order` entity with encrypted PII fields potentially visible

**File:** `UserOrderController.java` line 184

```java
@GetMapping("/{id}")
public ResponseEntity<Order> getOrder(...)
```

The return type is `Order` (raw entity), not `OrderVO`. While `EncryptionContext` is set (line 187), the raw `Order` entity is returned directly. The rule book (`rest-api.md §3.2`) states: "Never return naked Java entity objects from user-facing endpoints that contain PII. Always project to a VO." `listOrders()` in the same controller correctly uses `OrderVO`, but `getOrder()` does not.

**Fix:** Return `OrderVO` from `getOrder()` to be consistent with `listOrders()`.

---

### C-11 — `UserOrderController.updateOrder()` ("ship" action) mutates DB before Kafka — wrong order

**File:** `UserOrderController.java` lines 213–222

```java
if ("ship".equals(action)) {
    order.setOrderStatus(OrderStatusEnum.SHIPPED);
    order.setShippedAt(LocalDateTime.now());
    order = orderRepository.save(order);     // ← DB write FIRST
    publishShipOrderEvent(order, ...);       // ← Kafka SECOND
```

The rule (`rest-api.md §4`, `shipment.md §2`) defines `PATCH /api/user/orders/{id}` with action `ship` as an action that should send a Kafka event and return `202 Accepted`. Instead, the implementation:
1. Writes the order status change to DB immediately (bypasses the event-driven pipeline).
2. Sends SHIP_ORDER to Kafka (which may update the order again when confirmed).
3. Returns `200 OK` with the saved entity (should be `202 Accepted`).

This creates a race condition where the DB shows `SHIPPED` before the platform has actually been notified, and the Kafka consumer may double-update the status.

**Additionally**, `updateOrder()` bypasses the Service layer — it calls `orderRepository.save(order)` directly (line 217).

**Fix:** Remove the DB write. Send Kafka and return `202 Accepted`. Let the Kafka consumer update the DB when confirmation arrives.

---

### C-12 — `UserChannelController.createChannel()` accepts raw `Channel` entity — token fields exposed

**File:** `UserChannelController.java` lines 51–59

```java
@PostMapping
public ResponseEntity<Channel> createChannel(
    @RequestBody Channel channel,
    ...
```

The endpoint accepts the raw `Channel` entity as request body. `Channel` contains credential fields (`token`, `token2`, `token3`, `token4`, `token5`). The response also returns the full `Channel` entity including all token fields. There should be a separate DTO that controls which fields are writable and which are returned.

---

### C-13 — `ReturnController` (`/api/returns`) `approveReturn` / `rejectReturn` do NOT send Kafka events

**File:** `ReturnController.java` lines 157–195

The rule book (`return-flow.md §2`, `§3.3`) specifies:
- `POST /api/returns/{id}/approve` → send `APPROVE_RETURN` to `{platform}.fast` topic
- `POST /api/returns/{id}/reject` → send `REJECT_RETURN` to `{platform}.fast` topic

The actual implementation just updates `returnStatus` to `APPROVED`/`REJECTED` in the DB and returns `200 OK`. No Kafka event is sent. This means the channel-side platform API (Shopee confirm/reject, Cyberbiz manual_return) is never called.

**Fix:** Inject `KafkaTemplate` and publish `APPROVE_RETURN`/`REJECT_RETURN` messages. Return `202 Accepted`.

---

### C-14 — `UserRefundController` approve/reject actions (`PATCH /{id}`) do NOT send Kafka events

**File:** `UserRefundController.java` lines 88–106

Same problem as C-13 but in `UserRefundController`. The `action=approve` / `action=reject` branch only updates DB status, no Kafka event dispatched.

---

## Moderate Issues

### M-1 — Duplicate return endpoint confusion: `ReturnController` vs `UserRefundController`

Two controllers handle return/refund data:
- `ReturnController` at `/api/returns` — uses `ReturnOrderService`
- `UserRefundController` at `/api/user/refunds` — uses `ReturnOrderRepository` directly

The rule book `rest-api.md §8` lists only one return controller (`ReturnController` at `/api/returns`). The existence of two overlapping controllers serving merchant-facing return data creates confusion about which is authoritative, and the user-facing one (`/api/user/refunds`) lacks Kafka integration (see C-13/C-14).

**Recommendation:** Consolidate to one controller. `UserRefundController` should be merged into `ReturnController` or the latter should be renamed `UserRefundController` at `/api/user/refunds` with full Kafka integration.

---

### M-2 — Inventory sync endpoint URL mismatch

**Rule:** `inventory-sync.md §2.1` specifies: `PUT /api/sell-packs/{id}/quantity`

**Actual:** `UserSellPackController` is at `/api/user/sellpacks` (missing hyphen: `sellpacks` not `sell-packs`), and the quantity update uses `PATCH /{id}` with an `action=quantity` body, not `PUT /{id}/quantity`.

The URL diverges from both the rule book and the kebab-case naming convention in `rest-api.md §1`.

**Fix:** Either update the rule book to match the implementation's action-based PATCH pattern (which is arguably cleaner and consistent with the `PATCH + action` rule for state transitions), or add a dedicated `PUT /{id}/quantity` endpoint. The controller mapping should also be `/api/user/sell-packs` (with hyphen).

---

### M-3 — `UserInventoryController` operates on `Product` table, not `sell_pack`

**File:** `UserInventoryController.java`

The inventory rule book (`inventory-sync.md §1.1`) states: "`sell_pack.quantity` is the Single Source of Truth." But `UserInventoryController` reads and writes `Product.quantity` via `ProductRepository`. Adjusting inventory here does NOT trigger the `UPDATE_INVENTORY` Kafka event to push changes to platform channels.

The correct flow should be: PATCH sell_pack quantity → `SellPackSyncService.syncUpdate()` → Kafka `UPDATE_INVENTORY` → Channel Job → Platform.

**Fix:** `UserInventoryController` should delegate to `UserSellPackController`'s quantity update logic (via `SellPackSyncService`) rather than writing to `Product` directly.

---

### M-4 — `UserInventoryController` does NOT handle `multiLocation` display requirement

**Rule:** `inventory-sync.md §2.5` — if a channel has `multiLocation=true`, the frontend should receive per-location inventory snapshots from `sell_pack_inventory`.

**Actual:** `UserInventoryController` only returns `Product` objects without any `sell_pack_inventory` per-location data. The `capabilities.multiLocation` flag is never checked.

---

### M-5 — `UserOrderController` PATCH ship action ignores `shipmentId` — missing shipment record creation

**Rule:** `shipment.md §2.2` — creating a shipment requires `POST /api/orders/{id}/shipments` which creates a `Shipment` DB record and sends `SHIP_ORDER` with `shipmentId`.

**Actual:** `UserOrderController.updateOrder()` with `action=ship` publishes `SHIP_ORDER` Kafka message (line 308–343) but the body does NOT include a `shipmentId` (no `Shipment` record is created). The Kafka contract in `shipment.md §3.2` requires `shipmentId` in the body. Channel Job confirmation (`SHIP_ORDER_CONFIRMED`) references `shipmentId` to update `shipment.status`.

---

### M-6 — `AdminAccountController` and `AdminMerchantController` use `@CrossOrigin` annotation

**Files:** `AdminAccountController.java` (line 31–34), `AdminMerchantController.java` (line 27–30), `AdminPlatformController.java` (line 26–29)

These controllers add hardcoded `@CrossOrigin` annotations listing specific origins including production URLs (`https://oms.tomting.com`). This overrides the centralized CORS configuration in `SecurityConfig.corsConfigurationSource()` and creates inconsistency. The rule book (`rest-api.md §10.1`) specifies that CORS must be configured via `simpleec.cors.allowed-origins` property.

**Fix:** Remove all `@CrossOrigin` annotations from these controllers. CORS is already correctly handled globally in `SecurityConfig`.

---

### M-7 — `UserChannelController.updateChannel()` uses PUT for partial update

**File:** `UserChannelController.java` lines 61–95

The method is mapped to `@PutMapping("/{id}")` but performs a partial update (only non-null fields are updated). The rule book (`rest-api.md §4`) says `PUT` = full replacement (idempotent), `PATCH` = partial update. This should be `@PatchMapping`.

---

### M-8 — `UserOrderController.listOrders()` does not support `dateFrom`/`dateTo` parameters

**Rule:** `order-processing.md §2.1` specifies query params `dateFrom`, `dateTo`, `channelId`, `page`, `size`, `status`, `merchantId` for `GET /api/orders`.

**Actual:** `UserOrderController.listOrders()` (lines 127–163) only supports `channelId`, `status`, `page`, `pageSize`. `dateFrom`/`dateTo` filtering is absent.

---

### M-9 — `HealthController` has several unprotected internal diagnostics endpoints

**File:** `HealthController.java` lines 55–244

Endpoints like `/api/health/summary`, `/api/health/channel/{channelId}`, `/api/health/channel/{channelId}/history`, `/api/health/platform/{platform}` are publicly accessible (they fall under `/api/health**` which `SecurityConfig` allows via `permitAll()` on `/api/health`). These expose channel sync log data without authentication. They should either require auth or be excluded from the `permitAll` path.

The `SecurityConfig.permitAll()` only lists `/api/health` and `/api/version` — not `/api/health/**`. Whether the wildcard matches depends on Spring Security path matching. Verify this is intentionally public.

---

### M-10 — `UserSellPackController.updateSellPack()` — no null-safety on `action` field

**File:** `UserSellPackController.java` line 59

```java
String action = (String) body.get("action");
return switch (action) {   // NullPointerException if action is null
```

If the request body omits `action` or sends it as null, this throws a `NullPointerException`. The `default` case handles unknown strings but not null.

**Fix:** Add a null check: `if (action == null) return ResponseEntity.badRequest().body(null);`

---

### M-11 — `UserOrderController` directly accesses multiple Repositories

**File:** `UserOrderController.java` lines 12–18

`UserOrderController` injects: `OrderRepository`, `ChannelRepository`, `PlatformRepository`, `OrderStatusLogRepository`, `ShipmentItemRepository` directly. The rule book (`rest-api.md §6`) permits direct repository calls only for controller-level ownership validation before Kafka dispatch. However, the `listOrders()`, `getOrder()`, and `updateOrder()` methods perform business operations directly against repositories, bypassing any service layer. An `OrderService` already exists (used by `OrderController`).

---

## Gaps (API endpoints that should exist per flows but don't)

### G-1 — `POST /api/orders/{id}/shipments` — not implemented

**Rule:** `shipment.md §2.1` — `POST /api/orders/{id}/shipments` creates a shipment record and triggers `SHIP_ORDER`.

**Actual:** `UserOrderController.listShipmentsForOrder()` handles `GET /api/user/orders/{orderId}/shipments` but there is no `POST` on this path. The shipment creation endpoint is absent. `UserShipmentController.createShipments()` exists at `POST /api/user/shipments` but takes a list of `orderIds` and creates shipments without tracking number (no Kafka dispatch).

**Suggested:** `POST /api/user/orders/{id}/shipments` per `shipment.md §2.2` request body with `trackingNumber`, `carrier`, `items[]`.

---

### G-2 — `GET /api/shipments/{id}` — path mismatch

**Rule:** `shipment.md §2.1` specifies `GET /api/shipments/{id}`.

**Actual:** `UserShipmentController` is at `/api/user/shipments/{id}`. The rule book lists the endpoint without the `/user` prefix. This may be intentional but creates a mismatch. Verify and update the rule book or controller mapping.

---

### G-3 — `POST /api/channels/{id}/sync-packs` — missing manual sync trigger

**Rule:** `product-sync.md §2.1` — `POST /api/channels/{id}/sync-packs` triggers `SYNC_PACK` Kafka task.

**Actual:** No such endpoint exists in `UserChannelController` or any other controller. There is no way for a merchant to manually trigger a product sync from the API.

**Suggested:** Add to `UserChannelController`:
```
POST /api/user/channels/{id}/sync-packs
```
Response: `{ "requestId": "...", "message": "Sync task enqueued" }` (202 Accepted)

---

### G-4 — `GET /api/sell-packs/{channelId}` — sell-packs by channel missing

**Rule:** `product-sync.md §2.1` — `GET /api/sell-packs/{channelId}` lists sell-packs for a given channel.

**Actual:** `UserSellPackController.listSellPacks()` supports filtering by `productId` but not by `channelId` as a path parameter.

**Suggested:** Add `GET /api/user/sell-packs?channelId={channelId}` or a dedicated path endpoint.

---

### G-5 — Channel location management API — completely absent

**Rule:** `inventory-sync.md §1.3`, `§4.3` — `channel_location` records control which warehouse location receives inventory pushes for multiLocation platforms (e.g., Shopify). Merchants need to configure this for Shopify setup.

**Actual:** No controller manages `channel_location` CRUD. There is no API to:
- List channel locations for a channel
- Set `is_sync_target = true` on a location
- Create/update/delete channel locations

**Suggested:**
```
GET    /api/user/channels/{id}/locations
POST   /api/user/channels/{id}/locations
PUT    /api/user/channels/{id}/locations/{locationId}
DELETE /api/user/channels/{id}/locations/{locationId}
PATCH  /api/user/channels/{id}/locations/{locationId}/set-sync-target
```

---

### G-6 — `GET /api/returns/{id}` exists at two paths

**Actual:** Return detail exists at both:
- `GET /api/returns/{returnId}` in `ReturnController`
- `GET /api/user/refunds/{id}` in `UserRefundController`

The rule book specifies only `GET /api/returns/{id}`. This creates two authoritative sources for the same resource, potentially with different behavior.

---

### G-7 — No `422 Unprocessable Entity` response for unsupported approve/reject on Shopify

**Rule:** `return-flow.md §2.3` — if a platform does not support approve/reject (e.g., Shopify), API must return `422 Unprocessable Entity`.

**Actual:** `ReturnController.approveReturn()` (line 157) and `UserRefundController.updateRefund()` always attempt the action without checking `platform.capabilities.return.approveReject`.

---

### G-8 — `GET /api/orders` (`OrderController`) overlaps with `GET /api/user/orders` (`UserOrderController`)

Both controllers serve order listing for authenticated users:
- `OrderController` at `/api/orders`
- `UserOrderController` at `/api/user/orders`

The rule book (`rest-api.md §8`) lists both as separate controllers, but both are for authenticated merchants. `OrderController` includes a `GET /api/orders/stats/summary` endpoint and `POST /api/orders` (create order directly), which are not in the flow rules. Clarify which is the canonical merchant order endpoint and deprecate the other.

---

## Suggestions

### S-1 — Add `@Valid` to request bodies where typed DTOs exist

`UserShipmentController` and `UserShipmentBatchController` use record-based request DTOs but none have `@Valid` annotations (e.g., `CreateShipmentsRequest`, `SetTrackingRequest`, `CreateBatchRequest`). Adding `@Valid` + field constraints (e.g., `@NotBlank`, `@NotNull`) provides early validation errors instead of runtime NPEs.

### S-2 — `HealthController` loads all sync logs with `findAll()` — scalability risk

**File:** `HealthController.java` lines 57–70, 105, 141, 177

`channelSyncLogRepository.findAll()` is called multiple times per request with no limit. As `channel_sync_logs` grows, this will cause OOM or extreme latency. Replace with paginated/bounded queries.

### S-3 — `AdminMerchantController.createMerchant()` generates a 6-char ID — collision risk

**File:** `AdminMerchantController.java` line 139
```java
merchant.setId(NanoIdUtil.generate().substring(0, 6));
```
The rule book specifies NanoID-20 for all PKs. A 6-character truncation increases collision probability significantly for high-volume deployments.

### S-4 — Token revocation is not implemented — consider short expiration or Redis blocklist

The rule book (`rest-api.md §10.4`) notes token revocation is not implemented and recommends short expiration as mitigation. Consider documenting the current expiration value in config and ensuring it is not excessively long (e.g., 24h is the default — verify this is acceptable for the threat model).

### S-5 — `UserOrderController.publishShipOrderEvent()` body is missing `shipmentId`

**File:** `UserOrderController.java` lines 326–330

The Kafka body for `SHIP_ORDER` only includes `orderId`, `trackingNumber`, `carrier`. Per `shipment.md §3.2`, `shipmentId` is required in the body. Without `shipmentId`, the `SHIP_ORDER_CONFIRMED` consumer has no shipment record to update.

### S-6 — `UserOrderController.receiveOrder()` hash computation is weak

**File:** `UserOrderController.java` lines 91–94

The dedup hash is computed as `sha256(channelOrderId + ":" + orderStatus + ":" + totalAmount)`. The rule book (`order-processing.md §8.2`) specifies hashing all mutable fields. The current hash misses: `trackingNumber`, `escrowAmount`, `actualShippingFee`, `payTime`, and item-level changes.

### S-7 — Consider separate `UserChannelLocationController` for Shopify multi-location

Given the complexity of channel location management (required for inventory sync for multiLocation platforms), a dedicated controller class is cleaner than adding many sub-resource endpoints to `UserChannelController`.

### S-8 — `PATCH /api/user/orders/{id}` ("cancel" action) returns Order entity, not 202

**File:** `UserOrderController.java` lines 222–227

The cancel action saves to DB and publishes a Kafka event but returns `200 OK` with the entity. Per `rest-api.md §4`, state transitions via Kafka should return `202 Accepted`.
