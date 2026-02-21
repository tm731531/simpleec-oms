# SimpleEC OMS - User App Deployment Guide

This document describes the deployment process for the User App micro-frontend and the complete SimpleEC OMS system.

## Table of Contents

1. [Quick Start](#quick-start)
2. [System Architecture](#system-architecture)
3. [Service Startup](#service-startup)
4. [Environment Variables](#environment-variables)
5. [Port Mapping](#port-mapping)
6. [Dependency Services](#dependency-services)
7. [Troubleshooting](#troubleshooting)

---

## Quick Start

### Prerequisite: Build User App

Before deploying, ensure the User App is built:

```bash
cd /home/tom/ONEEC/simpleec-oms/user-app
npm install
npm run build
```

This generates:
- `dist/user-app.umd.js` (UMD bundle for Qiankun)
- `dist/style.css` (Styling)

### Run All Services

Start the complete system:

```bash
cd /home/tom/ONEEC/simpleec-oms

# Option 1: Using provided script
bash start-services.sh

# Option 2: Using docker-compose
docker-compose up -d --build
```

The system should be accessible at:
- **Container**: http://localhost:8080
- **Admin App**: http://localhost:8081
- **User App**: http://localhost:8083
- **API**: http://localhost:8082

---

## System Architecture

### Micro-Frontend Architecture

The system uses Qiankun as the micro-frontend framework:

```
┌─────────────────────────────────────────────────┐
│         Micro-Frontend Container (8080)         │
│  - Router/Navigation Hub                        │
│  - Qiankun Framework (Sandbox Isolation)        │
└────────────┬──────────────────┬─────────────────┘
             │                  │
      ┌──────▼──────┐    ┌──────▼──────┐
      │  Admin App  │    │  User App   │
      │  (8081)     │    │  (8083)     │
      └─────────────┘    └─────────────┘
```

### Services

| Service | Port | Purpose |
|---------|------|---------|
| Container | 8080 | Micro-frontend orchestration |
| Admin App | 8081 | Administrator dashboard |
| User App | 8083 | Merchant-facing application |
| API | 8082 | REST API backend |
| Kafka UI | 8088 | Message queue monitoring |
| PostgreSQL | 5432 | Primary database |
| Redis | 6379 | Caching & sessions |
| Grafana | 3000 | Monitoring & dashboards |

---

## Service Startup

### 1. Infrastructure Services (Database, Cache, Queue)

These must be running first:

```bash
# Start only infrastructure
docker-compose up -d postgres redis kafka otel-collector tempo loki prometheus grafana

# Verify services are running
docker-compose ps
```

Expected status: `Up`

### 2. Backend Services

```bash
# Build backend modules
cd /home/tom/ONEEC/simpleec-oms
./gradlew clean build -x test

# Start via docker-compose (preferred)
docker-compose up -d simpleec-api simpleec-gateway simpleec-scheduler-job
```

### 3. Frontend Services

#### User App (Development)

```bash
cd /home/tom/ONEEC/simpleec-oms/user-app
npm install
npm run dev
# Serves on http://localhost:8083
```

#### Admin App (Development)

```bash
cd /home/tom/ONEEC/simpleec-oms/admin-app
npm install
npm run dev
# Serves on http://localhost:8081
```

#### Container (Development)

```bash
cd /home/tom/ONEEC/simpleec-oms/micro-frontend-container
npm install
npm run dev
# Serves on http://localhost:8080
```

### 4. Complete System (Production with Docker)

```bash
cd /home/tom/ONEEC/simpleec-oms
docker-compose up -d --build
```

This starts all services in containers (26 containers total).

---

## Environment Variables

### Container Environment

Create `.env` in the micro-frontend-container root:

```env
VITE_API_BASE_URL=http://localhost:8082
VITE_ADMIN_APP_URL=http://localhost:8081
VITE_USER_APP_URL=http://localhost:8083
```

### User App Environment

Create `.env` in the user-app root:

```env
VITE_API_BASE_URL=http://localhost:8082
VITE_APP_MODE=development
```

### Backend Environment

See the main `.env` file in the project root:

```env
# Database
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=simpleec_oms
POSTGRES_USER=postgres
POSTGRES_PASSWORD=<secure-password>

# Redis
REDIS_HOST=redis
REDIS_PORT=6379

# Kafka
KAFKA_BROKERS=kafka:9092

# APIs
SERVER_PORT=8082
GATEWAY_PORT=8081
```

---

## Port Mapping

### Local Development

```
Host Port → Container Port → Service
────────────────────────────────────
8080 → 80/3000 (container) → Micro-Frontend Container
8081 → 3001 → Admin App (Vite)
8082 → 8080 → API
8083 → 3002 → User App (Vite)
8088 → 8080 → Kafka UI
3000 → 3000 → Grafana
5432 → 5432 → PostgreSQL
6379 → 6379 → Redis
9092 → 9092 → Kafka
```

### Testing Connectivity

```bash
# Test API
curl http://localhost:8082/health

# Test Container
curl http://localhost:8080

# Test User App (via Container)
curl -H "Accept: application/json" http://localhost:8080/#/app/

# Test Admin App (via Container)
curl -H "Accept: application/json" http://localhost:8080/#/admin/
```

---

## Dependency Services

### PostgreSQL

Database for storing orders, products, merchants, and system data.

```bash
# Check database
docker exec -it postgres psql -U postgres -d simpleec_oms
```

### Redis

Session caching and temporary data storage.

```bash
# Check Redis
docker exec -it redis redis-cli ping
# Output: PONG
```

### Kafka

Event streaming for order processing, inventory sync, and webhook events.

```bash
# Check Kafka topics
docker exec -it kafka kafka-topics.sh --list --bootstrap-server localhost:9092

# Monitor messages
docker exec -it kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic order.process \
  --from-beginning
```

### Grafana

Observability and monitoring dashboard.

Access at: http://localhost:3000
- Default credentials: admin / admin
- Shows metrics from Prometheus
- Shows logs from Loki
- Shows traces from Tempo

---

## Troubleshooting

### User App Not Loading in Container

**Problem**: `/app/` route shows loading indefinitely

**Solution**:
1. Verify User App is running on port 8083:
   ```bash
   curl http://localhost:8083
   ```
2. Check browser console for CORS errors
3. Ensure User App is built:
   ```bash
   cd user-app && npm run build
   ```
4. Restart container:
   ```bash
   docker-compose restart micro-frontend-container
   ```

### API Connection Errors

**Problem**: "Failed to connect to API"

**Solution**:
1. Verify API is running:
   ```bash
   curl http://localhost:8082/health
   ```
2. Check environment variables in `.env`
3. Verify firewall allows port 8082
4. Check API logs:
   ```bash
   docker logs simpleec-api
   ```

### Database Connection Issues

**Problem**: "Connection refused" errors in logs

**Solution**:
1. Verify PostgreSQL is running:
   ```bash
   docker ps | grep postgres
   ```
2. Check database credentials in `.env`
3. Verify database is initialized:
   ```bash
   docker exec postgres psql -U postgres -l | grep simpleec_oms
   ```
4. Reinitialize if needed:
   ```bash
   docker-compose down -v
   docker-compose up -d postgres
   docker exec postgres psql -U postgres < init-db.sql
   ```

### Redis Connection Errors

**Problem**: "Cannot connect to Redis" errors

**Solution**:
1. Verify Redis is running:
   ```bash
   docker ps | grep redis
   ```
2. Test connection:
   ```bash
   docker exec redis redis-cli ping
   ```
3. Check Redis logs if not responding:
   ```bash
   docker logs redis
   ```

### Kafka Message Queue Issues

**Problem**: Messages not being consumed

**Solution**:
1. Check Kafka status:
   ```bash
   docker exec kafka kafka-broker-api-versions.sh --bootstrap-server localhost:9092
   ```
2. Verify topics exist:
   ```bash
   docker exec kafka kafka-topics.sh --list --bootstrap-server localhost:9092
   ```
3. Check consumer group status:
   ```bash
   docker exec kafka kafka-consumer-groups.sh --list --bootstrap-server localhost:9092
   ```

### Port Already in Use

**Problem**: "Address already in use" when starting services

**Solution**:
1. Identify process using the port:
   ```bash
   lsof -i :8080
   ```
2. Kill the process:
   ```bash
   kill -9 <PID>
   ```
3. Or change port in `.env` / `docker-compose.yml`

### Build Failures

**Problem**: npm or gradle build fails

**Solution**:
1. Clear caches:
   ```bash
   # Frontend
   rm -rf node_modules package-lock.json
   npm install

   # Backend
   ./gradlew clean
   ./gradlew build
   ```
2. Check Node/Java versions:
   ```bash
   node --version  # Should be 18+
   java -version   # Should be 17+
   ```

---

## Health Checks

Verify system health:

```bash
#!/bin/bash

echo "Checking services..."

# Container
echo "Container: $(curl -s http://localhost:8080 > /dev/null && echo '✓' || echo '✗')"

# API
echo "API: $(curl -s http://localhost:8082/health | grep -q status && echo '✓' || echo '✗')"

# User App
echo "User App: $(curl -s http://localhost:8083 > /dev/null && echo '✓' || echo '✗')"

# Admin App
echo "Admin App: $(curl -s http://localhost:8081 > /dev/null && echo '✓' || echo '✗')"

# PostgreSQL
echo "Database: $(docker exec postgres pg_isready 2>/dev/null | grep -q accepting && echo '✓' || echo '✗')"

# Redis
echo "Cache: $(docker exec redis redis-cli ping 2>/dev/null | grep -q PONG && echo '✓' || echo '✗')"

# Kafka
echo "Queue: $(docker exec kafka kafka-broker-api-versions.sh --bootstrap-server localhost:9092 2>/dev/null | grep -q ApiVersion && echo '✓' || echo '✗')"
```

---

## Production Deployment

For production deployment:

1. **Use environment-specific configs**:
   - Separate `.env.prod` files
   - Use secrets management (e.g., HashiCorp Vault)
   - Never commit credentials to Git

2. **Enable security**:
   - HTTPS/TLS for all services
   - API authentication (JWT tokens)
   - CORS restrictions
   - Rate limiting

3. **Configure monitoring**:
   - Enable all Grafana dashboards
   - Set up alert rules
   - Configure log aggregation
   - Enable distributed tracing

4. **Optimize performance**:
   - Use CDN for static assets
   - Enable caching headers
   - Configure connection pools
   - Tune database indexes

5. **Backup & Recovery**:
   - Daily PostgreSQL backups
   - Redis persistence (AOF enabled)
   - Kafka retention policies
   - Test restore procedures

---

## Support

For issues or questions:

1. Check application logs:
   ```bash
   docker logs <service-name> -f
   ```

2. Access Grafana dashboards at http://localhost:3000

3. Monitor Kafka messages at http://localhost:8088

4. Review documentation in `/docs` folder

---

**Last Updated**: 2026-02-21
**Version**: 1.0.0
