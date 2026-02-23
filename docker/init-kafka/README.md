# Kafka Topic Initialization

This directory contains scripts and configuration for automatically initializing Kafka topics on system startup.

## Files

- **create-topics.sh**: Main initialization script that creates all required Kafka topics
- **Dockerfile** (in parent): Container image for running topic initialization

## Topics Created

### Platform Topics (Fast/Slow Channels)
Each platform has two topics for handling fast and slow API operations:
- `cyberbiz.fast` / `cyberbiz.slow`
- `momo.fast` / `momo.slow`
- `pchome.fast` / `pchome.slow`
- `shopee.fast` / `shopee.slow`
- `yahoo.fast` / `yahoo.slow`
- `shopline.fast` / `shopline.slow`
- `shopify.fast` / `shopify.slow`

**Configuration**: 3 partitions, 1-hour retention

### Order Processing Topics
- `order.process`: Order workflow events (create, update, ship, refund, etc.)

**Configuration**: 3 partitions, 1-hour retention

### System Topics
Low-volume administrative topics:
- `scheduler`: Cron and schedule events
- `task.backend`: Backend worker tasks
- `task.failed`: Failed task retries
- `task.frontend`: Frontend notification tasks
- `consumer-lag-tracking`: Consumer group monitoring

**Configuration**: 1 partition, 1-hour retention

## How It Works

1. **Docker Build**: When `docker-compose up` is run, the kafka-init service is built
2. **Health Check**: kafka-init waits for the Kafka broker to be healthy (up to 60 seconds)
3. **Topic Creation**: The script creates all topics with configured partitions and retention
4. **Verification**: Script lists all topics and their configuration
5. **Exit**: Container exits with code 0 after successful initialization

## Configuration

You can customize topic creation by setting environment variables:

```bash
KAFKA_BROKER="kafka:9092"              # Kafka broker address
KAFKA_RETENTION_MS="3600000"           # Retention time in milliseconds (1 hour)
KAFKA_PARTITIONS="3"                   # Number of partitions per platform topic
KAFKA_REPLICATION_FACTOR="1"           # Replication factor (1 for dev, 3+ for prod)
```

## Manual Topic Creation

If you need to manually create topics:

```bash
# Enter Kafka container
docker exec -it simpleec-kafka /bin/bash

# List topics
kafka-topics.sh --bootstrap-server localhost:9092 --list

# Create a topic
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create \
  --topic my-topic \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=3600000
```

## Logs

View initialization logs:

```bash
docker logs simpleec-kafka-init
```

## Troubleshooting

### Topics not created
1. Check Kafka is running: `docker logs simpleec-kafka`
2. Verify network connectivity: `docker network ls`
3. Check init container logs: `docker logs simpleec-kafka-init`

### Kafka broker not ready
The script waits up to 30 attempts (60 seconds). Check:
- Kafka container is running: `docker ps | grep kafka`
- Kafka health: `docker logs simpleec-kafka`
- Network: `docker network inspect simpleec-oms_default`

### Consumer groups not appearing
Consumer groups are created lazily when services first publish/consume messages. Run:
```bash
docker logs simpleec-channel-*-* | grep -i consumer
```

## Future Improvements

1. **Multi-broker replication**: Expand to 3+ Kafka brokers with replication factor 3
2. **Topic partitioning tuning**: Monitor message rates and adjust partition counts
3. **Monitoring**: Add topic lag monitoring to detect slow consumers
4. **Compacted topics**: Use compacted topics for state changelog (e.g., product catalog, inventory)
