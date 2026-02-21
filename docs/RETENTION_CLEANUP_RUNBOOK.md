# Retention & Cleanup Runbook

## Quick Reference

| Component | Retention | Status |
|-----------|-----------|--------|
| Kafka | 1 hour | ✓ Configured |
| Prometheus | 7 days / 5GB | ✓ Configured |
| Loki | 7 days | ✓ Configured |
| PostgreSQL WAL | 4GB max | ✓ Configured |
| Docker Container Logs | 100MB x 5 files | ✓ Configured |
| Docker Cache | Passive cleanup | ✓ Configured |

## Monitoring

### Check Disk Usage

```bash
df -h /var/lib/docker
# Or inside container:
docker exec simpleec-prometheus du -sh /prometheus
docker exec simpleec-loki du -sh /loki
```

### View Cleanup Logs

```bash
sudo tail -50 /var/log/simpleec-cleanup.log
sudo tail -f /var/log/simpleec-cleanup.log  # Follow in real-time
```

### Check Cron Job Status

```bash
crontab -l | grep cleanup-disk
# Or check cron logs:
sudo tail -f /var/log/syslog | grep CRON
```

### Verify Retention Configuration

```bash
# Check Kafka retention settings
docker exec simpleec-kafka kafka-configs.sh --bootstrap-server localhost:9092 \
  --describe --entity-type brokers

# Check Prometheus config
docker exec simpleec-prometheus cat /etc/prometheus/prometheus.yml | grep retention

# Check Loki retention
docker exec simpleec-loki cat /etc/loki/local-config.yaml | grep -A 5 retention
```

## Troubleshooting

### Disk Usage Still High After Cleanup

1. Run cleanup manually: `sudo /home/tom/ONEEC/simpleec-oms/docker/cleanup-disk.sh`
2. Check what's taking space: `docker system df`
3. Check individual container volumes:
   ```bash
   docker exec simpleec-prometheus du -sh /prometheus
   docker exec simpleec-loki du -sh /loki
   ```
4. If still high, run aggressive prune: `docker system prune -af --volumes`

### Cleanup Script Not Running

1. Check crontab: `crontab -l`
2. Verify permissions: `ls -la /home/tom/ONEEC/simpleec-oms/docker/cleanup-disk.sh` (should have `x` flag)
3. Test manually: `/home/tom/ONEEC/simpleec-oms/docker/cleanup-disk.sh`
4. Check for errors:
   ```bash
   bash -x /home/tom/ONEEC/simpleec-oms/docker/cleanup-disk.sh
   ```
5. Verify sudo access: Script may need sudo for docker commands. Check sudoers:
   ```bash
   sudo visudo -c  # Validate sudoers syntax
   ```

### Kafka Retention Not Working

1. Verify config applied:
   ```bash
   docker exec simpleec-kafka kafka-configs.sh --bootstrap-server localhost:9092 \
     --describe --entity-type brokers --entity-name 1 | grep retention
   ```
2. Check Kafka logs:
   ```bash
   docker logs simpleec-kafka | grep -i retention | tail -20
   ```
3. Verify environment variables in docker-compose.yml are applied
4. Restart Kafka if config changed:
   ```bash
   docker compose down && docker compose up -d kafka
   ```

### Prometheus Data Not Clearing

1. Check TSDB status:
   ```bash
   docker exec simpleec-prometheus curl -s http://localhost:9090/api/v1/admin/tsdb/cardinality
   ```
2. Verify retention settings in prometheus.yml:
   ```bash
   docker exec simpleec-prometheus cat /etc/prometheus/prometheus.yml | grep -i retention
   ```
3. Force compaction (if available):
   ```bash
   docker exec simpleec-prometheus curl -X POST http://localhost:9090/-/compact
   ```

### Loki Data Not Clearing

1. Check retention policy:
   ```bash
   docker exec simpleec-loki cat /etc/loki/local-config.yaml | grep -A 10 retention_deletes_enabled
   ```
2. Ensure `retention_deletes_enabled: true` is set
3. Check Loki logs:
   ```bash
   docker logs simpleec-loki | grep -i retention | tail -20
   ```
4. Restart Loki to force retention scan:
   ```bash
   docker compose restart loki
   ```

## Manual Operations

### Force Cleanup Now

```bash
# Run Docker cleanup
sudo docker system prune -af --volumes

# Or run custom cleanup script
sudo /home/tom/ONEEC/simpleec-oms/docker/cleanup-disk.sh
```

### Manually Trigger Loki Retention Delete

```bash
# Force deletion of expired logs
docker exec simpleec-loki loki \
  -config.file=/etc/loki/local-config.yaml \
  -cleanup.enabled=true
```

### Manually Delete Old Kafka Messages (use with caution!)

```bash
# Get list of topics
docker exec simpleec-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list

# Delete specific topic (recreates empty) - WARNING: data will be lost
docker exec simpleec-kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --delete --topic topic-name

# Wait for deletion
sleep 10

# Recreate topic
docker exec simpleec-kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic topic-name --partitions 1 --replication-factor 1
```

### Restore Kafka Retention to Infinite (rollback)

Edit `docker-compose.yml` Kafka section - change:
```yaml
KAFKA_LOG_RETENTION_HOURS: 1
```

To:
```yaml
KAFKA_LOG_RETENTION_HOURS: -1
```

Then restart:
```bash
docker compose up -d kafka
```

⚠️ **Warning**: Infinite retention can cause disk exhaustion. Only use for debugging purposes and revert immediately.

### View Current Topic Retention Settings

```bash
docker exec simpleec-kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic topic-name
```

### Disable Cleanup Temporarily

```bash
# Remove from crontab
crontab -e
# Comment out or delete cleanup line

# Or disable script
chmod -x /home/tom/ONEEC/simpleec-oms/docker/cleanup-disk.sh
```

## Docker Log Rotation

Docker container logs should be configured with automatic rotation to prevent disk exhaustion.

### Setup

```bash
# Copy daemon.json to Docker config directory
sudo cp docker/daemon.json /etc/docker/daemon.json

# Reload Docker daemon
sudo systemctl reload docker
```

See [DOCKER_LOG_ROTATION_SETUP.md](DOCKER_LOG_ROTATION_SETUP.md) for detailed instructions.

### Check Docker Log Usage

```bash
# Overall Docker system usage
docker system df

# Check specific container logs
du -sh /var/lib/docker/containers/{container-id}/
```

## Performance Impact

- **Kafka 1 hour retention**: 100-200MB per hour (depends on message volume)
  - Formula: `~100MB * (messages_per_second / 100)`
  - At full capacity (1000 msg/sec): ~100MB/hour retention cycle

- **Prometheus 7 days**: ~5GB max (enforced by retention policy)
  - Per million series: ~500MB per day
  - Typical (2-5k active metrics): 50-100MB per day

- **Loki 7 days**: 2-3GB based on ingestion
  - Per GB/hour ingestion: ~7GB in 7 days
  - Typical (100MB/hour logs): 2.1GB per week

- **Docker Container Logs**: 100MB x 5 per container (automatic rotation)
  - Automatic cleanup when 5 files reached
  - Old files deleted to maintain limit

- **PostgreSQL WAL**: Up to 4GB (circular buffer, auto-archived)
  - Only high-traffic days approach 4GB limit

- **Cleanup overhead**: < 1 minute per run
  - Runs every 30 minutes
  - No impact on application performance

## Alerts to Monitor

### Grafana Dashboard Metrics

Watch these metrics for space issues:

```
# Disk usage percentage
node_filesystem_avail_bytes{mountpoint="/var/lib/docker"} /
node_filesystem_size_bytes{mountpoint="/var/lib/docker"} * 100

# Prometheus TSDB blocks
prometheus_tsdb_symbol_table_size_bytes
prometheus_tsdb_storage_blocks_bytes

# Loki storage
loki_boltdb_shipper_index_cache_requests_total

# Kafka broker disk
kafka_server_replica_manager_at_min_isr_partition_count
```

### Alert Thresholds

- Disk Usage > 80% (warning) - monitor cleanup
- Disk Usage > 85% (critical) - cleanup triggers immediately
- Kafka lag > 100 (warning) - replication lagging
- Prometheus TSDB blocks > 1000 (warning) - too much historical data
- Kafka broker restart (critical) - may indicate space issues

## Validation

### Verify All Systems Working

```bash
#!/bin/bash

echo "=== Checking Disk Usage ==="
df -h | grep docker

echo "=== Checking Docker System ==="
docker system df

echo "=== Checking Kafka Retention ==="
docker exec simpleec-kafka kafka-configs.sh --bootstrap-server localhost:9092 \
  --describe --entity-type brokers 2>/dev/null | grep retention || echo "Kafka running"

echo "=== Checking Prometheus Logs ==="
docker logs simpleec-prometheus 2>/dev/null | grep -i retention | tail -3 || echo "Prometheus OK"

echo "=== Checking Loki Logs ==="
docker logs simpleec-loki 2>/dev/null | grep -i retention | tail -3 || echo "Loki OK"

echo "=== Cleanup Script Status ==="
ls -lh /home/tom/ONEEC/simpleec-oms/docker/cleanup-disk.sh
```

### Expected Health State

- Disk available: > 20GB (warning) / > 50GB (healthy)
- Kafka topics count: < 50 active topics
- Prometheus cardinality: < 100k active metrics
- Loki index size: < 5GB
- Cleanup job: runs every 30 minutes without errors
- All containers: running and stable

## Maintenance Schedule

| Task | Frequency | Owner | Notes |
|------|-----------|-------|-------|
| Check disk usage | Daily | DevOps | Via monitoring dashboard |
| Review cleanup logs | Weekly | DevOps | Look for errors or failures |
| Validate retention | Monthly | Platform | Verify all systems compliant |
| Capacity planning | Quarterly | DevOps | Review growth trends |
| Runbook review | Quarterly | Platform | Update based on learnings |

## Escalation Path

1. **Self-service**: Use monitoring dashboard and alerts
2. **Cleanup failure**: Run manual cleanup script + check logs
3. **Persistent issues**:
   - Check Docker daemon: `docker info`
   - Restart affected service: `docker compose restart <service>`
   - Restart stack: `cd /home/tom/ONEEC/simpleec-oms && docker compose down && docker compose up -d`
4. **Escalate to DevOps**: If manual steps don't resolve

---

**Last Updated**: 2026-02-21
**Maintained By**: DevOps / Platform Team
**Version**: 1.0
