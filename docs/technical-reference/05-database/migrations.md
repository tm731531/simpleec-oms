# Database Migrations (Flyway)

SimpleEC OMS uses [Flyway](https://flywaydb.org/) for schema version management. Flyway ensures
that schema changes are applied in order and never applied twice, providing a reliable upgrade
path from any schema version to the latest.

---

## 1. How Flyway Works in This Project

**Only `simpleec-api` runs migrations.** Other services (`simpleec-order-job`, `simpleec-channel-job`,
etc.) connect to the already-migrated database and rely on the schema being correct.

```
docker compose up
    │
    ├─ postgres starts (init-db/01-schema.sql creates all tables for fresh install)
    │
    └─ simpleec-api starts
           │
           └─ Flyway runs on startup:
                  • Checks flyway_schema_history table
                  • Fresh DB: no history table → baseline-on-migrate kicks in
                    (marks current state as V1, skips V1 migration)
                  • Existing DB with history: applies only pending migrations (V2+)
```

**Flyway dependency** (in `simpleec-api/build.gradle`):
```groovy
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-database-postgresql'
// Note: flyway-core is NOT in any other service's build.gradle
```

**JPA DDL** is set to `validate` (not `update` or `create`):
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # Hibernate validates schema matches entities; does NOT modify schema
```

This means Flyway owns the schema; Hibernate just checks it.

---

## 2. Migration File Naming Convention

Migration files follow the Flyway standard naming pattern:

```
V{version}__{description}.sql

Examples:
  V1__initial_schema.sql
  V2__add_priority_to_orders.sql
  V3__add_channel_api_versions.sql
```

Rules:
- **Double underscore** between version and description
- Version numbers are **integers** (1, 2, 3...) not decimals
- Description uses underscores, no spaces
- File must be SQL (`.sql` extension)
- Files are **immutable** once applied — never edit an applied migration

Location: `simpleec-api/src/main/resources/db/migration/`

---

## 3. Flyway Configuration

From `simpleec-api/src/main/resources/application.yml`:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration          # Where to find migration files
    baseline-on-migrate: true                  # See Section 4
    baseline-version: 1                        # V1 is the baseline version
    out-of-order: false                        # Migrations must be applied in strict order
```

---

## 4. baseline-on-migrate Explained

`baseline-on-migrate: true` is the key setting that makes Flyway work correctly in both
fresh-install and upgrade scenarios.

### Scenario A: Fresh Install

```
1. Docker starts → init-db/01-schema.sql runs → all 19 tables created
2. simpleec-api starts → Flyway inspects DB
3. No flyway_schema_history table found
4. baseline-on-migrate=true: Flyway marks current state as V1 baseline
   (creates flyway_schema_history with V1 as "BASELINE")
5. V1__initial_schema.sql is SKIPPED (already applied by Docker init)
6. V2+ migrations would run if they exist
```

### Scenario B: Existing Database (Upgrade)

```
1. DB already has data and all tables from a previous deployment
2. simpleec-api starts → Flyway inspects DB
3. flyway_schema_history exists with V1 entry
4. Flyway runs only V2, V3... (pending migrations)
5. Data is preserved; schema is extended
```

### Scenario C: Developer Machine (First Time)

```
1. git clone + docker compose up
2. Same as Scenario A — Docker init + Flyway baseline
3. Developer never needs to manually run SQL
```

---

## 5. Adding a New Migration

```bash
# Step 1: Create the migration file
touch simpleec-api/src/main/resources/db/migration/V2__add_priority_to_orders.sql

# Step 2: Write the SQL
cat > simpleec-api/src/main/resources/db/migration/V2__add_priority_to_orders.sql << 'EOF'
-- V2: Add priority field to orders for urgent order handling
ALTER TABLE orders ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_orders_priority ON orders(merchant_id, priority) WHERE priority > 0;
EOF

# Step 3: Rebuild simpleec-api (migration runs automatically on startup)
./gradlew :simpleec-api:build -x test
./quick-redeploy.sh simpleec-api

# Step 4: Verify migration ran
docker exec simpleec-postgres psql -U simpleec -d simpleec \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY version;"
```

**Never**:
- Edit an already-applied migration file
- Delete a migration file that has been applied to any environment
- Use `out-of-order: true` without careful consideration (it allows applying V3 before V2)
- Skip version numbers (V1 → V3 is confusing; use V1 → V2 → V3)

---

## 6. Checking Migration Status

```bash
# View Flyway migration history inside the container
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT installed_rank, version, description, type, success, installed_on
   FROM flyway_schema_history
   ORDER BY installed_rank;"

# Check which migrations are pending (simpleec-api logs on startup)
docker logs simpleec-api 2>&1 | grep -i "flyway\|migration"
```

---

## 7. Current Migrations

| Version | File | Status | Description |
|---------|------|--------|-------------|
| V1 | `V1__initial_schema.sql` | Applied (baseline) | Initial schema — all 19 tables, all indexes, all FK constraints, `daily_statistics` monthly partitions for 2026 |

### Pending Work

Task #33 (`[pending] 引入 Flyway schema 版本管理`) tracks schema changes that still need to be
formalized as migrations. If you add a structural change to `01-schema.sql` (the Docker init
file), you **must also** create a corresponding `V{n}__description.sql` migration so that existing
deployed databases can be upgraded without data loss.

---

## 8. Flyway in Docker Compose

`simpleec-api` depends on `postgres` being healthy before it starts:

```yaml
# docker-compose.yml (excerpt)
simpleec-api:
  depends_on:
    simpleec-postgres:
      condition: service_healthy
```

PostgreSQL's health check waits for `pg_isready` before signaling healthy. This ensures
`01-schema.sql` has fully executed before Flyway tries to run.

---

## 9. Environment-Specific Notes

### Development (local Docker Compose)
- `baseline-on-migrate: true` — safe for fresh containers
- Migration failures cause `simpleec-api` to fail startup with a clear Flyway error log

### Production
- Consider setting `baseline-on-migrate: false` once all environments have a `flyway_schema_history`
  table (i.e., after the first production deployment with Flyway)
- Set `REPLICATION_FACTOR=3` in Kafka topic creation for multi-broker production clusters
- Use `DB_PASSWORD` env var (from `.env` file, never hardcoded) for database credentials
