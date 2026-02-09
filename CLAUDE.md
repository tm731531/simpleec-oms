# SimpleEC OMS — Claude Session Guide

> 多平台電商訂單管理系統。Spring Boot 3.5 + Kafka + PostgreSQL + Redis。
> 整合 Momo / Shopee / Yahoo / PChome，統一管理商品、訂單、出貨與庫存。

---

## 快速定位

| 你要做什麼 | 讀哪份文件 |
|-----------|-----------|
| 理解系統全貌 | `DESIGN_v2.md` §0（現狀）→ §1-§15（完整設計） |
| 看 DB Schema | `docs/SCHEMA.md`（19 張表 DDL） |
| 看目前完成到哪 | **`docs/STATUS.md`**（唯一進度真相來源） |
| 看詳細修改歷史 | `REWRITE_PLAN.md`（16 輪演進） |
| 看事件流設計 | `docs/event-flows/FETCH_ORDERS.md`, `FETCH_PRODUCTS.md` |
| 看 Entity↔Schema 差異 | `docs/event-flows/DB_ENTITY_GAPS.md` |
| 看統計設計 | `docs/STATISTICS_DESIGN.md`（多角色統計 + 退貨流程） |
| Docker 操作 | `docs/DOCKER_GUIDE.md` |
| Kafka 維運 | `docs/OPERATIONS_RUNBOOK.md` |

---

## 建構與部署

```bash
cd /home/tom/ONEEC/simpleec-oms

# 編譯（跳過測試，目前 0 測試檔）
./gradlew clean build -x test

# 啟動全部 26 個容器
docker compose up -d --build

# 只啟動基礎設施（開發用）
docker compose up -d postgres redis kafka otel-collector tempo loki prometheus grafana
```

---

## 技術棧

- Java 17, Spring Boot 3.5.0, Gradle 8.14.4
- PostgreSQL 16, Redis 7 (AOF), Kafka 3.7.1 (KRaft)
- MyBatis-Plus (ORM), NanoID (PK), AES-256-GCM (PII 加密)
- OTEL Agent + Grafana (Prometheus + Loki + Tempo)
- 11 Gradle 模組, 26 Docker 容器, 14 Kafka Topics

---

## 模組結構

```
simpleec-oms/
├── simpleec-common        # 共用：Enum, Model, Util (IdGenerator, PiiMasker)
├── simpleec-core          # 核心：Entity, Mapper, Service, Kafka, Crypto
├── simpleec-channel       # 通路：ChannelAdapter 介面 + 平台實作
├── simpleec-api           # REST API (:8082) — Controller, VO
├── simpleec-gateway       # 對外 Gateway (:8081) — Webhook, ERP
├── simpleec-channel-job   # 通路同步 JOB (×8: 4 平台 × fast/slow)
├── simpleec-order-job     # 訂單處理 JOB
├── simpleec-scheduler-job # 排程引擎 (HeartbeatTimer)
├── simpleec-backend-job   # 後台非同步 JOB
├── simpleec-frontend-job  # 前台事件 JOB
└── simpleec-retry-job     # 失敗重試 / DLT 路由
```

---

## 編碼慣例

### PK / ID
- **所有表 PK 都是 `VARCHAR(20)` NanoID**，程式端用 `IdGenerator.nextId()` 產生
- Entity 用 `@TableId(type = IdType.ASSIGN_UUID)` + `String id`
- 所有 FK（merchantId, channelId 等）也是 `String`

### PII 加密
- `buyer_name`, `buyer_phone`, `buyer_email`, `shipping_address` — AES-256-GCM
- 透過 `EncryptedFieldTypeHandler` 在 MyBatis 層透明加解密
- 任何讀取加密欄位的程式碼必須包在 `EncryptionContext.setMerchantId()` / `clear()` 之間
- API 列表用 `OrderVO.fromMasked()`，詳情/匯出用 `OrderVO.fromPlain()`

### Kafka
- `TaskMessage` 統一訊息格式（schemaVersion=1, action, merchantId, ownerId, payload JSON）
- `SchemaVersionHandler.normalize()` 版本驗證；不支援版本 → `task.dlt`
- `TaskMdcHelper.set(msg)` / `clear()` 在所有 JOB handle() 方法中使用

### 日誌
- `logback-spring.xml`：local=pattern, docker=JSON (LogstashEncoder)
- MDC 自動帶 traceId, spanId, merchantId, action

### 錯誤處理
- `DefaultErrorHandler` + `FixedBackOff(0L, 0L)` 跳過 poison pill
- 失敗訊息 → `task.failed` → `RetryDispatchJob` → 重試或 `task.dlt`

### 正逆物流 & 退貨
- **正物流**：pending → confirmed → processing → shipped → delivered → completed → cancelled
- **逆物流**：refunding → refunded（**可從任何正物流狀態觸發**，不限 completed）
- **平台資料有 gap**：區間沒覆蓋、webhook 漏接、平台跳過中間狀態 — 不做狀態轉換驗證
- **部分退貨**：orders.status 不變（維持當前狀態），orders.refund_amount 累加，has_refund = true
- **全額退貨**：refund_amount >= total_amount 時 status 改為 refunded
- **兩邊都記**：refund_orders 記明細，orders.refund_amount 記匯總（ORDER_STATUS_CHANGED 觸發同步）
- **統計退款記在退款日**，不回溯訂單建立日

### 統計（4 角色視角）
- **業務**：新增訂單（看 created_at）
- **老闆**：營業額 = 排除 cancelled 的 total_amount
- **財務**：實收（confirmed 以上）- 退款 = 淨收
- **RMA**：退款筆數 + 金額
- 詳見 `docs/STATISTICS_DESIGN.md`

---

## 下一步工作

詳見 `docs/STATUS.md`。摘要：

**Phase 2（Adapter 層重構）— 待做：**
1. 新建 `ChannelProduct`, `ChannelOrder` DTO
2. `ChannelAdapter` 加 `fetchProducts(String channelId)`
3. `fetchOrders()` 回傳改 `ChannelOrder`（不是 `Order` Entity）
4. 各平台 Adapter 實作

**Level 2（Entity 補齊）— 待做：**
- 補齊 Merchant, Channel, Platform, Account 等 14 張表的 Entity / Mapper

**Level 3（業務邏輯）— 待做：**
- OrderProcessJob DB upsert
- DailyStatistics 多角色聚合（設計完成，見 `docs/STATISTICS_DESIGN.md`）
- ORDER_STATUS_CHANGED 退款同步（orders.refund_amount ↔ refund_orders）
- Statistics API（/summary, /daily, /by-channel + view 參數）
- SchedulerJob 從 DB 讀 channel 列表

---

## Git 資訊

- GitHub: `tm731531/simpleec-oms` (SSH)
- Branch: `main`
- 最近 commits:
  - `ab0a2c0` — Update docs (PII encryption, masking, entity alignment)
  - `618c16b` — Add PII masking for order API + CSV export
  - `b35fee0` — Add PII field-level encryption (AES-256-GCM)
  - `d640f8e` — Align Entity classes with Schema v4 (NanoID PK)
