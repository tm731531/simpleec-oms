# Troubleshooting Guide

Each section below starts with an observable symptom and walks through diagnosis to resolution. Start with the symptom that matches what you are seeing.

---

## Container Keeps Restarting

```bash
# First, see why it crashed
docker compose logs --tail=100 <service-name>

# Check the exit code
docker inspect <container-name> --format='{{.State.ExitCode}}'
# Exit code 137 = OOM kill (raised -Xmx or fix memory leak)
# Exit code 1   = JVM startup error (check logs carefully)
```

**Common causes and fixes:**

| Cause | Log signal | Fix |
|-------|-----------|-----|
| Dependency not ready | `Connection refused` to postgres/redis/kafka | Wait — depends_on health checks should handle this; if persistent, check the dependency container |
| Missing environment variable | `NullPointerException` during startup or `Could not resolve placeholder` | Verify `.env` file is present and has the required variable |
| Port conflict with host process | `Address already in use: 5433` | Stop local PostgreSQL: `sudo systemctl stop postgresql` |
| OOM during startup | Container exits immediately with code 137 | Raise `-Xmx` in `JAVA_TOOL_OPTIONS` for that service in `docker-compose.yml` |
| DB migration failure | `Flyway` or schema errors in logs | Check if schema is out of sync; inspect `docker/init-db/01-schema.sql` |

---

## API Returns 401 Unauthorized

```bash
# Test auth endpoint
curl -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin@example.com","password":"yourpassword"}'

# Use the returned token
curl http://localhost:8082/api/orders \
  -H "Authorization: Bearer <token>"
```

**Causes:**

1. **Token expired** — JWT tokens have a 7-day expiry. Re-login to obtain a new token.

2. **Wrong header format** — The header must be `Authorization: Bearer <token>`. A bare token without `Bearer ` prefix will be rejected.

3. **JWT_SECRET changed** — If the secret was rotated, all existing tokens are invalid. Re-login.

4. **Hitting a protected endpoint without authentication** — Endpoints under `/api/admin/**` and `/api/user/**` require a valid JWT. Public endpoints (`/api/auth/login`, `/api/health`) do not.

5. **CORS preflight failure** — If calling from a browser on a different origin, check that `CORS_ALLOWED_ORIGINS` in the API configuration matches your frontend URL. Wildcard origins (`*`) are not used; origins must be explicitly listed.

---

## Kafka Consumer Lag Building Up

Lag accumulates when messages are produced faster than consumers process them.

```bash
# Step 1: Identify which groups are lagging
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --list

# Step 2: Check the lagging group
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group channel-job-cyberbiz \
  --describe

# Step 3: Check if the consumer container is running and healthy
docker compose ps simpleec-channel-cyberbiz-slow

# Step 4: Check the consumer logs for errors
docker compose logs simpleec-channel-cyberbiz-slow --tail=50
```

**Diagnosis tree:**

```
Lag building?
├── Container is restarting → fix startup error first (see above)
├── Container running but not consuming
│   ├── Check logs for MessageConversionException → StringDeserializer config issue
│   ├── Check logs for ClassNotFoundException → scanBasePackages missing a module
│   └── Check logs for authentication errors against platform API
└── Container consuming but too slowly
    ├── Platform API rate limiting → normal, will self-resolve
    ├── Platform API timing out → check network; add retry
    └── Processing genuinely slow → increase JOB_CHANNEL_CONCURRENCY
```

**Increasing concurrency (temporary fix for lag backlog):**

```bash
# Edit docker-compose.yml for the affected service, e.g.:
#   JOB_CHANNEL_CONCURRENCY: '8'   (was '3')

# Then redeploy that service only
./quick-redeploy.sh simpleec-channel-cyberbiz-slow

# Monitor: lag should decrease within minutes
# Kafka UI → Consumer Groups → channel-job-cyberbiz
```

Note: Increasing concurrency adds load on the platform API. Stay within rate limits.

---

## Orders Not Appearing in Dashboard

Trace the pipeline from the platform through to the database.

**Step 1: Did the channel job fetch orders?**

```bash
docker compose logs simpleec-channel-cyberbiz-slow --tail=100 | grep -E "FETCH_ORDERS|ORDER_UPSERT|order list"
# Look for: "Mode A order list processing completed" or "Successfully sent ORDER_UPSERT"
```

**Step 2: Did order.process receive the message?**

In Kafka UI → Topics → `order.process` → Messages tab. Look for recent ORDER_UPSERT messages matching your expected channel.

**Step 3: Did order-job process the message?**

```bash
docker compose logs simpleec-order-job --tail=100 | grep -E "ORDER_UPSERT|channelOrderId"
# Look for: "Processing ORDER_UPSERT: <channelOrderId>"
# And:      "Successfully processed ORDER_UPSERT: <channelOrderId>"
# And:      "VERIFIED: Order ... successfully persisted to database"
```

**Step 4: Is the order in the database?**

```bash
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT id, channel_order_id, order_status, total_amount, created_at
   FROM orders
   WHERE channel_order_id = 'YOUR_ORDER_ID';"
```

**Step 5: Redis dedup — is the order being skipped as a duplicate?**

```bash
docker compose logs simpleec-order-job --tail=200 | grep "already processed"
# If you see "Order already processed (Redis hash match)", the order content hasn't changed
# since the last fetch — this is normal for unchanged orders
```

**Step 6: PII encryption context**

If the order was saved but buyer name/phone/email fields are null or garbled, check that `EncryptionContext.setMerchantId()` was called before the save:

```bash
docker compose logs simpleec-order-job --tail=200 | grep -i "encrypt\|cipher\|pii"
```

---

## BUILD FAILED

```bash
# Check Java version — must be 17
java -version
# Expected: openjdk version "17.x.x"

# Check Gradle version
./gradlew --version
# Expected: Gradle 8.14.4

# Clean cached build state and retry
./gradlew clean build -x test

# Build a specific module to isolate the error
./gradlew :simpleec-api:build -x test
./gradlew :simpleec-order-job:build -x test
```

**Common build errors:**

| Error | Cause | Fix |
|-------|-------|-----|
| `cannot find symbol: IdGenerator` | Lombok not running | Clean `.gradle/` directory and rebuild |
| `error: package com.simpleec.common does not exist` | Module dependency missing | Check `build.gradle` of the failing module includes `implementation project(':simpleec-common')` |
| `Address already in use: 5432` (test phase) | Local Postgres running on default port | Either stop it or use `-x test` to skip |
| `Execution failed for task ':module:compileJava'` | Syntax error in changed file | Read the full compiler output — it points to the file and line |

---

## Database Connection Refused

```bash
# Check if postgres container is running and healthy
docker compose ps simpleec-postgres
# Expected: Up (healthy)

# Test connection from host (note: host port is 5433, not 5432)
psql -h localhost -p 5433 -U simpleec -d simpleec
# Password: value of DB_PASSWORD in .env (default: simpleec123)

# Test connection from inside a Java service container
docker exec simpleec-api curl -s http://localhost:8080/actuator/health | python3 -m json.tool
# Look for "db" component status

# View postgres logs for connection errors
docker compose logs simpleec-postgres --tail=50
```

If postgres itself is healthy but Java services cannot connect, check that `DB_HOST=postgres` (not `localhost`) is set — containers communicate via Docker's internal DNS, not the host network.

---

## task.dlt Messages Piling Up

Messages land in `task.dlt` when retry attempts are exhausted or when the error is classified as non-retryable (e.g., schema version mismatch, malformed message).

```bash
# View DLT messages
docker exec simpleec-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic task.dlt \
  --from-beginning \
  --max-messages 20

# Check the failed_task_logs table (DLT consumer persists these)
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT task_type, error_type, error_message, created_at
   FROM failed_task_logs
   ORDER BY created_at DESC
   LIMIT 20;"

# Check retry-job logs for routing decisions
docker compose logs simpleec-retry-job --tail=100 | grep -E "DLT|dlt|terminal|non-retryable"
```

**Common DLT root causes:**

| `error_type` | Meaning | Fix |
|-------------|---------|-----|
| `UNSUPPORTED_SCHEMA_VERSION` | Message has schema version the consumer does not support | Update consumer to handle new version, or republish with correct version |
| `MALFORMED_MESSAGE` | Missing required header or body fields | Check the producer — it sent an incomplete message |
| `FORMAT_ERROR` | JSON parse failure | Check the producer is sending valid JSON |
| `SERVER_ERROR_5XX` after max retries | Persistent downstream failure | Fix the underlying service error, then consider manual replay |

To replay DLT messages after fixing the root cause, you can re-publish them to the original topic using Kafka UI's "Produce Message" feature or a custom re-drive script.

---

## Statistics Not Updating

Daily statistics are computed by `DailyStatisticsService` which is triggered by `STATS_RECALC` tasks. The pipeline uses a Redis dirty-set to track which (merchant, platform, channel, date) combinations need recalculation.

```bash
# Check if stats dirty markers are being written
docker compose logs simpleec-order-job --tail=100 | grep "stats dirty"
# Expected: "Marked stats dirty: a00000:SHOPEE:SHOPEE_001:2026-03-28"

# Check if STATS_RECALC is being dispatched
docker compose logs simpleec-scheduler-job --tail=100 | grep STATS_RECALC

# Check if backend-job is processing recalculations
docker compose logs simpleec-backend-job --tail=100 | grep -E "STATS_RECALC|recalculate|Stats upserted"

# Verify data in daily_statistics table
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT merchant_id, platform_id, channel_id, stat_date,
          new_order_count, gross_amount, net_amount
   FROM daily_statistics
   ORDER BY stat_date DESC
   LIMIT 10;"
```

If the `daily_statistics` table is empty but orders exist, the likely cause is:
1. `simpleec-backend-job` is not running
2. The Redis dirty-set is not being populated (check order-job logs)
3. `STATS_RECALC` is not being dispatched by scheduler-job

---

## Cyberbiz API Authentication Failing

```bash
# Look for 401 responses in channel job logs
docker compose logs simpleec-channel-cyberbiz-slow --tail=100 | grep -E "401|authentication|HMAC|signature"
```

Key facts about Cyberbiz authentication:
- API base URL must be `https://api.cyberbiz.co` (not `.io`)
- HMAC signature string format: `x-date: {date}\n{METHOD} {PATH} HTTP/1.1` — note the literal newline and no `request-line:` prefix
- The `CYBERBIZ_API_TOKEN` environment variable must be set in `.env`

If the API token itself is correct but requests still fail with 401, verify the signature format matches the Cyberbiz documentation exactly.

---

## Submodule (user-app) Out of Date

```bash
# Check submodule status
git submodule status

# Update to the commit recorded in the parent repo
git submodule update --init --recursive

# If you need to pull the latest commit from the submodule's remote
git submodule update --remote user-app

# After updating, rebuild the user-app container
./quick-redeploy.sh simpleec-user-app
```

---

## Checking Overall System Health (Quick Checklist)

Run this sequence to get a full health snapshot in under 2 minutes:

```bash
# 1. All containers up?
docker compose ps | grep -v " Up "
# Empty output = all containers healthy

# 2. API responding?
curl -s http://localhost:8082/api/health | python3 -m json.tool

# 3. Any consumer lag?
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --all-groups \
  --describe 2>/dev/null | awk '$6 > 0 {print $0}'
# Any output here = lag exists (column 6 is LAG)

# 4. Recent errors in order-job?
docker compose logs simpleec-order-job --since 10m 2>/dev/null | grep ERROR | tail -20

# 5. DLT messages in last hour?
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT count(*) FROM failed_task_logs WHERE created_at > now() - interval '1 hour';"
```
