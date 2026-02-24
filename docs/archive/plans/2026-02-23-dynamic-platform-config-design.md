# Dynamic Platform Configuration Architecture

**Version:** 1.0
**Date:** 2026-02-23
**Status:** Design Approved
**Author:** Claude Code

---

## 1. Overview

SimpleEC OMS currently supports 7 e-commerce platforms (MOMO, Shopee, Yahoo, PChome, Cyberbiz, Shopline, Shopify). The infrastructure must support these platforms at scale while maintaining a single source of truth for configuration across docker-compose (development) and future Kubernetes (production) deployments.

**Design Goal:** Create a configuration-driven architecture that eliminates docker-compose hardcoding while maintaining the ability to scale platform support and track channel health with 5-minute granularity.

---

## 2. Problem Statement

### Current State
- docker-compose.yml hardcodes all 8 platform-specific channel-job containers (momo-fast, momo-slow, shopee-fast, etc.)
- Adding a new platform requires manually editing docker-compose and restarting the system
- Platform configurations are scattered between docker-compose and database
- No unified way to track channel/platform health across time windows

### Requirements
1. **Configuration as Code:** Single source of truth (config.yaml) for all platforms
2. **Environment Agnostic:** Same config generates docker-compose (dev) and future k8s manifests (prod)
3. **Health Tracking:** 5-minute granularity logs with platform + channel level diagnostics
4. **Merchant Control:** Merchants can enable/disable channel sync via UI without affecting other operations

---

## 3. Design Solution

### 3.1 Single Configuration Source (config.yaml)

**File:** `config.yaml` (root directory, version controlled)

```yaml
system:
  name: "SimpleEC OMS"
  version: 1

platforms:
  - code: momo
    name: "MOMO"
    fastConcurrency: 8
    slowConcurrency: 4

  - code: shopee
    name: "Shopee"
    fastConcurrency: 8
    slowConcurrency: 4

  - code: yahoo
    name: "Yahoo 購物中心"
    fastConcurrency: 8
    slowConcurrency: 4

  - code: pchome
    name: "PChome"
    fastConcurrency: 8
    slowConcurrency: 4

  - code: cyberbiz
    name: "Cyberbiz"
    fastConcurrency: 6
    slowConcurrency: 3

  - code: shopline
    name: "Shopline"
    fastConcurrency: 6
    slowConcurrency: 3

  - code: shopify
    name: "Shopify"
    fastConcurrency: 8
    slowConcurrency: 4

infrastructure:
  postgres:
    port: 5433
    image: postgres:16-alpine

  redis:
    port: 6379
    image: redis:7-alpine

  kafka:
    port: 9092
    image: apache/kafka:3.7.1
    autoCreateTopics: true
    retentionHours: 1

services:
  api:
    port: 8082
    contextPath: /api

  gateway:
    port: 8081

  nginx:
    port: 8089

  userApp:
    port: 5173

  adminApp:
    port: 8084

jobs:
  orderJob: {}
  schedulerJob: {}
  backendJob: {}
  frontendJob: {}
  retryJob: {}
```

**Principles:**
- One file to add/remove platforms
- Future k8s/Helm can read same config
- Version controlled, audit trail of changes

---

### 3.2 Configuration Generation Pipeline

**Script:** `docker/generate-compose.py` (Python 3.8+)

```
config.yaml
    ↓
[Python script processes]
    ↓
Template substitution for all services
    ↓
Output: docker-compose.yml
```

**Script Responsibilities:**
1. Read config.yaml
2. For each platform, generate:
   - `simpleec-channel-{code}-fast` container
   - `simpleec-channel-{code}-slow` container
   - Environment variables: `JOB_CHANNEL_TOPICS`, `JOB_CHANNEL_GROUP_ID`, `JOB_CHANNEL_CONCURRENCY`
3. Add infrastructure services (postgres, redis, kafka, etc.)
4. Add API and job services (order-job, scheduler-job, backend-job, etc.)
5. Add frontend apps (user-app, admin-app, nginx)
6. Output complete docker-compose.yml

**Usage:**
```bash
python docker/generate-compose.py config.yaml docker-compose.yml
```

**Advantages:**
- No manual docker-compose editing
- All changes go through config.yaml
- Easy to version control
- Reproducible deployments

---

### 3.3 Kafka Topic Provisioning

**Auto-Creation Enabled:**
```yaml
KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
```

**Topic Coverage:**

Platform Topics (auto-created on first message):
- `momo.fast`, `momo.slow`
- `shopee.fast`, `shopee.slow`
- `yahoo.fast`, `yahoo.slow`
- `pchome.fast`, `pchome.slow`
- `cyberbiz.fast`, `cyberbiz.slow`
- `shopline.fast`, `shopline.slow`
- `shopify.fast`, `shopify.slow`

System Topics (fixed):
- `scheduler` - Scheduling tasks
- `order.process` - Order data pipeline
- `return.process` - Return/refund data
- `task.backend` - Backend processing (SYNC_PACK, UPDATE_PRICE)
- `task.frontend` - Frontend tasks
- `task.failed` - Failed task recovery
- `task.dlt` - Dead letter tracking

**Consumer Groups:**
- Auto-created when first message published
- Pattern: `channel-job-{platform}-{speed}`
- Example: `channel-job-momo-fast`, `channel-job-shopee-slow`

---

## 4. Data Model

### 4.1 Platform Table (Admin-managed)

**table: `platform`**

```sql
id              VARCHAR(20)  PRIMARY KEY
platform_name   VARCHAR(50)  NOT NULL
actived         BOOLEAN      DEFAULT true
currency        VARCHAR(3)   DEFAULT 'TWD'
created_at      TIMESTAMPTZ
updated_at      TIMESTAMPTZ
```

**Lifecycle:**
1. Developers complete adapter implementation (CyberbizAdapter, etc.)
2. Add platform to config.yaml
3. Generate new docker-compose
4. Start all jobs (Kafka auto-creates topics)
5. Admin inserts platform record via Admin App
6. Platform becomes visible in User App ChannelPage

---

### 4.2 Channel Table (Merchant-managed, Existing)

**table: `channel`** (Already exists - no schema changes)

```sql
id                    VARCHAR(20)   PRIMARY KEY
merchant_id           VARCHAR(20)   NOT NULL (FK → merchant)
platform_id           VARCHAR(20)   NOT NULL (FK → platform)
channel_name          VARCHAR(256)
channel_sn            VARCHAR(128)

-- Multi-token support
token                 VARCHAR(4096) NOT NULL
token2, token3, token4, token5  VARCHAR(4096)

-- Control flags
actived               BOOLEAN       DEFAULT true
enable_sync           BOOLEAN       DEFAULT false   -- ← Key flag (ALREADY EXISTS)
write_actived         BOOLEAN       DEFAULT false

-- Timestamp tracking
first_sync_start_time TIMESTAMPTZ
first_sync_end_time   TIMESTAMPTZ
last_sync_time        TIMESTAMPTZ
created_at            TIMESTAMPTZ
updated_at            TIMESTAMPTZ
```

**Semantics:**
- `actived` = Channel exists (soft delete)
- `enable_sync` = Whether to process this channel's messages (ALREADY PRESENT)
  - `false` → Channel-Job skips messages for this channel
  - `true` → Channel-Job processes; Scheduler includes in health checks
- `write_actived` = Whether to allow write operations
- `last_sync_time` = Timestamp of last successful sync (populated by channel-job)

**Note:** The `enable_sync` field already exists in current schema - no changes needed.

---

### 4.3 Channel Sync Logs Table (NEW - Health Tracking)

**table: `channel_sync_logs`**

```sql
id               VARCHAR(20)       PRIMARY KEY
merchant_id      VARCHAR(20)       NOT NULL
platform_id      VARCHAR(20)       NOT NULL  (FK → platform)
channel_id       VARCHAR(20)       NULL      (FK → channel, nullable)

sync_type        VARCHAR(20)       NOT NULL  -- 'CHANNEL' or 'PLATFORM'
http_status      INTEGER           NOT NULL  -- 200, 401, 500, etc.
health           VARCHAR(20)       GENERATED ALWAYS AS
                 (CASE WHEN http_status >= 400 THEN 'unhealthy' ELSE 'healthy' END)

error_message    TEXT
request_payload  TEXT              (optional)
response_payload TEXT              (optional)

created_at       TIMESTAMPTZ       NOT NULL DEFAULT now()
```

**Indexes:**
```sql
CREATE INDEX idx_sync_log_channel ON channel_sync_logs (channel_id, created_at DESC);
CREATE INDEX idx_sync_log_platform ON channel_sync_logs (platform_id, created_at DESC);
CREATE INDEX idx_sync_log_merchant ON channel_sync_logs (merchant_id, created_at DESC);
```

**Log Types:**

| sync_type | channel_id | Meaning |
|-----------|-----------|---------|
| `CHANNEL` | NOT NULL  | Health check for specific channel (e.g., token validity) |
| `PLATFORM` | NULL     | Health check for entire platform (e.g., API availability) |

**HTTP Status Codes:**
- `200-399` → `health = 'healthy'`
- `400+` → `health = 'unhealthy'`

**Common Status Patterns:**
- `200` - Success
- `401` - Unauthorized (token expired/invalid)
- `403` - Forbidden (permission issue)
- `500` - Server error (platform service down)
- `503` - Service unavailable

---

## 5. Data Flow

### 5.1 Channel Setup Flow (Merchant adds channel)

```
Merchant opens User App → ChannelPage
    ↓
Shows available platforms from DB: SELECT * FROM platform WHERE actived=true
    ↓
Merchant selects "Shopee" and fills:
  - account_name: "台北旗艦店"
  - api_token: "shop_xxx_yyy"
    ↓
POST /api/user/channels
{
  "platformId": "shopee",
  "accountName": "台北旗艦店",
  "apiToken": "shop_xxx_yyy"
}
    ↓
Backend validates:
  - Platform exists and is actived?
  - Token format valid?
    ↓
INSERT INTO channel
(id, merchant_id, platform_id, channel_name, token, actived, enable_sync)
VALUES
(nanoid(), 'M001', 'shopee', '台北旗艦店', 'shop_xxx_yyy', true, false)
    ↓
Response: { channelId: 'abc123xyz...', status: 'created' }
    ↓
Merchant sees new channel in ChannelPage
Channel status: "Created" (enable_sync=false)
```

---

### 5.2 Channel Activation Flow (Merchant enables sync)

```
Merchant toggles "Enable Sync" in ChannelPage UI
    ↓
PUT /api/user/channels/{channelId}
{ "enableSync": true }
    ↓
UPDATE channel SET enable_sync=true WHERE id='abc123xyz...'
    ↓
Response: { status: 'enabled', syncStartsAt: 'now' }
    ↓
Now Channel-Job will process messages for this channel
```

---

### 5.3 Message Processing Flow (with enable_sync gate)

```
External system publishes to Kafka:
shopee.fast topic
{
  "merchantId": "M001",
  "channelId": "abc123xyz...",
  "taskType": "FETCH_ORDERS",
  "orderId": "S12345"
}
    ↓
Channel-Job (listening to shopee.fast) receives
    ↓
[GATE 1: Check enable_sync]
SELECT enable_sync FROM channel WHERE id='abc123xyz...'
    ↓
If enable_sync=false → SKIP (log to channel_sync_logs as 'skipped')
If enable_sync=true → CONTINUE
    ↓
Query DB: SELECT token FROM channel WHERE id='abc123xyz...'
    ↓
Call Shopee API with token:
  GET /api/v2/orders?filter=unshipped
    ↓
[Record sync result]
INSERT INTO channel_sync_logs
(merchant_id, platform_id, channel_id, sync_type, http_status, error_message)
VALUES ('M001', 'shopee', 'abc123xyz...', 'CHANNEL', 200, null)
    ↓
Transform to OMS schema:
{ orderId, buyerName, totalAmount, items, ... }
    ↓
Publish to order.process topic
    ↓
Order-Job consumes and persists to orders table
(Order-Job doesn't check enable_sync; only Channel-Job gates)
```

---

### 5.4 Health Check Flow (5-minute cycle)

```
Scheduler-Job (runs every 5 minutes)
    ↓
FOR EACH enabled channel:
  SELECT id, platform_id FROM channel WHERE enable_sync=true
    ↓
  Publish to platform.{fast/slow} topic:
  {
    "taskType": "CHECK_HEALTH",
    "merchantId": "M001",
    "channelId": "abc123xyz..."
  }
    ↓
FOR EACH platform (once per 5 min):
  SELECT platform_id FROM platform WHERE actived=true
    ↓
  Publish to platform.fast topic:
  {
    "taskType": "CHECK_HEALTH_PLATFORM",
    "platformId": "shopee"
  }
    ↓
Channel-Job receives both types
    ↓
[Type: CHANNEL HEALTH CHECK]
  Query DB: SELECT token FROM channel WHERE id='abc123xyz...'
  Call quick API: GET /api/v2/health
  http_status = 200 (or 401, 500, etc.)
  INSERT INTO channel_sync_logs (channel_id, http_status, health='healthy'/'unhealthy')
    ↓
[Type: PLATFORM HEALTH CHECK]
  No specific channel
  Call Shopee public endpoint (no auth needed)
  http_status = 200 (or 503, etc.)
  INSERT INTO channel_sync_logs (platform_id, channel_id=null, http_status, health)
    ↓
Result logged with created_at timestamp (5-min granularity)
```

---

## 6. Analysis & Diagnostics

### 6.1 Channel Health Status

**ChannelPage shows per-channel:**
```
SELECT * FROM channel_sync_logs
WHERE channel_id='abc123xyz...'
  AND sync_type='CHANNEL'
ORDER BY created_at DESC
LIMIT 1
```

Result shows current health + last sync time

---

### 6.2 Platform vs Channel Issues (Time-Series Analysis)

**Scenario: Multiple channels failing on same platform**

```sql
-- Check platform health
SELECT platform_id, http_status, created_at
FROM channel_sync_logs
WHERE merchant_id='M001'
  AND platform_id='shopee'
  AND sync_type='PLATFORM'
ORDER BY created_at DESC;

-- Check channel health
SELECT channel_id, http_status, created_at
FROM channel_sync_logs
WHERE merchant_id='M001'
  AND platform_id='shopee'
  AND sync_type='CHANNEL'
ORDER BY created_at DESC;
```

**Diagnosis Rules:**
```
IF platform http_status < 400:
  AND all channels http_status >= 400 (e.g., 401):
  → Channel token issue (not platform issue)
  → Action: Notify merchant to refresh tokens

IF platform http_status >= 400:
  AND channels also fail:
  → Platform service issue
  → Action: Notify merchant to wait for platform recovery
  → Action: Automatically pause enable_sync for affected channels (optional)

IF platform http_status < 400:
  BUT some channels work (200) AND some fail (401):
  → Mixed: Some channels have bad tokens
  → Action: Target notification to affected channels only
```

**Time-Window Analysis:**
```sql
-- Find when issue started (5-min granularity)
SELECT created_at, http_status
FROM channel_sync_logs
WHERE channel_id='abc123xyz...'
  AND sync_type='CHANNEL'
ORDER BY created_at DESC;

-- Output shows exact 5-min windows when status changed
```

---

## 7. Role Responsibilities

### 7.1 Developers/Ops (Admin App)

**When adding new platform:**
1. Implement platform adapter (e.g., CyberbizAdapter)
2. Edit `config.yaml`: add platform to `platforms` list
3. Run: `python docker/generate-compose.py config.yaml docker-compose.yml`
4. Start docker-compose: `docker-compose up -d`
5. Kafka auto-creates platform.fast, platform.slow topics
6. Open Admin App → Insert platform record (name, code, status='active')
7. Platform now visible to merchants in User App

---

### 7.2 Merchants (User App)

**Channel Setup:**
1. Open User App → ChannelPage
2. See list of available platforms (from platform table)
3. Click "Add Channel" → Select platform → Enter account name + API token
4. Save → Channel created with `enable_sync=false`
5. Test token by clicking "Check Health" (optional)
6. Toggle "Enable Sync" → System starts processing

**Monitoring:**
- View channel health (last 24h logs)
- View sync history (orders synced, errors)
- Quick-disable any channel if having issues

---

### 7.3 Backend Jobs

**Channel-Job:**
- Listen to platform.{fast,slow} topics
- Check `enable_sync` before processing (gate)
- If enabled: fetch token, call API, record health
- If disabled: skip with log entry
- Publish successful data to order.process

**Scheduler-Job:**
- Every 5 minutes:
  - Publish CHECK_HEALTH task for all enabled channels
  - Publish CHECK_HEALTH_PLATFORM task for all platforms

**Order-Job, Backend-Job, etc.:**
- Do NOT check `enable_sync`
- Process whatever reaches their topics
- Only Channel-Job enforces the gate

---

## 8. Kafka Topic Naming Convention

**Platform Topics (created dynamically):**
```
{platform_code}.fast
{platform_code}.slow
```

Example:
- momo.fast, momo.slow
- shopee.fast, shopee.slow
- cyberbiz.fast, cyberbiz.slow

**System Topics (pre-created):**
- scheduler
- order.process
- return.process
- task.backend, task.frontend, task.failed
- task.dlt (dead letter)

**Consumer Group Naming:**
```
channel-job-{platform_code}-{speed}
```

Example:
- channel-job-momo-fast
- channel-job-shopee-slow

---

## 9. Configuration Changes & Deployments

### 9.1 Adding a New Platform

**Steps:**
1. Implement adapter + tests
2. Edit `config.yaml`:
   ```yaml
   - code: newplatform
     name: "New Platform"
     fastConcurrency: 8
     slowConcurrency: 4
   ```
3. Run: `python docker/generate-compose.py config.yaml docker-compose.yml`
4. Restart: `docker-compose up -d`
5. Admin App: Insert platform record
6. Done! Merchants can now see it in ChannelPage

**Kafka:** Topics auto-created on first message

---

### 9.2 Adjusting Concurrency

**Edit `config.yaml`:**
```yaml
- code: momo
  fastConcurrency: 16  # Was 8
  slowConcurrency: 8   # Was 4
```

**Regenerate and restart:**
```bash
python docker/generate-compose.py config.yaml docker-compose.yml
docker-compose up -d
```

---

### 9.3 Future: Kubernetes Migration

**Same config file generates k8s manifests:**
```bash
python k8s/generate-manifests.py config.yaml k8s/
```

**Result:**
- Helm values or kustomize overlays
- All platforms defined once, deployable to k8s
- No config duplication

---

## 10. Future Considerations

### 10.1 Runtime Platform Registration (Out of Scope for MVP)

Currently: Platforms added at deploy-time (config.yaml)

Future: Could add API to register platforms at runtime without restart
- Would require container orchestration (k8s) or dynamic channel-job scaling
- MVP doesn't need this; config-based is sufficient

### 10.2 Credential Rotation

`channel` table supports 5 token fields (token, token2-5) for gradual rotation
- Can update token2 without affecting token
- Implement zero-downtime token rotation in future

### 10.3 Alerting Integration

`channel_sync_logs` data feeds into:
- Alert system (Prometheus rules on http_status >= 400)
- Dashboard (Grafana showing health timeline)
- Notification service (email/SMS to merchant on token expiry)

---

## 11. Testing Strategy

### Unit Tests
- config.yaml parsing
- docker-compose generation logic
- Health check HTTP status mapping

### Integration Tests
- Full flow: config.yaml → docker-compose → services running
- Kafka topic creation
- Channel-Job enable_sync gate behavior
- Scheduler health check publishing

### Acceptance Tests
- Merchant adds channel → visible in UI
- Enable sync → job processes messages
- Disable sync → job skips messages
- Health check records appear in logs within 5 min

---

## 12. Success Criteria

✅ Single config.yaml defines all 7 platforms
✅ docker-compose generated from config, no manual editing
✅ Merchants can add channels without system restart
✅ Channel enable/disable gates message processing correctly
✅ 5-minute health checks with HTTP status codes recorded
✅ Platform vs channel issues diagnosable from time-series logs
✅ Can add new platform in < 10 minutes (code + config)
✅ Future k8s migration uses same config file

---

## Appendix: File Structure

```
simpleec-oms/
├── config.yaml                          # ← Single source of truth
├── docker-compose.yml                   # Generated from config.yaml
├── docker/
│   ├── generate-compose.py              # Generator script
│   ├── init-db/
│   │   └── 01-schema.sql               # Includes channel_sync_logs
│   └── ...
├── simpleec-channel-job/               # Channel-Job source
│   └── src/.../ChannelJobApplication.java
├── simpleec-scheduler-job/             # Scheduler-Job source
├── admin-app/                          # Admin UI (manage platforms)
├── user-app/                           # User UI (manage channels)
├── docs/
│   ├── plans/
│   │   └── 2026-02-23-dynamic-platform-config-design.md (this file)
│   └── ...
└── ...
```

---

**End of Design Document**
