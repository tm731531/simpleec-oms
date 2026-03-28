# Monitoring & Observability

SimpleEC OMS uses a full Grafana LGTM stack (Loki, Grafana, Tempo, Mimir-compatible Prometheus) plus Kafka UI for queue health. Every Java service ships with the OpenTelemetry Java agent, which automatically instruments Spring Boot, Kafka clients, and JDBC.

---

## Observability Stack

| Tool | URL | Credentials | Purpose |
|------|-----|-------------|---------|
| Grafana | http://localhost:3000 | admin / admin | Unified dashboards — JVM metrics, Kafka lag, API latency, log exploration, trace search |
| Prometheus | http://localhost:9090 | none | Raw metric scraping and PromQL queries |
| Loki | http://localhost:3100 | none | Log aggregation (query via Grafana → Explore → Loki) |
| Tempo | http://localhost:3200 | none | Distributed trace storage (query via Grafana → Explore → Tempo) |
| Kafka UI | http://localhost:8088 | none | Kafka topic browser, consumer group lag, offset management |

---

## Key Metrics to Watch

### Consumer lag (most critical)

Consumer lag is the primary health signal. Any lag > 0 that is not decreasing indicates a processing problem.

Open Kafka UI → **Consumer Groups** to see all groups and their per-partition lag.

Expected consumer groups and their normal state:

| Consumer Group | Topics consumed | Healthy lag |
|---------------|-----------------|-------------|
| `order-job-group` | `order.process` | 0 |
| `channel-job-momo` | `momo.fast`, `momo.slow` | 0 |
| `channel-job-shopee` | `shopee.fast`, `shopee.slow` | 0 |
| `channel-job-yahoo` | `yahoo.fast`, `yahoo.slow` | 0 |
| `channel-job-pchome` | `pchome.fast`, `pchome.slow` | 0 |
| `channel-job-cyberbiz` | `cyberbiz.fast`, `cyberbiz.slow` | 0 |
| `channel-job-shopline` | `shopline.fast`, `shopline.slow` | 0 |
| `channel-job-shopify` | `shopify.fast`, `shopify.slow` | 0 |
| `scheduler-dispatcher-group-v3` | `scheduler.heartbeat` | 0 |

If lag builds continuously, see the [Troubleshooting Guide](troubleshooting.md).

### JVM memory (watch for OOM risk)

All Java services use `-XX:+ExitOnOutOfMemoryError` — the container will restart rather than limp along in a degraded state. Memory limits per service:

| Service | Heap limit |
|---------|-----------|
| `simpleec-api` | 512 MB |
| `simpleec-order-job` | 384 MB |
| `simpleec-backend-job` | 384 MB |
| `simpleec-channel-*` | 256 MB each |
| `simpleec-gateway` | 256 MB |
| `simpleec-scheduler-job` | 192 MB |
| `simpleec-frontend-job` | 192 MB |
| `simpleec-retry-job` | 192 MB |

In Grafana → JVM dashboard, alert if heap usage exceeds 85% for more than 5 minutes.

### API response time

In Grafana → Spring Boot Actuator dashboard, watch:
- `http_server_requests_seconds_count` by URI and status
- P99 latency > 2 s for any endpoint is a concern
- 5xx rate > 0 warrants immediate investigation

### Error rate in logs

```
# Loki query for any service ERROR in the last 5 minutes
{job=~"simpleec-.*"} | json | level="ERROR"
```

---

## Log Querying with Loki

All logs are JSON-formatted in Docker (profile `docker`). Query them through Grafana → Explore → select **Loki** data source.

### Useful queries

```logql
# All errors for a specific merchant
{container="simpleec-order-job"} | json | level="ERROR" | merchantId="a00000"

# All ORDER_UPSERT processing messages for one channel
{container="simpleec-order-job"} | json | taskType="ORDER_UPSERT" | channelId="SHOPEE_001"

# Dead-letter messages (requires investigation)
{container="simpleec-retry-job"} |= "task.dlt"

# Failed messages with error details
{container=~"simpleec-.*-job"} | json | level="ERROR" | line_format "{{.ts}} [{{.traceId}}] {{.msg}}"

# Cyberbiz API calls (success and failure)
{container=~"simpleec-channel-cyberbiz-.*"} |= "Calling Cyberbiz API"

# Slow Kafka processing warnings (> 10 s)
{container=~"simpleec-channel-.*"} |= "processing completed" | json | duration > 10000

# All messages routed to DLT today
{container=~"simpleec-.*"} |= "task.dlt" | json | __error__=""
```

### MDC fields available in every log entry

All Java services populate the following MDC fields through `TaskMdcHelper.set()`. These fields are included in every structured log line while a message is being processed:

| Field | Example | Description |
|-------|---------|-------------|
| `traceId` | `4b3f2a1c8d...` | OpenTelemetry trace ID — links to Tempo |
| `spanId` | `7e9a3b...` | OpenTelemetry span ID |
| `merchantId` | `a00000` | Merchant being processed |
| `taskType` | `ORDER_UPSERT` | The Kafka task type being handled |
| `channelId` | `SHOPEE_001` | Channel instance ID |

Use these fields to correlate logs across multiple containers for the same business operation.

---

## Checking Consumer Lag

### Via Kafka UI (recommended)

1. Open http://localhost:8088
2. Click **Consumer Groups** in the left sidebar
3. Click a group name to see per-partition lag
4. Any partition with lag > 0 that is not decreasing needs attention

### Via CLI

```bash
# List all consumer groups
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --list

# Check lag for a specific group
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group order-job-group \
  --describe

# Check lag for all channel job groups at once
for group in momo shopee yahoo pchome cyberbiz shopline shopify; do
  echo "=== channel-job-${group} ==="
  docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --group "channel-job-${group}" \
    --describe 2>/dev/null | grep -v "^$"
done

# Check how many messages are in a topic
docker exec simpleec-kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic order.process
```

---

## Distributed Tracing

Every inbound HTTP request and every Kafka message processing span automatically gets a `traceId` from the OTEL Java agent. The trace flows end-to-end:

```
HTTP request → simpleec-api
  → publishes to Kafka (order.process)
    → simpleec-order-job consumes
      → writes to PostgreSQL
```

All of these spans share a single `traceId`, allowing you to reconstruct exactly what happened for any given request.

### Finding a trace

1. Get a `traceId` from a log line (Loki query above, look for `"traceId":"..."`)
2. Open Grafana → Explore → select **Tempo** data source
3. Paste the `traceId` into the search box
4. View the full waterfall of all spans

### Correlating logs and traces in Grafana

Grafana 11 supports trace-to-logs correlation. If configured:
1. Find a slow or failed trace in Tempo
2. Click any span
3. Click "Logs for this span" to jump directly to the relevant Loki log lines

---

## Kafka Topic Overview

Topics are pre-created by `simpleec-kafka-init` using the settings in `docker/init-kafka/create-topics.sh`. Default retention is 1 hour for most topics; the DLT retains for 30 days.

| Topic | Producers | Consumers | Retention |
|-------|-----------|-----------|-----------|
| `scheduler.heartbeat` | scheduler-job (HeartbeatTimer) | scheduler-job (SchedulerEventHandler) | 1 h |
| `scheduler` | scheduler-job | scheduler-job | 1 h |
| `{platform}.fast` | scheduler-job, API | channel-job-{platform} | 1 h |
| `{platform}.slow` | scheduler-job | channel-job-{platform} | 1 h |
| `order.process` | channel-job (ORDER_UPSERT) | order-job | 1 h |
| `return.process` | channel-job (RETURN_UPSERT) | order-job | 1 h |
| `task.backend` | scheduler-job, order-job | backend-job | 1 h |
| `task.frontend` | API | frontend-job | 1 h |
| `task.failed` | any consumer on retryable error | retry-job | 1 d |
| `task.dlt` | retry-job on terminal failure | retry-job (persists to DB) | 30 d |

---

## Alerting Recommendations

The following conditions should trigger alerts in a production environment:

| Condition | Threshold | Suggested action |
|-----------|-----------|-----------------|
| Consumer lag building on `order.process` | Lag > 100 for > 2 min | Check order-job logs; consider increasing concurrency |
| Container restart count > 0 in 10 min | Any service | Check logs for OOM or startup failure |
| `task.dlt` message count increasing | > 5 new messages/hour | Inspect DLT messages; fix root cause |
| `simpleec-api` 5xx rate | > 1% of requests | Check API logs for exception traces |
| PostgreSQL connection count | > 180 (max: 200) | Check for connection leaks or slow queries |
| Kafka broker offline | Any | System halts — investigate kafka container |
