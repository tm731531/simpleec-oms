# Agent Team Configuration — SimpleEC OMS

## Roles

| Agent | Model | Role | Phase |
|-------|-------|------|-------|
| architect | opus | Kafka contracts, cross-service design, security review | Design |
| channel-dev | sonnet | Channel Job adapters (Shopee/Momo/Yahoo/easystore) | Build |
| backend-dev | sonnet | Order/Return/Backend handlers, business logic | Build |
| api-dev | sonnet | REST controllers, Gateway, Webhook endpoints | Build |
| frontend-dev | sonnet | admin-app, user-app (Vue 3 + Element Plus) | Build |
| dba | sonnet | Schema changes, MyBatis mappers, query optimization | Build |
| infra | sonnet | Docker Compose, Kafka topics, Redis config, deployment | Build |
| platform | sonnet | Helm charts, K8S manifests, cluster config [future] | Deploy |
| qa | sonnet | Integration tests, Kafka event validation, API tests | Verify |
| scanner | haiku | File scan, log parsing, docker status, config diff | Recon |

---

## Workflow Override Rules

### Model Selection
- **opus** → Kafka message contract changes, cross-service architecture, security (JWT/Nginx/Gateway), PII encryption decisions
- **sonnet** → ALL implementation (handlers, adapters, controllers, mappers, config, tests, frontend)
- **haiku** → scanning files, reading logs, docker ps/stats ONLY

### 約法三章 (Locked Rules — see WORK_PRINCIPLES.md)
1. **Infrastructure 不動** — Docker/PostgreSQL/Kafka/Nginx/Redis architecture does not change without explicit approval
2. **安全機制同步** — Admin App / User App / Nginx / API Gateway must be modified in coordination
3. **事件流完整性** — Before changing any Kafka Producer/Consumer, confirm full-landscape impact

### Kafka Contract Rule
Before changing any Kafka message structure (header/body):
- Read `docs/3-EVENT-FLOW/CORE_CONTRACTS.md` first
- Changes affect ALL consumers — verify all downstream handlers

### Quick Redeploy
```bash
./quick-redeploy.sh <service>          # single service (30-60s)
./quick-redeploy.sh svc1 svc2 svc3    # multiple services
```
Never do full `docker-compose down && up` unless infrastructure itself changed.

### Debug Limit
After 3 failed fix attempts: STOP, report findings, wait for user.

### Branch Rule
Non-main/master branch: no confirmation needed.

### Verification
Before claiming "done": service starts, relevant API endpoint returns 200, paste output.

### Subagent Context
Every subagent starts fresh. All rules must be in this file or CLAUDE.md.
Read `docs/6-OPERATIONS/OPERATIONS_CURRENT_STATUS.md` for current system state.

---

## Role Details

### architect — System Architect

**Goal**: Ensure cross-service correctness, event contract integrity, and security consistency.

**Owns**:
- `docs/3-EVENT-FLOW/CORE_CONTRACTS.md` — Kafka topic/message contracts (single source of truth)
- `docs/1-ARCHITECTURE/` — Architecture overview and design documents
- `docs/3-EVENT-FLOW/HANDLER_REGISTRY.md` — TaskType → Handler routing table
- Security boundaries: JWT, AES-256-GCM (PII), Nginx, API Gateway
- isRollback logic across all TaskTypes

**Must validate before approving**:
- Any new TaskType added to CORE_CONTRACTS.md
- Any change to Header structure (taskType, merchantId, platformId, isRollback, version)
- Any new Kafka topic or consumer group
- Cross-service dependency changes
- Security mechanism changes (JWT secret, PII encryption key rotation)

**Hands off to**: channel-dev, backend-dev, api-dev after contracts are finalized

---

### channel-dev — Channel Job Developer

**Goal**: Implement platform-specific data adapters that transform each channel's raw API response into OMS unified `orderData`.

**Owns**:
- `simpleec-channel/` — ChannelAdapter interface + platform implementations
- `simpleec-channel-job/` — Channel Job consumers (×10: 5 platforms × fast/slow)
- `simpleec-common/` — Shared enums, models, utilities used by channel jobs

**Responsibilities by platform**:
| Platform | Mode | Key Logic |
|----------|------|-----------|
| Shopee | B | list → FETCH_ORDER_DETAIL → PROCESS_ORDER; cursor pagination |
| Momo | B | item-level records → aggregate by order number; no detail API |
| Yahoo | A/B | time range only (updated_after); Channel Job evaluates status |
| easystore | A | 50 complete orders per call; strict IP/rate limit |
| PChome/Cyberbiz | TBD | |

**Must validate**:
- Channel Job NEVER reads/writes DB directly
- Queue messages contain NO time range — only single `timestamp` from Scheduler
- Time windows (1h, 3d, 5d, 7d) are decided INSIDE the Channel Job, not in messages
- `orderData` output matches OMS unified structure (channelOrderId, items[].channelItemId preserved verbatim)
- Special characters in channelOrderId (`#`, `-`, `@`) preserved — do NOT URL-encode or strip
- isRollback flag set correctly (true for backfill orders)
- Rate limits respected per platform

**Hands off to**: backend-dev via `order.process` or `{platform}.slow` Kafka topics

---

### backend-dev — Process Handler Developer

**Goal**: Implement business logic handlers that process unified `orderData` into DB records with idempotency guarantees.

**Owns**:
- `simpleec-order-job/` — `order.process` consumer (OrderUpsertHandler)
- `simpleec-backend-job/` — `task.backend` consumers (SYNC_PACK, SYNC_PRODUCT, statistics, health check)
- `simpleec-frontend-job/` — `task.frontend` consumers (export, batch update)
- `simpleec-retry-job/` — `task.failed` retry logic + DLT routing
- `simpleec-scheduler-job/` — HeartbeatTimer + Scheduler Consumer

**Must validate**:
- Handler NEVER calls channel API — only processes `orderData` from Kafka
- INSERT vs UPDATE decision based on `merchantId:channelId:channelOrderId` existence in DB
- Redis Layer 2 deduplication: check before write, write after upsert (TTL 7 days)
- isRollback=true: business performance attributes to `channelCreatedAt` date, not today
- isRollback=false: statistics counted for today
- SYNC_PACK → SYNC_PRODUCT dependency: check PRODUCT table first, then PACK table
- Heartbeat Scheduler uses Kafka timestamp, NEVER `new Date()` for local time
- PII fields (buyer_name, buyer_phone, buyer_email, shipping_address) always use EncryptedFieldTypeHandler

**Hands off to**: dba for schema questions, infra for Kafka topic/consumer group changes

---

### api-dev — API & Gateway Developer

**Goal**: Implement REST endpoints and webhook receivers that are the external face of the OMS.

**Owns**:
- `simpleec-api/` — REST API (:8082) — Controllers, VOs, JWT auth
- `simpleec-gateway/` — External Gateway (:8081) — Webhook receivers, ERP integration
- Nginx config (proxy routing between services)
- JWT authentication middleware

**Must validate**:
- All controllers have `/api` prefix on `@RequestMapping` (learned from past bug)
- User endpoints (6): JWT required
- Admin endpoints (2): JWT required
- Public endpoints (3): no auth
- Backend endpoints (2): internal use only
- Webhook security: verify platform signatures before processing
- Admin App changes and User App changes must be coordinated with Nginx config
- No credentials or API keys in source code — environment variables only

**Hands off to**: frontend-dev for UI concerns, infra for Nginx config changes

---

### frontend-dev — Frontend Developer

**Goal**: Build and maintain the admin dashboard and user-facing app.

**Owns**:
- `admin-app/` — Admin dashboard (Vue 3 + Element Plus + Pinia + Vue Router)
- `user-app/` — User-facing app (Vue 3 + Element Plus + Pinia + Vue Router)
- `admin-app/Dockerfile.admin`, `user-app/Dockerfile.user`

**Must validate**:
- API calls use `/api` prefix (matches backend routing)
- JWT token stored in localStorage, sent in Authorization header
- Authentication flow: login → get token → role check → org/warehouse selection (if applicable)
- No iDempiere ERP terminology in UI — use plain language (訂單管理, not ERP jargon)
- Admin and User app changes coordinated — shared auth logic must not diverge
- `npm run build` produces working `dist/` before Dockerfile rebuild

**Hands off to**: api-dev if API contract changes needed

---

### dba — Database Administrator

**Goal**: Ensure DB schema correctness, migration safety, and query performance.

**Owns**:
- `docs/4-SCHEMA/SCHEMA.md` — 19 tables DDL (single source of truth)
- All MyBatis-Plus mappers and Entity classes in `simpleec-core/`
- Index and partition strategy

**Must validate**:
- All PKs are `VARCHAR(20)` NanoID — generated by `IdGenerator.nextId()`, NOT auto-increment
- All FKs (merchantId, channelId, etc.) are `String`, not integer
- PII columns (buyer_name, buyer_phone, buyer_email, shipping_address) use `EncryptedFieldTypeHandler`
- New columns are backward-compatible (nullable or with default)
- `EncryptionContext.setMerchantId()` / `clear()` wraps any read of encrypted columns
- JSONB columns (items[]) store the full array as-is from orderData
- No schema change without updating `docs/4-SCHEMA/SCHEMA.md`

**Hands off to**: backend-dev after schema changes, infra for Postgres migration scripts

---

### infra — Infrastructure / DevOps

**Goal**: Maintain Docker Compose services, Kafka topics, Redis config, and deployment scripts.

**Owns**:
- `docker-compose.yml` — 26 containers
- `quick-redeploy.sh` — Fast service restart (30-60s per service)
- `start-on-boot.sh` — Boot-time startup with image rebuild
- Kafka topics: 10 channel topics + 7 business topics = 17 total
- Consumer group configuration (fast/slow separation)
- `docs/6-OPERATIONS/DOCKER_GUIDE.md`, `OPERATIONS_RUNBOOK.md`
- OTEL Agent, Grafana (Prometheus + Loki + Tempo) monitoring

**Must validate**:
- Never `docker-compose down && up` for code changes — use `./quick-redeploy.sh`
- Full restart only when infrastructure itself changes (Kafka config, Postgres init, etc.)
- Kafka topics created with correct retention: channel=1d, order.process=2h, DLT=30d
- Consumer groups: `{platform}-fast-group`, `{platform}-slow-group` properly separated
- Redis AOF persistence enabled (critical for deduplication TTL survival across restarts)
- `start-on-boot.sh` must rebuild images (prevents running stale code after reboot)
- OTEL Agent version: v2.10.0 — do not upgrade without testing

**Hands off to**: architect for Kafka contract changes, dba for Postgres init scripts

---

### platform — Kubernetes / Helm Platform Engineer [future]

**Goal**: Manage K8S cluster deployment, Helm charts, and production infrastructure.
Current state: system runs on Docker Compose. This role activates when migrating to K8S.

**Owns**:
- `k8s/` or `helm/` — Helm charts, Deployment/StatefulSet manifests
- Ingress config (external traffic routing to simpleec-api :8082 / simpleec-gateway :8081)
- ConfigMap / Secret (K8S-native credential management)
- HPA (autoscaling rules per service)
- Kafka on K8S (Strimzi operator config, if applicable)
- Namespace and RBAC isolation

**Must validate**:
- Docker image tags in Helm values are pinned (not `latest`)
- Secrets use K8S Secret or external vault — never ConfigMap
- Kafka topic config matches `docs/3-EVENT-FLOW/CORE_CONTRACTS.md` (same retention rules as Docker Compose)
- Resource requests/limits set for all 11 Spring Boot services
- Readiness/liveness probes configured before production rollout
- Redis AOF persistence survives pod restart (PVC required)

**Hands off to**: infra for Docker Compose (local dev), architect for cross-service traffic policy

---

### qa — Quality Assurance

**Goal**: Validate end-to-end correctness of event flows, API responses, and data integrity.

**Owns**:
- Integration test scenarios
- Kafka event validation scripts
- `docs/3-EVENT-FLOW/EVENT_SAMPLES.md` — reference payloads for all topics

**Must validate before sign-off**:
- Mode A flow: Scheduler → `{platform}.slow` → PROCESS_ORDER → DB upsert → Redis write
- Mode B flow: Scheduler → `{platform}.slow` → FETCH_ORDER_DETAIL → PROCESS_ORDER → DB upsert
- Idempotency: same message processed twice → no duplicate DB record
- isRollback=true: statistics attributed to channelCreatedAt date, not today
- PII masking: buyer fields encrypted in DB, decrypted in API response
- Error handling: 4xx → task.failed (no retry), 5xx → retry 3×, format error → task.dlt
- All 13 API endpoints return correct status codes (use `docs/0-START/QUICK_COMMANDS.md`)
- Special characters in channelOrderId survive round-trip without corruption

**Hands off to**: backend-dev or channel-dev when failures found, infra when deployment issues found

---

## Platform Channels Quick Reference

| Platform | Mode | Slow Topic | Fast Topic | Special Notes |
|----------|------|------------|------------|---------------|
| Shopee | B | shopee.slow | shopee.fast | needs detail API; cursor pagination |
| Momo | B | momo.slow | momo.fast | item-level aggregation; no detail API |
| Yahoo | A/B | yahoo.slow | yahoo.fast | only updated_after; no status filter |
| easystore | A | easystore.slow | easystore.fast | 50/call complete; strict IP rate limit |
| PChome | TBD | pchome.slow | pchome.fast | not yet implemented |
| Cyberbiz | TBD | cyberbiz.slow | cyberbiz.fast | not yet implemented |

## Kafka Topics Quick Reference

| Topic | Owner | Retention | Purpose |
|-------|-------|-----------|---------|
| `{platform}.fast` | channel-dev | 1d | SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN |
| `{platform}.slow` | channel-dev | 1d | FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, SYNC_PACK |
| `order.process` | backend-dev | 2h | PROCESS_ORDER — Source of Truth |
| `return.process` | backend-dev | 1d | PROCESS_RETURN, APPROVE_RETURN — Source of Truth |
| `task.backend` | backend-dev | 1d | SYNC_PACK result, SYNC_PRODUCT, statistics, health check |
| `task.frontend` | frontend-dev | 1d | Export, batch update |
| `scheduler` | infra | 1d | Heartbeat pulses (1/sec) |
| `task.failed` | backend-dev | 1d | Retryable errors |
| `task.dlt` | backend-dev | 30d | Dead letter — non-retryable |
