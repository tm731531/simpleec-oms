# SimpleEC OMS - Event Flow Architecture (正確版)

> **Status**: ✅ Documented and Implemented
> **Last Updated**: 2026-02-22
> **Context**: 6-job event-driven architecture with 3 layers (Foundation, Data Processing, Channel Integration)

## Overview: 3-Layer Architecture

```
Layer A: Foundation (No dependencies)
├─ RetryJob ────────────── DLT Handler & Exponential Backoff
└─ SchedulerJob ────────── Task Scheduler & Heartbeat

Layer B: Data Processing (Depends on Layer A)
├─ OrderJob ────────────── ORDER_UPSERT & ORDER_STATUS_CHANGE
├─ BackendJob ──────────── RETURN_UPSERT & SYNC_PACK Handler Registry
└─ FrontendJob ─────────── WebSocket/SSE Notification Dispatcher

Layer C: Channel Integration (Depends on Layer A+B)
└─ ChannelJob ──────────── 6-Platform Adapter Orchestrator (Shopee, Momo, Yahoo, PChome, Cyberbiz, EasyStore)
```

---

## Complete Event Flow Diagram

```
╔════════════════════════════════════════════════════════════════════════════╗
║                           SCHEDULER JOB (Layer A)                          ║
║  Producer: 每 5min/15min/1hr 發布任務到平台特定 topics                      ║
║  Heartbeat: 每 1min 發送心跳訊息到 scheduler.heartbeat                     ║
╚════════════════════════════════════════════════════════════════════════════╝
                                      │
            ┌─────────────────────────┼─────────────────────────┐
            │                         │                         │
            ↓                         ↓                         ↓
        FETCH_ORDERS           SYNC_INVENTORY               UPDATE_PRICE
        (every 5min)            (every 15min)                (every 1hr)
            │                         │                         │
            ↓                         ↓                         ↓
     platform.fast            platform.slow              platform.slow
   (shopee, momo, yahoo,  (shopee, momo, yahoo,    (shopee, momo, yahoo,
    pchome, cyberbiz,      pchome, cyberbiz,       pchome, cyberbiz,
    easystore)             easystore)              easystore)
            │
            └─────────────────────────┬─────────────────────────┘
                                      │
                                      ↓
╔════════════════════════════════════════════════════════════════════════════╗
║                    CHANNEL JOB (Layer C - Adapter Layer)                   ║
║  Consumer: shopee.{fast,slow}, momo.{fast,slow}, ... (platform topics)    ║
║  Transformer: Platform API → OMS Schema (Mode A/B adapters)               ║
║  Producer: Publishes to order.process and return.process topics          ║
╚════════════════════════════════════════════════════════════════════════════╝
                                      │
            ┌─────────────────────────┼─────────────────────────┐
            │                         │                         │
            ↓                         ↓                         ↓
     order.process           return.process
  (ORDER_UPSERT,          (RETURN_UPSERT,
   ORDER_STATUS_CHG)       RETURN_STATUS_CHG)
            │                         │
    ┌───────┴─────────┐       ┌───────┴──────────┐
    │                 │       │                  │
    ↓                 ↓       ↓                  ↓
 ╔═════════╗  ╔════════════╗ ╔═══════════╗  ╔═══════════════╗
 ║ ORDER   ║  ║ FRONTEND   ║ ║ BACKEND   ║  ║ FRONTEND      ║
 ║ JOB     ║  ║ JOB        ║ ║ JOB       ║  ║ JOB           ║
 ║(Layer B)║  ║(Layer B)   ║ ║(Layer B)  ║  ║(Layer B)      ║
 ║         ║  ║            ║ ║           ║  ║               ║
 ║Handler: ║  ║Handler:    ║ ║Handler:   ║  ║Handler:       ║
 ║O-UPSERT ║  ║Filter &    ║ ║Return     ║  ║Filter &       ║
 ║(dedup)  ║  ║Format      ║ ║Handler    ║  ║Format         ║
 ║O-STATUS ║  ║for SSE/WS  ║ ║Registry   ║  ║for SSE/WS     ║
 ╚═════════╝  ╚════════════╝ ╚═══════════╝  ╚═══════════════╝
    │              │              │              │
    │              │              │              │
    ↓              ↓              ↓              ↓
  [DB]         [Clients]       [DB]          [Clients]
  orders       WebSocket/       return_orders WebSocket/
  table        SSE push         table         SSE push

**Note:** SYNC_PACK and UPDATE_PRICE are task types handled separately
via BackendJob consuming the task.backend topic (not a separate pack.sync topic)
```

### Error Handling Flow

```
Any Job (Order, Backend, Frontend, Channel)
         │
         ↓ (Exception thrown)
    Unacknowledged offset
         │
         ↓
    Kafka redelivers message
         │
         ↓ (Continues failing)
    task.failed topic (DLT Source)
         │
         ↓
╔════════════════════════════════════════╗
║    RETRY JOB (Layer A - Safety Net)   ║
║  Consumer: task.failed                 ║
║  Retry Logic:                          ║
║    • 1st retry: wait 1s                ║
║    • 2nd retry: wait 5s                ║
║    • 3rd retry: wait 30s               ║
║    • If failed: send to task.dlt       ║
║  State: retry count in Redis           ║
╚════════════════════════════════════════╝
         │
         ├─→ Success: Resend to original topic
         │
         └─→ Exhausted (3 retries failed):
                    │
                    ↓
                task.dlt (Dead Letter Topic)
                    │
                    ↓
            Manual Investigation
            Alert to PagerDuty
```

---

## Job Responsibilities (按照事件流)

### 🟦 Layer A: Foundation (無依賴)

#### 1. **SchedulerJob** — Periodic Event Producer
- **Role**: Publisher of scheduled events (not a consumer)
- **Publishes to**:
  - `platform.fast` (shopee.fast, momo.fast, ...) every 5 minutes
    - TaskType: `FETCH_ORDERS`
  - `platform.slow` (shopee.slow, momo.slow, ...) every 15 minutes
    - TaskType: `SYNC_INVENTORY`
  - `platform.slow` (shopee.slow, momo.slow, ...) every 1 hour
    - TaskType: `UPDATE_PRICE`
  - `scheduler.heartbeat` every 1 minute
    - Shows system is alive

- **State Management**:
  - Heartbeat key in Redis: `heartbeat:{jobId}` (120s TTL)
  - Confirms job is running and scheduler is working

- **Consumer Group**: None (producer only)

#### 2. **RetryJob** — Exponential Backoff & DLT Handler
- **Role**: Safety net for failed messages
- **Consumes from**:
  - `task.failed` (DLT Source) — messages that failed from any job

- **Publishes to**:
  - Original topic (on retry) — if retry succeeds, resend to original topic
  - `task.dlt` (Dead Letter Topic) — if all 3 retries exhausted

- **Retry Logic**:
  - Max 3 retries: 1s → 5s → 30s exponential backoff
  - Retry count stored in Redis: `retry:{messageId}` (24h TTL)
  - If all retries fail → send to task.dlt for manual investigation

- **Consumer Group**: `retry-job-group`

---

### 🟧 Layer B: Data Processing (Depends on Layer A)

#### 3. **OrderJob** — Order & Return Processing
- **Role**: Consumes order-related events and persists to database
- **Consumes from**:
  - `order.process` — ORDER_UPSERT, ORDER_STATUS_CHANGE
    - Handlers: `OrderUpsertHandler`, `OrderStatusChangeConsumer`
  - `return.process` (Legacy path) — RETURN_UPSERT, RETURN_STATUS_CHANGE
    - Handler: `ReturnUpsertConsumer`

- **Publishes to**: None (database only)

- **Data Processing**:
  - **OrderUpsertHandler**:
    - Two-layer deduplication: Redis (fast) → DB (safe)
    - Hash-based change detection (SHA256 with TreeMap)
    - INSERT (new order) or UPDATE (changed fields)
    - Idempotent: no duplicate writes even if Kafka retries

  - **ReturnUpsertConsumer**:
    - Same two-layer dedup pattern as OrderUpsertHandler
    - Processes return order UPSERT events
    - Defensive null-safety for Kafka message structure

- **Database Tables Written**:
  - `orders` (INSERT/UPDATE)
  - `return_orders` (INSERT/UPDATE)

- **Consumer Group**: `order-job-group` (concurrency: 4)

#### 4. **BackendJob** — Event Handler Registry & Routing
- **Role**: Route events to appropriate handlers based on TaskType
- **Consumes from**:
  - `return.process` — RETURN_UPSERT, RETURN_STATUS_CHANGE
  - `task.backend` — SYNC_PACK, UPDATE_PRICE
    - (Note: SYNC_INVENTORY is scheduled by SchedulerJob, not consumed)

- **Publishes to**: None (database only)

- **Handler Registry** (TaskType routing):
  - `RETURN_UPSERT` → `ReturnEventHandler` (already implemented)
  - `RETURN_STATUS_CHANGE` → `ReturnEventHandler`
  - `SYNC_PACK` → `PackSyncHandler` (future)
  - `UPDATE_PRICE` → `PriceUpdateHandler` (future)

- **Database Tables Written**:
  - `return_orders` (UPSERT)
  - `sell_pack` (SYNC_PACK)

- **Consumer Group**: `backend-job-group` (concurrency: 6)

#### 5. **FrontendJob** — Real-Time Notification Dispatcher
- **Role**: Convert order/return status changes to WebSocket/SSE messages
- **Consumes from**:
  - `order.process` (filtered by taskType) — `ORDER_STATUS_CHANGE` only
  - `return.process` (filtered by taskType) — `RETURN_STATUS_CHANGE` only

- **Publishes to**: WebSocket/SSE clients (scoped by `merchantId`)

- **Notification Flow**:
  - Filter by taskType (only status changes, not initial UPSERT)
  - Format message for UI consumption
  - Group clients by merchantId
  - Push via SSE (Server-Sent Events) or WebSocket

- **Consumer Group**: `frontend-job-group` (concurrency: 4)

---

### 🟨 Layer C: Channel Integration (Depends on Layer A+B)

#### 6. **ChannelJob** — Multi-Platform Adapter Orchestrator
- **Role**: Fetch from e-commerce platforms, transform to OMS schema, publish to business topics
- **Consumes from**:
  - `shopee.fast` / `shopee.slow`
  - `momo.fast` / `momo.slow`
  - `yahoo.fast` / `yahoo.slow`
  - `pchome.fast` / `pchome.slow`
  - `cyberbiz.fast` / `cyberbiz.slow`
  - `easystore.fast` / `easystore.slow`

- **Publishes to**:
  - `order.process` (ORDER_UPSERT, ORDER_STATUS_CHANGE)
  - `return.process` (RETURN_UPSERT, RETURN_STATUS_CHANGE)
  - `pack.sync` (SYNC_PACK, UPDATE_PRICE)

- **Platform Adapters** (6 total):
  - `ShopifyAdapter` (Mode A — Direct list API)
  - `CyberbizAdapter` (Mode B — List + Detail API)
  - `ShopeeAdapter` (Mode A/B TBD)
  - `MomoAdapter` (Mode A/B TBD)
  - `YahooAdapter` (Mode A/B TBD)
  - `PChomeAdapter` (Mode A/B TBD)
  - `EasystoreAdapter` (Mode A/B TBD)

- **TaskType Handlers**:
  - `FETCH_ORDERS` → Call platform API → Produce `ORDER_UPSERT`
  - `FETCH_RETURNS` → Call platform API → Produce `RETURN_UPSERT`
  - `SYNC_PACK` → Call platform API → Produce `SYNC_PACK` to pack.sync

- **Key Logic**:
  - Mode A: Direct mapping from list API
  - Mode B: List API → Detail API → OMS schema
  - Hash-based deduplication
  - Platform-specific status → OMS status mapping

- **Consumer Group**: Per-platform (TBD)

---

## Topic Architecture

### Business Topics (統一 OMS 訊息)

| Topic | TaskTypes | Consumer | Source |
|-------|-----------|----------|--------|
| `order.process` | ORDER_UPSERT, ORDER_STATUS_CHANGE | OrderJob, FrontendJob | ChannelJob |
| `return.process` | RETURN_UPSERT, RETURN_STATUS_CHANGE | BackendJob, FrontendJob | ChannelJob |
| `task.backend` | SYNC_PACK, UPDATE_PRICE | BackendJob | Scheduler/Admin |

### Platform Topics (平台特定訊息)

| Topic | TaskTypes | Consumer | Source |
|-------|-----------|----------|--------|
| `{platform}.fast` | FETCH_ORDERS, SHIP_ORDER, CANCEL_ORDER | ChannelJob | SchedulerJob |
| `{platform}.slow` | SYNC_INVENTORY, UPDATE_PRICE | ChannelJob | SchedulerJob |

### System Topics (內部系統訊息)

| Topic | Purpose | Consumer | Source |
|-------|---------|----------|--------|
| `task.failed` | DLT source for failed messages | RetryJob | Any job exception |
| `task.dlt` | Dead letter topic (exhausted retries) | Manual investigation | RetryJob |
| `scheduler.heartbeat` | System health monitoring | (optional monitoring) | SchedulerJob |

---

## Data Flow Examples

### Example 1: New Order from Cyberbiz
```
1. SchedulerJob (every 5 min)
   └─ Publishes: {taskType: FETCH_ORDERS, platform: cyberbiz, ...} → cyberbiz.fast

2. ChannelJob (listening to cyberbiz.fast)
   └─ Consumes FETCH_ORDERS
   └─ Calls: CyberbizAdapter.fetchOrderList() → fetchOrderDetail()
   └─ Transforms to OMS Order schema
   └─ Calculates hash (SHA256)
   └─ Publishes: {taskType: ORDER_UPSERT, header: {...}, body: {...}} → order.process

3. OrderJob (listening to order.process)
   └─ Consumes ORDER_UPSERT
   └─ OrderUpsertHandler:
      ├─ Redis dedup check: NOT found → proceed
      ├─ DB query: order NOT found → INSERT
      └─ Update Redis cache: order:dedup:cyberbiz:order123 = hash
   └─ Writes to orders table

4. FrontendJob (listening to order.process, filtered)
   └─ Ignores ORDER_UPSERT (not a status change)
   └─ Waits for ORDER_STATUS_CHANGE event
```

### Example 2: Order Status Update & Retry with Backoff
```
1. Cyberbiz sends order status change via webhook
   └─ ChannelJob converts to: {taskType: ORDER_STATUS_CHANGE, ...} → order.process

2. OrderJob processes ORDER_STATUS_CHANGE
   └─ Attempt 1: Exception (DB connection timeout)
   └─ Message NOT acknowledged → offset stays same

3. Kafka redelivers message (automatic retry)
   └─ OrderJob receives same message again
   └─ Attempt 2: Still fails
   └─ Message sent to task.failed topic

4. RetryJob consumes from task.failed
   └─ Sees messageId, increments retry count in Redis: retry:msg123 = 1
   └─ Waits 1 second
   └─ Publishes back to order.process

5. OrderJob receives retried message
   └─ Attempt 3: Success! Offset acknowledged

6. If Attempt 3 also fails:
   └─ RetryJob: retry count = 2
   └─ Wait 5 seconds, republish

7. If all 3 retries fail:
   └─ RetryJob publishes to task.dlt
   └─ Alert: Manual investigation required
```

---

## Consumer Groups Summary

| Job | Consumer Group | Concurrency | Topics |
|-----|----------------|-------------|--------|
| SchedulerJob | N/A (Producer) | N/A | - |
| RetryJob | `retry-job-group` | 2 | `task.failed` |
| OrderJob | `order-job-group` | 4 | `order.process` |
| BackendJob | `backend-job-group` | 6 | `return.process`, `task.backend` |
| FrontendJob | `frontend-job-group` | 4 | `order.process`, `return.process` (filtered) |
| ChannelJob | TBD (per-platform) | TBD | `{platform}.fast`, `{platform}.slow` |

---

## Key Architectural Principles

1. **Separation of Concerns**:
   - Scheduler: Time-driven task dispatch
   - ChannelJob: Platform-specific adaptation
   - OrderJob/BackendJob: Business logic & persistence
   - FrontendJob: Real-time notifications
   - RetryJob: Error recovery

2. **Two-Layer Deduplication**:
   - Layer 1 (Redis): Fast, TTL-based cache
   - Layer 2 (DB): Safe, constraint-based
   - Handles concurrent retries without duplicates

3. **Mode A vs Mode B Adapters**:
   - Mode A: Direct list API → OMS (fewer API calls)
   - Mode B: List API → Detail API → OMS (more complete data)

4. **Explicit TaskType Routing**:
   - Every message has `header.taskType`
   - Enables event-driven filtering and handler dispatch
   - Allows multiple consumers on same topic (each filters what it needs)

5. **Manual Offset Acknowledgment**:
   - Only acknowledge after successful processing
   - Failed messages automatically retry
   - No poison pills blocking the queue

6. **Exponential Backoff**:
   - 3 retries: 1s → 5s → 30s
   - Prevents cascading failures
   - Gives upstream systems time to recover

---

## Next Steps

- [ ] Implement ChannelJob consumer and FETCH_ORDERS handler
- [ ] Implement Platform Adapters for remaining 6 platforms
- [ ] Implement BackendJob handlers: PackSyncHandler, PriceUpdateHandler
- [ ] Set up consumer groups and concurrency settings
- [ ] Create monitoring/alerting for task.dlt messages
- [ ] End-to-end testing of complete order flow
