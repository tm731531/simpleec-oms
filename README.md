# SimpleEC OMS

[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.7-black?logo=apachekafka)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red?logo=redis)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue?logo=docker)](https://docs.docker.com/compose/)
[![License](https://img.shields.io/badge/License-Private-lightgrey)]()

**Select Language:** [English](README.md) | [繁體中文](README.zh-TW.md)

> Open-source multi-channel e-commerce Order Management System (OMS) built for Taiwan marketplaces.

---

## What is SimpleEC OMS?

SimpleEC OMS is an event-driven order management system that unifies orders, inventory, shipments, and returns from multiple e-commerce platforms into a single dashboard. It is designed for Taiwan-based sellers who operate across Cyberbiz, Shopee, MOMO, PChome, Yahoo, Shopline, and Shopify simultaneously.

Instead of logging into 7 different seller portals, SimpleEC OMS automatically syncs all order data via Kafka event streaming and provides a centralized management interface.

## Key Features

| Feature | Description |
|---------|-------------|
| **Multi-Channel Sync** | Automatically fetches orders from 7 platforms (Cyberbiz, Shopee, MOMO, PChome, Yahoo, Shopline, Shopify) |
| **Unified Order Management** | View, filter, and manage all orders in one place regardless of source platform |
| **Shipment Workflow** | Full warehouse workflow: picking, packing, labeling, dispatch with batch operations |
| **Inventory Sync** | Push inventory updates back to each channel |
| **Return Processing** | Centralized return/refund handling across all platforms |
| **Sales Reports** | Daily statistics aggregated per channel with historical tracking |
| **Event-Driven Architecture** | Kafka-based async processing with automatic retry and dead-letter queue |
| **Observability** | Built-in Grafana + Prometheus + Loki + Tempo for metrics, logs, and traces |

## Architecture Overview

```
                        ┌─────────────────────────┐
                        │     Nginx (8089)         │
                        │  / → User App (Vue 3)    │
                        │  /admin → Admin App      │
                        │  /api → Spring Boot API  │
                        └────────────┬────────────┘
                                     │
┌────────────────────────────────────┼────────────────────────────────────┐
│                           Spring Boot API (8083)                        │
│  JWT Auth · REST Controllers · Shipment Service · Statistics Service    │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              ▼                      ▼                      ▼
    ┌─────────────────┐   ┌──────────────────┐   ┌─────────────────┐
    │  Apache Kafka    │   │   PostgreSQL 16   │   │     Redis 7     │
    │  16 Topics       │   │   19+ Tables      │   │  Cache + Dedup  │
    │  KRaft Mode      │   │   AES-256 PII     │   │  Distributed    │
    └────────┬────────┘   └──────────────────┘   │  Locks          │
             │                                     └─────────────────┘
    ┌────────┴────────────────────────────────┐
    │         Kafka Consumer Jobs              │
    │  ChannelJob · OrderJob · RetryJob        │
    │  BackendJob · SchedulerJob · FrontendJob │
    └─────────────────────────────────────────┘
```

### How does the event flow work?

1. **Scheduler** sends a heartbeat timestamp to Kafka every 5 minutes
2. **Channel Jobs** receive the timestamp, call each platform's API with platform-specific time windows and pagination
3. Channel Jobs transform platform-specific formats into unified OMS structure and publish to `order.process` topic
4. **Order Job** persists orders to PostgreSQL with deduplication
5. On dispatch, **Shipment Service** publishes `SHIP_ORDER` events back to the platform's Kafka topic
6. **Channel Job** calls the platform's fulfillment API to confirm shipment

## Supported Platforms

| Platform | Order Sync | Shipment | Return | Inventory |
|----------|:----------:|:--------:|:------:|:---------:|
| Cyberbiz | ✅ | ✅ | ✅ | ✅ |
| Shopee | ✅ | ✅ | ✅ | ✅ |
| MOMO | ✅ | ✅ | ✅ | - |
| PChome | ✅ | ✅ | - | - |
| Yahoo | ✅ | ✅ | - | - |
| Shopline | ✅ | - | - | - |
| Shopify | ✅ | - | - | - |

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.5, MyBatis-Plus |
| Message Broker | Apache Kafka 3.7 (KRaft, no ZooKeeper) |
| Database | PostgreSQL 16 |
| Cache | Redis 7 (AOF persistence) |
| Frontend | Vue 3 + Vite |
| Auth | JWT + AES-256-GCM (PII encryption) |
| Observability | OpenTelemetry + Grafana + Prometheus + Loki + Tempo |
| Containerization | Docker Compose (26 containers) |
| Build | Gradle 8.14, 11 modules |

## Quick Start

### Prerequisites

- Docker 20.10+ and Docker Compose 2.0+
- JDK 17+ (recommended: [sdkman](https://sdkman.io/))

### Setup

```bash
git clone https://github.com/tm731531/simpleec-oms.git
cd simpleec-oms

# Build
./gradlew clean build -x test

# Start all 26 containers
docker compose up -d

# Verify
curl http://localhost:8083/api/health
```

### Access Points

| Service | URL | Description |
|---------|-----|-------------|
| User App | http://localhost:8089 | Seller dashboard |
| Admin App | http://localhost:8089/admin/ | Platform admin |
| API | http://localhost:8083/api | REST API |
| Kafka UI | http://localhost:8088 | Topic and consumer monitoring |
| Grafana | http://localhost:3000 | Metrics and logs dashboard |

**Demo credentials:** `admin@a00000.com` / `pass123456`

## Module Structure

```
simpleec-oms/
├── simpleec-common        # Shared: Enums, Models, Utilities
├── simpleec-core          # Core: Entities, Repositories, Services
├── simpleec-channel       # Channel adapters (per-platform API clients)
├── simpleec-api           # REST API (Spring Boot :8083)
├── simpleec-gateway       # External gateway: Webhooks, ERP
├── simpleec-channel-job   # Channel sync consumers (10 instances)
├── simpleec-order-job     # Order processing consumer
├── simpleec-scheduler-job # Heartbeat scheduler
├── simpleec-backend-job   # Async backend tasks
├── simpleec-frontend-job  # Frontend event consumer
└── simpleec-retry-job     # Retry + Dead Letter Queue routing
```

## Kafka Topics

SimpleEC uses 16 Kafka topics organized by function:

| Category | Topics | Purpose |
|----------|--------|---------|
| Channel (fast) | `{platform}.fast` x6 | Quick operations: ship, price update, inventory sync (<5s) |
| Channel (slow) | `{platform}.slow` x6 | Data sync: fetch orders, returns, products (<5min) |
| Business | `order.process`, `return.process` | Source of truth for order/return events |
| System | `scheduler`, `task.backend`, `task.frontend` | Internal coordination |
| Error | `task.failed`, `task.dlt` | Retry queue (1d retention) and dead letter (30d retention) |

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture Overview](docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md) | System design and component diagram |
| [Core Contracts](docs/3-EVENT-FLOW/CORE_CONTRACTS.md) | Kafka message format and topic definitions |
| [Data Flow Mapping](docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md) | API to Kafka to database mapping |
| [Database Schema](docs/4-SCHEMA/SCHEMA.md) | All table DDL (19+ tables) |
| [Channel Implementation Guide](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md) | How to add a new platform |
| [Operations Runbook](docs/6-OPERATIONS/OPERATIONS_RUNBOOK.md) | Deployment and troubleshooting |

## FAQ

### How do I add a new e-commerce platform?

Implement a `ChannelAdapter` for the platform's API, create handlers annotated with `@ChannelHandler(platform = "xxx")`, and add the platform's `.fast` and `.slow` Kafka topics. See the [Channel Implementation Guide](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md).

### How does order deduplication work?

Each order is identified by `channelId + channelOrderId`. Redis-based deduplication prevents duplicate processing. The `OrderUpsertConsumer` checks for existing records before insert/update.

### Why Kafka instead of REST for inter-service communication?

Kafka provides reliable async processing with automatic retry, dead-letter queues, and back-pressure handling. Each platform has different API response times (1s to 30s), and Kafka decouples the sync speed from processing speed.

### How is sensitive data protected?

Buyer PII (name, phone, email, address) is encrypted at rest using AES-256-GCM via transparent MyBatis type handlers. Each merchant has its own encryption key.

### Can this run without Docker?

Yes. Each module is a standard Spring Boot application. You need PostgreSQL 16, Redis 7, and Kafka 3.7 running separately, then configure `application.yml` for each module.

---

**Version**: v0.1-MVP | **Status**: Fully Operational | **Last Updated**: 2026-03

## License

Private - All rights reserved.
