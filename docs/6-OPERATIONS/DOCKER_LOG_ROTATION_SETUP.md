# Docker Log Rotation Setup

## Overview

Docker container logs can consume significant disk space if not properly managed. This document describes how to configure Docker daemon with log rotation policies to prevent disk exhaustion.

## Problem

Without log rotation, Docker container logs at `/var/lib/docker/containers/*/` can grow indefinitely, causing:
- Disk space exhaustion (as experienced in Feb 21 incident)
- Performance degradation
- Potential service failures

## Solution

Configure Docker daemon to automatically rotate container logs.

## Setup Instructions

### Step 1: Copy daemon.json to Docker config directory

```bash
sudo cp docker/daemon.json /etc/docker/daemon.json
```

Or create manually:
```bash
sudo vim /etc/docker/daemon.json
```

And paste the contents from `docker/daemon.json` in this repo.

### Step 2: Verify configuration

```bash
sudo cat /etc/docker/daemon.json
```

Expected output:
```json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "100m",
    "max-file": "5",
    "labels": "com.docker.container.id"
  },
  "storage-driver": "overlay2",
  "storage-opts": [
    "overlay2.override_kernel_check=true"
  ]
}
```

### Step 3: Reload Docker daemon

```bash
sudo systemctl reload docker
```

Or restart Docker (this will stop running containers):
```bash
sudo systemctl restart docker
```

### Step 4: Verify log rotation is working

Check if new container logs follow the rotation policy:

```bash
# Start a test container
docker run -d --name test-log-rotation busybox sh -c "while true; do echo 'test log line'; sleep 1; done"

# Monitor log file size
watch -n 2 'du -h /var/lib/docker/containers/*/\*-json.log | head -5'

# After 100MB is reached, new files should be created (test-log-rotation.1, .2, etc.)

# Clean up
docker stop test-log-rotation && docker rm test-log-rotation
```

## Configuration Details

### Parameters

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `log-driver` | `json-file` | Use JSON file logging (standard Docker format) |
| `max-size` | `100m` | Individual log file size limit (100MB) |
| `max-file` | `5` | Keep max 5 rotated log files (500MB total) |
| `labels` | `com.docker.container.id` | Add container ID to log metadata |
| `storage-driver` | `overlay2` | Use overlay2 for efficient image layering |

### Log Rotation Example

```
Container logs directory: /var/lib/docker/containers/{container-id}/

Initial state:
├── {container-id}-json.log (0 - 100MB)

After 100MB reached:
├── {container-id}-json.log.1 (100MB - oldest)
├── {container-id}-json.log    (0 - 100MB - current)

After 5 files created:
├── {container-id}-json.log.4 (100MB - will be deleted)
├── {container-id}-json.log.3 (100MB)
├── {container-id}-json.log.2 (100MB)
├── {container-id}-json.log.1 (100MB)
├── {container-id}-json.log    (0 - 100MB - current)
```

When a 6th file would be created, the oldest (.4) is deleted, keeping max 5 files.

## Troubleshooting

### Docker daemon fails to start

**Error**: `failed to load daemon config`

**Solution**: Validate JSON syntax
```bash
jq . /etc/docker/daemon.json
```

### Containers not restarting after config change

**Error**: Containers are in `Exited` state

**Solution**: Restart containers manually after reloading Docker:
```bash
docker compose up -d
```

### Log files not rotating

**Cause**: Daemon config not yet reloaded

**Solution**:
1. Verify daemon.json is in correct location: `cat /etc/docker/daemon.json`
2. Check Docker is using the config: `docker info | grep "Storage Driver"`
3. Restart Docker daemon: `sudo systemctl restart docker`

## Disk Space Impact

### Before Log Rotation
- Container logs: Unbounded (can reach 50GB+)
- Example problem: Single container with 1 minute of heavy logging fills 1GB log file

### After Log Rotation (max-size=100m, max-file=5)
- Per container: Max 500MB total logs
- All containers combined: Still depends on count, but individual containers are capped
- Automatic cleanup: Old files automatically deleted when limit reached

## Integration with Retention Strategy

This Docker log rotation complements the system-level retention policies:

| Component | Retention | Driver | Auto-Delete |
|-----------|-----------|--------|------------|
| **Docker logs** | 500MB/container | json-file | ✓ (by count) |
| **Kafka** | 1 hour | KRaft log | ✓ (by time) |
| **Prometheus** | 7 days / 5GB | TSDB | ✓ (by time + size) |
| **Loki** | 7 days | BoltDB | ✓ (by time) |

## Monitoring

Monitor log rotation effectiveness:

```bash
# Check current Docker log usage
docker system df

# Check specific container logs
du -sh /var/lib/docker/containers/{container-id}/

# Monitor in real-time
watch -n 5 'df -h /var/lib/docker && echo "---" && du -sh /var/lib/docker/containers/*/ | sort -h | tail -5'
```

## References

- [Docker Daemon Configuration](https://docs.docker.com/config/daemon/)
- [Docker Logging Driver](https://docs.docker.com/config/containers/logging/configure/)
- [JSON File Logging Driver](https://docs.docker.com/config/containers/logging/json-file/)

---

**Last Updated**: 2026-02-21
**Related Documents**:
- [RETENTION_CLEANUP_RUNBOOK.md](RETENTION_CLEANUP_RUNBOOK.md)
- [System Retention & Cleanup Design](docs/plans/2026-02-21-system-retention-cleanup-design.md)
