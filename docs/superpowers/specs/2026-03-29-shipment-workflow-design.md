# Shipment Workflow System Design

> **Status**: Approved — ready for implementation planning
> **Date**: 2026-03-29
> **Reviewed by**: PM, Architect, WMS Operator

---

## Goal

Build a warehouse shipment workflow system that:
- Supports **拆包** (one order → multiple boxes)
- Supports **混包** (multiple orders → one box)
- Tracks warehouse operations step-by-step (6 stages)
- Generates consolidated **拿貨清單** + per-order **分貨清單** for efficient picking
- Optionally groups boxes into vehicle batches with logistics cost tracking
- Auto-syncs dispatch events to all platform APIs via Kafka

---

## Key Decisions

| Decision | Choice | Notes |
|----------|--------|-------|
| `order_shipments` migration | **Replace** (Option A) | New `shipments` is strict superset. Migrate via Flyway. |
| CVS 超商取貨 | **Not in scope** (preparatory design only) | Schema fields reserved but no business logic. See work ticket #40. |
| Batch pick list | **In scope** | Two documents: 拿貨清單 (by location×SKU) + 分貨清單 (by order). |

---

## Architecture

Three-tier model:

```
shipment_batches   (optional — one vehicle/dispatch run, has logistics cost + manifest)
    └── shipments  (one per physical box / tracking number)
            └── shipment_items  (order line-items allocated to this box)
```

- **`shipment_batches`** is optional. Enabled per merchant via `merchant_options` (`name='shipment_batch_mode'`).
- When batch mode is OFF (default): shipments exist standalone.
- When batch mode is ON: all shipments must be attached to a batch before DISPATCHED. Manual override allowed with note.

---

## Migration: Replace `order_shipments`

### Impact scope

| File | Change |
|------|--------|
| `docker/init-db/01-schema.sql` | Drop `order_shipments`, add new tables |
| `simpleec-core/.../entity/OrderShipment.java` | Delete |
| `simpleec-core/.../repository/OrderShipmentRepository.java` | Delete |
| `simpleec-core/.../service/` | Replace any `OrderShipmentService` with `ShipmentService` |
| `simpleec-api/.../controller/UserShipmentController.java` | Rewrite against new schema |

### Flyway migration

```
V{N}__replace_order_shipments_with_shipments.sql
  1. CREATE TABLE shipments ...
  2. CREATE TABLE shipment_items ...
  3. CREATE TABLE shipment_batches ...
  4. CREATE TABLE shipment_status_logs ...
  5. INSERT INTO shipments SELECT ... FROM order_shipments  (migrate existing records)
  6. DROP TABLE order_shipments
```

Existing `order_shipments` data maps to `shipments` as:
- `status = 'DISPATCHED'` (all existing records are post-dispatch)
- `dispatched_at = shipped_at`
- `batch_id = NULL`
- No `shipment_items` rows needed (historical records, no item-level detail)

---

## Schema

### `merchant_options` — batch mode flag

Use existing `merchant_options` table (consistent with existing feature flag pattern):

```sql
INSERT INTO merchant_options (id, merchant_id, name, type, status)
VALUES (nanoid(), :merchantId, 'shipment_batch_mode', 'boolean', 'false');
```

### `shipments`

```sql
CREATE TABLE shipments (
    id                    VARCHAR(20) PRIMARY KEY,
    merchant_id           VARCHAR(20) NOT NULL REFERENCES merchants(id),
    channel_id            VARCHAR(20) NOT NULL REFERENCES channel(id),
    batch_id              VARCHAR(20) REFERENCES shipment_batches(id),   -- nullable
    shipment_no           VARCHAR(50),        -- system-generated: SHP-20260329-001
    tracking_number       VARCHAR(100),
    carrier               VARCHAR(100),
    logistics_cost        DECIMAL(10,2),      -- optional per-shipment cost
    status                VARCHAR(30) NOT NULL DEFAULT 'PICKING_LIST',
    -- PICKING_LIST | PICKING | PACKING | LABELING | AWAITING_PICKUP | DISPATCHED
    -- Terminal: DISPATCHED | CANCELLED
    -- Exception: ON_HOLD
    has_exception         BOOLEAN NOT NULL DEFAULT FALSE,
    exception_type        VARCHAR(30),        -- OUT_OF_STOCK | DAMAGED | ADDRESS_ERROR | OTHER
    exception_note        TEXT,
    dispatched_at         TIMESTAMP,
    platform_notified_at  TIMESTAMP,
    cancelled_at          TIMESTAMP,
    cancel_reason         TEXT,
    notes                 TEXT,
    version               INTEGER NOT NULL DEFAULT 0,   -- optimistic lock
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW(),

    -- CVS preparatory fields (nullable — no business logic in v1, see ticket #40)
    shipment_type         VARCHAR(30) DEFAULT 'HOME_DELIVERY',
    -- HOME_DELIVERY | CVS_711 | CVS_FAMILY | CVS_HILIFE | CVS_POST (reserved)
    cvs_store_code        VARCHAR(20),
    cvs_store_name        VARCHAR(100),
    cvs_recipient_name    VARCHAR(100),
    cvs_phone_last5       VARCHAR(5),

    -- COD fields (nullable — required for Yahoo/PChome COD orders)
    is_cod                BOOLEAN NOT NULL DEFAULT FALSE,
    cod_amount            DECIMAL(10,2)
);

CREATE INDEX idx_shipments_merchant        ON shipments(merchant_id);
CREATE INDEX idx_shipments_batch           ON shipments(batch_id);
CREATE INDEX idx_shipments_status          ON shipments(merchant_id, status);
CREATE INDEX idx_shipments_merchant_date   ON shipments(merchant_id, created_at DESC);
CREATE INDEX idx_shipments_channel         ON shipments(channel_id);
```

### `shipment_items`

```sql
CREATE TABLE shipment_items (
    id               VARCHAR(20) PRIMARY KEY,
    shipment_id      VARCHAR(20) NOT NULL REFERENCES shipments(id),
    merchant_id      VARCHAR(20) NOT NULL REFERENCES merchants(id),
    order_id         VARCHAR(20) NOT NULL REFERENCES orders(id),
    channel_order_id VARCHAR(100),
    items            JSONB NOT NULL,
    -- See items JSON structure below
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shipment_items_shipment   ON shipment_items(shipment_id);
CREATE INDEX idx_shipment_items_order      ON shipment_items(order_id);
CREATE INDEX idx_shipment_items_merchant   ON shipment_items(merchant_id);
CREATE INDEX idx_shipment_items_items_gin  ON shipment_items USING GIN (items);
```

**`items` JSONB structure** — one element per order line item in this box:

```json
[
  {
    "channel_item_id": "CI_12345",
    "sku": "SKU-001",
    "name": "商品名稱",
    "quantity": 2,
    "picked": false,
    "warehouse_location": "A-01-03",
    "barcode": null
  }
]
```

> Application-level uniqueness enforced: no duplicate `channel_item_id` for the same `order_id` across active (non-CANCELLED) shipments.

### `shipment_batches`

```sql
CREATE TABLE shipment_batches (
    id                   VARCHAR(20) PRIMARY KEY,
    merchant_id          VARCHAR(20) NOT NULL REFERENCES merchants(id),
    batch_no             VARCHAR(50),
    carrier              VARCHAR(100),
    logistics_cost       DECIMAL(10,2),
    scheduled_pickup_at  TIMESTAMP,
    actual_pickup_at     TIMESTAMP,
    carrier_driver_id    VARCHAR(100),     -- driver name/vehicle for handoff record
    handoff_box_count    INTEGER,          -- confirmed count at handoff
    status               VARCHAR(30) NOT NULL DEFAULT 'PREPARING',
    -- PREPARING | READY | PICKED_UP | COMPLETED
    -- READY: auto-set when all non-CANCELLED shipments reach LABELING
    --        OR manually overridden with force_ready_note
    force_ready_note     TEXT,             -- reason if manually forced to READY
    notes                TEXT,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shipment_batches_merchant ON shipment_batches(merchant_id);
```

### `shipment_status_logs`

```sql
CREATE TABLE shipment_status_logs (
    id           VARCHAR(20) PRIMARY KEY,
    shipment_id  VARCHAR(20) NOT NULL REFERENCES shipments(id),
    from_status  VARCHAR(30),
    to_status    VARCHAR(30) NOT NULL,
    operator_id  VARCHAR(20),
    remark       TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shipment_status_log ON shipment_status_logs(shipment_id);
```

### `OrderStatusEnum` addition

```java
PARTIALLY_SHIPPED("PARTIALLY_SHIPPED"),   // some boxes dispatched, some still in warehouse
```

> Code value is UPPERCASE, consistent with all existing enum codes.

---

## Status Flows

### Shipment (box-level)

```
PICKING_LIST → PICKING → PACKING → LABELING → AWAITING_PICKUP → DISPATCHED
                                                                   (terminal)
Any state → ON_HOLD       (exception raised — address error, wait for instruction)
Any state → CANCELLED     (terminal — releases items back to READY_TO_SHIP pool)
```

| Status | Meaning | Triggered by |
|--------|---------|-------------|
| PICKING_LIST | Created, pick list generated | System (Phase 1) |
| PICKING | Items being collected in warehouse | Warehouse staff |
| PACKING | Items being loaded into box | Warehouse staff |
| LABELING | Tracking number set, label printed | Warehouse staff |
| AWAITING_PICKUP | Box staged, waiting for carrier | Warehouse staff |
| DISPATCHED | Carrier collected — triggers platform sync | Warehouse staff / batch dispatch |
| ON_HOLD | Paused pending resolution (address error, damage, etc.) | Warehouse staff |
| CANCELLED | Voided — items released to READY_TO_SHIP pool | Operator |

> PICKING may be configured to auto-skip per merchant (no warehouse location system).

### Batch (vehicle-level)

```
PREPARING → READY → PICKED_UP → COMPLETED
```

| Status | Trigger |
|--------|---------|
| PREPARING | Manually created |
| READY | Auto when all non-CANCELLED shipments → LABELING. Or manual override with `force_ready_note`. |
| PICKED_UP | Manually confirmed at carrier arrival |
| COMPLETED | After all platform notifications succeed |

---

## Workflow Phases

### Phase 0 — 待出貨清單
- Source: `orders` WHERE `order_status = 'READY_TO_SHIP'`
- Operator reviews list, selects orders for current shipment run

### Phase 1 — 整理清單 + 生成撿貨單

Operator selects N orders → system creates shipments:

1. Create one `shipments` record per order (initial 1:1 draft)
2. Populate `shipment_items` from `orders.items` JSONB, enriching with `warehouse_location` from product master
3. Auto-assign `channel_id` from the order's channel
4. If `shipment_batch_mode=true`: assign all to current open batch (or create new batch)
5. Shipment status → `PICKING_LIST`

System generates **two documents** for warehouse staff:

**拿貨清單** (for picker — walk the warehouse once):
```
Consolidated by warehouse_location × SKU (sorted by location walk sequence)

Location A-01-03  SKU-001  商品A   ×5 units  (3 orders)
Location A-02-11  SKU-003  商品C   ×2 units  (1 order)
Location B-05-07  SKU-007  商品G   ×8 units  (4 orders)
```
API: `POST /api/user/shipments/pick-list` body: `{ shipmentIds: [...] }`

**分貨清單** (for packer — distribute at packing station):
```
Per-order breakdown

Order #001  黃先生  台北市
  SKU-001 ×2, SKU-003 ×1

Order #002  林小姐  新北市
  SKU-001 ×1, SKU-007 ×3
```
API: `POST /api/user/shipments/sort-list` body: `{ shipmentIds: [...] }`

### Phase 2 — 找貨 (Picking)

- Picker walks warehouse with 拿貨清單
- Marks items as picked (`items[].picked = true`)
- Shipment status → `PICKING`
- If item missing/damaged → raise exception (set `has_exception=true`, `exception_type`)

### Phase 3 — 裝箱 (Packing)

Packer at station uses 分貨清單 to distribute picked items into boxes.

**Split UI flow** (拆包):
1. Packer sees order line items for a shipment
2. Selects items to move to a new box → clicks "拆包"
3. System creates new `shipments` record
4. System updates `shipment_items`: selected items → new shipment; remaining → original
5. Both shipments now in PACKING status

**Merge UI flow** (混包):
1. Packer selects two shipments (same channel only; validated by server)
2. System moves all `shipment_items` from shipment B → shipment A
3. Shipment B → CANCELLED; shipment A continues

> Both operations use Redis distributed lock (`shipment:{id}:lock`, TTL 10s) + `version` optimistic lock to prevent concurrent mutation.

Shipment status → `PACKING`

### Phase 4 — 貼標 (Labeling)

- Input: tracking number (manual entry), carrier, logistics_cost (optional)
- Write `tracking_number`, `carrier` to shipment
- Print label (standard format for HOME_DELIVERY; CVS format reserved — see ticket #40)
- Shipment status → `LABELING`
- If batch mode: batch auto-transitions to `READY` when all non-CANCELLED shipments reach `LABELING`

### Phase 5 — 等物流 (Awaiting Pickup)

- Boxes staged in dispatch area
- Shipment status → `AWAITING_PICKUP`
- Batch mode: shows "此批次共 N 箱，已就緒 M 箱"

### Phase 6 — 取件確認 (Dispatch)

Operator confirms carrier collected.

**System actions (atomic DB transaction):**
1. `shipments.status = 'DISPATCHED'`, write `dispatched_at`
2. Log to `shipment_status_logs`
3. Check order completeness per affected order:
   - All items across all shipments dispatched → `orders.order_status = 'SHIPPED'`
   - Some items still in non-dispatched shipments → `orders.order_status = 'PARTIALLY_SHIPPED'`
4. Commit transaction

**Outside transaction (Kafka publish):**
5. Publish `SHIP_ORDER` event v2 per affected order (includes `lineItems` array — see Kafka section)
6. On success: write `platform_notified_at`
7. On failure: write to `task.failed` for retry; `platform_notified_at` stays null
   - Scheduled reconciliation job scans `DISPATCHED` shipments with `platform_notified_at IS NULL`

**Batch mode extras:**
- Record `actual_pickup_at`, `carrier_driver_id`, `handoff_box_count`
- Batch status → `PICKED_UP`
- Print **裝車清單 (manifest)**: `GET /api/user/shipment-batches/{id}/manifest`
  - Contains: batch_no, carrier, box count, tracking number list, total piece count

---

## Kafka Integration

### SHIP_ORDER event — version bump to v2

Existing event body is extended. `lineItems` array added for partial shipment support.
Platforms that ignore `lineItems` (backward compat) continue to work for non-split orders.

```json
{
  "header": {
    "taskType": "SHIP_ORDER",
    "version": 2,
    ...
  },
  "body": {
    "orderId": "...",
    "channelOrderId": "...",
    "trackingNumber": "TN12345",
    "carrier": "黑貓宅急便",
    "lineItems": [
      { "channelItemId": "CI_001", "quantity": 2 },
      { "channelItemId": "CI_002", "quantity": 1 }
    ]
  }
}
```

> `SchemaVersionHandler` updated to handle version 1 (no lineItems → treat as full fulfillment) and version 2.

### Platform handling

```
Cyberbiz → POST /v1/orders/{id}/fulfillments/custom_shipping
           (pass line_item_ids from lineItems; empty = fulfill all)
Shopee   → POST /v1/orders/ship
Momo     → stub
```

---

## REST API Endpoints

### Pick list generation

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/user/shipments/pick-list` | 拿貨清單：consolidated by location×SKU |
| POST | `/api/user/shipments/sort-list` | 分貨清單：per-order breakdown |

### Shipments

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/user/orders/ready-to-ship` | Orders ready for outbound |
| POST | `/api/user/shipments` | Create shipments from selected orders |
| GET | `/api/user/shipments` | List (filterable by status, date, channel) |
| GET | `/api/user/shipments/{id}` | Shipment detail + items |
| PUT | `/api/user/shipments/{id}/status` | Advance status |
| PUT | `/api/user/shipments/{id}/tracking` | Set tracking number + carrier |
| PUT | `/api/user/shipments/{id}/exception` | Raise / resolve exception |
| POST | `/api/user/shipments/{id}/split` | Split into two shipments |
| POST | `/api/user/shipments/merge` | Merge two shipments (same channel) |
| DELETE | `/api/user/shipments/{id}` | Cancel shipment (releases items) |

### Batches

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/user/shipment-batches` | Create batch |
| GET | `/api/user/shipment-batches` | List batches |
| GET | `/api/user/shipment-batches/{id}` | Batch detail + shipments |
| PUT | `/api/user/shipment-batches/{id}/status` | Advance status (or force-READY) |
| PUT | `/api/user/shipment-batches/{id}/cost` | Record logistics cost |
| GET | `/api/user/shipment-batches/{id}/manifest` | Print manifest (裝車清單) |

---

## Exception Handling

| Scenario | Status | System action |
|----------|--------|--------------|
| Item not found (缺貨) | ON_HOLD, `exception_type=OUT_OF_STOCK` | Notify order team via exception queue |
| Damaged item (破損品) | ON_HOLD, `exception_type=DAMAGED` | Same as above |
| Wrong address (地址錯誤) | ON_HOLD, `exception_type=ADDRESS_ERROR` | Hold — operator corrects then resumes |
| Order cancelled by platform | Shipment → CANCELLED | Release items to READY_TO_SHIP pool |
| Cancel a shipment in PACKING+ | CANCELLED + generate return-to-shelf task | Items listed with warehouse_location |
| Wrong tracking number | Update `tracking_number`, re-publish SHIP_ORDER v2 | Log old tracking in `shipment_status_logs` |
| Platform API fails on dispatch | `platform_notified_at = null` | Retry via `task.failed`; reconciliation job scans nightly |
| Carrier doesn't show up | Stay at `AWAITING_PICKUP` | No auto-timeout |
| Carrier arrives before batch READY | Force `READY` with `force_ready_note` | Manual override allowed |

---

## Service Architecture

- **`ShipmentService`** lives in `simpleec-core` (reusable by API and backend jobs)
  - `createShipments(List<String> orderIds, String channelId)` — Phase 1
  - `advanceStatus(String shipmentId, String operatorId)` — all phases
  - `split(String shipmentId, List<String> channelItemIds)` — Phase 3
  - `merge(String shipmentIdA, String shipmentIdB)` — Phase 3
  - `dispatch(String shipmentId, String operatorId)` — Phase 6 (DB + Kafka)
  - `generatePickList(List<String> shipmentIds)` — consolidated 拿貨清單
  - `generateSortList(List<String> shipmentIds)` — per-order 分貨清單
- **`UserShipmentController`** in `simpleec-api` — thin HTTP layer, delegates to `ShipmentService`

---

## Out of Scope (v1)

| Feature | Ticket |
|---------|--------|
| CVS 超商取貨出貨流程 | #40 |
| 貨到付款 (COD) label generation | #41 |
| Carrier API auto-tracking number | #42 |
| Barcode scanning hardware | #43 |
| Inbound / receiving (WMS putaway) | — |
| Inventory reservation during picking | — |
| Carrier performance reporting | — |
| Return label (退貨標籤) in-box | — |
