# SimpleEC OMS — 開發進度總覽

> **唯一的進度真相來源。** 所有進度查詢以此文件為準。
>
> 最後更新：2026-02-09

---

## 完成度概覽

```
Level 1   ████████████████████ 100%  Entity ↔ Schema 對齊
Level 1.5 ████████████████████ 100%  PII 加密 + API 遮罩
Level 2   ░░░░░░░░░░░░░░░░░░░░   0%  補齊 14 張表 Entity/Mapper
Level 3   ░░░░░░░░░░░░░░░░░░░░   0%  業務邏輯 (JOB upsert, 統計, 排程)
Level 4   ░░░░░░░░░░░░░░░░░░░░   0%  通路 Adapter 實作
```

---

## Level 1 — Entity ↔ Schema 對齊 ✅ 已完成

> Commit: `d640f8e` (2026-02-09)

| # | 項目 | 狀態 |
|---|------|------|
| 1 | 所有 Entity PK `Long` → `String` NanoID + `IdType.ASSIGN_UUID` | ✅ |
| 2 | 所有 FK（merchantId, channelId 等）`Long` → `String` | ✅ |
| 3 | `Order.java` 新增 `items` (JSONB) 欄位 | ✅ |
| 4 | `SellPack.java` 新增 4 欄位 (sku, channelSpecId, channelProductName, channelSpecName) | ✅ |
| 5 | 刪除 `OrderItem.java` + `ProductSpec.java` + 對應 Mapper | ✅ |
| 6 | `ChannelAdapter` 參數 `Long channelId` → `String channelId` | ✅ |
| 7 | `IdGenerator.java` — NanoID 工具類 | ✅ |
| 8 | `MyBatisPlusConfig` — 自訂 ID 產生器 | ✅ |

**影響檔案：** Order, Product, SellPack, OrderStatusLog (Entity) + 4 Mapper + 2 Service + 3 Controller + ChannelAdapter

---

## Level 1.5 — PII 加密 + API 遮罩 ✅ 已完成

> Commits: `b35fee0` (加密) + `618c16b` (遮罩) (2026-02-09)

### Step 16：AES-256-GCM 加密

| 新增檔案 | 說明 |
|---------|------|
| `core/crypto/EncryptionContext.java` | ThreadLocal merchantId |
| `core/crypto/MasterKeyProvider.java` | JdbcTemplate 讀三次 Base64 master key |
| `core/crypto/AesGcmEncryptor.java` | AES-256-GCM + PBKDF2 per-merchant key |
| `core/crypto/EncryptedFieldTypeHandler.java` | MyBatis TypeHandler 透明加解密 |
| `core/crypto/EncryptionConfig.java` | Spring Config 注入 encryptor |

**修改：** Order.java (autoResultMap + TypeHandler), OrderService.java (EncryptionContext), 01-schema.sql (加寬欄位), 02-seed-data.sql (master key)

### Step 17：API 遮罩 + 解鎖 + CSV 匯出

| 新增檔案 | 說明 |
|---------|------|
| `common/util/PiiMasker.java` | maskName / maskPhone / maskEmail / maskAddress |
| `api/vo/OrderVO.java` | fromMasked() / fromPlain() factory methods |

**修改：** OrderService.java (+getById +listForExport), OrderController.java (列表遮罩 + 詳情明文 + CSV 匯出)

**API 端點：**

| 方法 | 路徑 | PII | 說明 |
|------|------|-----|------|
| GET | `/api/v1/orders` | 遮罩 | 列表（分頁） |
| GET | `/api/v1/orders/{id}` | 明文 | 單筆詳情 |
| GET | `/api/v1/orders/export` | 明文 | CSV 下載（UTF-8 BOM） |

---

## Level 2 — 補齊 Entity / Mapper ❌ 待做

DB Schema（19 張表）已定義在 `docs/SCHEMA.md`，但目前只有 4 個 Entity。

| Entity | Mapper | Service | Controller | 狀態 |
|--------|--------|---------|------------|------|
| Order | ✅ | ✅ | ✅ | ✅ 完整 |
| Product | ✅ | ✅ | ✅ | ✅ 完整 |
| SellPack | ✅ | ❌ | ❌ | 🟡 只有 Entity+Mapper |
| OrderStatusLog | ✅ | ❌ | ❌ | 🟡 只有 Entity+Mapper |
| Merchant | ❌ | ❌ | ❌ | ❌ 待建 |
| Channel | ❌ | ❌ | ❌ | ❌ 待建 |
| Platform | ❌ | ❌ | ❌ | ❌ 待建 |
| Account | ❌ | ❌ | ❌ | ❌ 待建 |
| ProductGroup | ❌ | ❌ | ❌ | ❌ 待建 |
| ProductBarcode | ❌ | ❌ | ❌ | ❌ 待建 |
| RefundOrder | ❌ | ❌ | ❌ | ❌ 待建 |
| ShippingRecord | ❌ | ❌ | ❌ | ❌ 待建 |
| SyncLog | ❌ | ❌ | ❌ | ❌ 待建 |
| DailyStatistics | ❌ | ❌ | ❌ | ❌ 待建 |
| FailedTaskLog | ❌ | ❌ | ❌ | ❌ 待建 |
| GlobalConfig | ❌ | ❌ | ❌ | ❌ 待建 |
| ChannelSetting | ❌ | ❌ | ❌ | ❌ 待建 |
| InventoryReserve | ❌ | ❌ | ❌ | ❌ 待建 |

---

## Adapter 層重構 ❌ 待做

> 對應 `docs/event-flows/DB_ENTITY_GAPS.md` Phase 2

| # | 項目 | 狀態 |
|---|------|------|
| 1 | 新建 `ChannelProduct` DTO（平台商品） | ❌ |
| 2 | 新建 `ChannelProductSpec` DTO | ❌ |
| 3 | 新建 `ChannelOrder` DTO（平台訂單） | ❌ |
| 4 | 新建 `ChannelOrderItem` DTO | ❌ |
| 5 | `ChannelAdapter` 新增 `fetchProducts(String channelId)` | ❌ |
| 6 | `fetchOrders()` 回傳 `Order` → 改 `ChannelOrder` | ❌ |
| 7 | 各平台 Adapter 實作 fetchProducts + 修改 fetchOrders | ❌ |

DTO 設計稿見 `docs/event-flows/DB_ENTITY_GAPS.md` §6。

---

## Level 3 — 業務邏輯 ❌ 待做

| # | 項目 | 模組 | 狀態 |
|---|------|------|------|
| 1 | OrderProcessJob — DB upsert (match sell_pack → 填 sellPackId/productId) | order-job | ❌ |
| 2 | DailyStatistics 聚合 | backend-job | ❌ skeleton |
| 3 | ManagePartitions — 分區表管理 | backend-job | ❌ skeleton |
| 4 | FailedTaskLog 持久化 | retry-job | ❌ skeleton |
| 5 | SchedulerJob — 從 DB 讀 channel 列表 | scheduler-job | ❌ skeleton |
| 6 | FetchProductsActionService — 同步商品 | channel-job | ❌ |
| 7 | FetchOrdersActionService — 拉單 | channel-job | ❌ |
| 8 | Redis Hash Dedup — 訂單去重 | channel-job | ❌ |
| 9 | JWT 認證 — login / token / refresh | api | ❌ |

---

## Level 4 — 通路 Adapter 實作 ❌ 待做

| 平台 | Adapter | API 串接 | 測試 |
|------|---------|---------|------|
| Momo | ❌ placeholder | ❌ | ❌ |
| Shopee | ❌ 不存在 | ❌ | ❌ |
| Yahoo | ❌ 不存在 | ❌ | ❌ |
| PChome | ❌ 不存在 | ❌ | ❌ |

---

## 基礎設施 ✅ 已完成

| 項目 | 狀態 |
|------|------|
| Gradle 11 模組 | ✅ |
| Docker Compose 26 容器 | ✅ |
| PostgreSQL 16 (19 表 DDL + seed) | ✅ |
| Kafka KRaft (14 topics) | ✅ |
| Redis 7 (AOF) | ✅ |
| OTEL Agent + Grafana 觀測 | ✅ |
| Structured logging (logback) | ✅ |
| Schema version handler | ✅ |
| DLT / poison pill handling | ✅ |
| MDC trace propagation | ✅ |
| NanoID PK generator | ✅ |
| PII encryption (AES-256-GCM) | ✅ |
| PII API masking + CSV export | ✅ |
| Git repo + GitHub remote | ✅ |

---

## 現有 Java 檔案清單（70 個）

### simpleec-common (6)
- `enums/ActionType.java` — 34 種操作類型
- `enums/ChannelType.java` — 4 個平台
- `enums/OrderStatus.java` — 9 種訂單狀態
- `exception/BusinessException.java`
- `model/ApiResponse.java`, `PageResult.java`
- `util/IdGenerator.java`, `PiiMasker.java`

### simpleec-core (18)
- `entity/` — Order, Product, SellPack, OrderStatusLog
- `mapper/` — OrderMapper, ProductMapper, SellPackMapper, OrderStatusLogMapper
- `service/` — OrderService, ProductService
- `crypto/` — AesGcmEncryptor, EncryptedFieldTypeHandler, EncryptionConfig, EncryptionContext, MasterKeyProvider
- `kafka/` — KafkaConfig, TaskMessage, TaskProducer, SchemaVersionHandler
- `observability/` — TaskMdcHelper
- `config/` — FeatureGateConfig, MybatisPlusConfig

### simpleec-api (7)
- `controller/` — OrderController, ProductController, ChannelActionController, HealthController
- `config/` — GlobalExceptionHandler, MyBatisPlusConfig, SecurityConfig
- `vo/` — OrderVO

### simpleec-channel (3)
- `adapter/` — ChannelAdapter (interface), ChannelAdapterFactory
- `momo/` — MomoChannelAdapter (placeholder)

### JOB 模組 (17)
- channel-job: ChannelJob, ActionFactory, ActionService, CheckHealthActionService, Resource, SyncLogService
- order-job: OrderProcessJob
- backend-job: BackendJob, BackendActionService, DailyStatisticsActionService, ManagePartitionsActionService
- frontend-job: FrontendJob
- scheduler-job: SchedulerJob, HeartbeatTimer, ScheduleConfig, ScheduleRule
- retry-job: RetryDispatchJob, FailedTaskLogService, RetryPolicyService

### simpleec-gateway (3)
- HealthController, SecurityConfig, SimpleecGatewayApplication

---

## 建議的下一步執行順序

```
1. Level 2 — 補齊 Entity/Mapper（機械性工作，風險低）
   → Merchant, Channel, Platform, Account 等 14 張表
   → 每張表: Entity + Mapper + 基礎 Service

2. Adapter DTO — 新建 ChannelProduct/ChannelOrder
   → ChannelAdapter 介面調整
   → 為平台實作做準備

3. Level 3 — 核心業務邏輯
   → OrderProcessJob DB upsert（最高優先）
   → FetchOrders/FetchProducts ActionService
   → Redis Hash Dedup
   → JWT 認證

4. Level 4 — 通路 Adapter 實作
   → 需要各平台 API 文件
   → Momo → Shopee → Yahoo → PChome
```

---

## 相關文件索引

| 文件 | 用途 |
|------|------|
| `CLAUDE.md` | 新 session 入口（讀這份先） |
| `DESIGN_v2.md` | 完整系統設計（16 章節，4935 行） |
| `REWRITE_PLAN.md` | 16 輪演進歷史 + 決策原因 |
| `README.md` | 專案概覽 + Quick Start |
| `docs/SCHEMA.md` | DB Schema v4（19 表 DDL） |
| `docs/DOCKER_GUIDE.md` | Docker 環境操作 |
| `docs/OPERATIONS_RUNBOOK.md` | Kafka 營運手冊 |
| `docs/IMPLEMENTATION_PLAN.md` | Level 1 + 1.5 詳細實作紀錄 |
| `docs/event-flows/FETCH_ORDERS.md` | 拉單事件流（3-JOB chain） |
| `docs/event-flows/FETCH_PRODUCTS.md` | 同步商品事件流 |
| `docs/event-flows/DB_ENTITY_GAPS.md` | Entity ↔ Schema 差異追蹤 |
| `docs/event-flows/README.md` | 事件流一致性 checklist |
