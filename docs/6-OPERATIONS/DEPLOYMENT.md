# SimpleEC OMS - Deployment & Architecture Guide

**Last Updated**: Feb 23, 2026 | **Status**: ✅ All systems operational

This document describes the deployment process and architecture for the SimpleEC OMS system.

## Table of Contents

1. [Quick Start](#quick-start)
2. [System Architecture](#system-architecture)
3. [Service Startup](#service-startup)
4. [Environment Configuration](#environment-configuration)
5. [Port Mapping](#port-mapping)
6. [Troubleshooting](#troubleshooting)
7. [Recent Changes (Feb 23)](#recent-changes-feb-23)

---

## Quick Start

### All-in-One Deployment

```bash
cd /home/tom/ONEEC/simpleec-oms
bash start-all.sh
```

**System accessible at**:
- **User App** (via reverse proxy): http://localhost:8089/
- **Admin App** (via reverse proxy): http://localhost:8089/admin/
- **API** (direct): http://localhost:8083/api
- **Kafka UI**: http://localhost:8088
- **Nginx** (reverse proxy): http://localhost:8089

**External URLs** (Cloudflare):
- User App: https://oms.tomting.com
- Admin App: https://oms-admin.tomting.com

---

## System Architecture

### Modern Frontend Architecture (Feb 22+)

The system uses **independent containerized frontend apps** with **Nginx reverse proxy**:

```
┌──────────────────────────────────────────────────────────┐
│            Nginx Reverse Proxy (8089)                    │
│  - Route: / → User App (5173)                            │
│  - Route: /admin/ → Admin App (8084)                     │
│  - Route: /api/* → Backend API (8083)                    │
└──────────────────┬─────────────────┬──────────────────────┘
                   │                 │
        ┌──────────▼──────┐   ┌──────▼──────────┐
        │   User App      │   │   Admin App     │
        │   (5173)        │   │   (8084)        │
        │  Vue 3 + Vite   │   │  Vue 3 + Vite   │
        │  (Docker)       │   │  (Docker)       │
        └─────────────────┘   └─────────────────┘
                   │                 │
                   └────────┬────────┘
                            │
                   ┌────────▼─────────┐
                   │  Backend API     │
                   │  (8083)          │
                   │ Spring Boot      │
                   │ REST Endpoints   │
                   └──────────────────┘
```

### Why This Architecture?

- **Decoupling**: Each frontend app is independently deployable
- **Simplicity**: No micro-frontend complexity (Qiankun removed)
- **Scalability**: Nginx handles routing, load balancing ready
- **Debugging**: Direct port access (8084 for Admin, 5173 for User) for development

### Services

| Service | Port | Inside Docker | Purpose | Container |
|---------|------|---|---------|-----------|
| **Nginx** (Reverse Proxy) | 8089 | - | Routes /, /admin/, /api/ | simpleec-nginx |
| **User App** | 5173 | 5173 | Merchant dashboard | simpleec-user-app |
| **Admin App** | 8084 | 8084 | Platform admin panel | simpleec-admin-app |
| **API** | 8083 | 8083 | REST backend | simpleec-api |
| **PostgreSQL** | 5433 | 5432 | Primary database | simpleec-postgres |
| **Kafka** | 9092 | 9092 | Message queue (KRaft) | simpleec-kafka |
| **Redis** | 6379 | 6379 | Cache & sessions | simpleec-redis |
| **Kafka UI** | 8088 | 8088 | Topic monitoring | simpleec-kafka-ui |
| **Job Services** | - | - | Order/Channel/Retry jobs | simpleec-*-job |

### Key Components

#### Frontend (Vue 3 + Vite)
- **User App**: `/user-app/` - Merchant order & channel management
  - API detection: Localhost → `http://localhost:8083/api`, Remote → `/api`
  - Response interceptor handles multiple API response formats
  - Test credentials: `admin@a00000.com` / `pass123456`

- **Admin App**: `/admin-app/` - Platform management
  - Public API (no JWT required)
  - User/merchant CRUD, statistics, analytics

#### Backend (Spring Boot 3.5.0)
- **API Server** (8083): REST endpoints for user & admin operations
  - `/api/auth/*` - Login, logout, session management
  - `/api/user/*` - Products, orders, refunds, channels, settings
  - `/api/admin/*` - Platform, merchant, account CRUD + stats
  - JWT-based auth for user endpoints
  - Public endpoints for admin operations

#### Kafka Infrastructure
- **Message Queue**: KRaft-based (no ZooKeeper)
- **Consumer Groups**: 8 groups handling platform sync, order processing, task workflows
  - ChannelJob: Platform data sync (cyberbiz, momo, pchome, shopee, yahoo)
  - OrderJob: Order & return processing
  - RetryJob: Failed message handling + DLT
  - BackendJob, FrontendJob, SchedulerJob: System tasks
- **Topics**: 15 total (10 platform + 1 order + 4 system)
- **Retention**: 1 hour (aggressive disk cleanup enabled)

---

## Service Startup

### Option 1: Complete System (Recommended)

```bash
cd /home/tom/ONEEC/simpleec-oms
bash start-all.sh
# Watches startup stages [1/5] through [5/5]
```

### Option 2: Docker Compose (Manual)

```bash
cd /home/tom/ONEEC/simpleec-oms

# Start all services
docker compose up -d

# Verify
docker compose ps

# View logs
docker compose logs -f simpleec-api
```

### Option 3: Individual Services

```bash
# Start infrastructure only
docker compose up -d postgres kafka redis

# Build backend (if not cached)
./gradlew clean build -x test

# Start backend
docker compose up -d simpleec-api

# Start frontends
cd user-app && npm run dev    # localhost:5173
cd admin-app && npm run dev   # localhost:8084

# Start Nginx (optional, for reverse proxy testing)
docker compose up -d simpleec-nginx  # localhost:8089
```

---

## Environment Configuration

### Backend (API)

File: `docker/Dockerfile.api` & `docker-compose.yml`

**Key Variables**:
```yaml
SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/simpleec_oms
SPRING_DATASOURCE_USERNAME: postgres
SPRING_DATASOURCE_PASSWORD: postgres123
KAFKA_BOOTSTRAP_SERVERS: kafka:9092
REDIS_HOST: redis
REDIS_PORT: 6379
JWT_SECRET: (auto-generated)
```

### Frontend (User App)

File: `/user-app/src/api/index.ts`

**Smart Endpoint Detection**:
```typescript
function getAPIBaseURL(): string {
  const { hostname } = window.location

  // Localhost: Direct to backend (8083)
  if (hostname === 'localhost' || hostname === '127.0.0.1') {
    return 'http://localhost:8083/api'
  }

  // Remote: Via reverse proxy
  return '/api'
}
```

**Benefits**:
- Works with direct localhost access (port 5173)
- Works with reverse proxy (port 8089)
- Works with Cloudflare domains (oms.tomting.com)
- No build-time variables needed

### Frontend (Admin App)

File: `/admin-app/src/api/index.ts` - Similar configuration

---

## Port Mapping

### External Access

| URL | Port | Routes To | Purpose |
|-----|------|-----------|---------|
| http://localhost:8089/ | 8089 | User App (5173) | Default route, reverse proxy |
| http://localhost:8089/admin/ | 8089 | Admin App (8084) | Admin panel, reverse proxy |
| http://localhost:8089/api/* | 8089 | API (8083) | Backend API, reverse proxy |
| https://oms.tomting.com | 443 | localhost:8089 | External User App (Cloudflare) |
| https://oms-admin.tomting.com | 443 | localhost:8089/admin/ | External Admin App (Cloudflare) |

### Direct Development Access

| URL | Service | Purpose |
|-----|---------|---------|
| http://localhost:5173 | User App | Direct dev server (no proxy) |
| http://localhost:8084 | Admin App | Direct dev server (no proxy) |
| http://localhost:8083 | API | Direct REST backend |
| http://localhost:8088 | Kafka UI | Topic monitoring |

---

## Troubleshooting

### User App Login Fails

**Symptoms**: "Wrong email or password" error

**Fixes Applied (Feb 23)**:
1. ✅ API endpoint corrected: 8082 → 8083
2. ✅ Response interceptor fixed: Handles multiple API response formats
3. ✅ Docker network fixed: User App on simpleec-oms_default network

**Verify**:
```bash
# Test API directly
curl -X POST http://localhost:8083/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@a00000.com","password":"pass123456"}'

# Check containers on same network
docker network inspect simpleec-oms_default | grep simpleec-user-app
```

### Reverse Proxy Returns 502 Bad Gateway

**Cause**: User App not reachable from Nginx

**Fix**:
```bash
# Ensure User App is on correct network
docker inspect simpleec-user-app | grep NetworkID

# Restart User App with network flag
docker compose down simpleec-user-app
docker compose up -d simpleec-user-app
```

### Kafka Consumer Groups Not Created

**Symptoms**: `kafka-consumer-groups.sh --list` returns nothing

**Status (Feb 23)**: ✅ Fixed - All 8 groups operational

**Verify**:
```bash
# Check consumer group logs
docker logs simpleec-channel-job | grep "groupId="

# Check via Kafka CLI
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --list
```

See [simpleec-oms-kafka-consumer-groups-fix.md](/memory/simpleec-oms-kafka-consumer-groups-fix.md) for detailed root cause analysis.

### API Returns 500 Errors

**Debug Steps**:
```bash
# View API logs
docker logs simpleec-api -f --tail 50

# Check database connection
docker exec simpleec-postgres psql -U postgres -c "SELECT 1"

# Verify Kafka connectivity
docker logs simpleec-api | grep -i kafka
```

### KafkaUI Shows Timeout Errors

**Status**: ✅ Expected behavior - UI timeout issue only, not data loss

**Facts**:
- Topics exist: Verified via `kafka-topics.sh`
- Messages flow correctly: Verified via consumer group logs
- KafkaUI metadata refresh has timeout issue

**Workaround**: Use CLI commands instead
```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

---

## Recent Changes (Feb 23)

### ✅ User App Login Issue - FIXED

**Problem**: Login failed even with correct credentials

**Root Causes**:
1. API endpoint was `8082` instead of `8083`
2. Response interceptor didn't handle login response format `{user, token}`
3. User App container not on same Docker network as Nginx

**Solutions**:
1. Updated `/user-app/src/api/index.ts` with smart endpoint detection
2. Fixed response interceptor with format-aware unwrapping
3. Reconnected User App to `simpleec-oms_default` network

### ✅ Kafka Consumer Groups - FIXED

**Problem**: No consumer groups visible

**Root Causes**:
1. ServiceAutoConfiguration auto-loading OrderService in non-JPA jobs
2. KafkaConfig bean conflicts between core and job services
3. Overly broad component scanning

**Solutions**:
1. Added `exclude = {ServiceAutoConfiguration.class}` to all job @SpringBootApplication
2. Removed KafkaConfig from core's AutoConfiguration.imports
3. Narrowed scanBasePackages to job-specific packages

**Status**: 8 consumer groups operational, consuming from 15 topics

See [simpleec-oms-kafka-consumer-groups-fix.md](/memory/simpleec-oms-kafka-consumer-groups-fix.md) for detailed technical analysis.

### System Retention Strategy

- **Kafka**: 1 hour retention (KAFKA_LOG_RETENTION_HOURS=1)
- **PostgreSQL**: 4GB WAL max (max_wal_size=4GB)
- **Docker Logs**: 100MB × 5 per container
- **Prometheus**: 7 days / 5GB
- **Loki**: 7 days retention
- **Automated Cleanup**: Every 30 minutes, alert at 80%, cleanup at 85%

---

## Testing

### Health Checks

```bash
# API health
curl http://localhost:8083/api/admin/health

# User App (direct)
curl http://localhost:5173 -I

# User App (via proxy)
curl http://localhost:8089 -I

# Admin App (via proxy)
curl http://localhost:8089/admin/ -I

# Nginx reverse proxy
curl http://localhost:8089 -I
```

### Login Test

```bash
# Get token
TOKEN=$(curl -s -X POST http://localhost:8083/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@a00000.com","password":"pass123456"}' \
  | jq -r '.token')

# Use token
curl -H "Authorization: Bearer $TOKEN" http://localhost:8083/api/user/me
```

### Browser Testing

1. Open http://localhost:8089/ (User App via proxy)
2. Login with `admin@a00000.com` / `pass123456`
3. Open http://localhost:8089/admin/ (Admin App via proxy)

---

## Next Steps

1. ✅ Verify User App login works
2. ✅ Monitor Kafka consumer groups (verified Feb 23)
3. Test Order/Channel management flows
4. Performance testing and optimization
5. Production deployment checklist

---

**Support**: Check `/memory/` directory for:
- [simpleec-oms-current-status.md](simpleec-oms-current-status.md) - URLs & quick commands
- [simpleec-oms-fixes-feb23.md](simpleec-oms-fixes-feb23.md) - Root cause analysis
- [simpleec-oms-kafka-consumer-groups-fix.md](simpleec-oms-kafka-consumer-groups-fix.md) - Kafka troubleshooting
