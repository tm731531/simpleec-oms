#!/bin/bash
# Monitor SimpleEC OMS Data Flow: Heartbeat → Scheduler → Channel Job
# Runs every 5 minutes to verify complete processing pipeline

set -euo pipefail

LOG_DIR="/tmp/simpleec-oms-monitor"
mkdir -p "$LOG_DIR"
TIMESTAMP=$(date "+%Y-%m-%d_%H:%M:%S")
LOG_FILE="$LOG_DIR/flow-check-$(date +%Y%m%d).log"

echo "[${TIMESTAMP}] === SimpleEC OMS Data Flow Check ===" >> "$LOG_FILE"

# Function to log check results
log_check() {
    local title="$1"
    local result="$2"
    local status="$3"  # "✅" or "❌"
    echo "[${TIMESTAMP}] ${status} ${title}: ${result}" >> "$LOG_FILE"
}

# 1. Check Container Health
echo "[${TIMESTAMP}] 1. Container Health Check" >> "$LOG_FILE"
RUNNING_CONTAINERS=$(docker compose ps --status running --quiet 2>/dev/null | wc -l)
EXPECTED_CONTAINERS=18  # Adjust based on your setup
if [ "$RUNNING_CONTAINERS" -ge "$EXPECTED_CONTAINERS" ]; then
    log_check "Containers" "$RUNNING_CONTAINERS running" "✅"
else
    log_check "Containers" "$RUNNING_CONTAINERS running (expected $EXPECTED_CONTAINERS)" "❌"
fi

# Check critical services
for service in simpleec-api simpleec-order-job kafka postgres; do
    STATUS=$(docker compose ps $service --status running --quiet 2>/dev/null | wc -l)
    if [ "$STATUS" -eq 1 ]; then
        log_check "Service: $service" "Running" "✅"
    else
        log_check "Service: $service" "NOT running" "❌"
    fi
done

# 2. Check Kafka Topics & Message Flow
echo "[${TIMESTAMP}] 2. Kafka Topic Configuration" >> "$LOG_FILE"

REQUIRED_TOPICS=(
    "scheduler"
    "order.process"
    "return.process"
    "task.backend"
    "task.frontend"
    "task.failed"
    "task.dlt"
    "momo.fast" "momo.slow"
    "pchome.fast" "pchome.slow"
    "shopee.fast" "shopee.slow"
    "yahoo.fast" "yahoo.slow"
)

ACTUAL_TOPICS=$(docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092 2>/dev/null | sort)

for topic in "${REQUIRED_TOPICS[@]}"; do
    if echo "$ACTUAL_TOPICS" | grep -q "^${topic}$"; then
        log_check "Topic: $topic" "Exists" "✅"
    else
        log_check "Topic: $topic" "MISSING" "❌"
    fi
done

# 3. Check Message Counts on Key Topics
echo "[${TIMESTAMP}] 3. Message Flow in Key Topics" >> "$LOG_FILE"

for topic in scheduler order.process return.process task.backend task.frontend task.failed; do
    # Get offset stats (simplified check - just partition count)
    PARTITIONS=$(docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh \
        --describe --topic "$topic" --bootstrap-server localhost:9092 2>/dev/null | wc -l)

    log_check "Topic: $topic" "Partitions: $PARTITIONS" "ℹ️"
done

# 4. Check API Health
echo "[${TIMESTAMP}] 4. API Health Check" >> "$LOG_FILE"
API_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/health 2>/dev/null || echo "000")
if [ "$API_HEALTH" == "200" ]; then
    log_check "API Health" "HTTP 200" "✅"
else
    log_check "API Health" "HTTP $API_HEALTH" "❌"
fi

# 5. Check Database Connectivity
echo "[${TIMESTAMP}] 5. Database Check" >> "$LOG_FILE"
DB_CHECK=$(docker exec postgres psql -U simpleec_user -d simpleec_oms -c "SELECT COUNT(*) FROM orders;" 2>/dev/null | tail -1 | grep -oE "[0-9]+")
if [ ! -z "$DB_CHECK" ]; then
    log_check "Database: orders table" "Count: $DB_CHECK" "✅"
else
    log_check "Database: orders table" "UNREACHABLE" "❌"
fi

DB_RETURNS=$(docker exec postgres psql -U simpleec_user -d simpleec_oms -c "SELECT COUNT(*) FROM return_orders;" 2>/dev/null | tail -1 | grep -oE "[0-9]+")
if [ ! -z "$DB_RETURNS" ]; then
    log_check "Database: return_orders table" "Count: $DB_RETURNS" "✅"
else
    log_check "Database: return_orders table" "UNREACHABLE" "❌"
fi

# 6. Check Error Logs
echo "[${TIMESTAMP}] 6. Service Error Check" >> "$LOG_FILE"

for service in simpleec-api simpleec-order-job; do
    ERROR_COUNT=$(docker logs $service --since 5m 2>&1 | grep -ciE "(ERROR|Exception)" || echo "0")
    if [ "$ERROR_COUNT" -eq 0 ]; then
        log_check "Errors in $service" "None" "✅"
    else
        log_check "Errors in $service" "$ERROR_COUNT errors" "⚠️"
        docker logs $service --since 5m 2>&1 | grep -iE "(ERROR|Exception)" | head -3 >> "$LOG_FILE"
    fi
done

# 7. Data Flow Status Summary
echo "[${TIMESTAMP}] 7. Data Flow Pipeline Status" >> "$LOG_FILE"
echo "[${TIMESTAMP}] ├─ Heartbeat/Health: Check if services are alive" >> "$LOG_FILE"
echo "[${TIMESTAMP}] ├─ Scheduler: Check if scheduler topic has messages" >> "$LOG_FILE"
echo "[${TIMESTAMP}] ├─ Channel Jobs: Check momo/pchome/shopee/yahoo topics" >> "$LOG_FILE"
echo "[${TIMESTAMP}] ├─ Order Processing: Check order.process topic" >> "$LOG_FILE"
echo "[${TIMESTAMP}] ├─ Return Processing: Check return.process topic" >> "$LOG_FILE"
echo "[${TIMESTAMP}] ├─ Backend Jobs: Check task.backend topic (SYNC_PACK, SYNC_PRODUCT, UPDATE_PRICE)" >> "$LOG_FILE"
echo "[${TIMESTAMP}] └─ Error Handling: Check task.failed and task.dlt topics" >> "$LOG_FILE"

echo "[${TIMESTAMP}] === Check Complete ===" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

# Print recent summary
echo "Last check at: $(date)"
tail -40 "$LOG_FILE" | head -40
