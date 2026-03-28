# Deployment Guide

SimpleEC OMS runs as 26 Docker containers managed by Docker Compose. This guide covers fresh deployments, per-service rebuilds during development, and teardown procedures.

---

## Prerequisites

- Docker Engine 24+ and Docker Compose v2
- Java 17 (for building Java services)
- Gradle 8.14.4 (wrapper included — `./gradlew`)
- Git with submodule support
- 8 GB RAM minimum recommended (16 GB for full stack with observability)

---

## Full System Deployment (Fresh Install)

```bash
# 1. Clone repository and initialise submodules
git clone git@github.com:tm731531/simpleec-oms.git
cd simpleec-oms
git submodule update --init --recursive

# 2. Configure environment
cp .env.example .env
# Edit .env — at minimum set:
#   DB_PASSWORD          PostgreSQL password (default: simpleec123)
#   CYBERBIZ_API_TOKEN   Cyberbiz HMAC secret
#   WEBHOOK_SECRET_SHOPIFY, WEBHOOK_SECRET_SHOPEE, WEBHOOK_SECRET_EASYSTORE

# 3. Build all Java services (skip tests — none exist yet)
./gradlew clean build -x test
# Expected output: BUILD SUCCESSFUL

# 4. Launch all containers
docker compose up -d --build
# Kafka KRaft initialisation takes ~30 seconds; kafka-init runs topic creation after that
# Allow 2-3 minutes for the full stack to become healthy

# 5. Verify the system is up
docker compose ps                        # All containers should show "Up" or "Up (healthy)"
curl http://localhost:8082/api/health    # Expected: {"status":"UP"}
```

### First-boot checklist

- [ ] `simpleec-postgres` is healthy (`pg_isready` passes)
- [ ] `simpleec-kafka` is healthy (broker API versions endpoint responds)
- [ ] `simpleec-kafka-init` exited with code 0 (topics created)
- [ ] `simpleec-api` passes health check on port 8082
- [ ] `simpleec-kafka-ui` is accessible at http://localhost:8088

---

## Per-Service Rebuild (Development)

Use `quick-redeploy.sh` after modifying a single service. It rebuilds only the affected Docker image and restarts the container — completing in 30–60 seconds instead of the 3–5 minutes required for a full stack restart.

### How quick-redeploy.sh works

1. Validates the service name exists in `docker-compose.yml`
2. Runs `docker compose build --no-cache <service>` to rebuild the image
3. Stops and removes the old container
4. Starts the new container with `docker compose up -d`
5. Prints log-tailing instructions

### Single-service rebuild

```bash
# After modifying simpleec-api source
./gradlew :simpleec-api:build -x test
./quick-redeploy.sh simpleec-api

# After modifying simpleec-order-job source
./gradlew :simpleec-order-job:build -x test
./quick-redeploy.sh simpleec-order-job

# After modifying simpleec-channel-job source (affects all 14 channel containers)
./gradlew :simpleec-channel-job:build -x test
./quick-redeploy.sh simpleec-channel-cyberbiz-slow
```

### Multiple services at once

```bash
# When a shared module change affects multiple services
./gradlew clean build -x test
./quick-redeploy.sh simpleec-channel-cyberbiz-fast simpleec-channel-cyberbiz-slow

# When simpleec-core changes affect both order and backend jobs
./quick-redeploy.sh simpleec-order-job simpleec-backend-job
```

### Helper flags

```bash
./quick-redeploy.sh --list              # List all 26+ services by category
./quick-redeploy.sh --deps simpleec-api # Show what this service depends on
./quick-redeploy.sh --all               # Full teardown and rebuild (interactive confirmation)
./quick-redeploy.sh --help              # Full usage reference
```

### When to use --all instead

Use `--all` when:
- Changing `docker-compose.yml` itself (environment variables, port mappings)
- Modifying infrastructure init scripts (`docker/init-db/`, `docker/init-kafka/`)
- Updating base images
- After a system reboot (if containers did not auto-restart)

---

## Infrastructure-Only Startup

For development sessions where you only want the data layer and observability, skip the Java application containers:

```bash
docker compose up -d \
  postgres \
  redis \
  kafka \
  kafka-init \
  simpleec-kafka-ui \
  simpleec-prometheus \
  simpleec-grafana \
  simpleec-loki \
  simpleec-otel-collector \
  simpleec-tempo
```

Then start individual Java services as needed:

```bash
docker compose up -d simpleec-api
docker compose up -d simpleec-order-job
```

---

## Stopping and Cleanup

```bash
# Stop all containers, preserve volumes (data survives)
docker compose down

# Stop all containers AND remove all volumes (WARNING: all data lost)
docker compose down -v

# Stop a single service without removing it
docker compose stop simpleec-order-job

# Remove dangling build cache and images
docker image prune -f
docker builder prune -f
```

---

## Container Reference (All 26 Containers)

### Infrastructure Layer

| Container | Port | Purpose |
|-----------|------|---------|
| `simpleec-postgres` | 5433:5432 | PostgreSQL 16 — primary database. Exposed on host port 5433 to avoid conflicts with local Postgres. |
| `simpleec-redis` | 6379:6379 | Redis 7 with AOF persistence — order dedup cache, stats dirty set, session storage. |
| `simpleec-kafka` | 9092:9092 | Apache Kafka 3.7.1 in KRaft mode (no ZooKeeper). Single broker for development. |
| `simpleec-kafka-init` | — | One-shot container that creates all Kafka topics on first boot. Exits with code 0 on success. |

### Observability Layer

| Container | Port | Purpose |
|-----------|------|---------|
| `simpleec-kafka-ui` | 8088:8080 | Kafka UI — browse topics, consumer groups, and message offsets. |
| `simpleec-prometheus` | 9090:9090 | Prometheus — scrapes metrics from Spring Boot Actuator endpoints. |
| `simpleec-grafana` | 3000:3000 | Grafana — dashboards for JVM metrics, Kafka lag, and API latency. |
| `simpleec-loki` | 3100:3100 | Loki — log aggregation. Receives JSON logs from all Java services. |
| `simpleec-otel-collector` | 4317:4317 | OpenTelemetry Collector (OTLP gRPC) — receives traces from OTEL agents. |
| `simpleec-tempo` | 3200:3200 | Grafana Tempo — distributed trace storage and query. |

### Channel Jobs (14 containers — 7 platforms × fast/slow)

Each platform runs two consumer instances: `fast` (actions, <5 s expected) and `slow` (fetch/sync, <5 min expected).

| Container | Topic consumed | Concurrency |
|-----------|---------------|-------------|
| `simpleec-channel-momo-fast` | `momo.fast` | 3 |
| `simpleec-channel-momo-slow` | `momo.slow` | 3 |
| `simpleec-channel-shopee-fast` | `shopee.fast` | 3 |
| `simpleec-channel-shopee-slow` | `shopee.slow` | 3 |
| `simpleec-channel-yahoo-fast` | `yahoo.fast` | 3 |
| `simpleec-channel-yahoo-slow` | `yahoo.slow` | 3 |
| `simpleec-channel-pchome-fast` | `pchome.fast` | 3 |
| `simpleec-channel-pchome-slow` | `pchome.slow` | 3 |
| `simpleec-channel-cyberbiz-fast` | `cyberbiz.fast` | 3 |
| `simpleec-channel-cyberbiz-slow` | `cyberbiz.slow` | 3 |
| `simpleec-channel-shopline-fast` | `shopline.fast` | 3 |
| `simpleec-channel-shopline-slow` | `shopline.slow` | 3 |
| `simpleec-channel-shopify-fast` | `shopify.fast` | 3 |
| `simpleec-channel-shopify-slow` | `shopify.slow` | 3 |

All channel jobs share the same image (`simpleec-channel-job`) and are differentiated by the `JOB_CHANNEL_TOPICS` and `JOB_CHANNEL_GROUP_ID` environment variables.

To increase throughput during consumer lag, raise `JOB_CHANNEL_CONCURRENCY` (e.g., from 3 to 8) in `docker-compose.yml` and redeploy the affected containers.

### System Job Layer

| Container | Topic consumed | Memory limit | Purpose |
|-----------|---------------|-------------|---------|
| `simpleec-order-job` | `order.process` | 384 MB | Upserts orders to PostgreSQL with two-layer dedup (Redis + DB). |
| `simpleec-scheduler-job` | `scheduler.heartbeat` | 192 MB | Heartbeat-driven dispatcher — publishes FETCH_ORDERS, FETCH_RETURNS, and report tasks every 5 minutes. |
| `simpleec-backend-job` | `task.backend` | 384 MB | Internal async tasks: SYNC_PRODUCT, STATS_RECALC, report generation. |
| `simpleec-frontend-job` | `task.frontend` | 192 MB | Merchant-initiated actions routed from the API. |
| `simpleec-retry-job` | `task.failed`, `task.dlt` | 192 MB | Retries failed messages and routes terminal failures to the DLT. |

### API and Frontend Layer

| Container | Port | Purpose |
|-----------|------|---------|
| `simpleec-api` | 8082:8080 | Spring Boot REST API — merchant and admin endpoints. Requires JWT authentication. |
| `simpleec-gateway` | 8081:8081 | Webhook receiver for platform push events (Shopify, Shopee, Easystore). Publishes directly to Kafka. |
| `simpleec-user-app` | 5173:5173 | Vue 3 merchant-facing UI. |
| `simpleec-admin-app` | 8084:8084 | Vue 3 admin UI. |
| `simpleec-nginx` | 8089:80, 8090:443 | Nginx reverse proxy — routes `/api/*` to simpleec-api, serves static assets for both apps. |
| `simpleec-test-seeder` | — | Python test data generator (profile: `test` — not started by default). |

---

## Environment Variables Reference

These variables are read from `.env` by Docker Compose. The value shown after `:-` is the default used when the variable is not set.

| Variable | Default | Used by |
|----------|---------|---------|
| `DB_PASSWORD` | `simpleec123` | All Java services + postgres |
| `DATA_DIR` | `./data` | postgres, redis, kafka, prometheus, grafana, loki, tempo |
| `CYBERBIZ_API_TOKEN` | _(required)_ | simpleec-channel-cyberbiz-fast/slow |
| `CYBERBIZ_API_BASE_URL` | `https://api.cyberbiz.co` | simpleec-channel-cyberbiz-fast/slow |
| `WEBHOOK_SECRET_SHOPIFY` | _(empty)_ | simpleec-gateway |
| `WEBHOOK_SECRET_SHOPEE` | _(empty)_ | simpleec-gateway |
| `WEBHOOK_SECRET_EASYSTORE` | _(empty)_ | simpleec-gateway |

Sensitive values must never be committed to version control. The `.env` file is in `.gitignore`.
