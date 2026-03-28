# 資料庫 Migration（Flyway）

SimpleEC OMS 使用 [Flyway](https://flywaydb.org/) 進行 schema 版本管理。Flyway 確保 schema 變更依序套用且不會重複執行，提供從任意 schema 版本升級至最新版本的可靠路徑。

---

## 1. Flyway 在本專案的運作方式

**只有 `simpleec-api` 負責執行 migration。** 其他服務（`simpleec-order-job`、`simpleec-channel-job` 等）連接已完成 migration 的資料庫，依賴 schema 的正確性。

```
docker compose up
    │
    ├─ postgres 啟動（init-db/01-schema.sql 在全新安裝時建立所有資料表）
    │
    └─ simpleec-api 啟動
           │
           └─ Flyway 於啟動時執行：
                  • 檢查 flyway_schema_history 資料表
                  • 全新 DB：找不到 history 表 → baseline-on-migrate 啟動
                    （標記當前狀態為 V1，跳過 V1 migration）
                  • 已有 history 的 DB：僅套用待處理的 migration（V2+）
```

**Flyway 相依**（在 `simpleec-api/build.gradle` 中）：
```groovy
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-database-postgresql'
// 注意：flyway-core 不在其他任何服務的 build.gradle 中
```

**JPA DDL** 設定為 `validate`（非 `update` 或 `create`）：
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # Hibernate 驗證 schema 與 entity 相符；不修改 schema
```

這意味著 Flyway 負責管理 schema；Hibernate 僅負責驗證。

---

## 2. Migration 檔案命名規則

Migration 檔案遵循 Flyway 標準命名格式：

```
V{版本}__{說明}.sql

範例：
  V1__initial_schema.sql
  V2__add_priority_to_orders.sql
  V3__add_channel_api_versions.sql
```

規則：
- 版本號與說明之間使用**雙底線**
- 版本號為**整數**（1, 2, 3...），非小數
- 說明使用底線，不使用空格
- 檔案必須是 SQL（`.sql` 副檔名）
- 已套用的 migration 檔案**不可更動**——切勿修改已套用的 migration

位置：`simpleec-api/src/main/resources/db/migration/`

---

## 3. Flyway 設定

來自 `simpleec-api/src/main/resources/application.yml`：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration          # migration 檔案位置
    baseline-on-migrate: true                  # 見第 4 節
    baseline-version: 1                        # V1 為基線版本
    out-of-order: false                        # migration 必須嚴格依序套用
```

---

## 4. baseline-on-migrate 說明

`baseline-on-migrate: true` 是讓 Flyway 在全新安裝和升級兩種情境下都能正確運作的關鍵設定。

### 情境 A：全新安裝

```
1. Docker 啟動 → init-db/01-schema.sql 執行 → 建立所有 19 張資料表
2. simpleec-api 啟動 → Flyway 檢查 DB
3. 找不到 flyway_schema_history 資料表
4. baseline-on-migrate=true：Flyway 將當前狀態標記為 V1 基線
   （建立 flyway_schema_history，V1 標記為 "BASELINE"）
5. V1__initial_schema.sql 被跳過（已由 Docker init 套用）
6. 若存在 V2+ migration 則執行
```

### 情境 B：已有資料的資料庫（升級）

```
1. DB 已有資料和來自前次部署的所有資料表
2. simpleec-api 啟動 → Flyway 檢查 DB
3. flyway_schema_history 存在並包含 V1 記錄
4. Flyway 僅執行 V2、V3...（待處理的 migration）
5. 資料保留；schema 擴展
```

### 情境 C：開發人員機器（首次）

```
1. git clone + docker compose up
2. 同情境 A——Docker init + Flyway baseline
3. 開發人員不需要手動執行任何 SQL
```

---

## 5. 新增 Migration

```bash
# 步驟 1：建立 migration 檔案
touch simpleec-api/src/main/resources/db/migration/V2__add_priority_to_orders.sql

# 步驟 2：撰寫 SQL
cat > simpleec-api/src/main/resources/db/migration/V2__add_priority_to_orders.sql << 'EOF'
-- V2: Add priority field to orders for urgent order handling
ALTER TABLE orders ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_orders_priority ON orders(merchant_id, priority) WHERE priority > 0;
EOF

# 步驟 3：重新建置 simpleec-api（migration 在啟動時自動執行）
./gradlew :simpleec-api:build -x test
./quick-redeploy.sh simpleec-api

# 步驟 4：驗證 migration 已執行
docker exec simpleec-postgres psql -U simpleec -d simpleec \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY version;"
```

**禁止事項**：
- 修改已套用的 migration 檔案
- 刪除已套用到任何環境的 migration 檔案
- 未經審慎考量使用 `out-of-order: true`（允許在 V2 之前套用 V3）
- 跳過版本號（V1 → V3 容易混淆；應使用 V1 → V2 → V3）

---

## 6. 查看 Migration 狀態

```bash
# 在容器內查看 Flyway migration 歷史
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT installed_rank, version, description, type, success, installed_on
   FROM flyway_schema_history
   ORDER BY installed_rank;"

# 查看待處理的 migration（simpleec-api 啟動日誌）
docker logs simpleec-api 2>&1 | grep -i "flyway\|migration"
```

---

## 7. 目前的 Migration

| 版本 | 檔案 | 狀態 | 說明 |
|---------|------|--------|-------------|
| V1 | `V1__initial_schema.sql` | 已套用（基線） | 初始 schema——所有 19 張資料表、所有索引、所有 FK 約束、`daily_statistics` 2026 年月分區 |

### 待處理事項

Task #33（`[pending] 引入 Flyway schema 版本管理`）追蹤仍需正式化為 migration 的 schema 變更。若你對 `01-schema.sql`（Docker init 檔案）進行結構性修改，**也必須**建立對應的 `V{n}__description.sql` migration，以確保既有部署的資料庫可在不遺失資料的情況下升級。

---

## 8. Flyway 在 Docker Compose 中的配置

`simpleec-api` 依賴 `postgres` 健康後才會啟動：

```yaml
# docker-compose.yml（節錄）
simpleec-api:
  depends_on:
    simpleec-postgres:
      condition: service_healthy
```

PostgreSQL 的健康檢查在 `pg_isready` 回應之前不會回報 healthy。這確保 `01-schema.sql` 完整執行後，Flyway 才嘗試執行。

---

## 9. 環境專屬注意事項

### 開發環境（本地 Docker Compose）
- `baseline-on-migrate: true`——對全新容器安全
- Migration 失敗會導致 `simpleec-api` 啟動失敗，並在日誌中顯示清楚的 Flyway 錯誤訊息

### 生產環境
- 一旦所有環境都有 `flyway_schema_history` 資料表（即首次使用 Flyway 部署到生產後），考慮將 `baseline-on-migrate` 設為 `false`
- 在多 broker 生產叢集中，於 Kafka topic 建立時設定 `REPLICATION_FACTOR=3`
- 使用 `DB_PASSWORD` 環境變數（來自 `.env` 檔案，切勿硬編碼）提供資料庫憑證
