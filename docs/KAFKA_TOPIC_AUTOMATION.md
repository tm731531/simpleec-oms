# Kafka Topic Automation - SimpleEC OMS

## Overview

Kafka topics are now automatically created and configured when the system starts using a dedicated initialization container. This document describes the automation setup and manual management tools.

**Status**: ✅ Implemented (Feb 23, 2026)

## Automatic Initialization

### How It Works

1. **kafka-init Container**: A Docker service that runs after Kafka broker is healthy
2. **Topic Creation**: Runs `create-topics.sh` to create all required topics
3. **Configuration**: Topics are created with predefined partition counts and retention policies
4. **Exit**: Container exits successfully after topic creation is complete

### Required Files

```
docker/
├── Dockerfile.kafka-init          # Container image for initialization
└── init-kafka/
    ├── create-topics.sh           # Main topic creation script
    ├── topics-config.yaml         # Topic configuration documentation
    └── README.md                  # Detailed README
```

### Startup Sequence

```
docker compose up -d
  ↓
postgres, redis, kafka start
  ↓
kafka healthcheck passes
  ↓
kafka-init starts (depends_on kafka service_healthy)
  ↓
kafka-init runs create-topics.sh
  ↓
All topics created with proper configuration
  ↓
kafka-init exits (restart: 'no')
  ↓
channel-job services can now connect to topics
```

## Topics Configuration

### Platform Topics (14 total)

Fast and slow processing channels for each marketplace platform:

| Platform | Fast Topic | Slow Topic | Partitions | Retention |
|----------|-----------|-----------|-----------|-----------|
| cyberbiz | cyberbiz.fast | cyberbiz.slow | 3 | 1 hour |
| momo | momo.fast | momo.slow | 3 | 1 hour |
| pchome | pchome.fast | pchome.slow | 3 | 1 hour |
| shopee | shopee.fast | shopee.slow | 3 | 1 hour |
| yahoo | yahoo.fast | yahoo.slow | 3 | 1 hour |
| shopline | shopline.fast | shopline.slow | 3 | 1 hour |
| shopify | shopify.fast | shopify.slow | 3 | 1 hour |

### Order Topics (1)

- **order.process**: Order workflow events (create, update, ship, refund)
  - Partitions: 3
  - Retention: 1 hour

### System Topics (5)

Low-volume administrative events:

| Topic | Purpose | Partitions | Retention |
|-------|---------|-----------|-----------|
| scheduler | Cron and scheduled tasks | 1 | 1 hour |
| task.backend | Backend worker tasks | 1 | 1 hour |
| task.failed | Failed task retries | 1 | 1 hour |
| task.frontend | Frontend notifications | 1 | 1 hour |
| consumer-lag-tracking | Consumer monitoring | 1 | 1 hour |

**Total: 20 topics**

## Environment Variables

Configure topic creation via environment variables in docker-compose.yml:

```yaml
kafka-init:
  environment:
    KAFKA_BROKER: kafka:9092              # Broker address
    KAFKA_RETENTION_MS: '3600000'         # 1 hour retention
    KAFKA_PARTITIONS: '3'                 # Platform topic partitions
    KAFKA_REPLICATION_FACTOR: '1'         # Single broker (dev)
```

**Note**: Replication factor must be 1 for single-broker setup. For production with 3+ brokers, change to 3.

## Manual Topic Management

### Using kafka-topic-manager.sh

A comprehensive CLI tool for managing topics:

```bash
# List all topics
./kafka-topic-manager.sh list

# Describe topics with details
./kafka-topic-manager.sh describe

# Create all topics
./kafka-topic-manager.sh create-all

# Create specific topic groups
./kafka-topic-manager.sh create-platform
./kafka-topic-manager.sh create-system
./kafka-topic-manager.sh create-order

# Delete a topic
./kafka-topic-manager.sh delete <topic>

# Manage consumer groups
./kafka-topic-manager.sh consumer-groups
./kafka-topic-manager.sh monitor-lag <group>

# Check Kafka status
./kafka-topic-manager.sh check
```

### Using Docker Directly

Enter Kafka container:

```bash
docker exec -it simpleec-kafka /bin/bash
```

List topics:

```bash
kafka-topics.sh --bootstrap-server localhost:9092 --list
```

Create a topic:

```bash
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create \
  --topic my-topic \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=3600000
```

Describe topics:

```bash
kafka-topics.sh --bootstrap-server localhost:9092 --describe
```

Delete a topic:

```bash
kafka-topics.sh --bootstrap-server localhost:9092 \
  --delete \
  --topic my-topic
```

## Troubleshooting

### Topics Not Created

**Check initialization logs:**
```bash
docker logs simpleec-kafka-init
```

**Verify Kafka is running:**
```bash
docker ps | grep kafka
docker logs simpleec-kafka | tail -20
```

**Check network connectivity:**
```bash
docker network inspect simpleec-oms_default
```

### Kafka Broker Not Responding

The kafka-init container has a 60-second timeout (30 attempts × 2 seconds).

**Wait longer or restart:**
```bash
docker compose restart kafka-init
```

### Consumer Groups Not Appearing

Consumer groups are created **lazily** when services first publish/consume messages.

**Expected behavior:**
1. kafka-init creates topics ✓
2. channel-job services start and connect
3. Services publish first message
4. Consumer group is created
5. Subsequent messages processed with offset tracking

**Check job logs:**
```bash
docker logs simpleec-channel-cyberbiz-fast | head -50
```

### Disk Space Issues

If Kafka data grows too large:

```bash
# Delete old data
rm -rf ./data/kafka/*

# Recreate container
docker compose down kafka-init
docker compose up -d kafka-init

# Or just restart initialization
./kafka-topic-manager.sh create-all
```

## Production Considerations

### Multi-Broker Setup

For production reliability:

1. **Expand to 3+ Kafka brokers** in docker-compose.yml
2. **Set replication factor to 3**:
   ```yaml
   KAFKA_REPLICATION_FACTOR: '3'
   ```
3. **Update Kafka broker configuration**:
   ```yaml
   KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093
   ```

### Partition Tuning

Current partition counts are conservative. Adjust based on volume:

```bash
# Increase partitions for high-volume platforms
./kafka-topic-manager.sh delete shopee.fast
./kafka-topic-manager.sh create-platform  # Creates with default partitions

# Or manually create with custom partition count
docker exec simpleec-kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --topic shopee.fast \
  --partitions 5 \
  --replication-factor 1 \
  --config retention.ms=3600000
```

### Monitoring

Monitor Kafka health using Prometheus/Grafana (already configured):

```
http://localhost:3000  # Grafana
http://localhost:9090  # Prometheus
```

Monitor consumer lag:

```bash
./kafka-topic-manager.sh consumer-groups
./kafka-topic-manager.sh monitor-lag <group>
```

### Retention Policy

Current 1-hour retention is aggressive for development. For production:

```bash
# Adjust retention via environment variable
KAFKA_RETENTION_MS: '86400000'  # 24 hours
KAFKA_RETENTION_MS: '604800000' # 7 days
```

## Implementation History

- **Feb 23, 2026**: Created automated Kafka topic initialization
  - Added kafka-init Docker service to docker-compose.yml
  - Created create-topics.sh initialization script
  - Added kafka-topic-manager.sh CLI tool
  - Documented configuration and procedures

## Related Files

- [`docker-compose.yml`](../docker-compose.yml) - Service definitions
- [`docker/Dockerfile.kafka-init`](../docker/Dockerfile.kafka-init) - Container image
- [`docker/init-kafka/create-topics.sh`](../docker/init-kafka/create-topics.sh) - Initialization script
- [`docker/init-kafka/topics-config.yaml`](../docker/init-kafka/topics-config.yaml) - Topic configuration
- [`kafka-topic-manager.sh`](../kafka-topic-manager.sh) - Manual management tool
