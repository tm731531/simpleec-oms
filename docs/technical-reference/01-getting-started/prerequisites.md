# Prerequisites

Before setting up SimpleEC OMS, verify that your machine meets the following requirements.

---

## Required Software

| Tool | Minimum Version | Purpose |
|---|---|---|
| **Docker** | 24.0+ | Container runtime |
| **Docker Compose** | v2.20+ (plugin, not standalone) | Multi-container orchestration |
| **Java (JDK)** | 17 | Building Spring Boot modules with Gradle |
| **Git** | 2.30+ | Clone and submodule management |
| **Node.js** | 18+ | Frontend dev server only (not needed for Docker-only setup) |

### Verifying Installed Versions

```bash
docker --version
# Docker version 24.x or later

docker compose version
# Docker Compose version v2.x

java -version
# openjdk version "17.x.x" or later

git --version
# git version 2.x

node --version    # only if doing frontend development
# v18.x.x or later
```

> **Note:** Docker Compose v2 ships as a Docker CLI plugin (`docker compose`) rather than a
> standalone binary (`docker-compose`). The project uses `docker compose` (no hyphen). If you
> only have the standalone binary, install the plugin version.

---

## Hardware Requirements

| Resource | Minimum | Recommended |
|---|---|---|
| RAM | 12 GB | **16 GB** |
| Disk | 10 GB free | 20 GB free |
| CPU | 4 cores | 8 cores |

**Why 16 GB?**
The full stack runs 26 Docker containers simultaneously, including:
- PostgreSQL 16 + Redis 7 (persistent storage)
- Kafka 3.7.1 in KRaft mode (no ZooKeeper, but still memory-intensive)
- 6 Spring Boot job services (each ~300–500 MB JVM heap)
- simpleec-api + simpleec-gateway (REST layer)
- OpenTelemetry Collector, Tempo, Loki, Prometheus, Grafana (observability stack)
- Kafka UI

Under typical load the Docker engine uses 6–10 GB. A 12 GB machine will work but may swap
occasionally; 16 GB provides comfortable headroom for development and testing.

---

## Network: Ports That Must Be Free

The following ports must be unoccupied on your host machine before starting the stack.
Check with `lsof -i :<port>` or `ss -tlnp | grep <port>`.

| Port | Service |
|---|---|
| 5433 | PostgreSQL (external mapping, internal is 5432) |
| 6379 | Redis |
| 9092 | Kafka broker |
| 8081 | simpleec-gateway |
| 8082 | simpleec-api |
| 8088 | Kafka UI |
| 8089 | admin-app (nginx) |
| 8090 | user-app (nginx) |
| 5173 | user-app Vite dev server |
| 8084 | admin-app Vite dev server |
| 3000 | Grafana |
| 9090 | Prometheus |

---

## Docker Configuration

Docker must be configured to allow running at least **26 containers** concurrently. The default
Docker Desktop setting is sufficient on most systems, but verify:

1. Docker Desktop → Settings → Resources → Memory: set to at least **12 GB**
2. Docker Desktop → Settings → Resources → CPUs: set to at least **4**

On Linux (Docker Engine without Desktop), container limits are governed by cgroups and are
generally unrestricted by default.

---

## .env File

The project requires a `.env` file at the repository root before the stack can start.
An `.env.example` file is provided with all required keys and safe development defaults.

```bash
cp .env.example .env
# Then edit .env and fill in required secrets
```

Variables that **must** be changed before production deployment are marked in `.env.example`.
See [environment.md](environment.md) for the complete variable reference.

---

## Network Access (for Platform Integration)

If you intend to test live platform API integrations (not just local mock data), the machine
running the stack must have outbound HTTPS access to:

| Platform | API Domain |
|---|---|
| Cyberbiz | `api.cyberbiz.co` |
| Shopee | `partner.shopeemobile.com` |
| Momo | *(internal — coordinate with Momo tech team)* |
| Yahoo | *(coordinate with Yahoo Commerce team)* |
| PChome | *(coordinate with PChome API team)* |
| Easystore | `api.easystore.co` |

For initial local development, demo/sandbox credentials are sufficient and platforms can be
tested with their sandbox environments.
