# DBA Review Report

**Reviewer:** Senior DBA (automated review)
**Date:** 2026-04-05
**Scope:** V1-V5 migrations, 20 entity classes, 19 repository interfaces, all rule documents

---

## 修復狀態摘要

**Date fixed:** 2026-04-06
| 狀態 | 數量 | 項目 |
|------|------|------|
| ✅ FIXED | 3 | C1, C2, C3 |
| ⚠️ OPEN | 1 | C4 (index added; repository method still lacks channelId param) |

---

## Overall Assessment

The schema is **well-designed for a multi-platform e-commerce OMS**. The NanoID PK convention is consistently applied, TIMESTAMPTZ is used everywhere, PII encryption is properly integrated, and the JSONB usage is appropriate. The capabilities-driven model (V5) is a strong design choice that eliminates platform-name hardcoding.

However, there are **several concrete issues** ranging from data integrity risks to entity-DDL misalignment that should be addressed before production use. The most critical involve: (1) the `refund_orders` table missing columns required by the return-flow rules, (2) the `platform.ship_options` column using `JSON` instead of `JSONB`, (3) the `sell_pack_inventory` unique constraint using `UNIQUE (...)` syntax instead of a functional unique index with `COALESCE`, and (4) several entity classes diverging from their DDL definitions.

---

## Confirmed Compliant

### PK Convention (VARCHAR(20) NanoID)
- All 19+ tables use `VARCHAR(20) NOT NULL PRIMARY KEY` -- confirmed in V1, V2, V4.
- **Exception (by design):** `global_config.id` is `VARCHAR(128)` -- this is a config key, not a NanoID. Acceptable.
- No auto-increment (`BIGSERIAL`) or `UUID DEFAULT gen_random_uuid()` found anywhere.
- Entity classes correctly use `@Id @Column(name = "id", length = 20)` without `@GeneratedValue`.

### TIMESTAMPTZ Usage
- All `created_at` and `updated_at` columns use `TIMESTAMPTZ NOT NULL DEFAULT now()` across V1, V2, V4.
- Platform-sourced timestamps (`channel_created_at`, `paid_at`, `shipped_at`, `dispatched_at`, `cancelled_at`, `requested_at`, `last_synced_at`) are correctly nullable without `DEFAULT now()`.

### PII Encryption
- `Order` entity correctly applies `@Convert(converter = EncryptedAttributeConverter.class)` to all four PII fields: `buyerName`, `buyerPhone`, `buyerEmail`, `shippingAddress`.
- `Channel` entity encrypts token fields (`token` through `token5`) -- good security practice.
- DDL uses appropriately wide column types (`VARCHAR(512)`, `TEXT`) to accommodate encrypted ciphertext.

### JSONB Usage
- `orders.items` -- GIN-indexed, `DEFAULT '[]'::jsonb`, correctly annotated in entity.
- `orders.buyer_info`, `orders.shipping_info` -- correctly typed JSONB for platform-variant data.
- `sell_pack.channel_spec_attrs`, `sell_pack.sync_status`, `sell_pack.platform_metadata` (V3) -- appropriate JSONB usage.
- `shipment_items.items` -- JSONB with GIN index.
- `failed_task_logs.payload` -- JSONB for failed Kafka messages.
- `platform.capabilities` (V5) -- `JSONB NOT NULL DEFAULT '{}'`, correctly added with seed data.

### Index Coverage
- FK columns are indexed: `channel.platform_id`, `channel.merchant_id`, `sell_pack.merchant_id/channel_id/product_id`, `shipments.merchant_id/batch_id/channel_id`, `shipment_items.shipment_id/order_id/merchant_id`.
- Composite indexes for common query patterns: `idx_order_merchant_status`, `idx_order_stats`, `idx_shipments_status`.
- Partial indexes: `idx_order_has_refund WHERE has_refund = true`, `idx_channel_location_one_sync_target WHERE is_sync_target = true`.
- GIN indexes on JSONB: `idx_order_items`, `idx_shipment_items_gin`.
- Business unique indexes: `idx_sellpack_upsert_key`, `idx_order_channel_order`, `idx_product_merchant_sku`, `idx_daily_stats_unique`.

### Migration Integrity
- Migrations are sequential: V1, V2, V3, V4, V5. No gaps.
- V2 correctly drops the old `order_shipments` table and migrates data to `shipments`.
- V3-V5 use `ADD COLUMN IF NOT EXISTS` for idempotency.

### Constraint Naming
- FK constraints follow `fk_{child}_{parent}` convention consistently.
- Unique constraints/indexes follow `uq_*`, `idx_*`, `uk_*` patterns.

---

## Critical Issues (data integrity / correctness risks)

### C1. `platform.ship_options` uses `JSON` instead of `JSONB`

**狀態**: ✅ FIXED — V6 migration alters `platform.ship_options` to `JSONB` via `ALTER COLUMN ... TYPE JSONB USING ship_options::jsonb`.

**File:** `V1__initial_schema.sql`, line 113
**Column:** `platform.ship_options`
**DDL:** `ship_options JSON`
**Rule violated:** db-conventions.md Section 5 specifies JSONB for all JSON columns. `JSON` type does not support GIN indexing, `@>` containment queries, or `||` merge operations.

**Entity mismatch:** `Platform.java` line 80 declares `columnDefinition = "jsonb"` but the DDL says `JSON`. Hibernate will not auto-correct this -- the column is `JSON` in the database.

**Recommended fix (V6 migration):**
```sql
ALTER TABLE public.platform
    ALTER COLUMN ship_options TYPE JSONB USING ship_options::jsonb;
```

### C2. `sell_pack_inventory` unique constraint uses SQL `UNIQUE (...)` syntax with `COALESCE` -- invalid

**狀態**: ✅ FIXED — V6 migration drops the invalid `UNIQUE` constraint and replaces it with `CREATE UNIQUE INDEX` using `COALESCE(channel_location_id, '')`; partial unique index `uix_spi_no_location` also added.

**File:** `V4__channel_location_and_inventory.sql`, line 47
**DDL:** `CONSTRAINT uq_sell_pack_inventory UNIQUE (sell_pack_id, COALESCE(channel_location_id, ''))`

**Problem:** Standard SQL `UNIQUE` constraint does not support expressions like `COALESCE(...)`. PostgreSQL will reject this or silently fail. The rules (db-conventions.md Section 10) correctly specify using a **unique index** with `COALESCE`, not a `UNIQUE` constraint. The DDL should use `CREATE UNIQUE INDEX` instead.

**Additionally missing:** The inventory-sync flow rules (inventory-sync.md Section 6.3) specify a **partial unique index** for the NULL case:
```sql
CREATE UNIQUE INDEX uix_spi_no_location
    ON sell_pack_inventory (sell_pack_id, channel_id)
    WHERE channel_location_id IS NULL;
```
But `sell_pack_inventory` has **no `channel_id` column at all** in the V4 DDL. The flow rules reference `sell_pack_inventory.channel_id` in multiple places (Section 5.1 upsert, Section 6.2 queries), but the table only has `sell_pack_id` and `channel_location_id`.

**Recommended fix (V6 migration):**
```sql
-- 1. Drop the invalid constraint
ALTER TABLE public.sell_pack_inventory
    DROP CONSTRAINT IF EXISTS uq_sell_pack_inventory;

-- 2. Add the missing channel_id column
ALTER TABLE public.sell_pack_inventory
    ADD COLUMN IF NOT EXISTS channel_id VARCHAR(20) NOT NULL DEFAULT '';

-- 3. Create the correct unique index with COALESCE
CREATE UNIQUE INDEX IF NOT EXISTS uq_sell_pack_inventory
    ON public.sell_pack_inventory (sell_pack_id, COALESCE(channel_location_id, ''));

-- 4. Create the partial unique index for no-location platforms
CREATE UNIQUE INDEX IF NOT EXISTS uix_spi_no_location
    ON public.sell_pack_inventory (sell_pack_id, channel_id)
    WHERE channel_location_id IS NULL;

-- 5. Add FK for channel_id
ALTER TABLE public.sell_pack_inventory
    ADD CONSTRAINT fk_sell_pack_inventory_channel FOREIGN KEY (channel_id)
    REFERENCES public.channel (id) ON UPDATE CASCADE ON DELETE NO ACTION;
```

### C3. `refund_orders` table missing columns required by return-flow rules

**狀態**: ✅ FIXED — V6 migration adds `channel_id`, `channel_order_id`, `currency` columns to `refund_orders`; unique dedup index `idx_refund_orders_channel_return` on `(channel_id, channel_refund_id)` also added.

**File:** `V1__initial_schema.sql` (refund_orders DDL) vs `return-flow.md` Section 6.1

**Missing columns:**
| Column | Required by | Purpose |
|--------|-------------|---------|
| `channel_id` | return-flow.md S6.1, S6.3 `idx_refund_orders_channel_return` | Required for the unique dedup index `(channel_id, channel_return_id)` and for the query `findByChannelIdAndChannelReturnId` |
| `channel_return_id` | return-flow.md S6.1 | Platform return ID (dedup key). DDL has `channel_refund_id` instead -- naming mismatch |
| `channel_order_id` | return-flow.md S6.1 | Platform order ID for looking up the OMS order |
| `currency` | return-flow.md S6.1 | Currency of the refund |
| `status_history` | return-flow.md S6.1, S6.2 | JSONB append-only array for status history |

**Column naming mismatch:** DDL has `channel_refund_id` and `refund_status`, while the flow rules specify `channel_return_id` and `status`. The entity `ReturnOrder.java` uses `channelRefundId` and `returnStatus` which map to the DDL column names, not the flow-rule names.

**Entity `ReturnOrder.java` has `order_id NOT NULL`** but return-flow rules say `order_id` can be NULL when the corresponding order doesn't exist yet (Section 5.1 step 6: "order_id temporarily null").

**Recommended fix (V6 migration):**
```sql
ALTER TABLE public.refund_orders
    ADD COLUMN IF NOT EXISTS channel_id VARCHAR(20),
    ADD COLUMN IF NOT EXISTS channel_order_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS currency VARCHAR(10),
    ADD COLUMN IF NOT EXISTS status_history JSONB DEFAULT '[]'::jsonb;

-- Add the missing unique dedup index
CREATE UNIQUE INDEX IF NOT EXISTS idx_refund_orders_channel_return
    ON public.refund_orders (channel_id, channel_refund_id);

-- Add the merchant+channel index
CREATE INDEX IF NOT EXISTS idx_refund_orders_merchant_channel
    ON public.refund_orders (merchant_id, channel_id);

-- Allow order_id to be NULL
ALTER TABLE public.refund_orders
    ALTER COLUMN order_id DROP NOT NULL;
```

### C4. `ReturnOrderRepository.findByChannelRefundId` missing `channelId` filter

**狀態**: ⚠️ OPEN — Index `idx_refund_orders_channel_return` added in V6 migration, but `ReturnOrderRepository.findByChannelRefundId()` method itself still lacks `channelId` parameter — queries will not use the composite index and cross-channel collision risk remains. Fix: rename method to `findByChannelIdAndChannelRefundId(String channelId, String channelRefundId)` and update all callers.

**File:** `ReturnOrderRepository.java`, line 25
**Method:** `findByChannelRefundId(String channelRefundId)`

**Problem:** The return-flow rules (Section 5.1) specify dedup by `(channelId, channelReturnId)`. The repository query only filters by `channelRefundId` -- without `channelId`, two different channels with the same platform return ID would collide.

**Recommended fix:**
```java
Optional<ReturnOrder> findByChannelIdAndChannelRefundId(String channelId, String channelRefundId);
```

---

## Moderate Issues (performance / maintainability)

### M1. `sell_pack.product_id` is `NOT NULL` in DDL but rules say it can be NULL

**File:** `V1__initial_schema.sql`, line 264 -- `product_id VARCHAR(20) NOT NULL`
**Entity:** `SellPack.java`, line 35 -- `nullable = false`
**Rule:** product-sync.md Section 6.1 shows `product_id VARCHAR(20)` without NOT NULL, and the flow says "mapping completed later" (product_id filled in after SyncProductHandler runs).

**Impact:** New sell_packs synced from platforms cannot be inserted until a product mapping exists, breaking the SYNC_PACK flow which expects to create sell_pack first, then link product later.

**Recommended fix (V6):**
```sql
ALTER TABLE public.sell_pack ALTER COLUMN product_id DROP NOT NULL;
```

### M2. `sell_pack.sku` is `NOT NULL` in DDL but may not be available from all platforms

**File:** `V1__initial_schema.sql`, line 268 -- `sku VARCHAR(100) NOT NULL`

**Problem:** Many platforms (Shopee, Cyberbiz) do not always provide SKU values for variants. The SYNC_PACK flow (product-sync.md Section 5.3) creates auto-generated SKUs (`AUTO-{nanoId}`) as a fallback, but this requires the application to always provide a value. This is workable but fragile -- consider making SKU nullable and generating the default only when needed.

### M3. Missing `order_hash` column on `orders` table

**File:** `V1__initial_schema.sql` (orders DDL)
**Rule:** order-processing.md Section 6.2 shows `order_hash VARCHAR(64)` as a column for dedup hash storage.
**Cache rules:** cache.md Section 2 describes hash-based dedup requiring the hash to be stored in DB.

**Impact:** Without `order_hash` in the DB, the Layer 2 (DB fallback) dedup logic described in cache.md Section 3 cannot compare hashes -- it can only check existence by `(channel_id, channel_order_id)`, not detect content changes.

**Recommended fix (V6):**
```sql
ALTER TABLE public.orders
    ADD COLUMN IF NOT EXISTS order_hash VARCHAR(64);
```

### M4. Missing `revenue_date` and `is_backfill` columns on `orders` table

**File:** `V1__initial_schema.sql` (orders DDL)
**Rule:** order-processing.md Section 6.2 shows `revenue_date DATE` and `is_backfill BOOLEAN DEFAULT FALSE`.
**Note:** `orders.is_rollback` exists but `is_backfill` is separate per the rules -- `is_rollback` comes from the Kafka header, `is_backfill` is set by the backend handler.

**Impact:** The isRollback handling logic (order-processing.md Section 5.4) sets `order.setRevenueDate(...)` and `order.setIsBackfill(true)` but neither column exists.

**Recommended fix (V6):**
```sql
ALTER TABLE public.orders
    ADD COLUMN IF NOT EXISTS revenue_date DATE,
    ADD COLUMN IF NOT EXISTS is_backfill BOOLEAN NOT NULL DEFAULT false;
```

### M5. `SellPackInventory` entity missing `channel_id` field

**File:** `SellPackInventory.java`
**Problem:** The entity has no `channelId` field, matching the DDL (which also lacks it). But the inventory-sync flow rules (Section 5.1, 6.2, 6.3) consistently reference `sell_pack_inventory.channel_id` and queries like `WHERE sell_pack_id = ? AND channel_id = ?`.

This is related to Critical Issue C2. Without `channel_id`, the ON CONFLICT upsert in inventory-sync.md Section 5.1 cannot work as specified.

### M6. `channel_location` table missing `merchant_id` FK index

**File:** `V4__channel_location_and_inventory.sql`
**Column:** `merchant_id VARCHAR(20) NOT NULL` (line 8)
**Problem:** `merchant_id` is declared but has no FK constraint to `merchant` and no index. Per db-conventions.md Section 8, all FK columns must be indexed.

**Recommended fix (V6):**
```sql
ALTER TABLE public.channel_location
    ADD CONSTRAINT fk_channel_location_merchant
    FOREIGN KEY (merchant_id) REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION;
CREATE INDEX IF NOT EXISTS idx_channel_location_merchant ON public.channel_location (merchant_id);
```

### M7. `shipments` table missing `order_id` column/FK

**File:** `V2__shipment_workflow.sql` (shipments DDL)
**Rule:** shipment.md Section 6.1 shows `order_id VARCHAR(20) NOT NULL` as a direct FK from shipments to orders.
**Actual DDL:** The `shipments` table has no `order_id` column. Instead, the order relationship is indirect through `shipment_items.order_id`.

**Impact:** This is a design choice (one shipment can contain items from multiple orders), but it conflicts with the shipment flow rules which reference `shipments.order_id` directly. Either the rules or the DDL should be aligned.

### M8. `GlobalConfig` entity uses `OffsetDateTime` while all others use `LocalDateTime`

**File:** `GlobalConfig.java`, lines 23-27
**Problem:** Uses `OffsetDateTime` for `createdAt`/`updatedAt` while every other entity uses `LocalDateTime`. This inconsistency could cause timezone conversion issues if the JPA provider handles the types differently.

**Recommended:** Align to `LocalDateTime` for consistency.

---

## Gaps (rules say it should exist, not in schema)

### G1. Missing tables referenced in flow rules

| Table | Referenced in | Purpose |
|-------|--------------|---------|
| `channel_api_versions` | V1 DDL exists | Present -- confirmed |
| `product_barcode` | V1 DDL exists | Present -- confirmed |
| `merchant_options` | V1 DDL exists | Present -- confirmed |
| `product_group` | V1 DDL exists | Present -- confirmed |

All tables referenced in migrations are present. No missing tables.

### G2. Missing entity classes for existing DDL tables

| DDL Table | Entity Class | Status |
|-----------|-------------|--------|
| `channel_api_versions` | None found | **Missing** -- no entity class |
| `product_barcode` | None found | **Missing** -- no entity class |
| `merchant_options` | None found | **Missing** -- no entity class |
| `product_group` | None found | **Missing** -- no entity class |
| `channel_shipping_mapping` | None found | **Missing** -- no entity class |
| `channel_sync_logs` | None found | **Missing** -- no entity class |

These tables exist in the DDL but have no corresponding JPA entity or repository. They will need entity classes before any business logic can interact with them through JPA.

### G3. Missing `sell_pack_inventory.sync_status` column

**Rule:** inventory-sync.md Section 1.5 and Section 5.1 reference `sync_status` on `sell_pack_inventory` (values: `synced`, `failed`).
**DDL:** The V4 migration does not include a `sync_status` column on `sell_pack_inventory`.
**Entity:** `SellPackInventory.java` has no `syncStatus` field.

**Recommended fix (V6):**
```sql
ALTER TABLE public.sell_pack_inventory
    ADD COLUMN IF NOT EXISTS sync_status VARCHAR(20) DEFAULT 'pending';
```

### G4. Missing `fulfillment_status` column on `orders` table

**Rule:** shipment.md Section 5.1-5.2 references `orders.fulfillment_status` (values: UNFULFILLED, PARTIAL, FULFILLED).
**DDL:** No such column exists on the `orders` table.
**Entity:** `Order.java` has no `fulfillmentStatus` field.

**Recommended fix (V6):**
```sql
ALTER TABLE public.orders
    ADD COLUMN IF NOT EXISTS fulfillment_status VARCHAR(20) DEFAULT 'UNFULFILLED';
```

### G5. Missing `tracking_number`, `escrow_amount`, `actual_shipping_fee` columns on `orders`

**Rule:** order-processing.md Section 1.4 and 6.2 reference these as nullable columns that are filled later.
**DDL:** Not present in `orders` table definition.
**Entity:** `Order.java` has no corresponding fields.

**Recommended fix (V6):**
```sql
ALTER TABLE public.orders
    ADD COLUMN IF NOT EXISTS tracking_number VARCHAR(255),
    ADD COLUMN IF NOT EXISTS escrow_amount NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS actual_shipping_fee NUMERIC(10,2);
```

### G6. `shipment_status_logs` missing `error_code` and `error_message` columns

**Rule:** shipment.md Section 6.1 shows `error_code VARCHAR(50)` and `error_message TEXT` on `shipment_status_logs`.
**DDL (V2):** Only has `from_status`, `to_status`, `operator_id`, `remark`.
**Entity:** `ShipmentStatusLog.java` has no `errorCode` or `errorMessage` fields.

**Recommended fix (V6):**
```sql
ALTER TABLE public.shipment_status_logs
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS error_message TEXT;
```

---

## Schema Improvement Suggestions

### S1. Add `platform_code` column to `platform` table

The `platform` table uses `platform_name` for both display name and lookup. Adding a separate `platform_code` (e.g., `shopee`, `shopify`) with a unique index would:
- Enable cleaner lookups in V5 migration (currently `WHERE platform_name = 'shopify'` which is fragile)
- Separate human-readable name from programmatic identifier
- Allow renaming platforms without breaking references

### S2. Consider adding `deleted_at` soft-delete column to key tables

For `orders`, `sell_pack`, `product` -- soft-delete allows data recovery and audit trails. Currently there is no mechanism to mark records as deleted without physically removing them.

### S3. Add partition management automation for `daily_statistics`

V1 creates monthly partitions only for 2026. A scheduled job or migration should create 2027+ partitions before they are needed. Without partitions, inserts will fall into the `daily_statistics_default` partition and performance will degrade.

### S4. Consider adding `CONCURRENTLY` to index creation in future migrations

For production deployments on tables with data, `CREATE INDEX CONCURRENTLY` avoids locking the table during index creation. The current migrations create indexes with standard locking, which is fine for initial deployment but should be changed for any migration that runs on a table with production data.

### S5. `shipment_batches` missing `completed_at` column in V2 DDL

The flow rules (shipment.md Section 6.1) show `completed_at TIMESTAMPTZ` on `shipment_batches`, but V2 DDL does not include this column. The `ShipmentBatch.java` entity also does not have it.

### S6. `sell_pack` DDL `channel_product_id` could be `NOT NULL`

The product-sync rules (Section 6.1) declare `channel_product_id VARCHAR(100) NOT NULL`, but the V1 DDL has it as nullable (`VARCHAR(256)` without NOT NULL). Since the upsert key includes `channel_product_id`, it should not be null.

---

## Summary of Required Actions

| Priority | Issue | Action |
|----------|-------|--------|
| Critical | C1 | Change `platform.ship_options` from JSON to JSONB |
| Critical | C2 | Fix `sell_pack_inventory` unique constraint; add missing `channel_id` column |
| Critical | C3 | Add missing columns to `refund_orders`; fix nullability of `order_id` |
| Critical | C4 | Fix `ReturnOrderRepository` to filter by `channelId` |
| Moderate | M1 | Make `sell_pack.product_id` nullable |
| Moderate | M3 | Add `order_hash` column to `orders` |
| Moderate | M4 | Add `revenue_date` and `is_backfill` columns to `orders` |
| Moderate | M5 | Add `channel_id` to `SellPackInventory` entity (after C2 DDL fix) |
| Moderate | M6 | Add FK and index for `channel_location.merchant_id` |
| Gap | G3 | Add `sync_status` to `sell_pack_inventory` |
| Gap | G4 | Add `fulfillment_status` to `orders` |
| Gap | G5 | Add `tracking_number`, `escrow_amount`, `actual_shipping_fee` to `orders` |
| Gap | G6 | Add `error_code`, `error_message` to `shipment_status_logs` |

All fixes should be consolidated into a single `V6__schema_alignment.sql` migration.
