# PM Review Report — SimpleEC OMS Product Completeness

**Date:** 2026-04-05  
**Reviewer:** Product Management (via AI audit)  
**Scope:** Business rules completeness for MVP launch  
**Assessment:** NOT MVP-ready — critical gaps in operational visibility and multi-location merchant experience

---

## Overall Assessment

**Verdict:** ⚠️ **High Risk for Merchant Adoption**

The SimpleEC OMS has well-defined technical flow documents (order processing, inventory sync, shipment, returns, products) but **lacks critical features that real merchants expect** from an OMS. The system handles the "happy path" well (orders come in, inventory syncs, goods ship) but falls short on:

1. **Operational visibility** — Merchants can't see sync failures, retry status, or when data went wrong
2. **Multi-location inventory setup** — While Shopify multi-location is designed, the **onboarding experience is unclear** — how does a merchant understand and configure this?
3. **Error recovery paths** — No documented way for merchants to recover from platform API failures without contacting support
4. **Missing business flows** — Price updates, order cancellation, inventory adjustments, and bulk operations not documented

**Biggest risk:** Merchants will see orders and inventory as "syncing" but have **no way to diagnose why a sync failed**, leading to customer support chaos.

---

## ✅ Well-Defined Flows

These flows are documented end-to-end with clear business rules:

1. **Order Processing** (§1.1–§9 in `flows/order-processing.md`)
   - ✅ Clear API endpoint (`GET /api/orders`, `GET /api/orders/{id}`)
   - ✅ Dedup logic documented (hash-based)
   - ✅ Nullable fields handled (tracking_number, escrow_amount)
   - ✅ PII encryption required (`buyer_name`, `buyer_email`, etc.)
   - ✅ Implemented in controller: `UserOrderController`

2. **Inventory Sync** (§1.1–§9 in `flows/inventory-sync.md`)
   - ✅ Clear sync rule: OMS is source of truth, platforms are snapshots
   - ✅ Multi-location rule documented (Shopify sync target concept)
   - ✅ API endpoint clear (`PUT /api/sell-packs/{id}/quantity`)
   - ✅ Implemented in controller: `UserSellPackController.updateSellPack()`

3. **Shipment** (§1.1–§9 in `flows/shipment.md`)
   - ✅ Clear creation flow and partial fulfillment logic
   - ✅ Status logging (immutable `shipment_status_logs`)
   - ✅ Platform-specific handling (Shopee `get_shipping_parameter`, Shopify `fulfillment_order_id`)
   - ✅ Implemented in controller: `UserShipmentController`

4. **Return/Refund** (§1.1–§9 in `flows/return-flow.md`)
   - ✅ Clear approval/rejection flow
   - ✅ Platform capability handling documented (`supportsApproveReturn`)
   - ✅ Passive receive model (OMS accepts any state from platform)
   - ✅ Implemented in controller: `UserRefundController`

5. **Product Sync** (§1.1–§9 in `flows/product-sync.md`)
   - ✅ Clear separation of concerns: `SYNC_PACK` (Channel Job) → `SYNC_PRODUCT` (Backend Job)
   - ✅ Shopify `inventory_item_id` storage documented
   - ✅ SKU-based product deduplication rule clear
   - ✅ API endpoint for manual sync: `POST /api/channels/{id}/sync-packs`

---

## 🔴 Critical Product Gaps (blockers for MVP)

### 1. **NO SYNC STATUS VISIBILITY FOR MERCHANTS**

**Problem:** Orders arrive, inventory updates happen, but merchants have **zero visibility into:**
- "Is my Shopee inventory synced right now?"
- "Why did the last inventory sync fail?"
- "How many items failed to sync in the last hour?"

**Evidence:**
- Flow documents mention `sync_status` field in `sell_pack_inventory` (docs/rules/flows/inventory-sync.md §6.1)
- API response in `flows/inventory-sync.md` §2.3 shows `syncStatus: "syncing"` but **no endpoint to view sync status history**
- No controller method for `GET /api/sell-packs/{id}/sync-status` or `GET /api/channels/{id}/sync-log`

**Impact on merchants:**
- Run PChome sync; nothing shows up on PChome 1 hour later
- Merchant has no way to know if: (a) sync is pending, (b) sync failed, (c) API is broken
- Must email support: "Why aren't my products on PChome?"

**What's needed:**
```
GET /api/channels/{id}/sync-log  — show last 10 sync attempts
GET /api/sell-packs/{id}/platform-status — show sync status per platform
GET /api/orders/{id}/sync-status — show if order data synced to platform
```

### 2. **NO UI/UX CLARITY FOR MULTI-LOCATION SHOPIFY SETUP**

**Problem:** Shopify merchants with multiple warehouses need to:
1. Understand what "multi-location" means (OMS terminology)
2. Configure which Shopify location is the "sync target"
3. See inventory per location in the UI

**Evidence:**
- Capability model documented: `multiLocation: true` means per-location inventory (`capabilities-model.md` §2)
- Database schema has `channel_location` table with `is_sync_target` flag
- Flow document (`inventory-sync.md` §1.3) says: "OMS **only pushes** to `channel_location.is_sync_target = true`"
- **But no API endpoint to:**
  - List channel locations with their sync target status
  - Update which location is the sync target
  - Show per-location inventory snapshots in merchant view

**What Shopify merchants will experience:**
- Create channel → sees "Inventory" but **doesn't know there are multiple locations to manage**
- Updates inventory in OMS → pushes to **one random Shopify location** (whichever is marked `is_sync_target`)
- Inventory on other Shopify locations never updates
- Merchant: "I updated inventory in OMS, why is the NY warehouse still showing 0 stock?"

**What's needed:**
```
GET /api/channels/{id}/locations — list all Shopify locations, highlight sync target
PATCH /api/channels/{id}/locations/{locationId}/set-sync-target — change which location syncs
GET /api/sell-packs/{id}/inventory-by-location — show inventory snapshot per location
```

### 3. **NO MERCHANT-VISIBLE ERROR RECOVERY FOR FAILED OPERATIONS**

**Problem:** When a platform API call fails (rate limit, temporary outage, auth expired), merchants have no recovery path.

**Evidence:**
- Flow documents mention `sync_status = 'failed'` and sending to `task.failed` topic for retry
- But **no API endpoint to:**
  - View failed operations (orders, inventory syncs, shipments)
  - Manually retry failed operations
  - See error details (error code, error message from platform)

**Scenarios:**
- Inventory sync failed (Shopee rate limit) 2 hours ago
- Merchant doesn't know; customer gets angry on Shopee: "Out of stock but you sold it"
- No way for merchant to retry manually; must wait for automatic retry (if it exists)
- Or contact support: "Fix the sync for SKU-001"

**What's needed:**
```
GET /api/failed-operations?type=INVENTORY_SYNC — show failed sync history
POST /api/failed-operations/{id}/retry — retry a specific failed operation
```

### 4. **NO ORDER CANCELLATION FLOW**

**Problem:** Orders sometimes need to be cancelled (customer changes mind, oversold, etc.). Flow documents don't cover this.

**Evidence:**
- `flows/order-processing.md` defines FETCH_ORDERS, ORDER_UPSERT, status logs
- No mention of `order.cancel()` or `CANCEL_ORDER` task type
- Controller `UserOrderController.updateOrder()` exists but business rules for cancellation not defined
- Unclear: Can merchant cancel? Does OMS notify platform? What happens to inventory?

**Real scenario:**
- Order comes in for 10 items
- Merchant realizes only 5 in stock
- Tries to cancel via OMS UI → ❌ No flow defined, no API spec

### 5. **NO INVENTORY ADJUSTMENT / STOCK COUNT FLOW**

**Problem:** Physical inventory doesn't match OMS. Merchants need to adjust (e.g., "found 5 missing units") without a customer order triggering it.

**Evidence:**
- `flows/inventory-sync.md` only covers merchant-initiated quantity updates (PUT /api/sell-packs/{id}/quantity)
- No flow for: "System found 10 items missing, adjust down by 10"
- No support for cycle counts or physical stock reconciliation

**Real scenario:**
- Physical count: 100 units in warehouse
- OMS shows: 95 units
- Merchant needs: Adjust +5 without a customer order
- Solution: Update quantity to 100 → syncs to all platforms ✅

This actually works, but it's undocumented as a standalone flow. **Should be documented as "Inventory Adjustment" flow.**

---

## 🟡 Notable Gaps (important but not blocking MVP)

### 1. **Platform Onboarding Rules Unclear**

**Problem:** When adding a new platform, what does an admin need to do?

**Evidence:**
- `capabilities-model.md` defines the JSONB structure but doesn't explain:
  - How to add Momo, Yahoo, PChome (3 platforms mentioned but flow not in rules/)
  - Step-by-step: "Add new platform → insert capabilities → write Channel Job handlers"

**What's missing:**
- Checklist: "New Platform Onboarding" doc listing:
  1. Insert platform + capabilities in DB
  2. Write ChannelJob handler for FETCH_ORDERS
  3. Write ChannelJob handler for FETCH_PRODUCTS
  4. etc.

### 2. **Return Flow: Missing Shopify Webhook Support**

**Problem:** Shopify returns come via Webhook (push), not polling. But webhook registration and handling not documented.

**Evidence:**
- `flows/return-flow.md` §1.4 says Shopify uses "Webhook (refund/return event)" but no detail
- No mention of webhook registration endpoint
- No mention of webhook verification signature validation

**What's missing:**
- Webhook registration flow: How does OMS tell Shopify to send webhook to `POST /webhooks/shopify/return`?
- Signature verification: How to validate webhook is from Shopify?

### 3. **No Bulk Operations Documented**

**Problem:** Merchants often need batch actions:
- "Sync 500 products for channel X"
- "Approve all pending returns"
- "Create shipments for orders from today"

**Evidence:**
- Individual actions are documented (sync one pack, approve one return, ship one order)
- No mention of bulk operations in flows

**What's missing:**
- `POST /api/sell-packs/bulk-sync` — trigger SYNC_PACK for all packs in channel
- `POST /api/returns/bulk-approve` — approve N returns at once
- UI: Checkboxes to select multiple returns/orders/shipments for batch action

### 4. **No Price Update Flow Documentation**

**Problem:** Prices change. Merchants need to update prices on platforms. Not documented.

**Evidence:**
- `flows/product-sync.md` mentions price in sell_pack (section 3.3 `"price": 399`)
- But no flow document for `UPDATE_PRICE` task type
- Controller `UserSellPackController.updateSellPack()` has `case "price"` but no business rules (nullable? precision? currency handling?)

**What's needed:**
- `flows/price-update.md` documenting:
  - API endpoint: `PUT /api/sell-packs/{id}/price`
  - Kafka TaskType: `UPDATE_PRICE` sent to `{platform}.fast`
  - How Channel Job calls each platform's price update API (differs per platform)
  - Whether price change triggers inventory recheck

### 5. **Promotion / Discount Rules Not Mentioned**

**Problem:** Real merchants use promotions (flash sales, discounts). No mention in flows.

**Evidence:**
- Order entity has `shippingFee`, `escrowAmount`, but no mention of `promotionDiscount` or `couponCode`
- Sell_pack has price but no promo override

**What's needed (future):**
- How discounts affect order total and platform reconciliation
- Whether OMS needs to sync promotions to platforms or just accept them

---

## 🔵 Missing Flow Documentation

These flows are **implemented in code** (endpoints exist, handlers exist) but **not documented** in `rules/flows/`:

1. **Inventory Adjustment** (not in rules/flows/)
   - Merchant updates `sell_pack.quantity` via PUT → API sends UPDATE_INVENTORY to platforms
   - This works but is undocumented as its own flow
   - Should have: `flows/inventory-adjustment.md` explaining when/why merchant adjusts manually

2. **Health / Sync Status Checks** (implemented, not documented)
   - `UserStatsController` and `HealthController` exist (endpoints like `/api/health/channel/{channelId}`)
   - Shows "last sync time" but no business rules doc
   - Should have: `flows/sync-monitoring.md` explaining what "healthy" means per flow

3. **Channel Configuration / Setup** (not in rules/flows/)
   - Endpoints exist: `UserChannelController`, `AdminPlatformController`
   - No business rules doc for: "How does a merchant add a Shopee channel?"
   - Should have: `flows/channel-onboarding.md`

4. **Settings / Notification Preferences** (implemented, not documented)
   - `UserSettingsController` exists
   - No flow documenting what settings are available, who sets them, when they take effect

5. **Statistics / Reporting** (partially documented)
   - `UserReportController` and `UserStatsController` exist
   - No flow for how stats are calculated, when they update, what "backfill" (isRollback) means to reports

---

## 💡 Product Suggestions

### For MVP Launch (T+2 weeks)

**Priority 1: Add Sync Monitoring Pages**
- Create `/api/channels/{id}/sync-status` endpoint returning:
  ```json
  {
    "channelId": "...",
    "platformName": "Shopee",
    "lastSuccessfulSync": "2026-04-05T12:00:00Z",
    "nextScheduledSync": "2026-04-05T13:00:00Z",
    "recentFailures": [
      { "taskType": "UPDATE_INVENTORY", "failedAt": "2026-04-05T11:00:00Z", "errorCode": "RATE_LIMIT", "errorMessage": "Too many requests" }
    ],
    "successRate": "98.5%" (last 30 days)
  }
  ```
- Frontend: Add "Channel Health" widget on dashboard showing sync status per platform
- Merchant: At a glance, see if inventory is syncing normally

**Priority 2: Multi-Location Shopify Setup Wizard**
- When merchant creates Shopify channel, show dialog:
  ```
  1. "Shopify has multiple locations. Which one syncs inventory from OMS?"
  2. [Dropdown: Select Location]
  3. "Inventory on other locations can be managed directly on Shopify."
  ```
- Store selection in `channel_location.is_sync_target = true`
- Frontend: Show inventory per location in inventory view (read-only for non-sync targets)

**Priority 3: Manual Retry for Failed Operations**
- Add `/api/failed-operations` endpoint with:
  - List view: Show last 100 failed attempts (inventory syncs, order updates, shipments)
  - Detail view: Show error code, error message, timestamp, affected entity
  - Action: "Retry" button to re-queue to Kafka
- Reduces support tickets

### For Post-MVP (T+4 weeks)

**Priority 4: Order Cancellation Flow**
- Write `flows/order-cancellation.md` defining:
  - Who can cancel (merchant, admin, system)
  - What happens to inventory (restore stock? adjust?)
  - Does OMS notify platform?
  - Show cancellation in order status log

**Priority 5: Bulk Operations**
- Endpoints: `POST /api/sell-packs/bulk-sync`, `POST /api/returns/bulk-approve`
- UI: Multi-select checkboxes on list pages

**Priority 6: Price Update Flow Documentation**
- Write `flows/price-update.md` with same structure as other flows (9 sections)
- Implement: `PATCH /api/sell-packs/{id}/price` calling UPDATE_PRICE handler

### UX Improvements (T+3 weeks)

1. **Better Error Messages**
   - When inventory sync fails, show: "Shopee API returned: Rate limit exceeded. We'll retry in 5 minutes. [Retry Now]"
   - Not: "Sync failed" (unhelpful)

2. **Operational Dashboards**
   - Dashboard widgets: "Health Summary" (all channels), "Failed Operations This Hour", "Backlog (orders waiting to ship)"
   - Weekly email: "Your OMS health report" (x orders processed, y syncs, z failures)

3. **Onboarding Flows**
   - First-time merchant setup: "Connect your Shopee account" → guide through API keys, channel creation
   - Similar for Shopify, Yahoo, PChome
   - Currently not documented or guided

---

## Merchant Experience Red Flags

### Scenario 1: Shopify Multi-Location Merchant
**Current state:**
1. Merchant adds Shopify channel to OMS
2. Merchant updates "Blue Shirt - Large" quantity to 50 in OMS
3. OMS syncs to Shopify... but which location?
4. Merchant checks Shopify: NYC location has 0, LA location has 50
5. Merchant: "Why didn't it sync to NYC?"
6. **No error message, no way to know what happened**

**What's needed:** Onboarding wizard + location selector UI

### Scenario 2: Inventory Sync Failure
**Current state:**
1. Merchant updates 10 SKUs at 3 PM
2. OMS sends UPDATE_INVENTORY to Shopee
3. Shopee API returns 429 (rate limit); goes to task.failed for retry
4. 5 SKUs retry successfully at 3:05 PM, 5 failed again at 3:10 PM
5. Customer on Shopee buys item that's out of stock (failed to sync)
6. Merchant: "Why did that sell?"
7. **Merchant has zero visibility into what synced and what didn't**

**What's needed:** Sync status dashboard + manual retry button

### Scenario 3: Return Approval Not Working
**Current state:**
1. Return comes in: pending merchant approval
2. Merchant clicks "Approve" in OMS
3. OMS sends APPROVE_RETURN to Shopee
4. Shopee API returns 401 (auth expired); goes to task.failed
5. Return status in OMS shows "Pending" but merchant doesn't know why approve failed
6. **No notification, no retry option, silent failure**

**What's needed:** Error messages + notifications

---

## Recommendations

### MVP Must-Haves (go/no-go for launch)
- ✅ Order processing: documented, implemented
- ✅ Inventory sync (basic): documented, implemented
- ✅ Shipment creation: documented, implemented
- ✅ Return approval: documented, implemented
- ❌ **Sync status visibility:** NOT implemented — add before launch
- ❌ **Multi-location UI:** NOT implemented — add before Shopify launch
- ❌ **Error recovery:** NOT implemented — add before launch

### Launch Checklist
- [ ] Implement `/api/*/sync-status` endpoints for orders, inventory, shipments, returns
- [ ] Frontend: Add "Channel Health" dashboard widget
- [ ] Frontend: Add Shopify location selector UI on channel setup
- [ ] Frontend: Add "Failed Operations" list with manual retry
- [ ] Business rules: Document what "healthy sync" means per flow
- [ ] Support docs: "Why didn't my inventory sync?" troubleshooting guide

### Post-Launch Improvements
- [ ] Order cancellation flow documentation + implementation
- [ ] Price update flow documentation + implementation
- [ ] Bulk operations (sync, approve, ship)
- [ ] Webhook support for Shopify returns (currently polling only)

---

## Summary

SimpleEC OMS has **strong technical architecture** (well-designed Kafka flows, capabilities model, dedup logic) but **weak merchant UX and operational visibility**. Merchants will struggle to understand:
- Whether their data is syncing correctly
- How to set up multi-location inventory
- What to do when something fails

**Risk if launched as-is:** High support volume, merchant confusion, loss of trust.

**Time to fix:** 2-3 weeks for critical gaps (sync monitoring, multi-location UI, error recovery).

**Bottom line:** Document and implement sync status visibility and error recovery before launch. These are table-stakes for any OMS.
