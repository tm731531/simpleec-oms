# SimpleEC OMS — Technical Reference

> **MultiCloud E-commerce Order Management System**

## What is SimpleEC OMS?

SimpleEC OMS is a multi-tenant e-commerce order management system that unifies orders, returns,
shipments, and inventory from six e-commerce platforms — Shopee, Momo, Yahoo, PChome, Cyberbiz,
and Easystore — into a single operational backend. Merchants log in once and manage everything
through a unified dashboard without switching between platform portals.

The system is event-driven: a scheduler triggers platform-specific channel jobs at regular
intervals, each job fetches data from its platform's API, normalizes the response into a
canonical structure, and publishes to Kafka. Downstream jobs consume these events, persist
orders into PostgreSQL, and push real-time notifications to the frontend. This architecture
ensures reliability (failed jobs retry automatically via a dedicated retry pipeline) and
decoupling (each service is independently deployable and scalable).

SimpleEC OMS is built for production from day one: AES-256-GCM encryption for all buyer PII
fields, distributed tracing via OpenTelemetry, structured JSON logging shipped to Loki, and
metrics exposed to Prometheus/Grafana.

---

## Documentation Map

| Section | File | Description |
|---|---|---|
| **Getting Started** | [01-getting-started/prerequisites.md](01-getting-started/prerequisites.md) | Hardware, software requirements, ports |
| **Quick Start** | [01-getting-started/quick-start.md](01-getting-started/quick-start.md) | Zero to running in 30 minutes |
| **Environment Variables** | [01-getting-started/environment.md](01-getting-started/environment.md) | All env vars, defaults, production checklist |
| **Architecture Overview** | [02-architecture/overview.md](02-architecture/overview.md) | System design, modules, core principles |
| **Event Flow & Contracts** | ../../3-EVENT-FLOW/CORE_CONTRACTS.md | Kafka message structure, topic routing |
| **Handler Registry** | ../../3-EVENT-FLOW/HANDLER_REGISTRY.md | TaskType → Handler mapping table |
| **Database Schema** | ../../4-SCHEMA/SCHEMA.md | All 19 tables DDL |
| **Channel Implementation** | ../../7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md | How to add a new platform |
| **Operations** | ../../6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md | Current system status, known issues |

---

## Quick Reference

### Ports

| Service | External Port | Notes |
|---|---|---|
| `simpleec-api` | **8082** | REST API, JWT auth required |
| `simpleec-gateway` | **8081** | Webhooks and ERP integrations |
| `user-app` (nginx) | **8090** | Merchant frontend (Vue 3) |
| `user-app` (dev server) | **5173** | Vite dev server (local only) |
| `admin-app` (nginx) | **8089** | Admin frontend (Vue 3) |
| `admin-app` (dev server) | **8084** | Vite dev server (local only) |
| Kafka UI | **8088** | Browse topics, messages, consumer lag |
| Grafana | **3000** | Metrics, logs, traces dashboards |
| Prometheus | **9090** | Raw metrics scrape endpoint |
| PostgreSQL | **5433** | External mapping (internal: 5432) |
| Redis | **6379** | Cache and session store |
| Kafka broker | **9092** | Internal only (KRaft mode, no ZooKeeper) |

### Commonly Used URLs

```
http://localhost:8082/api/health          # API health check
http://localhost:8088                     # Kafka UI
http://localhost:3000                     # Grafana
http://localhost:8090                     # Merchant frontend
http://localhost:8089                     # Admin frontend
```

### Key Commands

```bash
# Build all modules (skip tests)
./gradlew clean build -x test

# Start all 26 containers
docker compose up -d --build

# Quick-redeploy a single service (30-60 seconds)
./quick-redeploy.sh <service-name>

# Quick-redeploy multiple services at once
./quick-redeploy.sh simpleec-channel-job simpleec-order-job

# Follow logs for a service
docker compose logs -f simpleec-api

# Check all container statuses
docker compose ps

# API health check
curl http://localhost:8082/api/health
```

### Module Names (for quick-redeploy)

```
simpleec-api           simpleec-gateway        simpleec-channel-job
simpleec-order-job     simpleec-return-job     simpleec-backend-job
simpleec-frontend-job  simpleec-scheduler-job  simpleec-retry-job
```
