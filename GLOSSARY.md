# SimpleEC OMS - Glossary & Terminology

This document provides a comprehensive list of terms, abbreviations, and key concepts used throughout the SimpleEC OMS documentation.

---

## Core Concepts

### Business Terms

| Term | Definition | Notes |
|------|-----------|-------|
| **OMS** | Order Management System | Multi-channel order management platform |
| **Channel** | Sales platform or marketplace | Shopee, Momo, Yahoo, PChome, Cyberbiz, Shopline, Shopify |
| **Merchant** | Business owner / seller | Uses the system to manage orders and inventory |
| **SKU** | Stock Keeping Unit | Product variant identifier |
| **Pack** | Product bundle sold on a channel | Channel-specific product configuration |
| **Order** | Customer purchase record | Contains multiple items from one or more packs |
| **Item** | Single line in an order | References a specific pack and quantity |
| **Return** | Customer return request | Initiation, approval, and fulfillment process |
| **Shipment** | Fulfillment of an order | Tracking and delivery status |
| **SLA** | Service Level Agreement | Response time commitments |
| **Multi-channel** | Multiple sales channels | Integrated management across platforms |
| **Fulfillment** | Order processing and shipping | End-to-end order delivery |
| **Inventory** | Stock management | Real-time quantity tracking |

### Financial Terms

| Term | Definition | Notes |
|------|-----------|-------|
| **ARR** | Annual Recurring Revenue | Year-over-year revenue projection |
| **MRR** | Monthly Recurring Revenue | Monthly revenue projection |
| **CAC** | Customer Acquisition Cost | Average cost to acquire one customer |
| **LTV** | Customer Lifetime Value | Total expected revenue from a customer |
| **Churn Rate** | Customer attrition rate | Percentage of customers lost per period |
| **ARPU** | Average Revenue Per User | Revenue divided by user count |
| **NPS** | Net Promoter Score | Customer satisfaction metric (-100 to 100) |
| **Gross Margin** | Revenue minus cost of goods | Profitability metric |
| **Operating Margin** | Net income divided by revenue | Overall profitability |

---

## Technical Architecture

### Event-Driven Architecture

| Term | Definition | Notes |
|------|-----------|-------|
| **Event** | Immutable fact describing what happened | Order created, payment received, shipment tracking updated |
| **Event Stream** | Ordered sequence of events | Append-only log, immutable history |
| **Handler** | Component that processes an event | Consumes messages and updates system state |
| **TaskType** | Classification of event/task | FETCH_ORDERS, PROCESS_ORDER, SHIP_ORDER, etc. |
| **Header** | Event metadata for routing | taskType, merchantId, platformId, requestId, timestamp |
| **Body** | Event business data | Order details, item information, return details |
| **isRollback** | Flag for backfill orders | `true` = historical data, `false` = new orders |
| **Source of Truth** | Authoritative data store | `order.process` and `return.process` topics |

### Kafka & Message Queue

| Term | Definition | Notes |
|------|-----------|-------|
| **Kafka** | Distributed event streaming platform | Core message broker |
| **Topic** | Logical channel for messages | 16 topics in SimpleEC OMS |
| **Partition** | Ordered subsequence of topic | Parallel processing capability |
| **Consumer Group** | Set of related consumers | Each group maintains independent offset |
| **Consumer** | Process that reads messages | Job that handles specific TaskType |
| **Producer** | Process that sends messages | Scheduler, Channel Job, Backend Job |
| **Offset** | Position in partition | Tracks consumption progress |
| **DLT** | Dead Letter Topic | Handles unprocessable messages |
| **Retention** | How long messages are kept | Configurable (default 1 day for main topics, 30 days for DLT) |
| **KRaft** | Kafka Raft Protocol | Mode without ZooKeeper (used in this project) |

### Message Topics

| Topic | Type | Purpose | Retention |
|-------|------|---------|-----------|
| **{platform}.fast** | Channel | Fast tasks (< 5s): SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 1 day |
| **{platform}.slow** | Channel | Slow tasks (< 5m): FETCH_ORDERS, FETCH_ORDER_DETAIL, FETCH_RETURNS, SYNC_PACK | 1 day |
| **order.process** | Business | Core order workflow - Source of Truth | 1 day |
| **return.process** | Business | Core return workflow - Source of Truth | 1 day |
| **task.backend** | System | Internal async tasks (SYNC_PRODUCT, Pack→Product mapping) | 1 day |
| **task.frontend** | System | Frontend event notifications | 1 day |
| **scheduler** | System | Scheduled task triggers | 1 day |
| **task.failed** | System | Temporary failures (will be retried) | 1 day |
| **task.dlt** | System | Permanent failures (poison pill) | 30 days |

**Note**: Platforms = cyberbiz, momo, shopee, yahoo, pchome, easystore, shopline

### Database & Persistence

| Term | Definition | Notes |
|------|-----------|-------|
| **PostgreSQL** | Relational database | Primary data store |
| **Redis** | In-memory cache | Deduplication, session cache |
| **Schema** | Database structure definition | 16 tables in SimpleEC OMS |
| **Entity** | ORM representation of table | MyBatis-Plus mapping |
| **JSONB** | JSON Binary format | PostgreSQL native type for flexible fields |
| **NanoID** | Unique identifier | Lightweight alternative to UUID |
| **PII** | Personally Identifiable Information | Encrypted fields: buyer name, phone, email, address |
| **Deduplication** | Prevent duplicate processing | Redis-based idempotency |
| **AOF** | Append-Only File | Redis persistence mode |

---

## Platform Integration

### Channel APIs

| Platform | Type | Key Features | Notes |
|----------|------|--------------|-------|
| **Shopee** | Marketplace | Cursor-based pagination, status lifecycle (UNPAID→CONFIRMED→SHIPPED→COMPLETED) | No batch detail API |
| **Momo** | Marketplace | Item-level records, no status classification, aggregation needed | IP whitelisting |
| **Yahoo** | Marketplace | Time-range queries only, evaluates order status from data | Limited history |
| **PChome** | Marketplace | TBD | Future implementation |
| **Cyberbiz** | SaaS | TBD | Taiwan SaaS platform |
| **easystore** | SaaS | Complete 50-item batches, strict rate limits | 7-day window default |
| **Shopify** | SaaS | Standard Shopify API | OAuth 2.0 integration |

### Platform-Specific Terms

| Term | Definition | Platform(s) |
|------|-----------|------------|
| **create_time_from/to** | Order creation time range filter | Shopee |
| **updated_after** | Orders updated since timestamp | Yahoo |
| **from_date/to_date** | Date range filter | easystore |
| **Cursor-based** | Pagination using cursor token | Shopee |
| **Offset-based** | Pagination using page offset | Momo |
| **Item-level** | Individual line-item records | Momo |
| **Order aggregation** | Grouping items by order number | Momo requires manual aggregation |

---

## System Components

### Core Services

| Service | Port | Purpose | Technology |
|---------|------|---------|------------|
| **API** | 8083 | REST API for merchants | Spring Boot 3.5 |
| **Gateway** | 8081 | Webhook endpoint for channels | Spring Boot |
| **Channel Job** | Internal | Channel data synchronization | 10 parallel jobs (5 platforms × 2 speeds) |
| **Order Job** | Internal | Order processing workflow | Kafka consumer |
| **Backend Job** | Internal | Internal async processing | Kafka consumer |
| **Scheduler Job** | Internal | Scheduled triggers | HeartbeatTimer |
| **Postgres** | 5433 | Primary database | PostgreSQL 16 |
| **Redis** | 6379 | Cache & deduplication | Redis 7 (AOF) |
| **Kafka** | 9092 | Event streaming | Kafka 3.7.1 (KRaft) |
| **Nginx** | 8089 | Reverse proxy | Frontend gateway |
| **Grafana** | 3000 | Monitoring dashboard | Observability |

### Frontend Services

| Service | Port | Purpose | Technology |
|---------|------|---------|------------|
| **User App** | 5173 (dev), 8089 (proxy) | Merchant UI | Vue 3 + Vite |
| **Admin App** | 8084 (dev), 8089 (proxy) | Platform admin UI | Vue 3 + Vite |

---

## Operations & Deployment

### Docker & Containers

| Term | Definition | Notes |
|------|-----------|-------|
| **Docker** | Container runtime | Containerized deployment |
| **Docker Compose** | Container orchestration | Multi-container setup |
| **Container** | Isolated application environment | Single service instance |
| **Image** | Blueprint for containers | Built from Dockerfile |
| **Network** | Docker internal networking | Service-to-service communication |
| **Volume** | Persistent storage | Data persistence across restarts |
| **Health Check** | Service readiness probe | Confirms service availability |

### Monitoring & Logging

| Term | Definition | Notes |
|------|-----------|-------|
| **Grafana** | Visualization dashboard | Real-time monitoring |
| **Prometheus** | Metrics collection | Time-series database |
| **Loki** | Log aggregation | JSON logging from services |
| **Tempo** | Distributed tracing | Trace visualization |
| **OpenTelemetry** | Observability standard | Unified instrumentation |
| **MDC** | Mapped Diagnostic Context | Contextual logging (traceId, spanId, merchantId) |
| **JSON Logging** | Structured log format | Logstash-compatible logs |

### Deployment & Operations

| Term | Definition | Notes |
|------|-----------|-------|
| **Branch** | Git branch variant | main, docs-only, feature branches |
| **Commit** | Git version snapshot | Immutable code history |
| **CI/CD** | Continuous Integration/Deployment | Automated testing and deployment |
| **Hotfix** | Emergency fix | Applies to production immediately |
| **Release** | Versioned software release | Tagged commit with version |
| **Rollout** | Gradual deployment | Phased rollout to prevent outages |
| **Rollback** | Revert to previous version | Undo recent changes |
| **Runbook** | Operational procedure | Step-by-step troubleshooting guide |

---

## Data Processing

### Task Types

| TaskType | Category | Purpose | Consumer | Response Time |
|----------|----------|---------|----------|-----------------|
| **FETCH_ORDERS** | Channel | Retrieve orders from channel | Channel Job | < 5 minutes |
| **FETCH_ORDER_DETAIL** | Channel | Get detailed order info | Channel Job | < 5 minutes |
| **FETCH_RETURNS** | Channel | Retrieve return requests | Channel Job | < 5 minutes |
| **FETCH_RETURN_DETAIL** | Channel | Get return details | Channel Job | < 5 minutes |
| **SYNC_PACK** | Channel | Sync product packs | Channel Job | < 5 minutes |
| **SYNC_PRODUCT** | Backend | Create product from pack | Backend Job | < 5 seconds |
| **PROCESS_ORDER** | Workflow | Order business logic | Order Job | < 5 seconds |
| **SHIP_ORDER** | Channel | Notify channel of shipment | Channel Job | < 5 seconds |
| **APPROVE_RETURN** | Channel | Approve return request | Channel Job | < 5 seconds |
| **UPDATE_INVENTORY** | Channel | Push inventory updates | Channel Job | < 5 seconds |
| **UPDATE_PRICE** | Channel | Push price updates | Channel Job | < 5 seconds |

---

## Team & Collaboration

| Term | Definition | Notes |
|------|-----------|-------|
| **Stakeholder** | Person invested in project | Investor, user, team member |
| **PR** | Pull Request | Code review mechanism |
| **Code Review** | Peer verification of changes | Quality gate before merge |
| **Merge** | Combine branches | Integrate changes to main |
| **Issue** | Problem or feature request | Tracked in GitHub Issues |
| **Documentation** | Technical guides and references | Markdown files in docs/ |
| **Runbook** | Step-by-step procedures | Operational documentation |
| **RACI** | Responsibility matrix | Clear ownership of decisions |

---

## Common Abbreviations

| Abbreviation | Full Form | Category |
|--------------|-----------|----------|
| **OMS** | Order Management System | Core |
| **API** | Application Programming Interface | Technical |
| **REST** | Representational State Transfer | Technical |
| **JWT** | JSON Web Token | Security |
| **HTTP/HTTPS** | HyperText Transfer Protocol | Network |
| **TCP/IP** | Transmission Control Protocol | Network |
| **SSL/TLS** | Secure Socket Layer / Transport Layer Security | Security |
| **JSON** | JavaScript Object Notation | Data Format |
| **XML** | Extensible Markup Language | Data Format |
| **CRUD** | Create, Read, Update, Delete | Database |
| **SQL** | Structured Query Language | Database |
| **NoSQL** | Non-relational databases | Database |
| **ORM** | Object-Relational Mapping | Database |
| **PII** | Personally Identifiable Information | Security |
| **AES** | Advanced Encryption Standard | Cryptography |
| **GCM** | Galois/Counter Mode | Cryptography |
| **SLA** | Service Level Agreement | Business |
| **RTO** | Recovery Time Objective | Disaster Recovery |
| **RPO** | Recovery Point Objective | Disaster Recovery |
| **CI/CD** | Continuous Integration/Deployment | DevOps |
| **VCS** | Version Control System | DevOps |
| **UI** | User Interface | Frontend |
| **UX** | User Experience | Frontend |
| **URL** | Uniform Resource Locator | Network |
| **ENV** | Environment (variables) | Configuration |
| **SSH** | Secure Shell | Network |
| **SSH Key** | Public-private key pair | Security |
| **YAML** | YAML Ain't Markup Language | Configuration |
| **KRaft** | Kafka Raft Protocol | Kafka |
| **AOF** | Append-Only File | Redis |
| **CAP** | Consistency, Availability, Partition tolerance | Distributed Systems |
| **ACID** | Atomicity, Consistency, Isolation, Durability | Database |
| **BASE** | Basically Available, Soft state, Eventually consistent | Distributed Systems |
| **MVP** | Minimum Viable Product | Product Development |
| **POC** | Proof of Concept | Product Development |
| **UAT** | User Acceptance Testing | QA |
| **E2E** | End-to-End | Testing |
| **QA** | Quality Assurance | Testing |

---

## Version & Status Codes

### System Versions

| Component | Current Version | Notes |
|-----------|-----------------|-------|
| **Java** | 17 | LTS version |
| **Spring Boot** | 3.5.0 | Latest stable |
| **Gradle** | 8.14.4 | Build tool |
| **PostgreSQL** | 16 | Latest major |
| **Redis** | 7 | Latest major |
| **Kafka** | 3.7.1 | Latest stable |
| **Docker** | 20.10+ | Minimum supported |
| **Docker Compose** | 2.0+ | Minimum supported |
| **Node.js** | 18+ | Frontend builds |
| **Vue** | 3 | Frontend framework |
| **Vite** | Latest | Frontend build tool |

### HTTP Status Codes

| Code | Meaning | Usage |
|------|---------|-------|
| **200** | OK | Successful request |
| **201** | Created | Resource successfully created |
| **202** | Accepted | Request accepted for processing |
| **204** | No Content | Successful, no response body |
| **400** | Bad Request | Invalid request format |
| **401** | Unauthorized | Authentication required |
| **403** | Forbidden | Insufficient permissions |
| **404** | Not Found | Resource not found |
| **500** | Internal Server Error | Server error |
| **503** | Service Unavailable | Service temporarily down |

---

## Related Documents

For more context on these terms, see:
- [CORE_CONTRACTS.md](docs/3-EVENT-FLOW/CORE_CONTRACTS.md) - Message structure definitions
- [DATA_FLOW_MAPPING.md](docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md) - How messages flow through the system
- [PLATFORM_MAPPING.md](docs/4-SCHEMA/PLATFORM_MAPPING.md) - Platform-specific details
- [ARCHITECTURE_OVERVIEW.md](docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md) - System overview
- [CHANNEL_IMPLEMENTATION_GUIDE.md](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md) - How to add new platforms

---

**Last Updated**: February 2026
**Maintainer**: SimpleEC Team
