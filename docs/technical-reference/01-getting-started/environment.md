# Environment Variables Reference

All configuration is supplied via environment variables loaded from the `.env` file at the
repository root. Docker Compose reads this file automatically when you run `docker compose up`.

Copy the template before making changes:

```bash
cp .env.example .env
```

Variables marked **YES (prod)** in the Required column must be changed from their development
defaults before deploying to any internet-accessible environment.

---

## Core Infrastructure

These variables control how each Spring Boot service connects to the shared infrastructure
components (PostgreSQL, Redis, Kafka).

| Variable | Default | Required | Description |
|---|---|---|---|
| `DB_HOST` | `simpleec-postgres` | No | PostgreSQL hostname. The default resolves inside the Docker Compose network. Change only if using an external database. |
| `DB_PORT` | `5432` | No | PostgreSQL port. The external host mapping is `5433 → 5432`; the internal port stays `5432`. |
| `DB_NAME` | `simpleec` | No | Database name. |
| `DB_PASSWORD` | `simpleec123` | **YES (prod)** | Database password. Must be changed in production. |
| `REDIS_HOST` | `simpleec-redis` | No | Redis hostname. |
| `REDIS_PORT` | `6379` | No | Redis port. |
| `KAFKA_BOOTSTRAP_SERVERS` | `simpleec-kafka:9092` | No | Kafka bootstrap server address. Uses the internal Docker network hostname. |

---

## API Service (`simpleec-api`)

| Variable | Default | Required | Description |
|---|---|---|---|
| `JWT_SECRET` | *(dev placeholder)* | **YES (prod)** | Secret used to sign and verify JWT tokens. Must be at least 256 bits (32+ random bytes). Generate with: `openssl rand -base64 64` |
| `JWT_EXPIRATION` | `604800` | No | Token expiry in seconds. Default is 7 days (604800s). Adjust based on security requirements. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080,...` | **YES (prod)** | Comma-separated list of allowed CORS origins. Set to the exact domain(s) of your frontend in production (e.g., `https://app.example.com`). Wildcards are not supported. |

---

## Channel Job Configuration

Each channel job container is configured independently. This allows running multiple instances
with different platform assignments and concurrency levels.

| Variable | Example Value | Description |
|---|---|---|
| `JOB_CHANNEL_TOPICS` | `momo.fast,momo.slow` | Comma-separated Kafka topics this job instance subscribes to. Each platform has a `.fast` topic (high-priority, short-lived tasks) and a `.slow` topic (batch fetch operations). |
| `JOB_CHANNEL_GROUP_ID` | `channel-job-momo` | Kafka consumer group ID. Must be unique per platform to ensure each platform's messages are consumed independently. |
| `JOB_CHANNEL_CONCURRENCY` | `3` | Number of concurrent Kafka consumer threads. Increase to improve throughput when platform API rate limits permit. Current production setting is `8`. |

### Supported Topic Names

```
momo.fast      momo.slow
shopee.fast    shopee.slow
yahoo.fast     yahoo.slow
pchome.fast    pchome.slow
cyberbiz.fast  cyberbiz.slow
easystore.fast easystore.slow
```

---

## Platform API Credentials

Each e-commerce platform requires its own API credentials. All platforms follow the same
naming convention: `{PLATFORM}_API_URL`, `{PLATFORM}_USERNAME`, `{PLATFORM}_SECRET`.

### Cyberbiz

| Variable | Description |
|---|---|
| `CYBERBIZ_API_URL` | Base URL for Cyberbiz API. Production: `https://api.cyberbiz.co` |
| `CYBERBIZ_USERNAME` | API username provided by Cyberbiz. Demo: `apidemo` |
| `CYBERBIZ_SECRET` | HMAC signing secret provided by Cyberbiz. Demo: `apidemo` |

### Shopee

| Variable | Description |
|---|---|
| `SHOPEE_API_URL` | Base URL for Shopee Partner API. |
| `SHOPEE_PARTNER_ID` | Shopee Partner ID (numeric). |
| `SHOPEE_PARTNER_KEY` | Shopee Partner secret key for HMAC signing. |

### Momo

| Variable | Description |
|---|---|
| `MOMO_API_URL` | Base URL for Momo Commerce API. |
| `MOMO_USERNAME` | API username. |
| `MOMO_SECRET` | API secret key. |

### Yahoo

| Variable | Description |
|---|---|
| `YAHOO_API_URL` | Base URL for Yahoo Commerce API. |
| `YAHOO_CLIENT_ID` | OAuth client ID. |
| `YAHOO_CLIENT_SECRET` | OAuth client secret. |

### Easystore

| Variable | Description |
|---|---|
| `EASYSTORE_API_URL` | Base URL for Easystore API. |
| `EASYSTORE_API_KEY` | API key for authentication. |

> **Note:** PChome credentials are configured similarly when that platform integration is
> implemented. Placeholder keys exist in `.env.example`.

---

## Scheduler Job

| Variable | Default | Description |
|---|---|---|
| `SCHEDULER_CRON_FAST` | `0 */5 * * * *` | Cron expression for fast-channel heartbeat (every 5 minutes). |
| `SCHEDULER_CRON_SLOW` | `0 0 * * * *` | Cron expression for slow-channel batch fetch (every hour). |

---

## Observability

| Variable | Default | Description |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://simpleec-otel:4317` | OpenTelemetry Collector gRPC endpoint. Traces, metrics, and logs are sent here and forwarded to Tempo, Prometheus, and Loki respectively. |
| `OTEL_SERVICE_NAME` | *(set per service)* | Service name tag attached to all telemetry data. Each service sets this to its own module name (e.g., `simpleec-api`). |

---

## Kafka Retention (Advanced)

Kafka topic retention periods can be tuned via Spring properties mapped from these variables.
Defaults are sufficient for development; adjust for production storage constraints.

| Variable | Default | Description |
|---|---|---|
| `KAFKA_RETENTION_DEFAULT` | `86400000` | Default topic retention in milliseconds (1 day). |
| `KAFKA_RETENTION_DLT` | `2592000000` | Dead-letter topic retention in milliseconds (30 days). |

---

## Production Security Checklist

Before deploying to any environment accessible from the internet:

- [ ] `DB_PASSWORD` — set to a strong, randomly generated password (not the default)
- [ ] `JWT_SECRET` — set to a random string of at least 256 bits (`openssl rand -base64 64`)
- [ ] `CORS_ALLOWED_ORIGINS` — set to the exact production frontend domain(s); no wildcards
- [ ] All platform API credentials — set to production (not demo/sandbox) values
- [ ] `.env` file — confirmed absent from git history (`git log --all -- .env` shows nothing)
- [ ] `.env` file — permissions restricted to owner only (`chmod 600 .env`)
- [ ] Grafana default admin password — changed from `admin/admin` via the UI after first login

---

## Loading .env Outside of Docker Compose

If you need to source the `.env` file for a local script or manual command:

```bash
# Export all variables from .env into the current shell session
set -a
source .env
set +a
```
