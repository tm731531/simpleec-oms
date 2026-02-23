#!/bin/bash
# Kafka Topic Initialization Script
# Creates all required topics for SimpleEC OMS system
# Called from docker-compose health check or init container

set -e

KAFKA_BROKER="${KAFKA_BROKER:-kafka:9092}"
RETENTION_MS="${KAFKA_RETENTION_MS:-3600000}"  # 1 hour default
PARTITIONS="${KAFKA_PARTITIONS:-3}"            # 3 partitions for parallel consumption
REPLICATION_FACTOR="${KAFKA_REPLICATION_FACTOR:-1}"  # 1 for single-broker; 3 for production

# Wait for Kafka broker to be ready
echo "[$(date '+%H:%M:%S')] Waiting for Kafka broker at $KAFKA_BROKER..."
max_attempts=30
attempt=0
while [ $attempt -lt $max_attempts ]; do
    if nc -z kafka 9092 2>/dev/null; then
        echo "[$(date '+%H:%M:%S')] ✓ Kafka broker is ready"
        break
    fi
    attempt=$((attempt + 1))
    echo "[$(date '+%H:%M:%S')] Attempt $attempt/$max_attempts..."
    sleep 2
done

if [ $attempt -eq $max_attempts ]; then
    echo "[$(date '+%H:%M:%S')] ✗ Kafka broker failed to start after $max_attempts attempts"
    exit 1
fi

# Function to create topic if it doesn't exist
create_topic() {
    local topic=$1
    local partitions=${2:-$PARTITIONS}
    local replication_factor=${3:-$REPLICATION_FACTOR}

    # Check if topic exists
    existing=$(/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --list 2>/dev/null | grep "^${topic}$" || true)

    if [ -n "$existing" ]; then
        echo "[$(date '+%H:%M:%S')] ✓ Topic '$topic' already exists"
        return 0
    fi

    echo "[$(date '+%H:%M:%S')] Creating topic '$topic' (partitions=$partitions, rf=$replication_factor, retention=${RETENTION_MS}ms)..."

    /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server "$KAFKA_BROKER" \
        --create \
        --topic "$topic" \
        --partitions "$partitions" \
        --replication-factor "$replication_factor" \
        --config retention.ms="$RETENTION_MS" \
        2>&1 | grep -v "^Created topic\|already exists" || true

    echo "[$(date '+%H:%M:%S')] ✓ Topic '$topic' created"
}

echo ""
echo "[$(date '+%H:%M:%S')] ========== Kafka Topic Initialization =========="
echo "[$(date '+%H:%M:%S')] Broker: $KAFKA_BROKER"
echo "[$(date '+%H:%M:%S')] Partitions: $PARTITIONS | Replication Factor: $REPLICATION_FACTOR"
echo "[$(date '+%H:%M:%S')] Retention: ${RETENTION_MS}ms ($(($RETENTION_MS / 3600000)) hour(s))"
echo ""

# Platform Topics (fast/slow channels) - 3 partitions each
echo "[$(date '+%H:%M:%S')] Creating Platform Topics (fast/slow)..."
for platform in cyberbiz easystore momo pchome shopee yahoo shopline shopify; do
    create_topic "${platform}.fast" "$PARTITIONS"
    create_topic "${platform}.slow" "$PARTITIONS"
done

# Order Processing Topics
echo ""
echo "[$(date '+%H:%M:%S')] Creating Order Processing Topics..."
create_topic "order.process" "$PARTITIONS"

# System Topics (scheduler, task management) - 1 partition (low volume)
echo ""
echo "[$(date '+%H:%M:%S')] Creating System Topics..."
create_topic "scheduler" 1
create_topic "task.backend" 1
create_topic "task.failed" 1
create_topic "task.dlt" 1
create_topic "task.frontend" 1

# Kafka Internal Topics (Coordinator offsets storage)
echo ""
echo "[$(date '+%H:%M:%S')] Creating Kafka Internal Topics..."
# Create __consumer_offsets with 50 partitions for scalability
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --list 2>/dev/null | grep -q "^__consumer_offsets$" || {
    echo "[$(date '+%H:%M:%S')] Creating topic '__consumer_offsets' (critical for consumer group coordination)..."
    /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server "$KAFKA_BROKER" \
        --create \
        --topic "__consumer_offsets" \
        --partitions 50 \
        --replication-factor "$REPLICATION_FACTOR" \
        --config cleanup.policy=compact \
        --config compression.type=snappy \
        --config segment.ms=86400000 \
        --config min.cleanable.dirty.ratio=0.5 \
        --config delete.retention.ms=86400000 \
        2>&1 | grep -v "^Created topic\|already exists" || true
    echo "[$(date '+%H:%M:%S')] ✓ Topic '__consumer_offsets' created"
}

# Operational Topics
echo ""
echo "[$(date '+%H:%M:%S')] Creating Operational Topics..."
create_topic "consumer-lag-tracking" 1

echo ""
echo "[$(date '+%H:%M:%S')] ========== Topic Initialization Complete =========="

# List all topics (for verification, but don't fail if command not found)
echo ""
echo "[$(date '+%H:%M:%S')] Verifying topics created..."
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --list 2>/dev/null | wc -l | xargs -I {} echo "[$(date '+%H:%M:%S')] {} topics found" || echo "[$(date '+%H:%M:%S')] Topics verification skipped"

echo "[$(date '+%H:%M:%S')] Kafka initialization completed successfully"
exit 0
