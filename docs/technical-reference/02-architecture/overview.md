# System Architecture Overview

## What SimpleEC OMS Does

SimpleEC OMS is an event-driven order management backend that continuously pulls orders,
returns, products, and inventory data from six e-commerce platforms — Shopee, Momo, Yahoo,
PChome, Cyberbiz, and Easystore — and consolidates them into a single PostgreSQL database.
A merchant logs in once to the unified portal and can view all orders across every platform,
process returns, trigger shipments, and track inventory without touching any individual
platform's native dashboard.

The system is designed around a scheduler-driven polling architecture. A scheduler job emits
heartbeat messages to Kafka on a configurable interval (every 5 minutes for fast tasks, hourly
for slow batch fetches). Platform-specific channel jobs consume these messages, call the
relevant external API, normalize the heterogeneous platform responses into a canonical message
format, and publish order/return events to internal Kafka topics. Downstream job services
consume those internal events, persist records to PostgreSQL, and send frontend notifications
through a task pipeline.

This architecture makes every integration point independently deployable and observable.
A Cyberbiz API outage does not affect Shopee processing. A slow Momo batch fetch does not
block real-time Shopee order updates. Every message that cannot be processed is routed to a
dead-letter topic (DLT) with 30-day retention for investigation and replay.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           SimpleEC OMS                                  │
│                                                                         │
│  ┌──────────────┐   ┌──────────────┐                                   │
│  │   user-app   │   │  admin-app   │  ← Vue 3 frontends                 │
│  │  (:8090/nginx│   │  (:8089/nginx│    (SPA, JWT auth)                 │
│  │   :5173/dev) │   │   :8084/dev) │                                   │
│  └──────┬───────┘   └──────┬───────┘                                   │
│         │                  │                                            │
│  ┌──────▼──────────────────▼──────────┐  ┌────────────────────────┐   │
│  │          simpleec-api (:8082)       │  │  simpleec-gateway       │   │
│  │   REST API + JWT Auth + CORS        │  │      (:8081)            │   │
│  │   Orders / Returns / Shipments /   │  │  Platform Webhooks      │   │
│  │   Inventory / Stats / Reports       │  │  ERP Integration        │   │
│  └────────────────┬────────────────── ┘  └──────────┬─────────────┘   │
│                   │                                  │                  │
│                   └──────────────┬───────────────────┘                  │
│                                  │  Kafka Publish                       │
│                   ┌──────────────▼──────────────────┐                  │
│                   │             Kafka                │                  │
│                   │   KRaft Mode (no ZooKeeper)      │                  │
│                   │   17 Topics across 3 tiers        │                  │
│                   │                                  │                  │
│                   │  Channel topics (×10):           │                  │
│                   │    {platform}.fast               │                  │
│                   │    {platform}.slow               │                  │
│                   │  Business topics (×7):           │                  │
│                   │    order.process  return.process │                  │
│                   │    task.backend   task.frontend  │                  │
│                   │    scheduler      task.failed    │                  │
│                   │    task.dlt                      │                  │
│                   └──────┬──────────────────┬────────┘                  │
│                          │                  │                           │
│         ┌────────────────┘                  └────────────────────┐     │
│         │                                                         │     │
│  ┌──────▼───────────────────────────┐  ┌───────────────────────┐ │     │
│  │  simpleec-channel-job (×10)      │  │  simpleec-order-job    │ │     │
│  │  One container per platform ×    │  │  simpleec-backend-job  │ │     │
│  │  fast/slow topic pair            │  │  simpleec-frontend-job │ │     │
│  │                                  │  │  simpleec-scheduler-job│ │     │
│  │  • Calls platform REST API       │  │  simpleec-retry-job    │ │     │
│  │  • Decides time windows          │  └───────────┬───────────┘ │     │
│  │  • Normalizes response format    │              │              │     │
│  │  • Publishes to order.process    │              │              │     │
│  │    or return.process             │              │              │     │
│  └──────┬───────────────────────────┘  ┌──────────▼──────────────┘     │
│         │  HTTPS outbound               │                               │
│  ┌──────▼───────────────────────────┐  │  ┌───────────────────────┐   │
│  │  External Platform APIs          │  └─►│  PostgreSQL 16         │   │
│  │                                  │     │  (19 tables, AES-256   │   │
│  │  • api.cyberbiz.co               │     │   PII encryption)      │   │
│  │  • partner.shopeemobile.com      │     ├───────────────────────┤   │
│  │  • Momo Commerce API             │     │  Redis 7 (AOF)         │   │
│  │  • Yahoo Commerce API            │     │  (dedup, cache,        │   │
│  │  • Easystore API                 │     │   session store)        │   │
│  └──────────────────────────────────┘     └───────────────────────┘   │
│                                                                         │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │  Observability Stack                                               │ │
│  │  OTEL Collector → Tempo (traces) · Loki (logs) · Prometheus (mtx) │ │
│  │  Grafana (:3000) unified dashboard                                 │ │
│  └────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Core Design Principles

### 1. Passive Sync: Accept Whatever Platforms Give

SimpleEC OMS is a downstream consumer of external platform data, not an authority over it.
The system does not validate order state transitions, reject unexpected status jumps, or
enforce business rules that might conflict with what a platform reports. If Shopee says an
order jumped from `PENDING` to `COMPLETED`, the system records that.

This principle prevents the most common integration failure mode: a guard condition in OMS
rejects a valid platform update because the OMS-internal state machine didn't expect that
transition. By removing all state guards, OMS becomes a faithful mirror of platform reality.

### 2. Channel Job Autonomy: Each Platform Decides Its Own Time Windows

The scheduler emits only a single timestamp (`header.timestamp`). Channel jobs are entirely
responsible for translating that timestamp into the appropriate API query parameters for their
specific platform:

- **Shopee** — splits into four overlapping windows by order lifecycle stage: 1h (UNPAID),
  3d (AWAITING_SHIPMENT), 5d (SHIPPED), 7d (COMPLETED). Uses cursor-based pagination.
- **Momo** — item-level records with no status categories. Channel job aggregates by order
  number. No detail API exists; aggregation happens in-memory.
- **Yahoo** — only supports `updated_after` queries. Channel job uses `timestamp - 1d`.
- **Easystore** — returns 50 complete orders per page. Channel job uses `timestamp - 7d`.
- **PChome / Cyberbiz** — platform-specific time window logic defined per their API contracts.

This autonomy means the Kafka message contract (`scheduler` topic) stays simple and stable
while each platform's fetch strategy can evolve independently. **The queue never carries
from/to date ranges** — only the trigger timestamp.

### 3. Unified Header/Body Contract: All Kafka Messages Share One Structure

Every message on every topic conforms to the same envelope:

```json
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "merchantId": "a00000",
    "platformId": "shopee",
    "channelId": "CHANNEL_SHOPEE_001",
    "requestId": "trace-correlation-id",
    "timestamp": "2026-02-13T10:00:00Z",
    "source": "scheduler",
    "version": 1,
    "isRollback": false
  },
  "body": {
    // taskType-specific payload
  }
}
```

The `taskType` field in the header is the routing key. Each consumer dispatches to the
appropriate handler based solely on `header.taskType`, making the system easy to extend:
adding a new TaskType means implementing a new handler class annotated with `@ChannelHandler`,
with no changes to the consumer infrastructure.

### 4. `isRollback` Flag: Distinguishing Live Orders from Historical Backfill

When a merchant connects a new platform, OMS needs to import historical orders without
distorting current business metrics. The `isRollback` flag in the message header signals
to all handlers whether a message represents a live event or a backfill:

- `isRollback = false` — **live order**: count toward today's statistics, deduct from current
  inventory, trigger normal notifications.
- `isRollback = true` — **backfill order**: attribute revenue to `channelCreatedAt` date (not
  today), mark statistics records as backfill so they are excluded from real-time dashboards,
  skip inventory deduction adjustments that would corrupt current stock counts.

This distinction is propagated through every handler in the `FETCH_ORDERS`, `PROCESS_ORDER`,
`SHIP_ORDER`, and all return-related TaskTypes.

### 5. Two-Layer Deduplication: Redis Fast-Path, DB Authoritative

Platform APIs are not idempotent. The same order may appear in multiple consecutive fetch
windows (e.g., a Shopee order created 2 days ago appears in both the 3d and 5d windows).
Without deduplication, the system would create duplicate order records.

The deduplication strategy is two-layered:

1. **Redis (fast path)** — before processing any message, the order-job checks a Redis key
   `dedup:{channelId}:{channelOrderId}`. If the key exists and the order hasn't changed, the
   message is discarded immediately without a DB round-trip.
2. **PostgreSQL (authoritative)** — on cache miss or detected change, the handler performs an
   upsert (`INSERT ... ON CONFLICT DO UPDATE`) keyed on `(channel_id, channel_order_id)`. The
   DB is always the source of truth; Redis is only an optimization.

This design handles cache restarts gracefully: a Redis flush causes slightly higher DB load for
one polling cycle, then returns to normal as the cache warms up again.

---

## Module Dependency Graph

All modules depend on `simpleec-common` for shared enums, utility classes, and ID generation.
`simpleec-core` adds the database layer (entities, MyBatis mappers, Kafka producer/consumer
utilities, crypto services). Application modules depend on both.

```
simpleec-common
    │
    └── simpleec-core
            │
            ├── simpleec-channel          (platform adapter interfaces + implementations)
            │
            ├── simpleec-api              (REST API :8082)
            ├── simpleec-gateway          (webhooks/ERP :8081)
            │
            ├── simpleec-channel-job      (platform fetch workers)
            ├── simpleec-order-job        (order.process consumer)
            ├── simpleec-backend-job      (async backend tasks)
            ├── simpleec-frontend-job     (frontend notification tasks)
            ├── simpleec-scheduler-job    (heartbeat timer)
            └── simpleec-retry-job        (failure retry + DLT routing)
```

**Key constraint:** no module may have a compile-time dependency going in the opposite
direction (e.g., `simpleec-core` must not import from `simpleec-api`). Cross-cutting
communication happens exclusively via Kafka messages.

---

## Technology Stack Summary

| Layer | Technology | Notes |
|---|---|---|
| Language | Java 17 | LTS release |
| Framework | Spring Boot 3.5.0 | Gradle multi-module project |
| Build | Gradle 8.14.4 | Wrapper committed to repo |
| ORM | MyBatis-Plus | Annotation-based, no XML mappers |
| Primary key | NanoID (VARCHAR 20) | `IdGenerator.nextId()`, URL-safe |
| Database | PostgreSQL 16 | 19 tables, AES-256-GCM PII encryption |
| Cache / Session | Redis 7 (AOF) | Persistence enabled for crash recovery |
| Message broker | Kafka 3.7.1 KRaft | No ZooKeeper required |
| Encryption | AES-256-GCM | buyer_name, phone, email, address fields |
| Tracing | OpenTelemetry Agent 2.10.0 | Auto-instrumentation via Java agent |
| Metrics | Prometheus + Grafana | All Spring Boot actuator metrics |
| Log aggregation | Loki + Grafana | JSON structured logs via Logstash encoder |
| Frontend | Vue 3 + Vite | Two separate SPA apps |
| Containers | Docker Compose | 26 containers total |
