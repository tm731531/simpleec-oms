#!/bin/bash

set -e

# Configuration
THRESHOLD_WARN=80      # Alert when disk usage >= 80%
THRESHOLD_CLEAN=85     # Auto-cleanup when disk usage >= 85%
DOCKER_VOLUME_PATH="/var/lib/docker"  # Adjust if different on your system
LOG_FILE="/var/log/simpleec-cleanup.log"

# Determine writable log location
if ! touch "$LOG_FILE" 2>/dev/null; then
  LOG_FILE="/tmp/simpleec-cleanup.log"
  mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || true
fi

log_message() {
  local msg="[$(date +'%Y-%m-%d %H:%M:%S')] $1"
  echo "$msg"
  echo "$msg" >> "$LOG_FILE" 2>/dev/null || true
}

# Get disk usage of Docker volume (or root if Docker uses root)
get_disk_usage() {
  df "$DOCKER_VOLUME_PATH" 2>/dev/null | tail -1 | awk '{print $5}' | sed 's/%//' || echo "0"
}

main() {
  USAGE=$(get_disk_usage)

  if [ -z "$USAGE" ] || [ "$USAGE" = "0" ]; then
    log_message "ERROR: Failed to get disk usage, skipping"
    exit 1
  fi

  log_message "DISK_CHECK: Disk usage: ${USAGE}%"

  # Alert at 80%
  if [ "$USAGE" -ge "$THRESHOLD_WARN" ]; then
    log_message "ALERT: Disk usage ${USAGE}% >= ${THRESHOLD_WARN}% threshold"
  fi

  # Auto-cleanup at 85%
  if [ "$USAGE" -ge "$THRESHOLD_CLEAN" ]; then
    log_message "CLEANUP_START: Disk usage ${USAGE}% >= ${THRESHOLD_CLEAN}%, triggering cleanup..."

    # 1. Remove unused Docker images, containers, volumes, and build cache
    log_message "CLEANUP_STEP: Removing unused Docker resources..."
    docker system prune -af --volumes \
      --filter "until=72h" \
      2>&1 | grep -E "^(Deleted|Total)" | tee -a "$LOG_FILE" || true

    # 2. Verify Kafka is still running (don't force restart)
    if docker ps | grep -q simpleec-kafka; then
      log_message "CLEANUP_KAFKA: Kafka broker running, cleanup did not affect it"
    else
      log_message "CLEANUP_WARNING: Kafka broker not running after cleanup"
    fi

    USAGE_AFTER=$(get_disk_usage)
    log_message "CLEANUP_END: Cleanup complete. Disk usage now: ${USAGE_AFTER}%"

    if [ "$USAGE_AFTER" -lt "$THRESHOLD_CLEAN" ]; then
      log_message "CLEANUP_SUCCESS: Successfully reduced disk usage below cleanup threshold"
    else
      log_message "CLEANUP_PARTIAL: Disk usage still high after cleanup. Manual intervention may be needed"
    fi
  fi
}

main "$@"
