# SimpleEC OMS 實施計劃

> 文件日期：2026-02-20
> 狀態：實施階段啟動
> 分支：implementation

---

## 1. 項目概況

### 1.1 目標

從 **0** 到 **MVP**（最小可行產品）：
- 支持多通路訂單入庫 (Momo, Shopee)
- 支持訂單出貨和退貨
- 基礎的 Kafka 事件處理

### 1.2 時間表

```
Phase 1：基礎設施建設    (1 週)    → Sprint 1
Phase 2：Kafka 消費者    (2 週)    → Sprint 2-3
Phase 3：平台適配器      (3 週)    → Sprint 4-6
Phase 4：集成測試 + 優化  (2 週)    → Sprint 7-8
────────────────────────────────
總計：8 週（2 個月）
```

### 1.3 成功指標

- ✅ 100% 完成單元測試
- ✅ 95% 代碼覆蓋率（關鍵路徑）
- ✅ 完整 E2E 測試流程
- ✅ 性能：處理 1000 訂單/分鐘
- ✅ 可靠性：99.5% 事件處理成功率

---

## 2. 架構概覽

### 2.1 模組清單

```
simpleec-oms/
├─ common/              ← Shared utilities, enums, DTOs
├─ core/                ← Database entities, repositories, base services
├─ adapter/             ← Platform-specific API clients (Momo, Shopee, etc)
├─ kafka-config/        ← Kafka topics, serialization setup
├─ handler/             ← Message handlers (OrderUpsert, Return, Ship)
├─ service/             ← Business logic layer
├─ api/                 ← REST endpoints (optional for Phase 1)
├─ scheduler/           ← Scheduled jobs (optional for Phase 1)
└─ main/                ← Boot application, config
```

### 2.2 依賴關係

```
API Layer
  ↓
Handler Layer (Consumer)
  ↓
Service Layer
  ↓
Adapter Layer (Platform APIs)
  ↓
Core Layer (JPA)
  ↓
common/
```

---

## 3. Phase 1：基礎設施（1 週）

### 3.1 目標

- ✅ Maven 多模組專案結構
- ✅ Spring Boot + Spring Data JPA 基礎設置
- ✅ Kafka Consumer 基礎類
- ✅ 資料庫連接 (PostgreSQL)
- ✅ 單元測試框架 (JUnit 5, Mockito)

### 3.2 任務

| # | 任務 | 優先級 | 工作量 | 負責 |
|---|------|--------|--------|------|
| 1.1 | Maven 多模組 POM 設置 | P0 | 4h | Backend |
| 1.2 | Spring Boot 主應用 | P0 | 3h | Backend |
| 1.3 | PostgreSQL 驅動 + flyway | P0 | 2h | Backend |
| 1.4 | Kafka Consumer 基類 | P0 | 4h | Backend |
| 1.5 | JUnit 5 + Mockito 配置 | P0 | 2h | QA |
| 1.6 | TestContainer 設置 | P0 | 3h | QA |
| 1.7 | application.yml 多環境配置 | P0 | 2h | DevOps |

**里程碑 1.1**：所有模組編譯成功，基礎測試能運行 ✅

### 3.3 交付物

```
src/main/
├─ java/com/simpleec/oms/
│  ├─ OmsApplication.java
│  ├─ config/
│  │  ├─ KafkaConfig.java
│  │  ├─ JpaConfig.java
│  │  └─ ObjectMapperConfig.java
│  └─ common/
│     ├─ constants/
│     └─ utils/

src/test/
├─ java/com/simpleec/oms/
│  ├─ common/
│  │  ├─ TestOrderBuilder.java
│  │  └─ TestEventCaptor.java
│  └─ ApplicationTest.java (smoke test)

resources/
├─ application.yml
├─ application-dev.yml
├─ application-test.yml
└─ schema/
   └─ V1__init_schema.sql (Flyway migration)
```

---

## 4. Phase 2：Kafka 消費者（2 週）

### 4.1 目標

- ✅ 實現 OrderUpsertHandler
- ✅ 實現 ReturnUpsertHandler
- ✅ 實現 ShipOrderHandler
- ✅ 所有 Handler 邏輯完整單元測試

### 4.2 詳細分解

#### Week 1：Order Handler

| # | 任務 | 優先級 | 工作量 | 完成條件 |
|---|------|--------|--------|---------|
| 2.1 | OrderData DTO + Validator | P0 | 6h | 所有必填欄位驗證正確 |
| 2.2 | OrderUpsertHandler | P0 | 8h | 新訂單 INSERT + Hash 驗證 |
| 2.3 | OrderRepository 查詢 | P0 | 4h | 支持 findByChannelOrderId |
| 2.4 | Channel/Merchant 關聯 | P0 | 4h | 正確 FK 檢查 |
| 2.5 | items JSONB 驗證 | P0 | 6h | ✅ qty/price/subtotal 正確 |
| 2.6 | 物流映射查詢 | P0 | 4h | channel_shipping_mapping lookup |
| 2.7 | Unit Tests | P0 | 10h | > 95% 覆蓋率 |

**里程碑 2.1**：OrderUpsertHandler 完整 ✅

#### Week 2：Return + Ship Handler

| # | 任務 | 優先級 | 工作量 | 完成條件 |
|---|------|--------|--------|---------|
| 2.8 | ReturnData DTO + Validator | P0 | 6h | 支持部分退貨 |
| 2.9 | ReturnUpsertHandler | P0 | 8h | orderId FK 驗證 |
| 2.10 | ShipOrderMessage 解析 | P0 | 4h | tracking number 映射 |
| 2.11 | ShipOrderHandler | P0 | 6h | shipping_status 更新 |
| 2.12 | 錯誤處理 + DLT | P1 | 6h | 失敗消息發送 task.failed |
| 2.13 | Integration Tests | P0 | 12h | Kafka + DB 互動 |

**里程碑 2.2**：所有 Handler 完整 ✅

### 4.3 Code Structure

```
src/main/java/com/simpleec/oms/handler/
├─ OrderUpsertHandler.java       (80 lines)
├─ ReturnUpsertHandler.java      (70 lines)
├─ ShipOrderHandler.java         (60 lines)
├─ BaseKafkaHandler.java         (abstract class)
└─ handler exception handling

src/main/java/com/simpleec/oms/dto/
├─ ProcessOrderMessage.java
├─ OrderData.java
├─ ProcessReturnMessage.java
├─ ShipOrderMessage.java
└─ ...

src/test/java/com/simpleec/oms/handler/
├─ OrderUpsertHandlerTest.java           (150 lines)
├─ OrderUpsertHandlerIntegrationTest.java (200 lines)
├─ ReturnUpsertHandlerTest.java
├─ ShipOrderHandlerTest.java
└─ MultiHandlerWorkflowTest.java
```

---

## 5. Phase 3：平台適配器（3 週）

### 5.1 目標

- ✅ Momo Adapter (Mode A/B)
- ✅ Shopee Adapter
- ✅ 基礎的 Cyberbiz Adapter
- ✅ 完整的適配器測試框架

### 5.2 詳細計劃

#### Week 3-4：Momo Adapter

| # | 任務 | 優先級 | 工作量 | 完成條件 |
|---|------|--------|--------|---------|
| 3.1 | MomoApiClient 基類 | P0 | 6h | HTTP 請求封裝 |
| 3.2 | MomoOrderAdapter | P0 | 10h | Mode A/B 偵測 |
| 3.3 | Fixture 蒐集 | P0 | 4h | 10+ JSON 樣本 |
| 3.4 | Unit Tests (Fixture) | P0 | 8h | 100% 覆蓋解析邏輯 |
| 3.5 | Mock HTTP Tests | P0 | 8h | 限流、超時、404 |
| 3.6 | VCR 錄製 + 測試 | P0 | 6h | Playback 測試 |

**里程碑 3.1**：Momo Adapter 完整 ✅

#### Week 4-5：Shopee Adapter

| # | 任務 | 優先級 | 工作量 | 完成條件 |
|---|------|--------|--------|---------|
| 3.7 | ShopeeApiClient | P0 | 6h | 複雜規格映射 |
| 3.8 | ShopeeOrderAdapter | P0 | 12h | tier_variation 解析 |
| 3.9 | Fixture + Unit Tests | P0 | 10h | 多規格訂單 |
| 3.10 | Mock HTTP Tests | P0 | 8h |  |
| 3.11 | VCR 錄製 | P0 | 4h |  |

**里程碑 3.2**：Shopee Adapter 完整 ✅

#### Week 5-6：Cyberbiz Adapter (可選)

| # | 任務 | 優先級 | 工作量 | 完成條件 |
|---|------|--------|--------|---------|
| 3.12 | CyberbizApiClient | P1 | 6h | REST API 調用 |
| 3.13 | CyberbizOrderAdapter | P1 | 10h | 虛擬商品支持 |
| 3.14 | Tests | P1 | 8h |  |

### 5.3 架構圖

```
Kafka: {platform}.fast
        ↓
   ChannelJob
        ↓
   ChannelJobFactory
        ├─ MomoChannelJob
        │  └─ MomoOrderAdapter
        │     └─ MomoApiClient
        ├─ ShopeeChannelJob
        │  └─ ShopeeOrderAdapter
        │     └─ ShopeeApiClient
        └─ CyberbizChannelJob
           └─ CyberbizOrderAdapter
              └─ CyberbizApiClient
```

---

## 6. Phase 4：集成測試 + 優化（2 週）

### 6.1 目標

- ✅ 完整 E2E 測試
- ✅ 性能測試
- ✅ 部署準備

### 6.2 任務

| # | 任務 | 優先級 | 工作量 | 完成條件 |
|---|------|--------|--------|---------|
| 4.1 | E2E 測試框架 | P0 | 8h | 完整訂單生命週期 |
| 4.2 | 多通路 E2E 測試 | P0 | 6h | 3+ 平台並行訂單 |
| 4.3 | 性能測試 | P1 | 8h | 1000 訂單/min 可達到 |
| 4.4 | 監控 + 告警設置 | P1 | 6h | Handler 錯誤率監控 |
| 4.5 | Docker 容器化 | P1 | 4h | Dockerfile + docker-compose |
| 4.6 | 文檔補充 | P1 | 4h | 部署手冊、運維指南 |

**里程碑 4.1**：可部署的 MVP ✅

---

## 7. 技術棧決策

### 7.1 後端框架

| 技術 | 選擇 | 理由 |
|------|------|------|
| **框架** | Spring Boot 3.x | 成熟、生態豐富 |
| **ORM** | Spring Data JPA | 簡單、對標準 SQL 友好 |
| **消息隊列** | Apache Kafka | 已有運維經驗 |
| **資料庫** | PostgreSQL 14+ | JSONB 支持好 |
| **構建工具** | Maven | 多模組支持 |
| **Java 版本** | JDK 17+ | LTS 版本 |

### 7.2 測試工具

| 工具 | 用途 | 版本 |
|------|------|------|
| JUnit 5 | 單元測試 | 5.9+ |
| Mockito | Mock 物件 | 5.x |
| Testcontainers | Docker 容器 | 1.17+ |
| WireMock | Mock HTTP | 3.x |
| VCR (Java) | API 錄製 | 2.x |
| Spring Boot Test | Integration | 3.x |

### 7.3 代碼質量

| 工具 | 配置 |
|------|------|
| **SonarQube** | Code coverage > 80% |
| **Checkstyle** | Google Java Style |
| **PMD** | Bug/Smell 檢測 |
| **Spotbugs** | 字節碼分析 |

---

## 8. 風險與應變

### 8.1 主要風險

| 風險 | 概率 | 影響 | 應變 |
|------|------|------|------|
| **Kafka 性能瓶頸** | M | H | 預先做壓力測試；partition 調優 |
| **Platform API 變化** | M | M | VCR tape 保存；API 版本控制 |
| **JSONB 查詢複雜** | L | M | 建立 JSONB 索引；考慮非規範化 |
| **測試環境穩定性** | H | M | 充分 Mock；本機測試優先 |
| **時間估算不足** | M | H | Sprint 預留 buffer；重新評估優先級 |

### 8.2 應變計劃

```
如果進度落後 2 週：
  ❌ 延後 Cyberbiz Adapter → Phase 2 (Sprint 9)
  ✅ 保留核心（Momo, Shopee）
  ✅ 提前發佈 MVP

如果 API 問題：
  ✅ 用 VCR tape 繞過
  ✅ 平行開發 (本地 Mock)
```

---

## 9. 團隊分工

### 9.1 角色與責任

```
Backend Developer (主)
  ├─ Phase 1: 主要負責 (Kafka 基礎、DB 設置)
  ├─ Phase 2: 主要負責 (Handler 代碼)
  ├─ Phase 3: 主要負責 (Adapter 實現)
  └─ Phase 4: 主要負責 (E2E + 優化)

QA Engineer
  ├─ Phase 1: 測試框架建設
  ├─ Phase 2: Unit + Integration 測試編寫
  ├─ Phase 3: Adapter 測試 (Fixture, Mock, VCR)
  └─ Phase 4: E2E + 性能測試

DevOps / Infra
  ├─ Phase 1: 環境配置 (PostgreSQL, Kafka)
  ├─ Phase 3: Sandbox API 金鑰管理
  └─ Phase 4: Docker 化 + CI/CD 設置
```

### 9.2 每週會議

```
Monday:
  ├─ Sprint Planning (1h)
  └─ 確認週目標

Wednesday:
  ├─ Mid-sprint Sync (30min)
  └─ 討論卡點

Friday:
  ├─ Sprint Retrospective (1h)
  ├─ Demo (30min)
  └─ 反思改進
```

---

## 10. 里程碑與交付物

### 10.1 Sprint 里程碑

```
Sprint 1 (Week 1)  → 基礎設施 ✅
Sprint 2 (Week 2)  → Order Handler ✅
Sprint 3 (Week 3)  → Return/Ship Handler ✅
Sprint 4 (Week 4)  → Momo Adapter ✅
Sprint 5 (Week 5)  → Shopee Adapter ✅
Sprint 6 (Week 6)  → Cyberbiz Adapter (可選) ✅
Sprint 7 (Week 7)  → 整合測試 ✅
Sprint 8 (Week 8)  → 優化 + 發佈 MVP ✅
```

### 10.2 發佈檢查清單

```
代碼質量
  ☐ 所有單元測試通過
  ☐ 代碼覆蓋率 > 80%
  ☐ SonarQube 無 blocker bugs
  ☐ Code review 通過

測試
  ☐ E2E 測試通過
  ☐ 性能測試達到 1000 ord/min
  ☐ 所有 3 個平台適配器測試通過

文檔
  ☐ README 更新
  ☐ API 文檔完整
  ☐ 部署手冊完成
  ☐ 故障排查指南

準備
  ☐ Docker 構建無誤
  ☐ 資料庫遷移腳本驗證
  ☐ Kafka topic 建立完成
  ☐ 監控告警配置
```

---

## 11. 目錄結構

```
simpleec-oms/
│
├─ docs/                      (✅ 已完成)
│  ├─ PLATFORM_MAPPING.md
│  ├─ EVENT_SAMPLES.md
│  ├─ TESTING_FRAMEWORK_DESIGN.md
│  └─ ...
│
├─ implementation/            (← 本分支)
│  ├─ IMPLEMENTATION_PLAN.md  (此文件)
│  ├─ CODE_STRUCTURE.md       (待)
│  └─ DEPLOYMENT_GUIDE.md     (待)
│
├─ pom.xml                   (多模組 POM)
│
├─ simpleec-oms-common/
│  ├─ src/main/java/...
│  ├─ src/test/java/...
│  └─ pom.xml
│
├─ simpleec-oms-core/
│  ├─ src/main/java/
│  │  ├─ entity/             (Order, RefundOrder, etc)
│  │  ├─ repository/
│  │  └─ service/
│  ├─ src/test/java/
│  └─ pom.xml
│
├─ simpleec-oms-adapter/
│  ├─ src/main/java/
│  │  ├─ momo/
│  │  ├─ shopee/
│  │  ├─ pchome/
│  │  ├─ cyberbiz/
│  │  └─ base/
│  ├─ src/test/java/
│  │  └─ fixtures/
│  │     ├─ momo/
│  │     ├─ shopee/
│  │     └─ ...
│  └─ pom.xml
│
├─ simpleec-oms-kafka/
│  ├─ src/main/java/
│  │  ├─ KafkaConfig.java
│  │  └─ handler/            (OrderUpsert, Return, Ship)
│  ├─ src/test/java/
│  └─ pom.xml
│
├─ simpleec-oms-app/
│  ├─ src/main/java/
│  │  ├─ OmsApplication.java
│  │  └─ config/
│  ├─ src/main/resources/
│  │  ├─ application.yml
│  │  └─ db/migration/
│  ├─ src/test/java/
│  └─ pom.xml
│
├─ docker/
│  ├─ Dockerfile
│  ├─ docker-compose.yml
│  └─ init-db/
│     └─ 01-schema.sql
│
└─ .github/
   └─ workflows/
      └─ test.yml
```

---

## 12. 成功定義

### 12.1 MVP 成功指標

```
功能
  ✅ 訂單入庫 (Momo/Shopee)
  ✅ 訂單出貨
  ✅ 訂單退貨
  ✅ 幂等性保證
  ✅ 物流映射正確

性能
  ✅ 吞吐量 ≥ 1000 訂單/分鐘
  ✅ 延遲 P99 < 100ms
  ✅ 記憶體佔用 < 512MB

可靠性
  ✅ 成功率 ≥ 99.5%
  ✅ 失敗消息進 DLT
  ✅ 可監控 / 可告警

代碼質量
  ✅ 覆蓋率 > 80%
  ✅ 無 blocker bugs
  ✅ 符合代碼規範
```

---

**計劃起始日期**：2026-02-20
**預期 MVP 發佈**：2026-04-10
**狀態**：待 Team Review & Kickoff
