# System-Level Retention & Cleanup Strategy Design

**Date**: 2026-02-21
**Status**: Design Approved
**Purpose**: Prevent disk exhaustion (previous incident: 31.85GB reclaimed from build cache + Kafka logs)

---

## Problem Statement

On 2026-02-21 12:36:49, the Kafka broker container ran out of disk space at `/tmp/kraft-combined-logs`, causing exit code 255. Root cause analysis revealed:

1. **Kafka Configuration**: `KAFKA_LOG_RETENTION_HOURS: -1` (infinite retention)
2. **Docker Build Cache**: Accumulated 33.9GB from failed/canceled builds
3. **No Cleanup Policy**: Services had no automated retention or cleanup
4. **No Monitoring**: No early warning system for disk usage

**Solution**: Implement system-level retention + cost-optimized cleanup with disk monitoring.

---

## Design Requirements

### Operational Goals

1. **Cost Optimization**: 1-day Kafka retention + 7-day observability data retention
2. **Early Warning**: Disk alert at 80%, automatic cleanup at 85%
3. **Passive Approach**: Cleanup triggered by threshold, not by time-based cron
4. **Simple Implementation**: Use shell scripts + cron, not complex orchestration
5. **No Data Loss**: Cleanup never deletes active application data

### Scope

| Component | Current State | Target Strategy |
|-----------|---------------|-----------------|
| **Kafka** | -1 (infinite) | 24 hours + segment-based rotation |
| **Prometheus** | Default (∞) | 7 days OR 5GB limit |
| **Loki** | Default (∞) | 7 days auto-cleanup |
| **PostgreSQL WAL** | Default | 4GB max size, 64 segments |
| **Docker Build Cache** | No cleanup | Passive prune at 85% usage |

---

## Detailed Design

### 1. Kafka Log Retention (1 Day)

**File**: `docker-compose.yml` (Kafka service)

```yaml
kafka:
  environment:
    # Current problematic setting
    # KAFKA_LOG_RETENTION_HOURS: -1

    # New settings
    KAFKA_LOG_RETENTION_HOURS: 24           # Retain for 24 hours
    KAFKA_LOG_RETENTION_BYTES: -1           # No size limit (retention_hours takes precedence)
    KAFKA_LOG_SEGMENT_BYTES: 104857600      # 100MB per segment for efficient rotation
    KAFKA_LOG_CLEANUP_POLICY: delete         # Delete old segments (not log compaction)
    KAFKA_LOG_CLEANUP_ENABLE: "true"        # Enable cleanup
    KAFKA_LOG_RETENTION_CHECK_INTERVAL_MS: 300000  # Check every 5 minutes
```

**Rationale**:
- 24-hour window sufficient for debugging channel job issues
- Segment-based rotation ensures natural log rotation boundaries
- Cleanup policy set to `delete` (not `compact`) for space-efficient cleanup
- 5-minute check interval catches excess retention quickly

**Impact**: Reduces `/data/kafka` growth from unlimited to ~2-3GB max.

---

### 2. Prometheus Metrics Retention (7 Days)

**File**: `docker/prometheus/prometheus.yml`

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

  # Add retention settings
  retention: 7d                  # Keep metrics for 7 days
  retention_size: "5GB"          # OR stop ingestion if >5GB (older prom versions)

# Alternative: If using --storage.tsdb.retention.time via command line
# docker-compose.yml:
# command:
#   - '--config.file=/etc/prometheus/prometheus.yml'
#   - '--storage.tsdb.path=/prometheus'
#   - '--storage.tsdb.retention.time=7d'
#   - '--storage.tsdb.retention.size=5GB'
```

**Rationale**:
- 7 days covers typical debugging window + trend analysis
- 5GB cap prevents unbounded disk usage
- Prometheus auto-deletes old blocks based on time

**Impact**: Reduces `/data/prometheus` from unbounded to ~5GB max.

---

### 3. Loki Log Retention (7 Days)

**File**: `docker/loki/loki.yml`

```yaml
limits_config:
  # Enforce per-tenant limits
  ingestion_rate_mb: 10
  ingestion_burst_size_mb: 15
  max_line_length: 262144

  # Retention settings
  retention_enabled: true
  retention_period: 168h         # 7 days (168 hours)
  retention_stream:
    - selector: '{job="simpleec"}'
      period: 168h
      priority: 1

table_manager:
  # Period for table management
  poll_interval: 10m
  retention_deletes_enabled: true
  retention_period: 168h
```

**Rationale**:
- Automatic deletion of logs older than 7 days
- Prevents `/data/loki` unbounded growth
- Sufficient for correlation with Prometheus metrics

**Impact**: Reduces `/data/loki` from unbounded to ~2-3GB max.

---

### 4. PostgreSQL WAL Management

**File**: `docker-compose.yml` (Postgres service)

```yaml
postgres:
  environment:
    POSTGRES_INITDB_ARGS: >
      -c max_wal_size=4GB
      -c wal_keep_segments=64
      -c checkpoint_completion_target=0.9
      -c wal_buffers=16MB
  volumes:
    # Ensure WAL is on same volume for cleanup
    - ${DATA_DIR:-./data}/postgres:/var/lib/postgresql/data
```

**Rationale**:
- `max_wal_size=4GB`: Automatic checkpoint triggers to prevent WAL exceeding 4GB
- `wal_keep_segments=64`: Keep ~1GB of WAL for replication safety
- Reduces `/data/postgres` WAL subdirectory pressure

**Impact**: Reduces PostgreSQL WAL growth from unbounded to ~4GB max.

---

### 5. Disk Monitoring + Automated Cleanup Script

**File**: `docker/cleanup-disk.sh` (new file)

```bash
#!/bin/bash

set -e

# Configuration
THRESHOLD_WARN=80      # Alert when disk usage >= 80%
THRESHOLD_CLEAN=85     # Auto-cleanup when disk usage >= 85%
DOCKER_VOLUME_PATH="/var/lib/docker"  # Adjust if different on your system
LOG_FILE="/var/log/simpleec-cleanup.log"

# Ensure log directory exists
mkdir -p "$(dirname "$LOG_FILE")"

log_message() {
  echo "[$(date +'%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

# Get disk usage of Docker volume (or root if Docker uses root)
get_disk_usage() {
  df "$DOCKER_VOLUME_PATH" 2>/dev/null | tail -1 | awk '{print $5}' | sed 's/%//' || echo "0"
}

main() {
  USAGE=$(get_disk_usage)

  if [ -z "$USAGE" ] || [ "$USAGE" = "0" ]; then
    log_message "❌ Failed to get disk usage, skipping"
    exit 1
  fi

  log_message "📊 Disk usage: ${USAGE}%"

  # Alert at 80%
  if [ "$USAGE" -ge "$THRESHOLD_WARN" ]; then
    log_message "⚠️  ALERT: Disk usage ${USAGE}% >= ${THRESHOLD_WARN}% threshold"
    # TODO: Add email/Slack notification here
  fi

  # Auto-cleanup at 85%
  if [ "$USAGE" -ge "$THRESHOLD_CLEAN" ]; then
    log_message "🔧 Disk usage ${USAGE}% >= ${THRESHOLD_CLEAN}%, triggering cleanup..."

    # 1. Remove unused Docker images, containers, volumes, and build cache
    log_message "  → Removing unused Docker resources..."
    docker system prune -af --volumes \
      --filter "until=72h" \
      2>&1 | grep -E "^(Deleted|Total)" | tee -a "$LOG_FILE" || true

    # 2. Verify Kafka is still running (don't force restart)
    if docker ps | grep -q simpleec-kafka; then
      log_message "  → Kafka broker running, cleanup did not affect it"
    else
      log_message "⚠️  WARNING: Kafka broker not running after cleanup"
    fi

    USAGE_AFTER=$(get_disk_usage)
    log_message "✅ Cleanup complete. Disk usage now: ${USAGE_AFTER}%"

    if [ "$USAGE_AFTER" -lt "$THRESHOLD_CLEAN" ]; then
      log_message "✓ Successfully reduced disk usage below cleanup threshold"
    else
      log_message "⚠️  Disk usage still high after cleanup. Manual intervention may be needed"
    fi
  fi
}

main "$@"
```

**Permissions**:
```bash
chmod +x docker/cleanup-disk.sh
```

**Cron Schedule** (every 30 minutes):
```bash
# Add to crontab:
# crontab -e
*/30 * * * * /home/tom/ONEEC/simpleec-oms/docker/cleanup-disk.sh

# Or as systemd timer (alternative):
# Create: /etc/systemd/system/simpleec-cleanup.timer
# Create: /etc/systemd/system/simpleec-cleanup.service
```

**Rationale**:
- Passive approach: Only cleanup when needed (≥85%)
- 30-minute intervals sufficient for early detection
- Logs all operations for audit trail
- Graceful handling (doesn't force-kill services)

---

### 6. Docker Build Cache Optimization

**File**: All Dockerfiles (add to build stages)

```dockerfile
# At the beginning of builder stage
FROM maven:3.9.8-eclipse-temurin-21 AS builder

# Clean workspace before building
RUN df -h /tmp && \
    rm -rf /tmp/* /var/cache/* && \
    df -h /tmp

# ... rest of build ...

# At the end of builder stage
RUN rm -rf \
    ~/.m2/repository/*/SNAPSHOT* \
    /tmp/maven-* \
    /var/cache/apk/*
```

**Rationale**:
- Removes intermediate build artifacts
- Reduces layer size
- Combined with `docker system prune`, prevents cache bloat

---

## Monitoring & Alerting

### Prometheus Metrics to Watch

```promql
# Available disk space
node_filesystem_avail_bytes{mountpoint="/var/lib/docker"} / 1024^3

# Disk usage percentage
(1 - node_filesystem_avail_bytes / node_filesystem_size_bytes) * 100

# Kafka log directory size (if mounted separately)
du -sb /data/kafka | awk '{print $1}'
```

### Grafana Dashboard

Create a dashboard with:
1. **Disk Usage %** (gauge, alert zones at 80%/85%)
2. **Docker Resource Usage** (images, containers, volumes, cache size)
3. **Kafka Topic Size** (by partition)
4. **Prometheus TSDB Size** (blocks count + age)

### Alert Rules

```yaml
# prometheus.yml rules
groups:
  - name: disk_cleanup
    rules:
      - alert: DiskUsageHigh
        expr: (1 - node_filesystem_avail_bytes / node_filesystem_size_bytes) * 100 > 80
        for: 5m
        annotations:
          summary: "Disk usage {{ $value }}% (threshold: 80%)"

      - alert: DiskUsageCritical
        expr: (1 - node_filesystem_avail_bytes / node_filesystem_size_bytes) * 100 > 85
        for: 1m
        annotations:
          summary: "Disk usage {{ $value }}% - cleanup triggered"
```

---

## Implementation Checklist

- [ ] Update `docker-compose.yml` with Kafka, Prometheus retention settings
- [ ] Update `docker/prometheus/prometheus.yml` with retention_time
- [ ] Update `docker/loki/loki.yml` with retention_period
- [ ] Create `docker/cleanup-disk.sh` script
- [ ] Set script permissions and test locally
- [ ] Add to crontab with `*/30 * * * *` schedule
- [ ] Verify cleanup works at 85% threshold (manual test if needed)
- [ ] Create Grafana dashboard for monitoring
- [ ] Add Prometheus alert rules for disk usage
- [ ] Document rotation strategy in runbook
- [ ] Set up log rotation for `/var/log/simpleec-cleanup.log`

---

## Success Criteria

1. ✓ Kafka automatically deletes messages older than 24 hours
2. ✓ Disk usage alert logged when >= 80%
3. ✓ Automatic cleanup executes when disk >= 85%
4. ✓ Prometheus/Loki data expires after 7 days
5. ✓ PostgreSQL WAL never exceeds 4GB
6. ✓ No disk exhaustion incidents within 30 days of deployment

---

## Rollback Plan

If issues arise:
1. Set `KAFKA_LOG_RETENTION_HOURS: -1` (revert to infinite) temporarily
2. Disable cleanup cron: `crontab -e` → comment out cleanup job
3. Scale up `/data` volume if using Docker volumes
4. Manual cleanup: `docker system prune -af --volumes`

---

## Future Improvements

1. **Alerting Integration**: Send Slack/PagerDuty alerts at 80% threshold
2. **Cloud Metrics**: Export cleanup metrics to CloudWatch/Datadog
3. **Per-Topic Retention**: Different retention for fast/slow topics
4. **Automated Snapshot**: Backup Kafka logs before cleanup (optional)
5. **ML-based Prediction**: Predict disk exhaustion based on growth rate
