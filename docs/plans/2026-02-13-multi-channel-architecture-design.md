# Multi-Channel Architecture Design

> **Date:** 2026-02-13
> **Status:** Draft
> **Scope:** Cross-channel shared architecture + Cyberbiz reference implementation

---

## Part 1: Cross-Channel Shared Architecture

### 1.1 Problem Statement

Current architecture has these gaps identified through Cyberbiz real-API validation:

| # | Gap | Impact |
|---|-----|--------|
| 1 | `fetchOrders(channelId, from, to)` too simple | Can't pass status filters, pagination, logistics type |
| 2 | Returns `List<Order>` entity directly | Couples adapter to DB schema |
| 3 | Single `order_status` field | Can't express financial/fulfillment/return independently |
| 4 | No platform raw status preservation | Can't debug or trace back to platform |
| 5 | No `channel_order_number` | Customer service can't search by human-readable order number |
| 6 | Status mapping hardcoded | Platform status changes require redeployment |

### 1.2 Two-Layer Status Model

#### Layer 1: Platform Raw Status (JSONB)

Stored verbatim from platform API response. Never interpreted by business logic.

```
Cyberbiz: {"order":"open","financial":"paid","fulfillment":"unshipped","return":"no_need"}
Shopee:   {"order_status":"READY_TO_SHIP"}
momo:     {"orderStatus":"2","logisticsStatus":"1"}
Yahoo:    {"OrderStatus":"1","ShipStatus":"2"}
PChome:   {"status":"processing","payment":"paid"}
```

Purpose: debug, audit trail, platform communication, re-mapping when definitions change.

#### Layer 2: Normalized Status (Independent Columns)

OMS-defined enums. **All business logic, queries, statistics, and UI only reference this layer.**

```
order_status:       pending | confirmed | processing | completed | cancelled
financial_status:   unpaid | paid | partial_refunded | refunded | cod | failed
fulfillment_status: unfulfilled | preparing | shipped | delivered | arrived | returned | problem
return_status:      none | requested | returning | received | checking | refused | returned | partial | cancelled | problem
```

#### Mapping Flow

```
Platform API response
    │
    ├──→ platform_status JSONB (Layer 1, verbatim)
    │
    └──→ StatusMapper.normalize()
            │
            │  reads mapping from cache (source: DB table)
            │
            └──→ order_status, financial_status, fulfillment_status, return_status (Layer 2)
```

### 1.3 Status Mapping Infrastructure

#### DB Table: `status_mapping`

```sql
CREATE TABLE public.status_mapping (
    id              VARCHAR(20)   NOT NULL,    -- NanoID
    platform_code   VARCHAR(20)   NOT NULL,    -- e.g. "cyberbiz", "shopee"
    status_axis     VARCHAR(30)   NOT NULL,    -- "order" | "financial" | "fulfillment" | "return"
    platform_value  VARCHAR(50)   NOT NULL,    -- platform's raw value, e.g. "READY_TO_SHIP"
    normalized_value VARCHAR(30)  NOT NULL,    -- OMS value, e.g. "paid"
    description     VARCHAR(200),              -- human-readable note
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_status_mapping_lookup
    ON public.status_mapping (platform_code, status_axis, platform_value);
```

Example data:

| platform_code | status_axis | platform_value | normalized_value | description |
|---------------|-------------|----------------|------------------|-------------|
| cyberbiz | financial | pending | unpaid | 等待付款 |
| cyberbiz | financial | paid | paid | 已收到款項 |
| cyberbiz | financial | cod | cod | 貨到付款 |
| cyberbiz | financial | refunded | refunded | 已退款 |
| cyberbiz | financial | partial_refunded | partial_refunded | 部分退款 |
| cyberbiz | fulfillment | unshipped | unfulfilled | 未出貨 |
| cyberbiz | fulfillment | preparing | preparing | 準備出貨 |
| cyberbiz | fulfillment | fulfilled | shipped | 已出貨 |
| cyberbiz | fulfillment | arrived | arrived | 已到店 |
| cyberbiz | fulfillment | received | delivered | 已收貨 |
| cyberbiz | fulfillment | returned | returned | 已退貨 |
| shopee | order | UNPAID | pending | 未付款 |
| shopee | order | READY_TO_SHIP | confirmed | 已付款待出貨 |
| shopee | order | SHIPPED | processing | 已出貨 |
| shopee | order | COMPLETED | completed | 已完成 |
| shopee | order | CANCELLED | cancelled | 已取消 |

**Note:** Some platforms (like Shopee) use a single status field that implies multiple axes. The mapping handles this:

| platform_code | status_axis | platform_value | normalized_value |
|---------------|-------------|----------------|------------------|
| shopee | financial | READY_TO_SHIP | paid |
| shopee | financial | UNPAID | unpaid |
| shopee | fulfillment | READY_TO_SHIP | unfulfilled |
| shopee | fulfillment | SHIPPED | shipped |

Same `platform_value` ("READY_TO_SHIP") maps to different axes independently.

#### Cache Strategy

```
Startup:
  DB → Redis Hash: status_mapping:{platform_code}:{status_axis} → {platform_value: normalized_value}

Runtime:
  StatusMapper reads from Redis (O(1) lookup)

UI Update:
  Admin updates DB → calls /api/cache/refresh/status-mapping → reload Redis
```

#### StatusMapper Interface

```java
public interface StatusMapper {
    NormalizedStatus normalize(String platformCode, Map<String, String> rawStatus);
}

@Data
@Builder
public class NormalizedStatus {
    private String orderStatus;
    private String financialStatus;
    private String fulfillmentStatus;
    private String returnStatus;
}
```

Single implementation reads from cache. No per-platform classes needed — the mapping data itself is per-platform.

```java
@Service
public class CachedStatusMapper implements StatusMapper {

    @Autowired
    private StatusMappingCache cache;

    @Override
    public NormalizedStatus normalize(String platformCode, Map<String, String> rawStatus) {
        // For platforms with multi-axis raw status (Cyberbiz):
        //   rawStatus = {"order":"open", "financial":"paid", ...}
        //   each key maps to one axis
        //
        // For platforms with single-axis raw status (Shopee):
        //   rawStatus = {"order_status":"READY_TO_SHIP"}
        //   same value queried across all 4 axes

        return NormalizedStatus.builder()
            .orderStatus(resolve(platformCode, "order", rawStatus))
            .financialStatus(resolve(platformCode, "financial", rawStatus))
            .fulfillmentStatus(resolve(platformCode, "fulfillment", rawStatus))
            .returnStatus(resolve(platformCode, "return", rawStatus))
            .build();
    }

    private String resolve(String platform, String axis, Map<String, String> raw) {
        // Try axis-specific key first, then all raw values
        // Returns mapped value or "unknown"
    }
}
```

### 1.4 Schema Changes: orders Table

```sql
-- New columns to add to orders table
ALTER TABLE public.orders
    ADD COLUMN channel_order_number VARCHAR(100),       -- human-readable order number
    ADD COLUMN financial_status     VARCHAR(20) NOT NULL DEFAULT 'unpaid',
    ADD COLUMN fulfillment_status   VARCHAR(20) NOT NULL DEFAULT 'unfulfilled',
    ADD COLUMN return_status        VARCHAR(20) NOT NULL DEFAULT 'none',
    ADD COLUMN platform_status      JSONB       NOT NULL DEFAULT '{}';

-- New indexes
CREATE INDEX idx_order_financial ON public.orders (merchant_id, financial_status);
CREATE INDEX idx_order_fulfillment ON public.orders (merchant_id, fulfillment_status);
```

**Existing `order_status`** remains as-is, just now one of four axes instead of the only status field.

### 1.5 ChannelOrder DTO (Revised)

Replace the current design in `DB_ENTITY_GAPS.md`:

```java
package com.simpleec.channel.dto;

@Data
@Builder
public class ChannelOrder {
    // === Identity ===
    private String channelOrderId;          // platform's system ID
    private String channelOrderNumber;      // human-readable order number (display)

    // === Status (raw from platform) ===
    private Map<String, String> rawStatus;  // verbatim platform status fields

    // === Buyer / Receiver PII ===
    private String buyerName;
    private String buyerPhone;
    private String buyerEmail;
    private String shippingAddress;

    // === Logistics ===
    private String shippingMethod;
    private String paymentMethod;

    // === Money ===
    private BigDecimal totalAmount;
    private BigDecimal shippingFee;
    private BigDecimal discountAmount;

    // === Timestamps ===
    private LocalDateTime channelCreatedAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;

    // === Line Items ===
    private List<ChannelOrderItem> items;
}

@Data
@Builder
public class ChannelOrderItem {
    private String channelProductId;        // platform's product ID
    private String channelSpecId;           // platform's variant/spec ID
    private String channelProductName;
    private String channelSpecName;
    private String sku;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
}
```

Key changes from previous design:
- Added `channelOrderNumber` (human-readable)
- Changed `orderStatus` (String) → `rawStatus` (Map) to carry all platform status axes
- Added `@Builder` for clean construction in adapters

### 1.6 FetchOrdersRequest DTO

```java
package com.simpleec.channel.dto;

@Data
@Builder
public class FetchOrdersRequest {
    private String channelId;
    private LocalDateTime from;
    private LocalDateTime to;

    // === Platform-specific filters ===
    private String timeField;               // "created_at" | "updated_at" | "cancelled_at" etc.
    private String orderStatus;             // platform-native status value (if API requires it)
    private String logisticsType;           // momo: "Company" | "Stores" | "Third"
    private String queryMode;               // shopify: "created_at" | "updated_at"

    // === Pagination ===
    private Integer page;
    private Integer pageSize;

    // === Catch-all ===
    private Map<String, Object> extra;      // anything else platform-specific
}
```

### 1.7 ChannelAdapter Interface (Revised)

```java
public interface ChannelAdapter {

    ChannelType getChannelType();

    boolean validateConnection(Map<String, String> credentials);

    // ==================== Product Operations ====================

    String createListing(String channelId, SellPack sellPack, Map<String, Object> extraData);
    void updateListing(String channelId, SellPack sellPack, Map<String, Object> extraData);
    void updatePrice(String channelId, String channelProductId, BigDecimal price);
    void updateQuantity(String channelId, String channelProductId, int quantity);
    void startSelling(String channelId, String channelProductId);
    void stopSelling(String channelId, String channelProductId);

    // ==================== Order Operations (CHANGED) ====================

    /** Fetch orders from platform. Handles pagination internally, returns all results. */
    List<ChannelOrder> fetchOrders(FetchOrdersRequest request);

    void confirmShipment(String channelId, String channelOrderId,
                         String trackingNumber, String logisticsCompany);
    void acceptCancellation(String channelId, String channelOrderId);
    String getShippingLabel(String channelId, String channelOrderId);
}
```

Changes:
- `fetchOrders(channelId, from, to)` → `fetchOrders(FetchOrdersRequest request)`
- Returns `List<ChannelOrder>` instead of `List<Order>`

### 1.8 Order Processing Flow (Revised)

```
ChannelJob (FetchOrdersActionService.doAction())
  │
  │  adapter.fetchOrders(request) → List<ChannelOrder>
  │
  │  for each channelOrder:
  │    hash = SHA-256(channelOrder)
  │    if hash != redis.get(key):
  │      send to order.process topic
  │        payload: {
  │          channelOrder: { ... },           // full ChannelOrder DTO
  │          channelType: "cyberbiz",         // for StatusMapper lookup
  │          merchantId: "xxx",
  │          channelId: "yyy"
  │        }
  │
  ▼
OrderProcessJob
  │
  │  1. Extract ChannelOrder from payload
  │  2. StatusMapper.normalize(channelType, rawStatus) → NormalizedStatus
  │  3. Query: SELECT * FROM orders WHERE channel_id=? AND channel_order_id=?
  │  4. If new:
  │       INSERT with all 4 status fields + platform_status JSONB
  │  5. If existing and any status changed:
  │       UPDATE status fields + platform_status
  │       INSERT order_status_logs
  │  6. Write Redis hash (after successful DB write)
  │
  ▼
BackendJob (if status changed)
  │  Statistics, notifications, etc.
```

---

## Part 2: Cyberbiz Reference Implementation

### 2.1 ChannelType Addition

```java
public enum ChannelType {
    MOMO("momo", "momo購物"),
    SHOPEE("shopee", "蝦皮購物"),
    YAHOO("yahoo", "Yahoo購物中心"),
    PCHOME("pchome", "PChome商店街"),
    CYBERBIZ("cyberbiz", "Cyberbiz");       // NEW

    // ...
}
```

### 2.2 Cyberbiz Authentication

**Method:** HMAC-SHA256 signature per request (no OAuth token flow).

```
Credentials (stored in platform.credential1~2):
  credential1 = username (e.g. "rick@testcyb.info@1")
  credential2 = secret

Per-request signing:
  1. X-Date = GMT timestamp
  2. signing_string = "x-date: {X-Date}\n{METHOD} {PATH} HTTP/1.1"
     (if body: append "\ndigest: SHA-256={base64(sha256(body))}")
  3. signature = base64(hmac_sha256(signing_string, secret))
  4. Authorization: hmac username="{user}", algorithm="hmac-sha256",
                    headers="x-date request-line [digest]", signature="{sig}"
```

No token refresh needed. No channel.token1~5 used. Simpler than OAuth platforms.

**Rate limit:** 5 requests/second.

### 2.3 Cyberbiz API: GET /v1/orders

```
GET https://api.cyberbiz.co/v1/orders

Query Parameters:
  start_time / end_time                 — order created time window
  updated_at_start_time / end_time      — order updated time window
  closed_at_start_time / end_time       — order closed time window
  refund_at_start_time / end_time       — order refund time window
  cancelled_at_start_time / end_time    — order cancelled time window
  statuses                              — open,closed,cancelled
  financial_statuses                    — pending,paid,cod,refunded,...
  fulfillment_statuses                  — unshipped,preparing,fulfilled,...
  return_statuses                       — no_need,request_return,returned,...
  page (default: 1)
  per_page (default: 50)
```

### 2.4 Cyberbiz Fetch Strategy

```java
// CyberbizFetchOrdersActionService.doAction()

int minute = requestTime.atZone(timezone).getMinute();

// Fast refresh: every 10 minutes — recent updates
if (minute % 10 >= 5) {
    adapter.fetchOrders(FetchOrdersRequest.builder()
        .channelId(channelId)
        .timeField("updated_at")
        .from(now.minusHours(1))
        .to(now)
        .build());
}

// Slow refresh: once per hour — catch cancelled/refunded
if (minute > 53) {
    adapter.fetchOrders(FetchOrdersRequest.builder()
        .channelId(channelId)
        .timeField("cancelled_at")
        .from(now.minusDays(3))
        .to(now)
        .build());

    adapter.fetchOrders(FetchOrdersRequest.builder()
        .channelId(channelId)
        .timeField("refund_at")
        .from(now.minusDays(7))
        .to(now)
        .build());
}
```

**Why simpler than other platforms:**
- No logistics type split (unlike momo)
- No mandatory status filter per API call (unlike Shopee)
- `updated_at` filter covers most scenarios
- Only need time-based splits for cancelled/refund catch-up

### 2.5 Cyberbiz Status Mapping

Cyberbiz natively provides 4-axis status, maps almost 1:1:

| Axis | Cyberbiz Value | → OMS Normalized |
|------|----------------|-------------------|
| **order** | open | confirmed |
| | closed | completed |
| | cancelled | cancelled |
| **financial** | pending | unpaid |
| | paid | paid |
| | cod | cod |
| | remitted | unpaid |
| | failed | failed |
| | pending_refund | partial_refunded |
| | pending_partial_refund | partial_refunded |
| | partial_refunded | partial_refunded |
| | refunded | refunded |
| **fulfillment** | unshipped | unfulfilled |
| | preparing | preparing |
| | partial | preparing |
| | fulfilled | shipped |
| | arrived | arrived |
| | received | delivered |
| | returned | returned |
| | expired | problem |
| | problem | problem |
| **return** | no_need | none |
| | request_return | requested |
| | returning | returning |
| | checking | checking |
| | refused | refused |
| | returned | returned |
| | partial_return | partial |
| | in_origin_cvs | returning |
| | in_hub | returning |
| | problem | problem |

### 2.6 Cyberbiz → ChannelOrder Field Mapping

```java
// CyberbizChannelAdapter.mapToChannelOrder(CyberbizOrderResponse resp)

ChannelOrder.builder()
    .channelOrderId(String.valueOf(resp.getId()))
    .channelOrderNumber(resp.getOrderNumber())      // human-readable "#1001"
    .rawStatus(Map.of(
        "order",      resp.getStatuses().getOrderStatus(),
        "financial",  resp.getStatuses().getFinancialStatus(),
        "fulfillment",resp.getStatuses().getFulfillmentStatus(),
        "return",     resp.getStatuses().getReturnStatus()
    ))
    .buyerName(resp.getReceiver().getName())
    .buyerPhone(resp.getReceiver().getPhone())
    .buyerEmail(resp.getBuyer().getEmail())
    .shippingAddress(resp.getReceiver().getAddress())
    .shippingMethod(resp.getShippingName())
    .paymentMethod(resp.getPaymentName())
    .totalAmount(resp.getPrices().getTotalPrice())
    .shippingFee(resp.getPrices().getShippingRatePrice())
    .discountAmount(calculateTotalDiscount(resp.getPrices().getDiscounts()))
    .channelCreatedAt(parseDateTime(resp.getCreatedAt()))
    .paidAt(null)  // Cyberbiz doesn't expose paid_at directly
    .shippedAt(parseShippedAt(resp.getFulfillments()))
    .items(resp.getLineItems().stream().map(li ->
        ChannelOrderItem.builder()
            .channelProductId(String.valueOf(li.getProductId()))
            .channelSpecId(String.valueOf(li.getProductVariantId()))
            .channelProductName(li.getTitle())
            .channelSpecName(li.getVariantTitle())
            .sku(li.getSku())
            .quantity(li.getQuantity())
            .unitPrice(li.getPrice())
            .subtotal(li.getTotalPriceAfterDiscounts())
            .build()
    ).toList())
    .build();
```

### 2.7 Cyberbiz Kafka & Docker

**New topics:**
```java
@Bean public NewTopic cyberbizFast() { return channelTopic("cyberbiz.fast"); }
@Bean public NewTopic cyberbizSlow() { return channelTopic("cyberbiz.slow"); }
```

**New Docker instances:**
```yaml
simpleec-channel-cyberbiz-fast:
  <<: *channel-job-template
  container_name: simpleec-channel-cyberbiz-fast
  environment:
    <<: *common-env
    JOB_CHANNEL_TOPICS: cyberbiz.fast
    JOB_CHANNEL_GROUP_ID: channel-job-cyberbiz-fast

simpleec-channel-cyberbiz-slow:
  <<: *channel-job-template
  container_name: simpleec-channel-cyberbiz-slow
  environment:
    <<: *common-env
    JOB_CHANNEL_TOPICS: cyberbiz.slow
    JOB_CHANNEL_GROUP_ID: channel-job-cyberbiz-slow
```

Total: 16 topics (was 14), 26 containers (was 24).

### 2.8 Cyberbiz vs Other Platforms — Comparison

| Dimension | Cyberbiz | Shopee | momo |
|-----------|----------|--------|------|
| **Auth** | HMAC-SHA256 (stateless) | OAuth 2.0 (refresh token) | API Key + Token |
| **Rate limit** | 5/sec | 10/sec (varies) | Unknown |
| **Order query** | Time-based, multi-axis filter | Must specify status per call | Must specify logistics type |
| **Status model** | 4-axis native | Single composite status | Numeric codes |
| **Pagination** | page + per_page | cursor-based | page-based |
| **Fetch complexity** | Low | Medium | High (logistics split) |
| **Webhook** | Supported (optional) | Required for some events | Limited |

---

## Part 3: Status Mapping Admin UI & API

### 3.1 Overview

Non-RD staff (e.g. operations, PM) need to:
1. **View** current status mappings per platform — understand what each platform status means in OMS
2. **Edit** mappings — when a platform adds/changes status values
3. **Refresh cache** — make changes take effect immediately without redeployment

### 3.2 API Endpoints (simpleec-api)

```
GET    /api/v1/status-mappings?platform={code}
       → List all mappings, optionally filtered by platform
       → Response: { data: [ { id, platformCode, statusAxis, platformValue, normalizedValue, description } ] }

GET    /api/v1/status-mappings/platforms
       → List all platforms that have mappings configured
       → Response: { data: ["cyberbiz", "shopee", "momo", "yahoo", "pchome"] }

GET    /api/v1/status-mappings/normalized-values?axis={axis}
       → List valid OMS normalized values for a given axis (for dropdown)
       → Response: { data: ["unpaid", "paid", "cod", "partial_refunded", "refunded", "failed"] }

POST   /api/v1/status-mappings
       → Create a new mapping
       → Body: { platformCode, statusAxis, platformValue, normalizedValue, description }

PUT    /api/v1/status-mappings/{id}
       → Update an existing mapping
       → Body: { normalizedValue, description }
       → Note: platformCode + statusAxis + platformValue are immutable after creation

DELETE /api/v1/status-mappings/{id}
       → Delete a mapping (rarely used — usually just update normalizedValue)

POST   /api/v1/status-mappings/refresh-cache
       → Reload all mappings from DB into Redis cache
       → Response: { refreshed: 156, platforms: ["cyberbiz","shopee","momo","yahoo","pchome"] }
```

### 3.3 Cache Refresh Flow

```
Admin clicks "Refresh Cache" in UI
    │
    ▼
POST /api/v1/status-mappings/refresh-cache
    │
    ▼
StatusMappingService.refreshCache()
    │
    ├── SELECT * FROM status_mapping
    ├── Group by (platform_code, status_axis)
    ├── For each group:
    │     Redis DEL  status_mapping:{platform}:{axis}
    │     Redis HSET status_mapping:{platform}:{axis}
    │                {platform_value_1} → {normalized_value_1}
    │                {platform_value_2} → {normalized_value_2}
    │                ...
    └── Return count + platform list
```

**Cache key format:**
```
status_mapping:cyberbiz:order       → {"open":"confirmed", "closed":"completed", "cancelled":"cancelled"}
status_mapping:cyberbiz:financial   → {"pending":"unpaid", "paid":"paid", "refunded":"refunded", ...}
status_mapping:shopee:order         → {"UNPAID":"pending", "READY_TO_SHIP":"confirmed", ...}
status_mapping:shopee:financial     → {"UNPAID":"unpaid", "READY_TO_SHIP":"paid", ...}
```

### 3.4 UI Design (Frontend)

**Route:** `/admin/status-mappings`

**Layout:**

```
┌──────────────────────────────────────────────────────────────┐
│  Status Mapping Management                    [Refresh Cache]│
├──────────────────────────────────────────────────────────────┤
│  Platform: [All ▾] [cyberbiz ▾] [shopee ▾] [momo ▾] ...    │
│  Axis:     [All ▾] [order ▾] [financial ▾] [fulfillment ▾]  │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─ cyberbiz ─────────────────────────────────────────────┐  │
│  │                                                         │  │
│  │  order                                                  │  │
│  │  ┌──────────────┬──────────────┬────────────┐          │  │
│  │  │ Platform     │ → OMS        │ Description│          │  │
│  │  ├──────────────┼──────────────┼────────────┤          │  │
│  │  │ open         │ confirmed  ▾ │ 已開啟      │  [Edit]  │  │
│  │  │ closed       │ completed  ▾ │ 已結案      │  [Edit]  │  │
│  │  │ cancelled    │ cancelled  ▾ │ 已取消      │  [Edit]  │  │
│  │  └──────────────┴──────────────┴────────────┘          │  │
│  │                                                         │  │
│  │  financial                                              │  │
│  │  ┌──────────────┬──────────────┬────────────┐          │  │
│  │  │ pending      │ unpaid     ▾ │ 等待付款    │  [Edit]  │  │
│  │  │ paid         │ paid       ▾ │ 已收到款項  │  [Edit]  │  │
│  │  │ cod          │ cod        ▾ │ 貨到付款    │  [Edit]  │  │
│  │  │ refunded     │ refunded   ▾ │ 已退款      │  [Edit]  │  │
│  │  │ ...          │              │             │          │  │
│  │  └──────────────┴──────────────┴────────────┘          │  │
│  │                                               [+ Add]   │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌─ shopee ───────────────────────────────────────────────┐  │
│  │  order                                                  │  │
│  │  ┌──────────────┬──────────────┬────────────┐          │  │
│  │  │ UNPAID       │ pending    ▾ │ 未付款      │  [Edit]  │  │
│  │  │ READY_TO_SHIP│ confirmed  ▾ │ 已付款待出貨│  [Edit]  │  │
│  │  │ ...          │              │             │          │  │
│  │  └──────────────┴──────────────┴────────────┘          │  │
│  │                                                         │  │
│  │  financial  (same platform_value maps to different axis) │  │
│  │  ┌──────────────┬──────────────┬────────────┐          │  │
│  │  │ UNPAID       │ unpaid     ▾ │ 未付款      │  [Edit]  │  │
│  │  │ READY_TO_SHIP│ paid       ▾ │ 已付款      │  [Edit]  │  │
│  │  │ ...          │              │             │          │  │
│  │  └──────────────┴──────────────┴────────────┘          │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                              │
│  Last cache refresh: 2026-02-13 14:30:00 (2 minutes ago)     │
└──────────────────────────────────────────────────────────────┘
```

**Key interactions:**

1. **View**: Grouped by platform → axis → mapping rows. Filter by platform/axis dropdowns.
2. **Edit**: Click [Edit] → normalized value becomes dropdown (options from `/normalized-values?axis=`), description becomes text input → Save → auto-refreshes display.
3. **Add**: Click [+ Add] at bottom of platform section → new row with platform_value text input + normalized_value dropdown + description → Save.
4. **Refresh Cache**: Top-right button → POST refresh-cache → toast shows "Refreshed 156 mappings for 5 platforms" → updates "Last cache refresh" timestamp.

### 3.5 Safety Considerations

- **No delete button in default view** — accidentally deleting a mapping causes orders to map to "unknown" status. Delete available only through a confirmation modal: "This will cause platform status '{value}' to be unmappable. Are you sure?"
- **Unknown status alert** — When `CachedStatusMapper.resolve()` finds no mapping, it returns `"unknown"` and logs a WARN. A scheduled job (or dashboard) can surface unmapped statuses so operations can add new mappings.
- **Audit trail** — `status_mapping` table has `updated_at`. Future enhancement: add `updated_by` for accountability.
- **Validation** — API validates `normalizedValue` against known enum values before saving. Cannot save arbitrary values.

---

## Implementation Priority

This design affects these modules:

| Module | Change | Priority |
|--------|--------|----------|
| `simpleec-common` | Add status enums, ChannelType.CYBERBIZ | P0 |
| `simpleec-channel` | Revise ChannelAdapter interface, add ChannelOrder/FetchOrdersRequest DTOs | P0 |
| `simpleec-core` | Add Order entity fields, status_mapping entity/mapper/service | P0 |
| Schema | ALTER orders + CREATE status_mapping | P0 |
| `simpleec-api` | Status mapping CRUD endpoints + cache refresh endpoint | P1 |
| `simpleec-channel-job` | StatusMapper service, cache loader | P1 |
| `simpleec-order-job` | Use NormalizedStatus in upsert logic | P1 |
| Admin UI (frontend) | Status mapping management page: view/edit/add + refresh cache button | P1 |
| `simpleec-channel` (cyberbiz) | CyberbizChannelAdapter implementation | P2 |
| `simpleec-channel-job` (cyberbiz) | CyberbizFetchOrdersActionService | P2 |
| Kafka + Docker | cyberbiz.fast/slow topics, 2 new containers | P2 |

---

## Part 4: Complete Sync Surface — Beyond Orders

### 4.1 All ActionTypes and Their Sync Design

The system defines 18 actions in `ActionType.java`. Each action follows the same 3-layer pattern:

```
SchedulerJob or Frontend trigger
    → {platform}.fast or {platform}.slow topic
        → ChannelJob (ActionService 4-step lifecycle)
            → ChannelAdapter method call
```

Grouped by domain:

#### A. Order Sync (slow topic)

| Action | Adapter Method | DTO | Direction | Cyberbiz API |
|--------|---------------|-----|-----------|--------------|
| `FETCH_ORDERS` | `fetchOrders(FetchOrdersRequest)` | `ChannelOrder` | Platform → OMS | `GET /v1/orders` |
| `FETCH_REFUND_ORDERS` | `fetchRefunds(FetchRefundsRequest)` | `ChannelRefund` | Platform → OMS | `GET /v1/orders` (filter by return_statuses) |

**Refund sync design:**

Cyberbiz doesn't have a separate refund API. Refunds are detected through:
1. `financial_status` changing to `refunded` / `partial_refunded` / `pending_refund`
2. `return_status` changing to `request_return` / `returning` / `returned`

```java
// CyberbizFetchRefundOrdersActionService.doAction()
// Strategy: query orders with refund-related statuses
adapter.fetchOrders(FetchOrdersRequest.builder()
    .channelId(channelId)
    .timeField("refund_at")
    .from(now.minusDays(7))
    .to(now)
    .build());

// Also catch return requests
adapter.fetchOrders(FetchOrdersRequest.builder()
    .channelId(channelId)
    .extra(Map.of("return_statuses", "request_return,returning,returned"))
    .timeField("updated_at")
    .from(now.minusDays(3))
    .to(now)
    .build());
```

**ChannelRefund DTO:**

```java
@Data @Builder
public class ChannelRefund {
    private String channelOrderId;          // which order
    private String channelRefundId;         // platform's refund ID (if exists)
    private String refundStatus;            // platform raw status
    private String returnReason;            // platform raw reason (buyer_cancel, defective, wrong_item, etc.)
    private BigDecimal refundAmount;
    private String reason;                  // free-text description from buyer/platform
    private List<ChannelRefundItem> items;  // which items refunded
    private LocalDateTime refundedAt;
}

@Data @Builder
public class ChannelRefundItem {
    private String channelProductId;
    private String channelSpecId;
    private String sku;
    private Integer quantity;
    private BigDecimal refundAmount;
}
```

**Refund processing flow:**

```
ChannelJob (FETCH_REFUND_ORDERS)
    → detect refund via status change or refund_at timestamp
    → send to order.process: { action: "PROCESS_REFUND", channelRefund: {...} }

OrderProcessJob
    → UPSERT refund_orders table
    → UPDATE orders SET refund_amount += X, has_refund = true
    → UPDATE orders status fields (financial_status, return_status)
    → INSERT order_status_logs
```

**return_status complete enum:**

| Value | Description | Scenario |
|-------|-------------|----------|
| `none` | No return needed | Normal order |
| `requested` | Buyer requested return | Buyer initiated |
| `returning` | Return in transit | Buyer shipped back, in transit / at CVS / in hub |
| `received` | Seller received returned goods | Pending inspection |
| `checking` | Return under review | Seller inspecting goods |
| `refused` | Return rejected | Seller refused, goods sent back to buyer |
| `returned` | Return completed | Full return completed |
| `partial` | Partial return completed | Some items returned |
| `cancelled` | Return request cancelled | Buyer withdrew return request |
| `problem` | Return abnormal | Lost in transit, damaged, logistics error |

**refund_orders schema changes:**

```sql
-- Add return_reason column to refund_orders
ALTER TABLE public.refund_orders
    ADD COLUMN return_reason VARCHAR(50),     -- platform raw reason code (e.g. "buyer_cancel", "defective")
    ADD COLUMN return_reason_text TEXT;        -- free-text reason from buyer/platform
```

This separates:
- `return_reason` — structured code for analytics/filtering (e.g. `defective`, `wrong_item`, `buyer_cancel`, `not_as_described`)
- `return_reason_text` — buyer's free-text explanation
- `reason` (existing) — internal note or summary

**Platform return reason examples:**

| Platform | Return Reason Codes |
|----------|-------------------|
| Cyberbiz | Free-text only (no structured codes) |
| Shopee | `NON_RECEIPT`, `WRONG_ITEM`, `PRODUCT_QUALITY`, `CHANGE_OF_MIND`, etc. |
| momo | Numeric codes mapped to reasons |

**Platform differences for refund fetch:**

| Platform | Refund API | Strategy |
|----------|-----------|----------|
| Cyberbiz | No separate API — detect from order status changes | Query refund_at + return_statuses filter |
| Shopee | `GET /api/v2/order/get_order_list` with status=`IN_CANCEL`/`CANCELLED` + returns API | Separate returns API call |
| momo | Refund embedded in order status changes | Query by status code |
| Yahoo | Separate return request API | Independent fetch |

### 4.1.1 Status Completeness & Practical Realities

#### Problem 1: Not all platforms have all statuses

Some platforms have fewer status granularity. The OMS must handle "status not available from platform":

| return_status | Cyberbiz | Shopee | momo | Yahoo |
|---------------|----------|--------|------|-------|
| none | yes | yes | yes | yes |
| requested | yes | yes | yes | yes |
| returning | yes | yes | yes | yes |
| received | **no** | yes | **no** | **no** |
| checking | yes | **no** | **no** | yes |
| refused | yes | yes | yes | **no** |
| returned | yes | yes | yes | yes |
| partial | yes | **no** | yes | **no** |
| cancelled | **no** | yes | **no** | **no** |
| problem | yes | **no** | **no** | **no** |

**Design rule:** When a platform skips a status (e.g. jumps from `requested` → `returned` without `returning`/`received`/`checking`), **OMS accepts it as-is**. We are the passive sync party — we don't invent intermediate states. The `platform_status` JSONB preserves whatever the platform actually sent. StatusMapper only maps what the platform provides.

**Unmapped status handling:**
- StatusMapper returns the previous OMS status (no change) if a platform status value has no mapping
- Logs WARN with `unmapped_status` tag for monitoring
- Admin can add new mappings via UI without redeployment

#### Problem 2: API may not capture all state transitions

Platforms may:
- Skip intermediate states (e.g. `UNPAID` → `COMPLETED`, skipping `READY_TO_SHIP` and `SHIPPED`)
- Delay status updates in their API (eventual consistency)
- Not expose certain transitions via API at all (only visible in platform admin UI)

**Design rule:** OMS does NOT validate state transitions. Any status change from platform is accepted:

```java
// OrderProcessJob — NO state machine validation
// Old: if (canTransition(oldStatus, newStatus)) { ... }
// New: always accept
if (!oldStatus.equals(newStatus)) {
    order.setOrderStatus(newStatus);
    insertStatusLog(orderId, axis, oldStatus, newStatus, source="platform_sync");
}
```

`order_status_logs` records every transition for audit trail, even if it looks "impossible".

#### Problem 3: Late returns (beyond normal fetch window)

A buyer may return an order 30-90 days after purchase. Regular fetch windows (1hr/3d/7d) won't catch these.

**Solution: Multi-tier fetch strategy for refunds:**

```java
// FetchRefundOrdersActionService.doAction()

int minute = requestTime.getMinute();
int dayOfWeek = requestTime.getDayOfWeek().getValue();

// Tier 1: Fast — every 10 minutes, last 3 days
if (minute % 10 >= 5) {
    fetchRefunds(now - 3d, now);
}

// Tier 2: Daily — once per day, last 30 days
if (minute > 53 && hour == 3) {  // 3 AM
    fetchRefunds(now - 30d, now - 3d);
}

// Tier 3: Weekly — once per week, last 90 days
if (dayOfWeek == 1 && hour == 4) {  // Monday 4 AM
    fetchRefunds(now - 90d, now - 30d);
}
```

**Why 3 tiers?**
- Tier 1: Catch fresh returns quickly (customer service needs fast response)
- Tier 2: Catch returns within the typical return policy window (7-30 days)
- Tier 3: Catch late returns, disputes, platform-initiated refunds (30-90 days)

**Rate limit awareness:** Tier 2 and 3 cover large time windows that may return many results. Adapters must handle pagination properly and respect platform rate limits (e.g. Cyberbiz 5 req/sec).

**Hash dedup handles duplicates:** Since the same order may be re-fetched across tiers, Redis hash dedup ensures no duplicate processing. Only status changes trigger updates.

#### Summary: Defensive Design Principles

1. **Accept any status** — no state machine validation on sync
2. **Map what you can** — unmapped values fall through gracefully with WARN log
3. **Keep raw status** — `platform_status` JSONB is the source of truth for platform communication
4. **Multi-tier time windows** — fast (3d), daily (30d), weekly (90d) for late returns
5. **Idempotent processing** — hash dedup + upsert = safe to re-fetch overlapping windows
6. **Monitor gaps** — dashboard for `unmapped_status` WARNs, so ops can react without RD

#### B. Order Actions (fast topic)

| Action | Adapter Method | Direction | Cyberbiz API |
|--------|---------------|-----------|--------------|
| `SHIPPING_CONFIRMED` | `confirmShipment(channelId, orderId, tracking, company)` | OMS → Platform | `POST /v1/orders/{id}/fulfillments` |
| `ORDER_CANCELED` | `acceptCancellation(channelId, orderId)` | OMS → Platform | `PUT /v1/orders/{id}/update_status` (status=closed) |
| `CHANGE_ORDER_STATUS` | `updateOrderStatus(channelId, orderId, status)` | OMS → Platform | `PUT /v1/orders/{id}/update_status` |
| `GET_SHIP_CODE` | `getShippingLabel(channelId, orderId)` | Platform → OMS | N/A (Cyberbiz uses external logistics) |
| `DOWNLOAD_SHIP_DOCUMENT` | `downloadShipDocument(channelId, orderId)` | Platform → OMS | N/A |
| `ACCEPT_BUYER_CANCELLATION` | `acceptCancellation(channelId, orderId)` | OMS → Platform | Same as ORDER_CANCELED |
| `REJECT_BUYER_CANCELLATION` | `rejectCancellation(channelId, orderId)` | OMS → Platform | N/A (Cyberbiz doesn't have this) |

**Not all actions apply to all platforms.** Adapter returns `UnsupportedOperationException` or no-op for actions the platform doesn't support.

#### C. Product Sync

| Action | Topic | Adapter Method | DTO | Direction | Cyberbiz API |
|--------|-------|---------------|-----|-----------|--------------|
| `FETCH_PRODUCTS` (list+diff) | `{platform}.fast` | `fetchProductList(request)` | `ChannelProductRef` | Platform → OMS | `GET /v1/products` (paginated) |
| `FETCH_PRODUCT_DETAIL` | `{platform}.slow` | `fetchProductDetail(channelId, productId)` | `ChannelProduct` | Platform → OMS | `GET /v1/products/{id}` |

> **Why FETCH_PRODUCTS on fast?** List + diff is fast (one paginated API call + Redis SET comparison). User expects immediate acknowledgment ("新增 5、移除 2"). Detail fetches go to `{platform}.slow` in the background.
>
> **Yahoo exception:** Yahoo has no detail API. The list API is async (request + webhook callback with CSV). The webhook handler performs diff + routing directly to `task.backend`, skipping detail fetches entirely. See §5.4 Yahoo Special Flow.

**Product DTOs:**

```java
@Data @Builder
public class ChannelProductRef {
    private String channelProductId;    // platform product ID
    private String title;               // quick reference, no details
    private List<ChannelVariantRef> variants;  // variant/spec IDs for diff sync
}

@Data @Builder
public class ChannelVariantRef {
    private String channelVariantId;    // platform variant/spec ID — the diff comparison unit
}

@Data @Builder
public class ChannelProduct {
    private String channelProductId;
    private String title;
    private String vendor;
    private BigDecimal price;           // lowest variant price
    private String productUrl;
    private boolean published;
    private List<ChannelProductVariant> variants;
}

@Data @Builder
public class ChannelProductVariant {
    private String channelVariantId;    // platform variant/spec ID
    private String variantName;         // e.g. "60ml x 12入"
    private String sku;
    private BigDecimal price;
    private BigDecimal cost;
    private Integer inventoryQuantity;
    private String option1;             // e.g. "Red"
    private String option2;             // e.g. "Large"
    private String option3;
}
```

**Cyberbiz product fetch mapping:**

```
Cyberbiz ProductsEntity       → ChannelProductRef
  id                          → channelProductId
  title                       → title

Cyberbiz ProductEntity        → ChannelProduct
  id                          → channelProductId
  title                       → title
  vendor                      → vendor
  price                       → price
  product_url                 → productUrl
  published                   → published
  product_variants[]          → variants[]
    product_variant_id        → channelVariantId
    name (option1+option2)    → variantName
    sku                       → sku
    price                     → price
    cost                      → cost
    inventory_quantity        → inventoryQuantity
```

#### D. Product Actions (fast topic)

| Action | Adapter Method | Direction | Cyberbiz API |
|--------|---------------|-----------|--------------|
| `NEW_SELL_PACK` | `createListing(channelId, sellPack, extra)` | OMS → Platform | `POST /v1/products` + variants |
| `MODIFY_CONTENT` | `updateListing(channelId, sellPack, extra)` | OMS → Platform | `PUT /v1/products/{id}` |
| `MODIFY_PRICE` | `updatePrice(channelId, productId, price)` | OMS → Platform | `PUT /v1/products/{id}/product_variants/{vid}` |
| `MODIFY_QUANTITY` | `updateQuantity(channelId, productId, qty)` | OMS → Platform | `PUT /v1/products/{id}/product_variants/{vid}` |
| `START_SELLING` | `startSelling(channelId, productId)` | OMS → Platform | `PUT /v1/products/{id}` (published=true) |
| `STOP_SELLING` | `stopSelling(channelId, productId)` | OMS → Platform | `PUT /v1/products/{id}` (published=false) |
| `CHECK_LAUNCH_STATUS` | `checkLaunchStatus(channelId, productId)` | Platform → OMS | `GET /v1/products/{id}` (check published) |
| `GET_QUANTITY` | `getQuantity(channelId, productId)` | Platform → OMS | `GET /v1/products/{id}/product_variants` |

#### E. Info Sync (slow topic)

| Action | Adapter Method | Direction | Cyberbiz API |
|--------|---------------|-----------|--------------|
| `SYNC_CATEGORIES` | `syncCategories(channelId)` | Platform → OMS | `GET /v1/custom_collections` |
| `SYNC_BRANDS` | `syncBrands(channelId)` | Platform → OMS | N/A (Cyberbiz uses `vendor` field) |
| `GET_STATIC_INFO` | `getStaticInfo(channelId)` | Platform → OMS | `GET /v1/branch_stores` etc. |

#### F. Health Check (fast topic)

| Action | Adapter Method | Direction | Cyberbiz API |
|--------|---------------|-----------|--------------|
| `CHECK_HEALTH` | `validateConnection(credentials)` | Platform → OMS | Any lightweight GET call |

**Already implemented** (skeleton in `CheckHealthActionService.java`).

Cyberbiz health check: try `GET /v1/products?page=1&per_page=1` with HMAC auth. If 200 → OK, else → API_DOWN.

### 4.2 Revised ChannelAdapter Interface (Complete)

```java
public interface ChannelAdapter {

    ChannelType getChannelType();

    // === Connection ===
    boolean validateConnection(Map<String, String> credentials);

    // === Product Fetch (slow) ===
    List<ChannelProductRef> fetchProductList(FetchProductsRequest request);
    ChannelProduct fetchProductDetail(String channelId, String channelProductId);

    // === Product Actions (fast) ===
    String createListing(String channelId, SellPack sellPack, Map<String, Object> extraData);
    void updateListing(String channelId, SellPack sellPack, Map<String, Object> extraData);
    void updatePrice(String channelId, String channelProductId, BigDecimal price);
    void updateQuantity(String channelId, String channelProductId, int quantity);
    void startSelling(String channelId, String channelProductId);
    void stopSelling(String channelId, String channelProductId);
    String checkLaunchStatus(String channelId, String channelProductId);
    int getQuantity(String channelId, String channelProductId);

    // === Order Fetch (slow) ===
    List<ChannelOrder> fetchOrders(FetchOrdersRequest request);
    List<ChannelRefund> fetchRefunds(FetchRefundsRequest request);

    // === Order Actions (fast) ===
    void confirmShipment(String channelId, String channelOrderId,
                         String trackingNumber, String logisticsCompany);
    void updateOrderStatus(String channelId, String channelOrderId, String status);
    void acceptCancellation(String channelId, String channelOrderId);
    void rejectCancellation(String channelId, String channelOrderId);
    String getShippingLabel(String channelId, String channelOrderId);
    byte[] downloadShipDocument(String channelId, String channelOrderId);

    // === Info Sync (slow) ===
    List<ChannelCategory> syncCategories(String channelId);
    List<ChannelBrand> syncBrands(String channelId);
    Map<String, Object> getStaticInfo(String channelId);
}
```

**Default method pattern for unsupported operations:**

```java
// Platforms that don't support an operation can use default methods
default void rejectCancellation(String channelId, String channelOrderId) {
    throw new UnsupportedOperationException(
        getChannelType().getCode() + " does not support rejectCancellation");
}

default byte[] downloadShipDocument(String channelId, String channelOrderId) {
    throw new UnsupportedOperationException(
        getChannelType().getCode() + " does not support downloadShipDocument");
}
```

### 4.3 FetchProductsRequest & FetchRefundsRequest DTOs

```java
@Data @Builder
public class FetchProductsRequest {
    private String channelId;
    private Integer page;
    private Integer pageSize;
    private Map<String, Object> extra;  // platform-specific filters
}

@Data @Builder
public class FetchRefundsRequest {
    private String channelId;
    private LocalDateTime from;
    private LocalDateTime to;
    private String timeField;           // "refund_at", "updated_at"
    private String refundStatus;        // platform-native filter
    private Integer page;
    private Integer pageSize;
    private Map<String, Object> extra;
}
```

### 4.4 Action ↔ Topic ↔ Adapter Summary

```
                         ┌─────────────────────────────────────────────┐
                         │              {platform}.slow                 │
                         │                                             │
                         │  FETCH_ORDERS ──→ fetchOrders()             │
                         │  FETCH_REFUND_ORDERS ──→ fetchRefunds()     │
                         │  FETCH_PRODUCT_DETAIL                       │
                         │    ──→ fetchProductDetail()                  │
                         │    key = channelId + suffix(0..N)            │
                         │  SYNC_CATEGORIES ──→ syncCategories()       │
                         │  SYNC_BRANDS ──→ syncBrands()               │
                         │  GET_STATIC_INFO ──→ getStaticInfo()        │
                         │  FETCH_STATISTICS ──→ (future)              │
                         └─────────────────────────────────────────────┘

                         ┌─────────────────────────────────────────────┐
                         │              {platform}.fast                 │
                         │                                             │
                         │  FETCH_PRODUCTS ──→ fetchProductList()      │
                         │    (list+diff only, fast — user sees result) │
                         │  SHIPPING_CONFIRMED ──→ confirmShipment()   │
                         │  ORDER_CANCELED ──→ acceptCancellation()    │
                         │  CHANGE_ORDER_STATUS ──→ updateOrderStatus()│
                         │  GET_SHIP_CODE ──→ getShippingLabel()       │
                         │  DOWNLOAD_SHIP_DOC ──→ downloadShipDoc()    │
                         │  ACCEPT_BUYER_CANCEL ──→ acceptCancellation│
                         │  REJECT_BUYER_CANCEL ──→ rejectCancellation│
                         │  NEW_SELL_PACK ──→ createListing()          │
                         │  MODIFY_CONTENT ──→ updateListing()         │
                         │  MODIFY_PRICE ──→ updatePrice()             │
                         │  MODIFY_QUANTITY ──→ updateQuantity()       │
                         │  START_SELLING ──→ startSelling()           │
                         │  STOP_SELLING ──→ stopSelling()             │
                         │  CHECK_LAUNCH_STATUS ──→ checkLaunchStatus()│
                         │  GET_QUANTITY ──→ getQuantity()              │
                         │  CHECK_HEALTH ──→ validateConnection()      │
                         └─────────────────────────────────────────────┘
```

### 4.5 Cyberbiz Completeness Matrix

| Action | Cyberbiz Support | Notes |
|--------|-----------------|-------|
| FETCH_ORDERS | Yes | `GET /v1/orders` with rich filters |
| FETCH_REFUND_ORDERS | Partial | No separate API; detect via order status + refund_at |
| FETCH_PRODUCTS | Yes | `GET /v1/products` (list) + `GET /v1/products/{id}` (detail) |
| SHIPPING_CONFIRMED | Yes | `POST /v1/orders/{id}/fulfillments` |
| ORDER_CANCELED | Yes | `PUT /v1/orders/{id}/update_status` |
| CHANGE_ORDER_STATUS | Yes | `PUT /v1/orders/{id}/update_status` |
| GET_SHIP_CODE | No | Uses external logistics (綠界 etc.) |
| DOWNLOAD_SHIP_DOCUMENT | No | N/A |
| ACCEPT_BUYER_CANCELLATION | Yes | Same as ORDER_CANCELED |
| REJECT_BUYER_CANCELLATION | No | Cyberbiz doesn't have this concept |
| NEW_SELL_PACK | Yes | `POST /v1/products` |
| MODIFY_CONTENT | Yes | `PUT /v1/products/{id}` |
| MODIFY_PRICE | Yes | `PUT /v1/products/{id}/product_variants/{vid}` |
| MODIFY_QUANTITY | Yes | `PUT /v1/products/{id}/product_variants/{vid}` |
| START_SELLING | Yes | `PUT /v1/products/{id}` (published=true) |
| STOP_SELLING | Yes | `PUT /v1/products/{id}` (published=false) |
| CHECK_LAUNCH_STATUS | Yes | `GET /v1/products/{id}` check published field |
| GET_QUANTITY | Yes | `GET /v1/products/{id}/product_variants` |
| CHECK_HEALTH | Yes | Any lightweight authenticated GET |
| SYNC_CATEGORIES | Yes | `GET /v1/custom_collections` |
| SYNC_BRANDS | Partial | Cyberbiz uses `vendor` field, no brand API |
| GET_STATIC_INFO | Yes | `GET /v1/branch_stores` etc. |

---

## Part 5: Product Differential Sync

### 5.1 Problem Statement

Current `FETCH_PRODUCTS` design (in `FETCH_PRODUCTS.md`) does a **full sync** every time: list all products → fetch detail for every product → upsert all sell_packs. For a merchant with 3000 products:

| Problem | Impact |
|---------|--------|
| 3000 detail API calls per sync | Wastes platform API quota, risks rate limit lockout |
| All detail fetches inside one ChannelJob worker | Worker timeout (75+ seconds), blocks queue |
| No change detection | 95% of fetches are redundant (products haven't changed) |
| No deletion detection | Products removed from platform are invisible to OMS |
| Single message = 300+ sequential API calls | Violates ACID principle (one message should = one action) |

### 5.2 Trigger Mode Summary

| Sync Type | Trigger | Topic | Who Initiates |
|-----------|---------|-------|--------------|
| **Product sync** (`FETCH_PRODUCTS`) | **Manual only** — customer clicks per channel | `{platform}.fast` | Customer (逐通路手動觸發) |
| **Product detail** (`FETCH_PRODUCT_DETAIL`) | Automated — emitted by FETCH_PRODUCTS | `{platform}.slow` | System (由 FETCH_PRODUCTS 發散) |
| **Order sync** (`FETCH_ORDERS`) | **Automatic scheduler** — every 5-10 minutes | `{platform}.slow` | SchedulerJob (排程自動) |
| **Refund sync** (`FETCH_REFUND_ORDERS`) | **Automatic scheduler** — multi-tier (3d/30d/90d) | `{platform}.slow` | SchedulerJob (排程自動) |

**Design rationale:**
- **Product sync = manual**: Prevents system from simultaneously hitting multiple platform APIs. Customer controls when and which channel to sync, reducing pressure on both our system and platform APIs.
- **Order/refund sync = automatic**: Orders and refunds are time-sensitive — customers need near-real-time visibility. SchedulerJob ensures continuous sync without manual intervention.

### 5.3 Design Principles

1. **Structural diff, not data diff** — sync is about which products/specs **exist** on the platform, not about price/quantity changes
2. **One message = one action (ACID)** — each Kafka message triggers exactly one API call and one DB write
3. **Shared topic with partition key isolation** — product detail fetches share `{platform}.slow` with orders; partition key throttle (channelId+suffix) isolates detail traffic to specific partitions, leaving other partitions free for orders
4. **Partition key throttle** — use the existing `channelId + configurable suffix` pattern to control parallelism per channel
5. **Soft delete with distinguishable status** — products removed from platform get `delisted` status (not `inactive`) so UI can show the source of deletion

### 5.4 Differential Sync Architecture

#### Comparison Unit

The diff is based on **Set comparison** of `channelProductId:channelVariantId` pairs (structural existence).

**What triggers sync:** A new spec appears or an existing spec disappears from the platform.
**What does NOT trigger sync:** Price, quantity, title, or other data changes.

#### Redis Snapshot

```
Key:    product_snapshot:{channelId}
Type:   Redis SET
Values: "PROD-1:SPEC-A", "PROD-1:SPEC-B", "PROD-2:", "PROD-3:SPEC-X", ...

Note: single-spec products use "PROD-2:" (empty variant suffix)
TTL:   none (persistent, overwritten each sync)
```

#### Three-Tier Message Flow (General Platforms)

Applies to: Cyberbiz, Shopee, momo, PChome — platforms with separate list + detail APIs.

```
FETCH_PRODUCTS on {platform}.fast (list + diff, fast response to user)
    │
    │  ① adapter.fetchProductList(request)
    │     → List<ChannelProductRef> (with variants)
    │     → paginated internally by adapter
    │
    │  ② Build currentSet = Set<"PROD-1:SPEC-A", "PROD-1:SPEC-B", ...>
    │     for each ref in productRefs:
    │       if ref.variants is empty:
    │         add "channelProductId:"               // single-spec
    │       else:
    │         for each variant:
    │           add "channelProductId:variantId"     // multi-spec
    │
    │  ③ Read previousSet from Redis: product_snapshot:{channelId}
    │     (cold start: previousSet = empty → all specs are "new")
    │
    │  ④ Set diff:
    │     added   = currentSet - previousSet   // new specs on platform
    │     removed = previousSet - currentSet   // specs disappeared from platform
    │
    │  ⑤a For added specs:
    │     Dedup by channelProductId (1 product may have N new specs,
    │       but 1 detail API call returns ALL variants)
    │     For each unique channelProductId:
    │       send FETCH_PRODUCT_DETAIL to {platform}.slow topic
    │         key = channelId + suffix(round-robin 0..N)
    │         payload = { channelId, channelProductId, merchantId, syncLogId }
    │
    │  ⑤b For removed specs:
    │     For each removed "channelProductId:channelVariantId":
    │       send DELIST_SELL_PACK to task.backend
    │         key = channelId:channelProductId:channelVariantId
    │         payload = { channelId, channelProductId, channelSpecId, syncLogId }
    │
    │  ⑥ Overwrite Redis snapshot: product_snapshot:{channelId} = currentSet
    │
    │  ⑦ Write channel_sync_logs:
    │     sync_type='FETCH_PRODUCTS', status='processing',
    │     added=5, removed=2, unchanged=2993,
    │     detail_total=5, detail_completed=0
    │     ★ User sees this immediately — "受理完成，新增5筆處理中"
    │
    │  ⑧ Redis counter for progress tracking:
    │     SET product_sync_progress:{syncLogId} = { total: 5, completed: 0 }
    │
    ▼
FETCH_PRODUCT_DETAIL on {platform}.slow topic (one message per product)
    │
    │  Each message = 1 API call (ACID)
    │  rateLimiter.acquire()  // simple sleep-based, per partition
    │
    │  adapter.fetchProductDetail(channelId, channelProductId)
    │     → ChannelProduct (with all variants)
    │
    │  For each variant (or the product itself if single-spec):
    │    determine SKU, query productService.findByMerchantAndSku()
    │    → send to task.backend:
    │        CREATE_PRODUCT (if no matching product)
    │        or CREATE_SELL_PACK (if product exists)
    │
    │  After successful processing:
    │    Redis INCR product_sync_progress:{syncLogId}.completed
    │    if completed == total:
    │      → update channel_sync_logs SET status='success'
    │      → send notification to user (see §5.8)
    │
    ▼
task.backend (existing flow, unchanged)
    │
    │  CREATE_PRODUCT → BackendJob → insert product → routeNext CREATE_SELL_PACK
    │  CREATE_SELL_PACK → BackendJob → upsert sell_pack
    │  DELIST_SELL_PACK → BackendJob → update sell_pack status = 'delisted'
    │
    ▼
  Done
```

#### Yahoo Special Flow (No Detail API)

Yahoo has no `fetchProductDetail()` API. The list API is async: request → Yahoo processes → webhook callback with CSV containing **full product data**.

```
FETCH_PRODUCTS on yahoo.fast
    │
    │  ① adapter.fetchProductList(request)
    │     → Yahoo: send API request with callbackUrl = /webhook/yahoo/{merchantId}
    │     → returns immediately (async — no product data yet)
    │
    │  ② Write channel_sync_logs:
    │     sync_type='FETCH_PRODUCTS', status='waiting_callback'
    │     ★ User sees: "已送出同步請求，等待Yahoo回傳"
    │
    ▼
  (Yahoo processes asynchronously, then calls our webhook)
    │
    ▼
Webhook: POST /webhook/yahoo/{merchantId}
    │  Body: CSV file with full product data (product + specs + prices + inventory)
    │
    │  WebhookController:
    │    ① Parse CSV → List<ChannelProduct> (complete data, not just refs)
    │
    │    ② Build currentSet from parsed products (same Set<productId:variantId>)
    │
    │    ③ Read previousSet from Redis: product_snapshot:{channelId}
    │
    │    ④ Set diff: added / removed
    │
    │    ⑤a For added products (already have full data from CSV!):
    │       No FETCH_PRODUCT_DETAIL needed — skip detail fetch step entirely
    │       For each product+variant:
    │         query productService.findByMerchantAndSku()
    │         → send directly to task.backend:
    │             CREATE_PRODUCT or CREATE_SELL_PACK
    │             key = channelId:channelProductId:channelSpecId
    │
    │    ⑤b For removed specs:
    │       → send DELIST_SELL_PACK to task.backend (same as general flow)
    │
    │    ⑥ Overwrite Redis snapshot
    │
    │    ⑦ Update channel_sync_logs:
    │       status='success', added=5, removed=2
    │       → notify user
    │
    ▼
  Done (no FETCH_PRODUCT_DETAIL messages needed)
```

**Key difference:** Yahoo webhook delivers complete product data in one shot. The diff + routing logic is the same, but it happens inside the webhook handler instead of going through `{platform}.slow`. No `FETCH_PRODUCT_DETAIL` messages are emitted.

### 5.5 Partition Key Throttle Pattern

This is a **reusable, cross-action pattern** for any action that may produce large bursts but needs rate limiting.

#### How It Works

```
Kafka partition key = channelId + suffix

suffix = 0, 1, 2, ..., N
N is configurable per channel from the frontend UI

Example with N=3 for channel CH-001:
  Messages distributed across keys: CH-001-0, CH-001-1, CH-001-2
  → at most 3 partitions → at most 3 concurrent consumers
  → natural rate limiting without distributed locks

Example with N=1 (conservative):
  All messages to key: CH-001-0
  → 1 partition → strictly serial processing
  → safest for platforms with tight rate limits

Round-robin assignment in ChannelJob:
  int suffix = messageIndex % (N + 1);
  String partitionKey = channelId + "-" + suffix;
```

#### Frontend Configuration

```
Channel Settings → Advanced → Concurrency Level

  蝦皮旗艦館     ▼ 並行數: [3]
  momo 官方館    ▼ 並行數: [2]
  Cyberbiz 主館  ▼ 並行數: [1]  (rate limit: 5/sec, conservative)

Stored in: channel table or channel config
Field: concurrency_level (INTEGER, default: 1)
```

#### Where This Pattern Applies

| Action | Topic | Uses Throttle? | Why |
|--------|-------|---------------|-----|
| FETCH_PRODUCT_DETAIL | {platform}.slow | Yes | Cold start may emit 3000+ messages |
| MODIFY_PRICE (batch) | {platform}.fast | Yes (future) | Batch price update across all products |
| MODIFY_QUANTITY (batch) | {platform}.fast | Yes (future) | Batch inventory sync |

#### Rate Limiter (Per-Partition)

Since partition key guarantees same-account requests serialize to the same consumer, a simple **in-process sleep** is sufficient — no distributed locks needed:

```java
// In FETCH_PRODUCT_DETAIL handler (ChannelJob on {platform}.slow topic)
private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

public void handle(TaskMessage msg) {
    String channelId = msg.getPayload().get("channelId");
    RateLimiter limiter = limiters.computeIfAbsent(channelId,
        id -> RateLimiter.create(platformRateLimit));  // e.g. 4.0 per second (80% of 5/sec)

    limiter.acquire();  // blocks until rate allows
    ChannelProduct detail = adapter.fetchProductDetail(channelId, channelProductId);
    // ... route to task.backend
}
```

### 5.6 sell_pack.status Complete Values

```
draft    — 草稿（OMS 端建立，尚未上架）
pending  — 審核中 / 待上架
active   — 上架中 / 販售中
inactive — 已下架 / 停售（賣家主動操作）
failed   — 審核失敗
delisted — 平台已移除（同步偵測到消失）
```

**`delisted` vs `inactive` distinction:**

| Status | Who caused it | Meaning | UI Display |
|--------|--------------|---------|------------|
| `inactive` | Seller manually took down | Intentional — seller chose to stop selling | 「已下架」 |
| `delisted` | Detected by sync (disappeared from platform) | Unintentional or external — may indicate platform removal, policy violation, or authorized deletion | 「平台已移除」 |

Future: when the system supports authorized auto-deletion (OMS deletes on behalf of seller), `delisted` records will also carry metadata indicating "who" initiated the deletion (manual vs authorized-auto).

### 5.7 DELIST_SELL_PACK Action

A new `BackendJob` action for handling specs that disappeared from the platform:

```java
// DelistSellPackActionService

execute(msg):
    SellPack sp = sellPackService
        .findByChannelAndProductSpec(channelId, channelProductId, channelSpecId);

    if (sp != null && !"delisted".equals(sp.getStatus())) {
        sp.setStatus("delisted");
        sp.setLastSyncAt(now());
        sellPackService.update(sp);

        log.info("Delisted sell_pack: channel={}, product={}, spec={}",
            channelId, channelProductId, channelSpecId);
    }
    // if already delisted or not found → no-op (idempotent)
```

### 5.8 Detail Progress Tracking & Completion Notification

When FETCH_PRODUCTS emits N detail messages, the user needs to know:
1. **Processing status** — how many done vs total
2. **Completion** — all details finished, final result

#### Redis Progress Counter

```
Key:    product_sync_progress:{syncLogId}
Type:   Redis HASH
Fields:
  total      — number of FETCH_PRODUCT_DETAIL messages emitted (set by FETCH_PRODUCTS)
  completed  — incremented by each FETCH_PRODUCT_DETAIL upon success (INCR)
  failed     — incremented on failure (INCR)
TTL:    24 hours (auto-cleanup)
```

#### channel_sync_logs Status Lifecycle

```
FETCH_PRODUCTS creates log:
  status = 'processing'     ← user sees "同步中 (0/5)"
  detail_total = 5
  detail_completed = 0

Each FETCH_PRODUCT_DETAIL completes:
  Redis HINCRBY completed +1
  if completed + failed == total:
    UPDATE channel_sync_logs SET
      status = (failed > 0) ? 'partial' : 'success',
      detail_completed = completed,
      detail_failed = failed,
      completed_at = now()
    → notify user
```

#### Polling API for Frontend

```
GET /api/v1/channels/{channelId}/sync-logs/{syncLogId}/progress

Response:
{
  "status": "processing",        // processing | success | partial | failed
  "detailTotal": 5,
  "detailCompleted": 3,
  "detailFailed": 0,
  "addedCount": 5,
  "removedCount": 2,
  "unchangedCount": 2993
}
```

Frontend polls this endpoint (e.g. every 3 seconds) while status = `processing`. Displays progress bar or counter: "處理中 3/5".

#### Completion Notification

When all details are done (completed + failed == total):

```java
// In FETCH_PRODUCT_DETAIL handler, after INCR
long completed = redis.hincrBy(progressKey, "completed", 1);
long failed = redis.hget(progressKey, "failed");    // default 0
long total = redis.hget(progressKey, "total");

if (completed + failed >= total) {
    // 1. Update sync log
    syncLogService.complete(syncLogId, completed, failed);

    // 2. Send notification (future: WebSocket / SSE / push)
    // For now: sync log status change is sufficient — frontend polls
}
```

**Future notification options** (not implemented now):
- WebSocket push to connected frontend
- In-app notification badge
- Email/Slack notification for long-running cold starts

#### Yahoo: No Progress Tracking Needed

Yahoo webhook delivers all data at once — no background detail processing. SyncLog goes directly from `waiting_callback` → `success` in one step.

### 5.9 Cold Start & Edge Cases

#### Cold Start (First Sync)

```
previousSet = empty (no Redis snapshot)
currentSet  = all 3000 specs from platform
added       = all 3000
removed     = empty

→ emits up to 3000 FETCH_PRODUCT_DETAIL messages (deduped by productId)
→ throttled by partition key (concurrency_level controls speed)
→ acceptable one-time cost; subsequent syncs are differential
```

#### Concurrent Sync Protection

Same `channelId` as Kafka key on `{platform}.fast` → only one FETCH_PRODUCTS runs at a time per channel. API endpoint returns `409 Conflict` if a sync is already in progress.

#### Platform API Failure During List

If `fetchProductList()` fails partway through pagination:
- Do NOT update Redis snapshot (⑥ only runs after successful ⑤)
- SyncLog records failure
- Retry via existing `task.failed` → `RetryDispatchJob` flow
- Next successful sync will do a clean diff against the old snapshot

#### Snapshot Expiry (Optional Future)

If Redis snapshot is lost (Redis restart without persistence):
- Equivalent to cold start → full sync
- Safe because all operations are idempotent (upsert + delist is no-op for already-correct records)

### 5.10 One-Click Listing (Push) — Design Only

> **Status: Designed, not implemented.** This section documents the intended architecture for pushing products FROM OMS TO platform.

#### Intended Flow

```
Frontend: select product + channel → click "一鍵上架"
    │
    ▼
API: POST /api/v1/channels/{channelId}/listings
    Body: { productId, variants: [...], pricing: {...} }
    │
    ▼
TaskMessage → {platform}.fast topic
    action = NEW_SELL_PACK
    │
    ▼
ChannelJob: adapter.createListing(channelId, sellPack, extraData)
    → platform returns channelProductId, channelVariantIds
    │
    ▼
task.backend: CREATE_SELL_PACK (with platform-assigned IDs)
    → insert sell_pack with status = 'pending' or 'active'
```

**Key design decisions (for future implementation):**
- Platform field requirements vary widely — `extraData` map handles platform-specific fields
- Some platforms require category, images, description — build dynamic form based on platform requirements
- Cyberbiz: `POST /v1/products` with `product_variants[]`
- Shopee: multi-step (create product → add images → add variants → publish)
- Implementation deferred until pull sync is stable

### 5.11 Revised FETCH_PRODUCTS Flow Diagram

```
前端 → API → {platform}.fast → ChannelJob (FETCH_PRODUCTS)
                                  │
                                  ├─ fetchProductList()    ← Step 1: 取列表 (含規格ID)
                                  ├─ Build currentSet      ← Step 2: 組裝 Set<productId:variantId>
                                  ├─ Redis SMEMBERS        ← Step 3: 讀取上次快照
                                  ├─ Set diff              ← Step 4: 找出 added/removed
                                  │
                                  ├─ added specs:
                                  │    dedup by productId
                                  │    → {platform}.slow topic (FETCH_PRODUCT_DETAIL)
                                  │      key = channelId + suffix(0..N)
                                  │
                                  ├─ removed specs:
                                  │    → task.backend (DELIST_SELL_PACK)
                                  │
                                  ├─ Redis: overwrite snapshot
                                  └─ SyncLog: added=5, removed=2, unchanged=2993
                                     ★ 到這裡 user 就能看到結果（快速受理）

                               {platform}.slow topic
                                  │
                                  ▼
                               ChannelJob (FETCH_PRODUCT_DETAIL)
                                  │  rateLimiter.acquire()
                                  │  adapter.fetchProductDetail()
                                  │
                                  ├─ product 不存在 → task.backend (CREATE_PRODUCT)
                                  │                     │ routeNext ↓
                                  │                     ▼
                                  │                   task.backend (CREATE_SELL_PACK)
                                  │
                                  └─ product 存在   → task.backend (CREATE_SELL_PACK)
```

### 5.12 New Infrastructure

> **No dedicated topic or container needed.** FETCH_PRODUCT_DETAIL shares `{platform}.slow` with FETCH_ORDERS.
> The same ChannelJob instance on `{platform}.slow` handles both actions, naturally sharing the platform's rate limiter context.
> Partition key throttle (`channelId + suffix`) isolates detail traffic to specific partitions, leaving other partitions free for order processing.
>
> Total topics: 16 (10 channel + 6 system). Total containers: 26 (24 existing + 2 Cyberbiz).

#### New ActionType

```java
public enum ActionType {
    // ... existing ...
    FETCH_PRODUCT_DETAIL("fetch_product_detail", "拉取商品明細"),
    DELIST_SELL_PACK("delist_sell_pack", "下架商品");
}
```

### 5.13 Implementation Priority (Product Sync)

| Component | Change | Priority |
|-----------|--------|----------|
| `ChannelProductRef` DTO | Add `List<ChannelVariantRef> variants` | P0 |
| `ChannelAdapter` | `fetchProductList()` must return variant IDs | P0 |
| Redis snapshot logic | `product_snapshot:{channelId}` SET operations | P0 |
| `FetchProductsActionService` | Rewrite: list → diff → emit FETCH_PRODUCT_DETAIL to `{platform}.slow` | P0 |
| `FetchProductDetailActionService` | New: single product detail fetch + route + progress INCR (on `{platform}.slow`) | P0 |
| `DelistSellPackActionService` | New: update sell_pack status to `delisted` | P0 |
| `sell_pack.status` | Add `delisted` value | P0 |
| Progress tracking (Redis + sync_log) | `product_sync_progress:{syncLogId}` counter + sync_log status lifecycle | P0 |
| Progress polling API | `GET /api/v1/channels/{channelId}/sync-logs/{id}/progress` | P0 |
| Yahoo webhook handler | Parse CSV → diff → route to task.backend (skip detail fetch) | P0 |
| `channel.concurrency_level` | New column for throttle configuration | P1 |
| Frontend: concurrency config | Channel settings → concurrency level input | P1 |
| Frontend: sync progress UI | Poll progress API, show "處理中 3/5", completion toast | P1 |
| One-click listing | Design complete, implementation deferred | P2 |
| Completion push notification | WebSocket / SSE for real-time completion notification | P2 |
