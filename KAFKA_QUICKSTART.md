# Kafka Topic Automation - Quick Start Guide

## What's New

Kafka topics are now **automatically created** when you start the system. No more manual topic creation!

## Starting the System

```bash
cd /home/tom/ONEEC/simpleec-oms
docker compose up -d
```

The system will:
1. Start PostgreSQL, Redis, Kafka broker
2. Wait for Kafka broker to be healthy
3. Run kafka-init to create all 20 topics
4. Start channel job services
5. All ready in ~60 seconds

## Verifying Topics Were Created

```bash
# List all topics
./kafka-topic-manager.sh list

# Show detailed information
./kafka-topic-manager.sh describe

# View logs from initialization
docker logs simpleec-kafka-init
```

Expected output:
```
✓ Topic 'cyberbiz.fast' created
✓ Topic 'cyberbiz.slow' created
✓ Topic 'momo.fast' created
✓ Topic 'momo.slow' created
... (14 platform topics)
✓ Topic 'order.process' created
✓ Topic 'scheduler' created
... (system topics)
========== All Topics Created ==========
```

## Topics Created

| Type | Count | Names |
|------|-------|-------|
| Platform | 14 | cyberbiz, momo, pchome, shopee, yahoo, shopline, shopify (each with .fast and .slow) |
| Order | 1 | order.process |
| System | 5 | scheduler, task.backend, task.failed, task.frontend, consumer-lag-tracking |
| **Total** | **20** | |

Configuration: 3 partitions (platform), 1 hour retention

## Common Tasks

### List all topics
```bash
./kafka-topic-manager.sh list
```

### Create topics manually (if needed)
```bash
./kafka-topic-manager.sh create-all
```

### Delete a topic
```bash
./kafka-topic-manager.sh delete order.process
```

### Monitor consumer groups
```bash
./kafka-topic-manager.sh consumer-groups
```

### Check Kafka broker health
```bash
./kafka-topic-manager.sh check
```

## Troubleshooting

### Topics not appearing
```bash
# Check initialization logs
docker logs simpleec-kafka-init

# Verify Kafka is healthy
docker logs simpleec-kafka | tail -20

# Manually create topics
./kafka-topic-manager.sh create-all
```

### Consumer groups not showing
Normal behavior - they're created lazily when services first consume messages.
Check job logs:
```bash
docker logs simpleec-channel-cyberbiz-fast | grep -i consumer
```

### Customize topic configuration
Edit docker-compose.yml kafka-init section:
```yaml
kafka-init:
  environment:
    KAFKA_PARTITIONS: '5'         # Increase partitions
    KAFKA_RETENTION_MS: '86400000' # 24 hours instead of 1 hour
```

Then restart:
```bash
docker compose restart kafka-init
```

## Full Documentation

For detailed information, see:
- **[docs/KAFKA_TOPIC_AUTOMATION.md](docs/KAFKA_TOPIC_AUTOMATION.md)** - Complete implementation guide
- **[KAFKA_AUTOMATION_SUMMARY.md](KAFKA_AUTOMATION_SUMMARY.md)** - Implementation details
- **[docker/init-kafka/README.md](docker/init-kafka/README.md)** - Technical details

## Files Overview

```
├── docker-compose.yml              # Updated with kafka-init service
├── docker/
│   ├── Dockerfile.kafka-init       # Container for topic initialization
│   └── init-kafka/
│       ├── create-topics.sh        # Topic creation script
│       ├── README.md               # Detailed guide
│       └── topics-config.yaml      # Topic configuration
├── kafka-topic-manager.sh          # CLI tool for topic management
├── KAFKA_QUICKSTART.md             # This file
├── KAFKA_AUTOMATION_SUMMARY.md     # Implementation summary
└── docs/
    └── KAFKA_TOPIC_AUTOMATION.md   # Complete documentation
```

## Key Points

✅ **Automatic**: Topics created on system startup
✅ **Idempotent**: Safe to restart kafka-init
✅ **Configurable**: Environment variables in docker-compose.yml
✅ **Manageable**: CLI tool for operations
✅ **Documented**: Full guide included

---

**Questions?** Check the full documentation in [docs/KAFKA_TOPIC_AUTOMATION.md](docs/KAFKA_TOPIC_AUTOMATION.md)
