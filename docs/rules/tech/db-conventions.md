# DB Conventions

> Defines PostgreSQL schema standards for SimpleEC OMS: primary keys, foreign keys, timestamps, PII encryption, JSONB usage, Flyway migrations, naming, indexes, NULLability, and constraint rules. Read this before writing any entity, migration, or repository.

---

## 1. Primary Key Rules

**All table PKs are `VARCHAR(20)` NanoID. Never use UUID. Never use BIGSERIAL / auto-increment.**

```sql
-- CORRECT
id VARCHAR(20) NOT NULL PRIMARY KEY

-- WRONG — do not use
id BIGSERIAL PRIMARY KEY
id UUID DEFAULT gen_random_uuid() PRIMARY KEY
```

**Generation in application code:**
```java
// Simple random NanoID (most common):
String id = NanoIdUtil.generate();  // → 20-char URL-safe string

// Composite NanoID (merchant-prefixed + timestamp, optional but useful for partitioned tables):
String id = NanoIdUtil.generateComposite(merchantId);  // → "M12320260317144530AB"

// Prefixed NanoID (keeps total length 20):
String id = NanoIdUtil.generateWithPrefix("ORD_");  // → "ORD_" + 16 random chars
```

**Character set:** `A-Z a-z 0-9 _ -` (URL-safe). All 20 characters.

**Never generate IDs in SQL** (e.g., no `DEFAULT gen_random_uuid()`). The application always provides the PK value.

**Entity annotation pattern:**
```java
@Id
@Column(name = "id", length = 20, nullable = false)
private String id;
```
Do not use `@GeneratedValue` — the application supplies the value before save.

---

## 2. Foreign Key Rules

All FKs reference VARCHAR(20) NanoID columns. FK columns use the same name as the referenced table's PK column, suffixed with `_id`.

| Pattern | Example |
|---|---|
| `merchant_id` references `merchant.id` | `CONSTRAINT fk_account_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id)` |
| `channel_id` references `channel.id` | Same pattern |
| `order_id` references `orders.id` | Same pattern |

**All FK columns are `VARCHAR(20)` in Java:**
```java
@Column(name = "merchant_id", length = 20, nullable = false)
private String merchantId;
```

**ON DELETE behavior:** Default is `NO ACTION` (strict). Use `CASCADE` only for child-of-owner relationships (e.g., `order_status_logs → orders`, `sell_pack_inventory → sell_pack`). Never use `SET DEFAULT`.

**FK column declaration in SQL:**
```sql
merchant_id VARCHAR(20) NOT NULL,
CONSTRAINT fk_xxx_merchant FOREIGN KEY (merchant_id)
    REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION
```

---

## 3. Timestamp Rules

**Use `TIMESTAMPTZ` (not `TIMESTAMP`). Always `NOT NULL DEFAULT now()`.**

```sql
-- CORRECT
created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
updated_at TIMESTAMPTZ NOT NULL DEFAULT now()

-- WRONG
created_at TIMESTAMP,                    -- missing timezone, missing NOT NULL
updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP  -- use now() consistently
```

**Entity annotation pattern:**
```java
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private LocalDateTime createdAt;

@UpdateTimestamp
@Column(name = "updated_at", nullable = false)
private LocalDateTime updatedAt;
```

**Platform-sourced timestamps** (e.g., `channel_created_at`, `paid_at`, `shipped_at`) may be NULL because they arrive from the platform asynchronously. They do NOT need `DEFAULT now()`:

```sql
channel_created_at TIMESTAMPTZ,  -- NULL until platform provides it
paid_at            TIMESTAMPTZ,
shipped_at         TIMESTAMPTZ
```

**Time zone handling:** The database stores UTC. Application code uses `LocalDateTime` (which JPA/Hibernate maps to the DB timezone setting). When displaying to merchants, convert to `merchant.user_local_time_zone`.

---

## 4. PII Encryption

The following fields contain Personally Identifiable Information and are encrypted at rest using **AES-256-GCM** via `EncryptedAttributeConverter`:

| Table | Column | Java Field |
|---|---|---|
| `orders` | `buyer_name` | `Order.buyerName` |
| `orders` | `buyer_phone` | `Order.buyerPhone` |
| `orders` | `buyer_email` | `Order.buyerEmail` |
| `orders` | `shipping_address` | `Order.shippingAddress` |

**Entity annotation (required for every encrypted field):**
```java
@Convert(converter = EncryptedAttributeConverter.class)
@Column(name = "buyer_name", length = 512)
private String buyerName;
```

**Encryption context (mandatory before any read or write of PII fields):**
```java
EncryptionContext.setMerchantId(merchantId);
try {
    List<Order> orders = orderRepository.findByMerchantId(merchantId, pageable);
    // ... work with orders (PII automatically decrypted by converter)
} finally {
    EncryptionContext.clear();  // ALWAYS in finally block — prevents key leakage across threads
}
```

If `EncryptionContext` has no `merchantId` set when the converter fires, it throws `IllegalStateException`. This will cause the query to fail loudly. Do not suppress this exception.

**Key derivation:** `MerchantKeyProvider` derives a per-merchant AES key from `merchantId` + a global secret. Keys are never stored in the DB.

**Logging PII:** Never log `buyerName`, `buyerPhone`, `buyerEmail`, or `shippingAddress`. Log only `orderId` and `merchantId` for traceability.

---

## 5. JSONB Usage

JSONB is used where structure varies per platform or the field is a flexible key-value bag.

| Table | Column | Purpose |
|---|---|---|
| `orders` | `items` | Order line items array. GIN-indexed. |
| `orders` | `buyer_info` | Additional buyer data (structured differently per platform). |
| `orders` | `shipping_info` | Logistics tracking detail. |
| `sell_pack` | `channel_spec_attrs` | Platform-specific spec attributes (e.g., `{color: "紅色", size: "M"}`). |
| `sell_pack` | `sync_status` | Sync state tracking: `pending \| syncing \| completed \| failed`. |
| `sell_pack` | `platform_metadata` | Platform IDs that don't fit the generic schema (added V3). e.g., `{"shopify": {"inventory_item_id": "457924702"}}` |
| `platform` | `ship_options` | Platform-level shipping option definitions. **Type: JSONB** (changed from JSON in V6). |
| `platform` | `capabilities` | Feature flags per platform (added V5). e.g., `{"multiLocation": true}`. |
| `failed_task_logs` | `payload` | Original Kafka message payload for failed tasks. |

**Reading JSONB values — always use `.path("key")`, never string comparison:**
```java
// CORRECT
JsonNode capabilities = objectMapper.readTree(platform.getCapabilities());
boolean multiLocation = capabilities.path("multiLocation").asBoolean(false);

// WRONG — fragile, breaks on formatting differences
platform.getCapabilities().contains("\"multiLocation\": true")
```

**JSONB for arrays (`items`):** Always default to `'[]'::jsonb` in SQL, and in Java:
```java
@Column(name = "items", columnDefinition = "jsonb", nullable = false)
@JdbcTypeCode(SqlTypes.JSON)
@ColumnDefault("'[]'::jsonb")
private String items;
```

**When NOT to use JSONB:** Business fields that are queried with `WHERE`, `JOIN`, or `ORDER BY` must be proper columns. JSONB is only for data that is stored-and-returned but not filtered in SQL.

---

## 6. Migration Rules (Flyway)

**Migration file naming:** `V{N}__{snake_case_description}.sql` — two underscores.

```
V1__initial_schema.sql            ← first migration (all base tables)
V2__shipment_workflow.sql
V3__sell_pack_platform_metadata.sql
V4__channel_location_and_inventory.sql
V5__platform_capabilities.sql
V6__review_fixes.sql
```

**Current highest migration: V6.** The next migration must be `V7__...sql`.

**Rules:**
1. Never skip a version number. V1, V2, V3… sequential only.
2. Never modify an already-applied migration file. Flyway checksums will fail and the application will refuse to start.
3. To undo a change: write a new migration that reverses it.
4. Include `IF NOT EXISTS` / `IF EXISTS` guards where appropriate for idempotency during development (though production migrations should be tested on a clean state first).
5. Seed data that is environment-dependent goes in `02-seed-data.sql` (Docker init), not in Flyway migrations.

**Migration template:**
```sql
-- V7: Short description of what this migration does and why
-- Relates to: [link to issue or design doc if applicable]

ALTER TABLE public.some_table
    ADD COLUMN IF NOT EXISTS new_column VARCHAR(50);

CREATE INDEX idx_some_table_new_column ON public.some_table (new_column);
```

### V6 Migration Summary (`V6__review_fixes.sql`, 2026-04-06)

Three schema fixes applied from a comprehensive review pass:

**DB-C1 — `platform.ship_options`: JSON → JSONB**

```sql
ALTER TABLE public.platform
    ALTER COLUMN ship_options TYPE JSONB USING ship_options::jsonb;
```

Rationale: The entity declared the column as `jsonb` but the DDL had `JSON` (non-binary, no GIN indexing, no JSONB operators). Changed to match the entity and enable future operator use.

**DB-C2 — `sell_pack_inventory` UNIQUE constraint replaced with partial indexes**

The old inline `UNIQUE (sell_pack_id, COALESCE(channel_location_id, ''))` constraint is non-standard in PostgreSQL — function calls in inline UNIQUE constraints are not supported. Replaced with two partial unique indexes:

```sql
-- Unique per sell_pack when there is no location (Shopee, Cyberbiz, Shopline, etc.)
CREATE UNIQUE INDEX uq_sell_pack_inv_no_loc
    ON public.sell_pack_inventory (sell_pack_id)
    WHERE channel_location_id IS NULL;

-- Unique per (sell_pack, location) for multi-location platforms (e.g. Shopify)
CREATE UNIQUE INDEX uq_sell_pack_inv_with_loc
    ON public.sell_pack_inventory (sell_pack_id, channel_location_id)
    WHERE channel_location_id IS NOT NULL;
```

Rationale: The two indexes together enforce the same business invariant as the old constraint (at most one inventory row per sell_pack per location, where "no location" is treated as its own unique slot), but using valid PostgreSQL syntax and with correct NULL semantics.

**DB-C3 — `refund_orders` new columns: `channel_id`, `channel_order_id`, `currency`**

```sql
ALTER TABLE public.refund_orders
    ADD COLUMN IF NOT EXISTS channel_id       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS channel_order_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS currency         VARCHAR(3) NOT NULL DEFAULT 'TWD';
```

- FK: `channel_id → channel.id ON UPDATE CASCADE ON DELETE SET NULL` (nullable; channel deletion should not delete refund history)
- Index: `(channel_id)` for fast channel-scoped lookups
- Index: `(channel_id, channel_refund_id)` for dedup queries scoped to channel (fixes DB-C4: `findByChannelRefundId` must be scoped to channel, not global)

Rationale: Required for Translation Layer compliance — outbound refund actions need `channel_order_id` to call the platform API. The composite index enables efficient dedup checks when ingesting returns from a channel.

---

## 7. Naming Conventions

| Object | Convention | Example |
|---|---|---|
| Table names | `snake_case`, plural where natural | `orders`, `sell_pack`, `channel_location`, `order_status_logs` |
| Column names | `snake_case` | `merchant_id`, `channel_order_id`, `created_at` |
| Constraint names | `fk_{table}_{referenced}`, `uq_{table}_{...}`, `idx_{table}_{...}` | `fk_account_merchant`, `uq_sell_pack_inventory` |
| Index names | `idx_{table}_{columns}` | `idx_order_merchant_status`, `idx_channel_location_sync_target` |
| Java entity fields | `camelCase` | `merchantId`, `channelOrderId`, `createdAt` |
| Enum values in columns | `UPPER_SNAKE_CASE` stored as VARCHAR | `PENDING`, `SHIPPED`, `CANCELLED` |

**Table name exceptions:** `orders` (plural, avoids SQL reserved word `order`). `sell_pack` (singular noun phrase by design).

---

## 8. Index Rules

**Always index FK columns.** Missing FK indexes causes full table scans on JOINs.

```sql
-- After every FK column, add a corresponding index:
CREATE INDEX idx_channel_merchant ON public.channel (merchant_id);
CREATE INDEX idx_channel_platform ON public.channel (platform_id);
```

**Composite indexes for common query patterns:**
```sql
-- Queries filtering by merchant + status are very common
CREATE INDEX idx_order_merchant_status ON public.orders (merchant_id, order_status);

-- Stats queries join by merchant + channel + date
CREATE INDEX idx_order_stats ON public.orders (merchant_id, channel_id, channel_created_at);
```

**Partial indexes for sparse boolean columns:**
```sql
-- Only index rows where is_sync_target = true (very few rows per channel)
CREATE UNIQUE INDEX idx_channel_location_one_sync_target
    ON public.channel_location (channel_id)
    WHERE is_sync_target = true;

-- Only index orders that have refunds (minority of rows)
CREATE INDEX idx_order_has_refund ON public.orders (merchant_id, has_refund)
    WHERE has_refund = true;
```

**GIN indexes for JSONB columns that are searched:**
```sql
CREATE INDEX idx_order_items ON public.orders USING GIN (items);
```

**Unique indexes for business keys** (see Section 10 for constraint rules):
```sql
CREATE UNIQUE INDEX idx_order_channel_order ON public.orders (channel_id, channel_order_id);
```

---

## 9. NULL Rules

NULL is semantically meaningful in some cases. Use it deliberately, not by omission.

| Scenario | Rule | Example |
|---|---|---|
| Platform has no location concept | `channel_location_id` is NULL | `sell_pack_inventory.channel_location_id = NULL` for Shopee, Cyberbiz, Shopline |
| Platform timestamp not yet received | NULL is valid | `orders.channel_created_at`, `orders.paid_at`, `orders.shipped_at` |
| Optional reference (may not exist) | NULL allowed | `product.product_group_id` |
| Required business field | NOT NULL + appropriate DEFAULT | `orders.order_status NOT NULL DEFAULT 'PENDING'` |
| OMS system fields | NOT NULL DEFAULT now() | `created_at`, `updated_at` |
| Counts and amounts | NOT NULL DEFAULT 0 | `orders.total_amount NOT NULL DEFAULT 0` |

**Do NOT use NULL to mean "zero" for numeric fields.** Use `NOT NULL DEFAULT 0` so aggregations (`SUM`, `AVG`) work without `COALESCE`.

**Do NOT use NULL to mean "unknown string".** Use `NOT NULL DEFAULT ''` or an explicit sentinel value, unless NULL carries meaning (e.g., "not yet assigned").

---

## 10. Constraint Rules

**Business uniqueness constraints** use unique indexes rather than unique constraints where partial indexes are needed:

```sql
-- Unique index with COALESCE to handle NULLable column in uniqueness:
-- (standard UNIQUE constraint treats two NULLs as not equal, which is wrong here)
CREATE UNIQUE INDEX idx_sellpack_upsert_key
    ON public.sell_pack (channel_id, channel_product_id, COALESCE(channel_spec_id, ''));
```

> **Rule: UNIQUE constraints in PostgreSQL do not support function calls (e.g. `COALESCE`).** An inline `UNIQUE (COALESCE(col, ''))` is non-standard and may be rejected depending on PG version. Use partial unique indexes instead when NULL must be treated as a distinct key value.

**Partial unique indexes for nullable business keys** — preferred pattern when a column may be NULL and NULL values must be treated as distinct participants in the uniqueness guarantee:

```sql
-- sell_pack_inventory: one row per sell_pack with no location (platforms without multi-location)
CREATE UNIQUE INDEX uq_sell_pack_inv_no_loc
    ON public.sell_pack_inventory (sell_pack_id)
    WHERE channel_location_id IS NULL;

-- one row per (sell_pack, location) for multi-location platforms (e.g. Shopify)
CREATE UNIQUE INDEX uq_sell_pack_inv_with_loc
    ON public.sell_pack_inventory (sell_pack_id, channel_location_id)
    WHERE channel_location_id IS NOT NULL;
```

**Single sync-target constraint (partial unique index):**
```sql
-- Only one row per channel may have is_sync_target = true:
CREATE UNIQUE INDEX idx_channel_location_one_sync_target
    ON public.channel_location (channel_id)
    WHERE is_sync_target = true;
```

**Natural business key constraints:**
```sql
-- Each platform account email is globally unique:
CONSTRAINT platform_account_email UNIQUE (email)

-- Each merchant account email is globally unique:
CONSTRAINT account_email_un UNIQUE (account_email)

-- Each (channel_id, channel_order_id) pair is unique:
CREATE UNIQUE INDEX idx_order_channel_order ON public.orders (channel_id, channel_order_id);
```

**FK constraint naming:**
```
fk_{child_table}_{parent_table}
```
Examples: `fk_account_merchant`, `fk_order_channel`, `fk_sell_pack_inventory_location`.
