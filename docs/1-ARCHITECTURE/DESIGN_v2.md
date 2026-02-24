# SimpleEC OMS — 最簡完整設計文件

> 基於現有 simpleec-oms 骨架，補齊所有缺口的完整設計
> 目標：登入 → 通路設定 → 三大表（訂單/商品/賣場）+ MQ + JOB + Cache

---

## 0. 現狀盤點

### 已完成（現有 code — 可保留）

| 層 | 已完成 | 狀態 |
|----|--------|------|
| **Gradle 骨架** | 5 模組 (common/core/channel/web/app)，Spring Boot 3.5.0 + Java 17 | ✅ 可用（編譯成功） |
| **DB Schema** | 01-original-schema.sql (20 表) + 02-new-tables.sql (11 表) | ✅ 可用 |
| **Entity** | Product, SellPack, Order, OrderStatusLog | ✅ 可用（OrderItem/ProductSpec 已刪除，NanoID PK） |
| **Mapper** | 6 個 MyBatis-Plus BaseMapper（自動 CRUD） | ✅ 可用 |
| **Service** | ProductService, OrderService（分頁、篩選、交易、狀態紀錄） | ✅ 可用 |
| **Controller** | ProductController, OrderController, ChannelActionController, Health | ✅ 可用 |
| **Channel 介面** | ChannelAdapter（完整介面）+ ChannelAdapterFactory + MomoAdapter（placeholder） | ✅ 骨架 |
| **Enum** | ChannelType(4), ActionType(34), OrderStatus(9) | ✅ 可用 |
| **共用模型** | ApiResponse, PageResult, BusinessException | ✅ 可用 |
| **Spring Config** | SecurityConfig(permitAll)、MyBatisPlusConfig、GlobalExceptionHandler | ✅ 可用 |
| **application.yml** | DB/Redis/RabbitMQ/Jackson/MyBatis-Plus 設定 | ✅ 可用（需改 MQ 設定） |
| **Docker** | docker-compose + Dockerfile + nginx.conf | ✅ 可用（需加 Kafka） |

### 需要替換（RabbitMQ → Kafka）

| 現有（RabbitMQ） | 要換成 | 理由 |
|------------------|--------|------|
| `spring-boot-starter-amqp` 依賴 | `spring-kafka` 依賴 | 設計需要 Kafka partition key 保證同 key 有序 |
| `RabbitMQConfig.java`（Exchange + Queue + DLX + Retry） | `KafkaConfig.java`（per-channel Topics） | Topic per-channel，不是 Exchange + Queue |
| `ChannelActionProducer.java`（RabbitTemplate） | `TaskProducer.java`（KafkaTemplate + partition key） | 需要 partition key 語意 |
| `ChannelActionConsumer.java`（@RabbitListener） | ChannelJob（@KafkaListener + ActionFactory） | 設計需要 ActionFactory + ActionService 4 步生命週期 |
| `ChannelActionMessage.java` | `TaskMessage.java`（統一訊息格式） | 新增 topic/partitionKey/retryCount/sourceJobType 欄位 |
| `docker-compose.yml` 的 RabbitMQ service | Kafka (bitnami/kafka:3.7) service | 改用 KRaft 模式 |
| `application.yml` 的 `spring.rabbitmq` | `spring.kafka` 設定 | bootstrap-servers、consumer/producer 設定 |

### 缺口（本文件要補齊）

| 缺口 | 優先度 |
|------|--------|
| **MQ 從 RabbitMQ 換成 Kafka** — 整套 Config/Producer/Consumer 重寫 | P0 |
| **JWT 認證** — account 表有但沒有 login/token 邏輯 | P0 |
| **JOB 架構** — ActionFactory + ActionService + 6 支 JOB（全部不存在） | P0 |
| **任務調度引擎** — HeartbeatTimer → SchedulerJob（直接發到各目標 topic） | P0 |
| **Redis 快取策略** — 設定有但 code 裡完全沒用到（Hash Dedup、Cache） | P1 |
| **通路 Adapter 實作** — Momo 是 placeholder，其餘 3 家不存在 | P1 |
| **SellPack CRUD** — 沒有 SellPackService/Controller | P1 |
| **Channel CRUD** — 沒有 ChannelService/Controller | P1 |
| **前端** — 完全沒有 | P0 |
| **Git** — 目前不是 git repo，需要初始化 | P0 |
| **測試** — 0 個測試檔 | P2 |
| **對外 API（simpleec-gateway）** — Webhook + ERP 獨立部署，跟前端 API 分開 | P1 |
| **Webhook 接收** — 通路主動推送訂單狀態/出貨通知 → 驗簽 → 丟 Kafka（simpleec-gateway） | P1 |
| **ERP 串接 API** — 外部 ERP 系統打 API 同步商品/訂單/庫存（simpleec-gateway） | P1 |

### 基礎設施狀態

| 服務 | 狀態 |
|------|------|
| PostgreSQL | ✅ 原生跑在 :5432 |
| Redis | ❌ 沒在跑 |
| Kafka | ❌ 沒在跑（docker-compose 也沒有，要加） |
| RabbitMQ | ❌ 沒在跑（docker-compose 有但要換成 Kafka） |
| Docker / Docker Compose | ✅ 可用 (v28.2 / v2.37) |
| Java | ✅ OpenJDK 17.0.18 |
| Gradle | ✅ 8.14.4 (wrapper) |

---

## 1. 資料庫設計（已有，補充說明）

### 1.1 表關係概覽

```
account ←─── 登入用
merchant ──┬── platform (平台: momo/shopee/yahoo/pchome — 第三方認證)
           │     └── channel (通路/館: momo冷凍/momo一般/pchome電子 — 授權 token + 賣場設定)
           └── product ──┬── product_spec (SKU)
                         └── sell_pack (商品×通路上架)

★ 先平台再通路：
  platform = 平台級第三方認證（credential1~N），各平台所需認證資料不同，先給 2 欄後續可擴充
  channel  = 該平台下的具體館/賣場，各自有獨立的授權 token（token1~token5）
  一個 platform 可以有多個 channel
  （舊表名 channel_setting → 已改名 platform）

orders ──┬── order_status_logs
         ├── order_shipments
         └── refund_orders ── refund_order_items

注意：orders 不再拆 order_items（訂單就是訂單，項次拆分在出貨階段處理）

channel_sync_logs (同步紀錄 — ★ 需加 health 欄位：OK / TOKEN_INVALID / API_DOWN)
channel_api_versions (API 版本)
daily_statistics (每日統計 — 預彙整，merchant × date × channel)
failed_task_logs (失敗任務 LOG — RD 定時查看清理)
```

### 1.2 核心表已建（02-new-tables.sql）

- `product` — 商品主檔（merchant_id + item_number 唯一）
- `product_spec` — 商品規格/SKU
- `sell_pack` — 賣場檔（商品在通路的上架資訊，含平台雙 ID + 雙 Name：channel_product_id/channel_spec_id/channel_product_name/channel_spec_name）
- `orders` — 訂單（channel_id + channel_order_id 唯一，含商品明細 JSONB，不再拆 order_items）
- `order_status_logs` — 狀態變更紀錄
- `order_shipments` — 出貨物流
- `refund_orders` / `refund_order_items` — 退貨退款
- `channel_sync_logs` — 通路同步紀錄（★ 需加 health 欄位：OK / TOKEN_INVALID / API_DOWN）
- `channel_api_versions` — API 版本追蹤

### 1.3 需新增的表

```sql
-- =============================================
-- 每日統計表（預彙整，不即時查訂單表）
-- =============================================
-- BackendJob 每天依 merchant 時區判斷「昨天結束了」→ 彙整昨日數據寫一筆 record
-- 前端查統計 = 查這張表（輕量），不是 SELECT SUM(*) FROM orders（重量）
-- 客戶選日期區間 = 把那個區間的 daily records 加總
-- 最小單位 = 一天

CREATE TABLE daily_statistics (
    id              bigserial       PRIMARY KEY,
    merchant_id     varchar(20)     NOT NULL REFERENCES merchant(merchant_id),
    stat_date       date            NOT NULL,           -- 統計日期（客戶時區的那一天）
    channel_id      varchar(20)     REFERENCES channel(channel_id),  -- NULL = 全通路彙總

    -- 訂單統計
    order_count         integer     NOT NULL DEFAULT 0, -- 訂單數
    order_amount        decimal(12,2) NOT NULL DEFAULT 0, -- 營業額
    shipped_count       integer     NOT NULL DEFAULT 0, -- 已出貨數
    shipped_amount      decimal(12,2) NOT NULL DEFAULT 0, -- 已出貨金額
    completed_count     integer     NOT NULL DEFAULT 0, -- 已完成數
    completed_amount    decimal(12,2) NOT NULL DEFAULT 0, -- 已完成金額
    cancelled_count     integer     NOT NULL DEFAULT 0, -- 取消數
    cancelled_amount    decimal(12,2) NOT NULL DEFAULT 0, -- 取消金額
    refund_count        integer     NOT NULL DEFAULT 0, -- 退貨數
    refund_amount       decimal(12,2) NOT NULL DEFAULT 0, -- 退貨金額

    -- 商品統計
    product_count       integer     NOT NULL DEFAULT 0, -- 商品種類數（有異動的）
    item_sold_count     integer     NOT NULL DEFAULT 0, -- 售出件數

    created_at      timestamptz     NOT NULL DEFAULT now(),

    UNIQUE(merchant_id, stat_date, channel_id)  -- 每 merchant 每天每 channel 一筆
);

-- channel_id = NULL 的那筆是全通路彙總
-- 查某個月營業額 = SELECT SUM(order_amount) FROM daily_statistics WHERE merchant_id=? AND stat_date BETWEEN ? AND ?
-- 查某 channel 某天 = WHERE channel_id=? AND stat_date=?

CREATE INDEX idx_daily_statistics_merchant_date ON daily_statistics(merchant_id, stat_date);
CREATE INDEX idx_daily_statistics_channel_date ON daily_statistics(channel_id, stat_date);

-- =============================================
-- 失敗任務 LOG 表（RetryDispatchJob 超過 maxRetry 寫入，RD 定時清理）
-- =============================================
CREATE TABLE failed_task_logs (
    id              bigserial       PRIMARY KEY,
    merchant_id     varchar(20),
    original_topic  varchar(128)    NOT NULL,
    original_key    varchar(256),
    original_action varchar(100),
    retry_count     integer         NOT NULL DEFAULT 0,
    max_retry       integer         NOT NULL DEFAULT 3,
    failure_reason  varchar(50)     NOT NULL,           -- MAX_RETRY_EXCEEDED / NOT_RETRYABLE / NOT_RETRYABLE_FAST
    error_message   text,
    payload         jsonb,
    created_at      timestamptz     NOT NULL DEFAULT now()
);

CREATE INDEX idx_failed_task_logs_created ON failed_task_logs(created_at DESC);
CREATE INDEX idx_failed_task_logs_reason ON failed_task_logs(failure_reason);

-- =============================================
-- channel_sync_logs 加欄位（健康度三層判斷）
-- =============================================
ALTER TABLE channel_sync_logs
    ADD COLUMN health varchar(20) NOT NULL DEFAULT 'OK';
-- health 值：OK / TOKEN_INVALID / API_DOWN
-- CheckHealthActionService 專門寫入，Dashboard 查最近一筆判斷通路健康

CREATE INDEX idx_sync_log_health ON channel_sync_logs (channel_id, health, created_at DESC);
```

### 1.4 DB Partition 設計（自動管理）

> 時序資料表必須做 Partition，否則隨著資料累積查詢越來越慢。
> 舊系統用 `PARTITION BY RANGE (count_date)` + DEFAULT partition，新系統改為按月自動建立。

```
需要 Partition 的表（時序資料，會持續膨脹）：

  表名                    Partition Key     策略          理由
  ─────────────────────  ────────────────  ─────────    ──────────────────
  orders                 created_at        按月 RANGE   訂單量大，查詢常帶日期範圍
  order_status_logs      created_at        按月 RANGE   每筆訂單多次狀態變更，量更大
  channel_sync_logs      created_at        按月 RANGE   每次操作都寫 LOG，量極大
  daily_statistics       stat_date         按月 RANGE   每天每 merchant × channel 一筆
  failed_task_logs       created_at        按月 RANGE   累積的失敗記錄，定時清理
  order_shipments        created_at        按月 RANGE   出貨記錄
  refund_orders          created_at        按月 RANGE   退款記錄

不需要 Partition 的表（設定檔 / 主檔，量少）：
  merchant, account, channel, platform, channel_api_versions
  product, product_spec, sell_pack

Partition 命名規則：
  {table}_y{yyyy}m{mm}
  例：orders_y2026m01, orders_y2026m02, ...

DDL 範例（orders 改為 partitioned table）：
  CREATE TABLE orders (...) PARTITION BY RANGE (created_at);
  -- BackendJob 自動建立：
  CREATE TABLE orders_y2026m02
    PARTITION OF orders
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

★ Partition 由 BackendJob (MANAGE_PARTITIONS action) 自動建立
  - SchedulerJob 每天觸發一次
  - 提前建好「下個月 + 下下個月」的 partition（避免寫入時 partition 不存在）
  - 如果 partition 已存在 → skip（idempotent）
  - 同時可清理超過保留期（如 12 個月前）的舊 partition（DROP / DETACH）
```

> **排程設定在 application.yml 裡**（見 §6.3），不需要 task_schedule 資料表。
> **統計靠預彙整**（daily_statistics），不即時查訂單表，避免對 DB 造成壓力。
> **統計時區**：BackendJob 依 merchant.user_local_time_zone 判斷「哪個 merchant 的昨天結束了」。

---

## 2. 後端架構

### 2.1 系統角色分離

```
前端 API（simpleec-api）的角色：
  - 看平台：查通路設定、查同步狀態
  - 塞設定：CRUD 通路/商品/賣場檔
  - 看資料：查訂單/商品/賣場檔（分頁、篩選）
  - 丟事件：用戶操作 → TaskProducer → Kafka topic → 結束（非同步，不等結果）
  - 不消費任何 Kafka topic
  - 不直接呼叫任何 JOB
  - 前台時常客變、流量混雜 → 獨立部署，不影響對外 API 穩定性

對外 API（simpleec-gateway）的角色：
  - Webhook 接收：通路平台主動推送（URL 路徑依平台要求，Nginx 配合路由）→ 驗簽 → 丟 Kafka topic
  - ERP 串接 API：外部 ERP 系統打 API 同步商品/訂單/庫存（REST，JWT 認證）
  - 不消費任何 Kafka topic
  - 跟 simpleec-api 共用 simpleec-core 的 Service 層（同一套 DB 操作）
  - 對外穩定、獨立限流、獨立部署 — 不因前端改版/流量突波而影響

為什麼分開：
  - 前端時常客變，release 頻率高 → simpleec-api 經常重啟
  - Webhook 必須穩定在線（通路平台推送不等人） → simpleec-gateway 不隨前端一起動
  - 流量特性不同：前端是人操作（低頻/突發），Webhook 是平台推送（穩定/可預測）
  - 安全邊界不同：前端 JWT 是用戶 token，ERP JWT 是系統 token，Webhook 是簽名驗證
  - 獨立 scale：ERP 量大可以單獨加 instance，不用動前端

JOB 的角色：
  - 各自獨立的 Spring Boot application
  - 看 topic 做事，做完自治路由到下一個 topic（或結束）
  - JOB 之間唯一的耦合是 Kafka topic
  - API / Gateway 和 JOB 之間唯一的耦合也是 Kafka topic

唯一特例 — ChannelJob：
  - 同一份 CODE，不同環境變數 = 不同 instance（fast / slow / per-channel）
  - 參考舊系統 call-to-user-job / call-to-warehouse-job
  - 其他 JOB 都是各自獨立的 Spring Boot application

業務後台 API（simpleec-admin-api）— 獨立，不在 MVP 範圍：
  - 建立/管理 merchant（商家）
  - 建立/管理 account（帳號，含 init 帳號）
  - 查看所有商家的統計/狀態
  - 獨立的 Spring Boot + 獨立前端
  - 跟 simpleec-api 共用 simpleec-core（同一套 DB），但獨立部署
  - MVP 階段用 SQL 手動建 merchant/account，後台之後再做
```

### 2.2 Gradle 模組總覽

```
simpleec-oms/                          ← Gradle Multi-Module（單一 repo）
│
├── 共用 Module（被所有 Boot Module 依賴）
│   ├── simpleec-common/              ✅ 已有 — enum, dto, exception
│   ├── simpleec-core/                ✅ 已有 — entity, mapper, service, TaskMessage, TaskProducer
│   └── simpleec-channel/             ✅ 已有 — adapter 骨架（需補 4 家實作）
│
├── Boot Module（各自獨立的 Spring Boot Application，各自 Dockerfile）
│   ├── simpleec-api/                 ← 前端 API (REST + JWT)，不消費 Kafka
│   ├── simpleec-gateway/             ← 對外 API (Webhook + ERP)，不消費 Kafka
│   ├── simpleec-channel-job/         ← ChannelJob（同 CODE 不同設定，唯一特例）
│   ├── simpleec-order-job/           ← OrderProcessJob
│   ├── simpleec-scheduler-job/       ← HeartbeatTimer + SchedulerJob（含分發邏輯）
│   ├── simpleec-backend-job/         ← BackendJob + BackendActionFactory
│   ├── simpleec-frontend-job/        ← FrontendJob
│   └── simpleec-retry-job/           ← RetryDispatchJob
│
└── 前端
    └── simpleec-admin/               ❌ 需新建 — Vue 3 + Vite + PrimeVue
```

### 2.3 需補的後端 Code

#### 2.3.1 JWT 認證（simpleec-api + simpleec-gateway 共用）

**新增檔案：**

```
simpleec-web/src/main/java/com/simpleec/web/
├── controller/AuthController.java       # 登入 API
└── security/
    ├── JwtTokenProvider.java            # JWT 生成/驗證
    ├── JwtAuthenticationFilter.java     # Filter 攔截每個 request
    └── UserDetailsServiceImpl.java      # 從 account 表查帳號
```

**AuthController API：**

```java
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest req) {
        // 1. 查 account 表驗證帳號密碼 (BCrypt)
        // 2. 產生 JWT (含 accountId, merchantId, role)
        // 3. 回傳 { token, expiresIn, account }
    }

    @GetMapping("/me")
    public ApiResponse<AccountDTO> me(@AuthenticationPrincipal ...) {
        // 回傳當前登入帳號資訊
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(...) {
        // JWT 續期
    }
}
```

```java
// LoginRequest
{ accountId: string, password: string }

// LoginResponse
{ token: string, expiresIn: long, account: { id, name, merchantId, role } }
```

**JWT Token 結構：**

```json
{
  "sub": "acc_001",
  "merchantId": "M001",
  "role": "admin",
  "iat": 1707350400,
  "exp": 1707436800
}
```

**SecurityConfig 修改：**

```java
// 現有: .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
// 改為:
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/login").permitAll()
    .requestMatchers("/api/v1/health").permitAll()
    .anyRequest().authenticated()
)
.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
```

#### 2.3.2 SellPack CRUD（simpleec-core + simpleec-api）

**新增：**

```java
// simpleec-core
public class SellPackService {
    PageResult<SellPack> list(merchantId, channelId, status, page, size)
    SellPack getById(id)
    void save(sellPack)
    void updateStatus(id, status)
    List<SellPack> getByProductId(productId)
}

// simpleec-web
@RestController
@RequestMapping("/api/v1/sell-packs")
public class SellPackController {
    GET    /                           → list (可篩 channelId, status)
    GET    /{id}                       → detail
    POST   /                           → create
    PUT    /{id}                       → update
    POST   /{id}/publish               → 上架（發 MQ: START_SELLING）
    POST   /{id}/unpublish             → 下架（發 MQ: STOP_SELLING）
}
```

#### 2.3.3 Channel 管理（simpleec-core + simpleec-api）

**新增：**

```java
// simpleec-core
public class ChannelService {
    List<Channel> listByMerchant(merchantId)
    Channel getById(channelId)
    void save(channel)                  // 含 token1~token5 加密（token 存在 channel 層級）
    boolean testConnection(channelId)   // 呼叫 adapter.validateConnection
}

// simpleec-web — ★ 先平台再通路
@RestController
@RequestMapping("/api/v1/platforms")
public class PlatformController {
    GET    /                           → 平台列表
    GET    /{id}                       → 平台詳情（credential 脫敏）
    POST   /                           → 新增平台（momo/shopee/...）
    PUT    /{id}                       → 更新平台認證（credential1~N）
    POST   /{id}/test                  → 測試平台連線
}

@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {
    GET    /                           → 通路列表（可篩 platformId）
    GET    /{id}                       → 通路詳情（token 脫敏）
    POST   /                           → 新增通路（掛在某平台下，含 token）
    PUT    /{id}                       → 更新通路設定（token1~token5）
    PUT    /{id}/activate              → 啟用/停用
    POST   /{id}/sync-products         → 同步商品
}
```

#### 2.3.4 Webhook 接收（simpleec-gateway）

```
Webhook 設計原則：
  1. URL 路徑不是我們決定的 — 每個平台可能指定 webhook URL 的格式
     例如 shopee 要求 /webhook/shopee/{shop_id}、momo 可能要求 /callback/order
  2. Nginx 前面可能有 prefix 改寫、路由規則
  3. 每個平台驗簽方式不同（HMAC-SHA256 / IP 白名單 / Token header / 自訂簽名）
  4. 每個平台推送的 payload 格式完全不同

所以：
  - Webhook endpoint 的 URL 路徑由各平台 Adapter 定義，不硬編統一前綴
  - 各平台各一個 Controller（或同一個 Controller 用 @PathVariable 分流）
  - Nginx 層處理路由/rewrite，API 層只管接收和處理
```

```java
/**
 * Webhook 接收 — 共用邏輯
 *
 * 實際 URL 路徑由各平台需求決定，可能是：
 *   /webhook/shopee/{shop_id}        ← shopee 指定的格式
 *   /callback/momo/order             ← momo 指定的格式
 *   /api/v1/webhooks/yahoo           ← 我們自己定的（yahoo 沒限制時）
 *
 * Nginx 可做 prefix rewrite，也可直接透傳
 */

// 共用流程（各平台 Controller 呼叫）：
//   1. 驗簽（各平台 WebhookVerifier 實作不同）
//   2. 解析事件類型 + payload
//   3. 組 TaskMessage → 丟到對應的 {channel}.fast 或 {channel}.slow topic
//   4. 回傳平台要求的回應格式（有些要求特定 JSON、有些只要 200）

public interface WebhookHandler {
    ChannelType getChannelType();
    boolean verify(String body, HttpServletRequest req);       // 驗簽
    WebhookEvent parse(String body);                            // 解析事件
    ResponseEntity<?> buildResponse(WebhookEvent event);        // 各平台要求的回應格式
}

// WebhookEvent — 解析後的統一結構
@Data
public class WebhookEvent {
    private String action;          // ORDER_STATUS_CHANGED, SHIPPING_UPDATE, ...
    private String speed;           // "fast" or "slow"
    private String partitionKey;    // orderId, sellPackId, ...
    private Map<String, Object> data;
}
```

> **Webhook URL 彈性**：各平台 Adapter 實作時定義自己的 `@RequestMapping`。
> Nginx 前面的 location / rewrite 規則配合平台要求。
> API 層只負責：驗簽 → 解析 → 丟 Kafka → 回應。

#### 2.3.5 ERP 串接 API（simpleec-gateway）

```java
/**
 * ERP 系統串接 — REST API + JWT 認證
 * ERP 可以建立專用 account（role=erp），用 JWT 認證
 * 寫入的資料跟前端 CRUD 共用同一套 Service 層
 */
@RestController
@RequestMapping("/api/v1/erp")
public class ErpController {

    // 查詢類 — ERP 拉資料
    GET  /products            → 商品列表（分頁，可篩 category/status）
    GET  /orders              → 訂單列表（依時間區間/狀態）
    GET  /inventory           → 庫存快照

    // 同步類 — ERP 推資料
    POST /products/sync       → 批次 upsert 商品（ERP 主資料 → SimpleEC）
    POST /orders/status       → 訂單狀態更新（ERP 出貨/取消 → SimpleEC → 丟 Kafka 到通路）
    POST /inventory/sync      → 庫存同步（ERP 庫存 → SimpleEC → 丟 {channel}.fast 更新通路庫存）
}
```

> **Webhook 和 ERP 都在 simpleec-gateway，不消費 Kafka** — 只負責接收和丟事件。
> 真正做事的是後面的 JOB。
> simpleec-gateway 跟 simpleec-api 共用 simpleec-core 的 Service 層（同一套 DB），但各自獨立部署。

### 2.4 完整 API 端點清單

```
═══ simpleec-api（前端 API — :8080）═══

認證
  POST   /api/v1/auth/login            → JWT token
  GET    /api/v1/auth/me               → 當前帳號
  POST   /api/v1/auth/refresh          → 續期

平台（platform — 第三方認證 credential1~N）
  GET    /api/v1/platforms                     → 平台列表
  POST   /api/v1/platforms                     → 新增平台（momo/shopee/...）
  GET    /api/v1/platforms/{id}                → 平台詳情（credential 脫敏）
  PUT    /api/v1/platforms/{id}                → 更新平台認證設定（credential1~N）
  POST   /api/v1/platforms/{id}/test           → 測試平台連線

通路（channel — 具體賣場，掛在平台下）
  GET    /api/v1/channels              → 通路列表（可篩 platformId）
  POST   /api/v1/channels              → 新增通路（需帶 platformId）
  GET    /api/v1/channels/{id}         → 通路詳情（token 脫敏）
  PUT    /api/v1/channels/{id}         → 更新通路設定（token1~token5）
  PUT    /api/v1/channels/{id}/activate → 啟用/停用

商品
  GET    /api/v1/products              → 商品列表（分頁）
  GET    /api/v1/products/{id}         → 商品詳情
  POST   /api/v1/products              → 新增
  PUT    /api/v1/products/{id}         → 更新

賣場
  GET    /api/v1/sell-packs            → 賣場列表（可篩 channel/status）
  GET    /api/v1/sell-packs/{id}       → 賣場詳情
  POST   /api/v1/sell-packs           → 新增（建立上架資訊）
  PUT    /api/v1/sell-packs/{id}       → 更新
  POST   /api/v1/sell-packs/{id}/publish    → 上架
  POST   /api/v1/sell-packs/{id}/unpublish  → 下架

訂單
  GET    /api/v1/orders                → 訂單列表（★ PII 遮罩：姓名留首尾、電話留前4後3、Email留前2@domain、地址留城市）
  GET    /api/v1/orders/{id}           → 訂單詳情（★ 完整明文 PII — 點開解鎖）
  GET    /api/v1/orders/export         → CSV 匯出（★ 完整明文 PII，UTF-8 BOM for Excel）
  POST   /api/v1/orders/{id}/ship      → 出貨確認（發 MQ）
  POST   /api/v1/orders/{id}/cancel    → 取消（發 MQ）

  ★ PII 加密: 4 個買家欄位（buyer_name, buyer_phone, buyer_email, shipping_address）
    DB 層透過 EncryptedFieldTypeHandler 做 AES-256-GCM 加密/解密（透明）
    API 層透過 OrderVO + PiiMasker 做遮罩（列表）/ 明文（詳情+匯出）

通路動作
  POST   /api/v1/channels/{id}/sync-products  → 同步商品（發到 {platform}.slow，action=FETCH_PRODUCTS，key=channelId 排隊）
  POST   /api/v1/channel-actions/send         → 手動發 MQ 訊息（除錯用）

Dashboard 統計（查 daily_statistics 表，不即時查 orders）
  GET    /api/v1/statistics/summary    → 指定日期區間的彙總（營業額/訂單數/出貨/取消/退貨）
  GET    /api/v1/statistics/daily      → 每日明細（圖表用，區間內每天一筆）
  GET    /api/v1/statistics/by-channel → 依通路分組統計
  ※ 查詢參數：startDate, endDate（必填）, channelId（選填）
  ※ 資料來源：daily_statistics 表（BackendJob 每日預彙整）
  ※ 最小單位 = 一天

通路健康狀態（三層判斷）
  GET    /api/v1/channels/health       → 各通路健康等級 + 最後同步時間 + 是否啟用
  ※ 健康度三層：
    🟢 OK            — API 活著 + Token 有效 → 雙向暢通
    🟡 TOKEN_INVALID — API 活著但 Token 不對（過期/被撤銷）→ 要重新授權
    🔴 API_DOWN      — 通路 API 本身掛了（timeout / 5xx）→ 等平台恢復

系統
  GET    /api/v1/health                → 健康檢查


═══ simpleec-gateway（對外 API — :8081）═══

Webhook（通路主動推送 — URL 路徑依各平台要求）
  POST   /{平台指定路徑}               → 各平台 webhook 接收
  ※ URL 不是我們決定的 — 每個平台可能指定 webhook URL 格式
     例如 shopee: /webhook/shopee/{shop_id}
     例如 momo:   /callback/momo/order
     例如 yahoo:  /api/v1/webhooks/yahoo（沒限制時我們自己定）
  ※ Nginx 層處理路由/rewrite，Gateway 只管驗簽→解析→丟 Kafka
  ※ 每個平台驗簽方式不同（HMAC / IP白名單 / Token header）
  ※ 驗簽通過 → 解析事件類型 → 丟到 {channel}.fast 或 {channel}.slow
  ※ 驗簽失敗 → 403，不入 Kafka
  ※ 回應格式依平台要求（有些要特定 JSON、有些只要 200）

ERP 串接
  GET    /api/v1/erp/products          → ERP 查商品（分頁）
  POST   /api/v1/erp/products/sync     → ERP 推送商品同步（批次 upsert）
  GET    /api/v1/erp/orders            → ERP 查訂單（依時間區間/狀態篩選）
  POST   /api/v1/erp/orders/status     → ERP 推送訂單狀態更新
  GET    /api/v1/erp/inventory         → ERP 查庫存
  POST   /api/v1/erp/inventory/sync    → ERP 推送庫存同步（→ 丟 {channel}.fast 更新通路）
  ※ 全部 JWT 認證，可為 ERP 建立專用 account（role=erp）
  ※ ERP 寫入的資料跟前端 CRUD 共用同一套 Service 層

系統
  GET    /api/v1/health                → 健康檢查

═══ Nginx 路由規則 ═══

  simpleec-admin（:80）的 Nginx 負責前端 + 前端 API：
    /          → Vue 3 SPA 靜態檔
    /api/      → proxy_pass simpleec-api:8080

  對外流量由獨立 Nginx server block 或外部 load balancer 處理：
    Webhook 路徑  → proxy_pass simpleec-gateway:8081
    /api/v1/erp/  → proxy_pass simpleec-gateway:8081
```

---

## 3. MQ 設計（Kafka）

> **兩大鐵律：**
> 1. **絕不丟訊息，絕不跳過，全部按序處理。** 每一筆操作都代表對平台的一次 API 呼叫，中間狀態都是真實的。`+5 → -3 → +10` 三筆都要跑，跳任何一筆都是我們的過錯。
> 2. **快慢分離** — Fast topic + Slow topic，用戶操作不被排程同步卡住。

### 3.1 為什麼用 Kafka 不用 RabbitMQ

```
需求：
  +5 → -3 → +10  三筆操作都要按序執行，一筆都不能跳
  同一個商品/通路的操作必須有序
  不同商品/通路之間要並行，不能互卡

RabbitMQ 的問題：
  - 單 Queue FIFO 有序，但 concurrency > 1 就亂序
  - 要「同商品有序 + 不同商品並行」→ 沒有原生支援
  - Consistent Hash Exchange 可以做，但是 workaround

Kafka 天然支援：
  - Partition key = 同一個 key 的訊息保證進同一個 partition → 有序
  - Partition 夠多 + Consumer 夠多 → 不同 key 之間並行
  - 同一個 topic、同一個 consumer group 裡，一個 partition 只被一個 consumer 消費
  → 不需要分散式鎖，不需要 dedup，天生保證有序處理
```

### 3.2 快慢分離（環境參數化 + per-channel Topic）

快慢不是「分成多支 JOB」，而是 **同一支 JOB 同樣的 CODE，透過環境變數 / 設定檔決定吃哪些 TOPIC**：

- **Fast Topic**：用戶觸發、即時性要求高（改價、改量、出貨確認、上下架）。丟進來的 event 本身就是「輕量級」。**失敗不重打。**
- **Slow Topic**：排程觸發、批次處理（拉單、庫存同步）。丟進來的 event 可能觸發「重量級」操作。**失敗可重打（有條件）。**

快慢分離的本質 — 不是程式判斷，是商務判斷：
  這是分散式 CAP 理論在電商的商務應用。
  有些操作要快、要即時、失敗就算了（改價改量出貨 → AP 取向）。
  有些操作可以等、可以重試、最終一致就好（拉單庫存同步 → CP 取向）。
  哪些東西走 fast、哪些走 slow，由業務決定，不是程式自動分類。
  ★ 鐵則：fast topic 的失敗一律不重打（見 §3.9 失敗處理）。

```
舊系統（微服務）：
  call-to-user-job       → 一支程式，部署參數指定吃哪些 fast topic
  call-to-warehouse-job  → 同一支程式，部署參數指定吃哪些 slow topic
  同樣的 CODE，不同的部署設定 → 不同 instance → 各自 scale

新系統（模組化單體，參數化部署）：
  ChannelJob（平台 JOB）→ 同一支程式，每個平台 × 每個速度 = 一個 instance
    instance A：JOB_CHANNEL_TOPICS=momo.fast       group-id=channel-job-momo-fast
    instance B：JOB_CHANNEL_TOPICS=momo.slow       group-id=channel-job-momo-slow
    instance C：JOB_CHANNEL_TOPICS=shopee.fast     group-id=channel-job-shopee-fast
    instance D：JOB_CHANNEL_TOPICS=shopee.slow     group-id=channel-job-shopee-slow
    instance E：JOB_CHANNEL_TOPICS=yahoo.fast      group-id=channel-job-yahoo-fast
    instance F：JOB_CHANNEL_TOPICS=yahoo.slow      group-id=channel-job-yahoo-slow
    instance G：JOB_CHANNEL_TOPICS=pchome.fast     group-id=channel-job-pchome-fast
    instance H：JOB_CHANNEL_TOPICS=pchome.slow     group-id=channel-job-pchome-slow
    共 8 個 instance（4 平台 × 2 速度），各自 concurrency / 數量由部署設定控制

  部署方式不限定：
    - Docker Compose: 多個 service 用同一個 image，各自 environment 不同
    - K8s / Helm: 同一份 image 不同 Deployment，Helm values 管參數
    - 裸機 / systemd: 同一份 JAR，不同 .env 檔
    - 都可以，核心是「同 CODE + 不同設定 = 不同用途」

  重點：JOB 收到 event 後不需要判斷「我是 fast 還是 slow」
        — event 本身就帶了 taskAction，JOB 根據 action 決定怎麼做
        — 部署設定只決定「這個 instance 吃哪些 topic」來做物理隔離

場景：slow instance 正在拉 momo 的 1000 筆訂單（要 30 秒）
      → 用戶此時按「確認出貨」
      → 出貨確認進 momo.fast topic → 由 fast instance 處理 → 秒級回應
```

### 3.3 Topic 與 Partition 設計（per-channel 模式）

每個平台的限速制度、規則都不同，因此 **Topic 以平台為單位，分 fast / slow**：

```
─── 通路 Topic（per-channel × fast + slow）───

momo.fast           8 partitions     momo 用戶操作：改價、改量、出貨、上下架
momo.slow           8 partitions     momo 排程同步：拉單、拉庫存、拉退貨
shopee.fast         8 partitions     shopee 用戶操作
shopee.slow         8 partitions     shopee 排程同步
yahoo.fast          8 partitions     yahoo 用戶操作
yahoo.slow          8 partitions     yahoo 排程同步
pchome.fast         8 partitions     pchome 用戶操作
pchome.slow         8 partitions     pchome 排程同步

─── 訂單處理 Topic ───

order.process        8 partitions     訂單整理（與拉單分開！見 §5.3）

─── 業務 JOB Topic ───

task.backend         8 partitions     後端任務：庫存計算、報表、彙整、通知、推薦
task.frontend        8 partitions     前台任務

─── 排程 Topic ───

scheduler            4 partitions     心跳事件（SchedulerJob 消費，決定時間→直接發到各目標 topic）

─── 基礎設施 ───

task.failed          4 partitions     失敗 LOG（RetryDispatchJob 消費，★ fast 一律不重打，slow 依條件重打）

─── 死信 Topic ───

task.dlt             4 partitions     死信佇列（終點站，不被消費，30 天 retention。見 §14）

新增平台時：新增 {platform}.fast + {platform}.slow 兩個 topic 即可
```

> **為什麼 per-channel 而不是統一 channel.fast/slow？**
> 1. 每個平台的 Rate Limit 不同（momo 低、shopee 有明確限制文件、yahoo/pchome 中等）
> 2. 每個平台的處理規則不同（momo 轉單模式、shopee token refresh、yahoo OAuth...）
> 3. 統一 topic + 不同 consumer group → 每個 group 都收到全部平台的訊息，雜訊太多
> 4. per-channel topic 讓部署設定可以精確控制每個平台的 concurrency / instance 數
> 5. 某個平台限速被封鎖 → 只影響該平台的 topic，不會卡住其他平台

> **為什麼新增 order.process？**
> 收訂單和整理訂單必須分開。
> 原因：一次抓三天完成訂單，一家就可能 1000 張，雙十一更是突波。
> 混在一起會造成重複訂單、處理阻塞。分開後中間有 Hash 快取去重（見 §4.6）。

> **為什麼 task.backend 合併 report/summary/recommend？**
> 參考舊系統 `recover-data-job`：單一 `call-to-backend` topic，內部 Factory 依 event 路由到 25+ 個 Worker。
> 事件只告訴時間到了 + `taskAction`，JOB 內部 Factory 分派。

### 3.4 Partition Key 策略（依場景分層）

**核心原則：細 key 穩準確，粗 key（或無 key）穩系統完整。**

| 場景 | Topic | Partition Key | 粒度 | 理由 |
|------|-------|---------------|------|------|
| 拉取訂單 | {channel}.slow | **無 key**（round-robin） | 最粗 | 最大吞吐，不需要排序 |
| **健康檢查** | {channel}.fast | **`channelId`** | 粗 | **同一通路健康檢查排隊，結果寫 LOG** |
| **同步商品** | {channel}.slow | **`channelId`** | 粗 | **同一通路的商品同步必須排隊，不能並行跑兩次** |
| 庫存同步 | {channel}.slow | `channelId:merchantId` | 粗 | 同通路+商家有序即可 |
| 訂單整理 | order.process | `channelId:merchantId` | 中 | 同通路+商家的訂單按序處理 |
| 改價/改量 | {channel}.fast | `sellPackId` | **最細** | 同一 SKU 的價量操作必須嚴格有序 |
| 出貨確認 | {channel}.fast | `orderId` | 細 | 同一訂單的出貨操作有序 |
| 取消訂單 | {channel}.fast | `orderId` | 細 | 同上 |
| 上下架 | {channel}.fast | `sellPackId` | 細 | 同商品上下架有序 |
| 報表/彙整/通知 | task.backend | `merchantId` | 粗 | 同商家有序即可 |
| 失敗重打 | task.failed | `originalTopic:originalKey` | 原始 | 保留原始順序語意 |

```
為什麼拉單不需要 key？
  拉單事件由排程觸發，每個通路一支定時 → 本身就不會衝突
  round-robin 分散到所有 partition → 最大機器效能
  後面的「訂單整理」才需要 key 確保有序

為什麼同步商品要用 channelId 做 key？
  同步商品是「全量拉取 → upsert」，必須排隊：
  如果同一通路同時跑兩次同步 → 兩邊同時寫 sell_pack → 有可能互相覆蓋
  用 channelId 做 partition key → Kafka 保證同 channelId 依序消費
  按鈕連點兩下、或排程和手動撞在一起 → 第二次會排在第一次後面

為什麼改價要細到 sellPackId？
  同一個 SKU 在同一通路上的改價 +5, -3, +10 必須按序
  如果 key 太粗（如 channelId），不同 SKU 的改價會互相卡住
  sellPackId 是賣場檔的 DB ID，最細粒度

為什麼 per-channel topic 而不是統一 topic + 不同 partition key？
  統一 topic 的問題：
  1. momo 被限速 → 該 partition 的訊息堆積 → 但 shopee 的訊息也可能在同 partition
  2. 不同平台的 rate limit 策略完全不同，無法用統一 concurrency 管理
  3. per-channel topic 讓每個平台的堆積、限速、重打完全隔離
```

### 3.5 Partition 數與 Consumer 數

```
原則：
  - partition 數 >= consumer 數（否則有 consumer 閒置）
  - partition 數決定最大並行度
  - 後續擴容只能增加 partition，不能減少

per-channel topics：
  momo.fast       8 partitions × ChannelJob fast instance (concurrency 由部署設定控制)
  momo.slow       8 partitions × ChannelJob slow instance
  shopee.fast     8 partitions × ChannelJob fast instance
  shopee.slow     8 partitions × ChannelJob slow instance
  yahoo.fast      8 partitions
  yahoo.slow      8 partitions
  pchome.fast     8 partitions
  pchome.slow     8 partitions

業務 topics：
  order.process   8 partitions × OrderProcessJob
  task.backend    8 partitions × BackendJob
  task.frontend   8 partitions × FrontendJob
  scheduler       4 partitions × SchedulerJob
  task.failed     4 partitions × RetryDispatchJob

並行度管理：
  同一支 ChannelJob 程式，每個 instance = 一個平台 × 一個速度：
    - 吃哪個 topic（如：momo.fast）
    - group-id（如：channel-job-momo-fast）
    - concurrency（如：8）
  每個平台 fast/slow 完全隔離 → momo 被限速不影響 shopee
  擴容 = 多起同設定 instance → 同 consumer group → Kafka 自動 rebalance
```

### 3.6 快慢通道分流規則

| 來源 | 動作類型 | Topic | Partition Key | 理由 |
|------|----------|-------|---------------|------|
| Scheduler 排程 | FETCH_ORDERS | {channel}.slow | **無**（round-robin） | 最大吞吐，後面整理才需要有序 |
| Scheduler 排程 | GET_QUANTITY | {channel}.slow | channelId:merchantId | 同通路+商家有序 |
| Scheduler 排程 | FETCH_RETURNS | {channel}.slow | **無**（round-robin） | 同拉單邏輯 |
| ChannelJob 產出 | PROCESS_ORDER | order.process | channelId:merchantId | 同通路+商家的訂單按序整理 |
| 用戶操作 | SHIPPING_CONFIRMED | {channel}.fast | orderId | 同訂單出貨有序 |
| 用戶操作 | MODIFY_PRICE | {channel}.fast | sellPackId | 同 SKU 改價嚴格有序 |
| 用戶操作 | MODIFY_QUANTITY | {channel}.fast | sellPackId | 同 SKU 改量嚴格有序 |
| 用戶操作 | START_SELLING | {channel}.fast | sellPackId | 同商品上下架有序 |
| 用戶操作 | STOP_SELLING | {channel}.fast | sellPackId | 同商品上下架有序 |
| JOB 自治路由 | * | 由 JOB 決定 | 由 JOB 決定 | 每支 JOB 自己決定往哪個 topic 發 |

### 3.7 KafkaConfig 實作

```java
@Configuration
public class KafkaConfig {

    // ===== per-channel Topics（新增平台時在這裡加）=====
    // 每個平台 fast + slow 兩個 topic
    @Bean public NewTopic momoFast()    { return channelTopic("momo.fast"); }
    @Bean public NewTopic momoSlow()    { return channelTopic("momo.slow"); }
    @Bean public NewTopic shopeeFast()  { return channelTopic("shopee.fast"); }
    @Bean public NewTopic shopeeSlow()  { return channelTopic("shopee.slow"); }
    @Bean public NewTopic yahooFast()   { return channelTopic("yahoo.fast"); }
    @Bean public NewTopic yahooSlow()   { return channelTopic("yahoo.slow"); }
    @Bean public NewTopic pchomeFast()  { return channelTopic("pchome.fast"); }
    @Bean public NewTopic pchomeSlow()  { return channelTopic("pchome.slow"); }

    private NewTopic channelTopic(String name) {
        return TopicBuilder.name(name)
            .partitions(8)
            .replicas(1)         // 開發環境 1，生產環境 3
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")  // 永不刪除
            .build();
    }

    // ===== 業務 Topics =====
    @Bean
    public NewTopic taskBackend() {
        return TopicBuilder.name("task.backend")
            .partitions(8).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    @Bean
    public NewTopic taskFrontend() {
        return TopicBuilder.name("task.frontend")
            .partitions(8).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    @Bean
    public NewTopic orderProcess() {
        return TopicBuilder.name("order.process")
            .partitions(8).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    // ===== 排程 Topics =====
    @Bean
    public NewTopic scheduler() {
        return TopicBuilder.name("scheduler")
            .partitions(4).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    // ===== 失敗 LOG Topic =====
    @Bean
    public NewTopic taskFailed() {
        return TopicBuilder.name("task.failed")
            .partitions(4).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    // ===== Producer 設定 =====
    @Bean
    public ProducerFactory<String, TaskMessage> producerFactory() {
        Map<String, Object> props = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "${spring.kafka.bootstrap-servers}",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
            ProducerConfig.ACKS_CONFIG, "all",            // 所有 replica 確認
            ProducerConfig.RETRIES_CONFIG, 3,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true // 防止 Producer 重複發送
        );
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, TaskMessage> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ===== Consumer 設定 =====
    @Bean
    public ConsumerFactory<String, TaskMessage> consumerFactory() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "${spring.kafka.bootstrap-servers}",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,  // 手動 commit
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10
        );
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskMessage> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, TaskMessage> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
```

### 3.8 Producer 發送範例

```java
@Component
public class TaskProducer {

    private final KafkaTemplate<String, TaskMessage> kafka;

    /**
     * 發送訊息到指定 topic
     * @param topic  目標 topic（momo.fast / shopee.slow / task.backend / scheduler / ...）
     * @param key    partition key（保證同 key 有序），null 表示 round-robin
     * @param msg    訊息
     */
    public void send(String topic, String key, TaskMessage msg) {
        kafka.send(topic, key, msg)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka send failed: topic={}, key={}, error={}",
                        topic, key, ex.getMessage());
                } else {
                    log.debug("Kafka sent: topic={}, partition={}, offset={}",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                }
            });
    }
}
```

### 3.9 失敗處理（做了就做了，不 retry）

```
核心原則：
  JOB 收到訊息 → 做 → 一定 ack 消化掉 → queue 永遠暢通
  差異只在「成功」或「失敗（寫失敗 LOG）」

Consumer 收到訊息
  │
  ├── 執行成功
  │   ├── 手動 ack.acknowledge()
  │   ├── 回寫 DB (channel_sync_logs, status=SUCCESS, health=OK)
  │   └── JOB 自治路由：根據業務邏輯決定是否發送到下一個 topic（見 §5.4）
  │
  └── 執行失敗
      ├── 手動 ack.acknowledge()  ← 一樣 ack！不卡 partition
      ├── 回寫 DB (channel_sync_logs, status=FAILED, error=...)
      └── 發到 task.failed topic（帶原始 topic/key/action/payload + 錯誤資訊）

  ★ 通路健康度由獨立的 CHECK_HEALTH action 判斷（不附帶在其他操作裡）
    SchedulerJob 每 10 分鐘 → {channel}.fast → CheckHealthActionService
    三層結果寫 channel_sync_logs：
      🟢 OK            — API + Token 暢通
      🟡 TOKEN_INVALID — API 活著但 Token 不對（要重新授權）
      🔴 API_DOWN      — 通路 API 掛了（等平台恢復）

為什麼不 retry？
  1. 打通路失敗不代表 3 分鐘後會成功 — 可能是暫時性限速、也可能是資料問題
  2. 更新操作有時候不要 retry 比較好 — 重複改價/改量可能造成更大問題
  3. queue 一定要消化掉 — 不能因為一筆失敗卡住整個 partition
  4. 失敗的處理有複雜的開關/時間控制/delay 邏輯 → 交給專門的 RetryDispatchJob

★ 鐵則：fast topic 的失敗一律不重打 ★
  *.fast topic 代表的是即時性操作（改價、改量、出貨確認、上下架）
  這些操作失敗了就是失敗了 — 重打可能造成更大問題（重複改價、重複出貨）
  快慢分離本質上是分散式 CAP 理論的商務應用：
    - fast = 要快、要即時、失敗就算了（AP 取向）
    - slow = 可以等、可以重試、最終一致就好（CP 取向）
  這是商務判斷，不是程式判斷 — 哪些東西走 fast、哪些走 slow 是由業務決定的

RetryDispatchJob（專門把失敗 LOG 轉成重打 TOPIC）：
  消費 task.failed topic
  0. ★ 鐵則第一關：originalTopic 以 .fast 結尾 → 一律不重打，直接寫 DB LOG（NOT_RETRYABLE_FAST）
  1. 讀取訊息上的 retryCount（失敗次數）
  2. retryCount >= maxRetry → 寫 DB LOG 標記「放棄」，人工檢視
  3. 判斷這個 action 能不能重打（retryable 白名單，有些 action 不能打回去）
  4. 判斷是否在開關允許的時間範圍內
  5. 判斷是否需要 delay（間隔多久才重打）
  6. 符合條件 → retryCount + 1 標註在訊息上 → 重新 produce 到原始 topic + 原始 key
  7. 不符合條件 → 寫 DB LOG 標記「放棄」

  每次重打都會在 QUEUE 訊息上標註失敗次數
  JOB 超過一定次數的寫 DB LOG（參考舊系統 flow-retry-times <= 3）
  所有的開關機制、時間控制、delay、最大重試次數都集中在這一支 JOB
  ★ fast 永遠不重打 — 這是鐵則，寫死在程式裡，不需要白名單判斷
```

### 3.10 application.yml

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      retries: 3
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.simpleec.*"
```

---

## 4. Redis 快取設計

### 4.1 快取什麼

OMS 的讀取熱點：

| Key Pattern | 資料 | TTL | 理由 |
|-------------|------|-----|------|
| `jwt:blacklist:{token_hash}` | "1" | = token 剩餘有效期 | JWT 登出黑名單 |
| `platform:all` | List\<Platform\> | 1 小時 | 平台定義很少改 |
| `channel:{merchantId}:list` | List\<Channel\> | 10 分鐘 | 通路列表常讀 |
| `product:{id}` | Product | 5 分鐘 | 商品詳情 |
| `sellpack:{channelId}:{channelProductId}` | SellPack | 5 分鐘 | 通路下單時反查賣場檔 |
| `order:hash:{merchantId}:{channelId}:{orderId}` | SHA-256 hash | 7 天 | 訂單去重（見 §4.6） |

### 4.2 不快取什麼

- **訂單列表** — 變動頻繁 + 分頁查詢參數多，快取命中率低
- **訂單詳情** — 狀態隨時變，快取反而造成顯示不一致

### 4.3 快取策略

```
讀取：Cache-Aside
  1. 查 Redis → 有就回
  2. 沒有 → 查 DB → 寫 Redis → 回

寫入：Write-Through + Evict
  1. 寫 DB
  2. 刪 Redis key（不是更新，下次讀自然重建）
```

### 4.4 Spring Boot 實作

```java
// simpleec-app/config/RedisConfig.java（需新增）
@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair
                    .fromSerializer(new GenericJackson2JsonRedisSerializer())
            );

        return RedisCacheManager.builder(factory)
            .cacheDefaults(config)
            .withCacheConfiguration("platforms",
                config.entryTtl(Duration.ofHours(1)))
            .withCacheConfiguration("channels",
                config.entryTtl(Duration.ofMinutes(10)))
            .build();
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        // 給 JWT blacklist、Order Hash Dedup 用
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }
}
```

```java
// 使用方式 — Service 層加 annotation
@Service
public class ProductService {

    @Cacheable(value = "product", key = "#id")
    public Product getById(String id) { ... }

    @CacheEvict(value = "product", key = "#product.id")
    public void save(Product product) { ... }
}
```

### 4.5 分散式鎖（幾乎不需要）

```
為什麼 Redis 鎖使用機率不高？

  Kafka partition key 天然保證：
    同一個 key 的訊息 → 進同一個 partition → 同一時間只有一個 consumer 處理
    → 不需要分散式鎖就能保證「同一個 SKU / 同一筆訂單」的操作有序且不衝突

  排程防重複：
    SchedulerJob 只需要 1 個 consumer instance（concurrency=1）
    → 本身就是單執行緒，不需要鎖
    → memory-first ConcurrentHashMap 防抖就夠了

  什麼時候才需要 Redis 鎖？
    極少數跨 JOB 的操作需要互斥（例如同時有排程和手動觸發同一個操作）
    → 但 Kafka partition key 設計好的話，這種情況也不會發生

  結論：只要 Kafka 的分散設計好，Redis 鎖使用機率非常低
        暫不實作，真正需要時再加
```

### 4.6 Order Hash Dedup Cache（拉單→整理之間的去重）

> 收訂單和整理訂單是兩支 JOB。中間靠 Hash 快取去重。
> 一次抓三天完成訂單，一家就可能 1000 張。雙十一突波更恐怖。
> 沒有去重 → 每 5 分鐘整理 JOB 都收到一模一樣的 1000 張 → 浪費。

```
Key Pattern:  order:hash:{merchantId}:{channelId}:{orderId}
Value:        SHA-256 hash of order data JSON
TTL:          7 天（覆蓋典型 3 天抓取範圍 + 餘裕）

兩邊 SYNC（ChannelJob 寫 + OrderProcessJob 更新，用同一個 key 結構）：

  ChannelJob（FetchOrdersActionService）— Producer side：
    1. 從通路 API 拉取訂單（如：3 天內的完成訂單）
    2. 對每筆訂單：計算 order data 的 SHA-256 hash
    3. GET order:hash:{merchantId}:{channelId}:{orderId}
    4. hash 相同 → SKIP（訂單無變動，不發到 order.process）
    5. hash 不同或不存在 → SEND 到 order.process topic

  OrderProcessJob — Consumer side：
    1. 收到訂單 → DB 比對 + upsert
    2. 處理完後 → SET order:hash:{merchantId}:{channelId}:{orderId} = newHash

  兩邊用同樣的 key 結構（merchantId:channelId:orderId）+ 同樣的 hash 算法
  ChannelJob 讀 hash 判斷要不要發 → OrderProcessJob 處理完寫回 hash

為什麼是 Producer-side dedup（不是 Consumer-side）：
  - 在 ChannelJob 這一端就過濾掉沒變的訂單
  - OrderProcessJob 只收到真正有變動/新增的訂單
  - 減少 order.process topic 的訊息量，減少 OrderProcessJob 負擔
```

```java
// ChannelJob (FetchOrdersActionService) 裡的去重邏輯 — Producer side
for (Order order : fetchedOrders) {
    String hashKey = "order:hash:" + merchantId + ":" + channelId + ":" + order.getPlatformOrderId();
    String newHash = DigestUtils.sha256Hex(JSONMapper.toJSON(order));
    String existingHash = redis.opsForValue().get(hashKey);

    if (!newHash.equals(existingHash)) {
        // 有變動或新訂單 → 發到 order.process（帶 hash 供 consumer 更新）
        orderMsg.getPayload().put("orderHash", newHash);
        taskProducer.send("order.process", channelId + ":" + merchantId, orderMsg);
    }
    // hash 相同 → skip，不發
}

// OrderProcessJob 處理完後更新 hash — Consumer side（同一個 key 結構）
String hashKey = "order:hash:" + merchantId + ":" + channelId + ":" + order.getPlatformOrderId();
redis.opsForValue().set(hashKey, newHash, Duration.ofDays(7));
```

---

## 5. JOB 架構（核心設計）

> **每一支 JOB 都是獨立的 Spring Boot application。** 參考舊系統 `recover-data-job` / `call-to-user-job` 模式：
> - JOB 消費特定 Kafka topic
> - 內部 Factory pattern 根據 event 路由到對應 ActionService
> - ActionService 完成後自主決定往哪個 topic 發（不是中央 PipelineExecutor）
> - JOB 之間唯一的耦合是 Kafka topic — 不互相呼叫、不共享 process
> - API 和 JOB 之間唯一的耦合也是 Kafka topic
> - event 丟進來本身就要思考這個工作的重量
>
> **唯一特例 — ChannelJob（平台 JOB）：**
> - **同一份 CODE，不同環境變數 = 不同 instance**（fast / slow / per-channel）
> - 這是為了開發邏輯性的統一 — 所有跟平台溝通的 ActionService 都在同一個專案
> - 參考舊系統 call-to-user-job / call-to-warehouse-job：同一支程式，38 個部署
> - **其他 JOB 都是各自獨立的 Spring Boot application**
>
> 排程引擎：心跳 → scheduler topic → SchedulerJob 決定時間該做什麼 → 發到各 topic

### 5.1 JOB = 自治單元（舊系統 action-to-platform 模式）

```
舊系統 action-to-platform（消化隊列 → Factory → ActionService 4 步生命週期）：

  ┌──────────────────────────────────────────────────────────────────┐
  │  simpleec-consuming-action-job-ga (action-to-{platform} topics)     │
  │                                                                  │
  │  MainManager extends Thread                                      │
  │  while(true) {                                                   │
  │      records = consumer.poll(1000);                               │
  │      for (record : records) {                                     │
  │          service = ActionFactory.getService(topic, action);      │  ← Factory 依 topic+action 路由
  │          service.setting(resource);                              │  ← 步驟 1: 初始化 + 載入設定
  │          service.getPlatformTokens();                            │  ← 步驟 2: 組合認證憑證（credential + token）
  │          service.verifyNeedData();                               │  ← 步驟 3: 驗證必要資料
  │          service.doAction();                                     │  ← 步驟 4: 執行平台 API 操作
  │      }                                                           │
  │      consumer.commitAsync();                                     │
  │  }                                                               │
  │                                                                  │
  │  40+ ActionService 實作（改價、改量、出貨、拉單...）               │
  │  同一份 Docker image，38 個部署（每平台 fast + slow）             │
  │  部署設定管 topic / groupId / instance 數量                       │
  └──────────────────────────────────────────────────────────────────┘

  舊系統 recover-data-job（後台工作）：

  ┌──────────────────────────────────────────────────────────────────┐
  │  消費 call-to-backend topic                                      │
  │  RecoverDataFactory 依 event 路由到 26+ Worker                    │
  │  Worker 生命週期相同：setting → verifyData → getNeedData → do     │
  └──────────────────────────────────────────────────────────────────┘

新系統（模組化單體，保留 Factory + ActionService 模式）：

  ┌──────────────────────────────────────────────────────────────────┐
  │  ChannelJob（平台 JOB — 參考 action-to-platform）                 │
  │                                                                  │
  │  同一支 CODE，環境變數決定（每個 instance = 一個平台 × 一個速度）：  │
  │    - topics: "momo.fast"                           ← 環境變數     │
  │    - concurrency: 8                                ← 環境變數     │
  │    - groupId: "channel-job-momo-fast"              ← 環境變數     │
  │                                                                  │
  │  @KafkaListener(                                                 │
  │    topics = "${job.channel.topics}",               ← 參數化       │
  │    groupId = "${job.channel.group-id}",                          │
  │    concurrency = "${job.channel.concurrency}")                   │
  │  void handle(TaskMessage msg, Ack ack) {                         │
  │      service = ActionFactory.getService(msg.topic, msg.action); │  ← Factory
  │      service.setting(resource);                                  │  ← 步驟 1
  │      service.getPlatformTokens();                                │  ← 步驟 2
  │      service.verifyNeedData();                                   │  ← 步驟 3
  │      service.doAction();                                         │  ← 步驟 4
  │      ack.acknowledge();                    ← 一定 ack，做了就做了  │
  │      // 成功 → sync log SUCCESS                                  │
  │      // 失敗 → sync log FAILED + task.failed                     │
  │      service.routeNext(taskProducer);      ← 自主路由到下一個 topic│
  │  }                                                               │
  └──────────────────────────────────────────────────────────────────┘
```

### 5.2 JOB 類型總覽（各自獨立 Spring Boot + 大粒度收斂）

> **每支 JOB 是獨立的 Spring Boot application，各自有 Dockerfile，各自是 docker-compose 裡的一個 service。**
> 唯一特例：ChannelJob 是同一份 CODE 不同環境變數 = 不同 service。
>
> **粒度太小未來管理會非常困難。** 收斂原則：
> - 所有跟平台溝通的都收斂成一支（因為很常一個平台有需求其他平台跟著要，但每個平台支援度不一）
> - 訂單整理是一個
> - 時間管理排程是一個（含分發邏輯，直接發到各目標 topic）
> - 後台工作一個（內部有 BackendActionFactory + BackendActionService 抽象層）
> - 前台工作一個（內部有 FrontendActionFactory 抽象層）
> - 失敗重打一個
> - Web API 獨立

| JOB（各自獨立 Spring Boot） | Gradle Module | Topics | Consumer Group | 職責 |
|----|-------------|--------|----------------|------|
| **ChannelJob**（平台 JOB） | simpleec-channel-job | `${job.channel.topics}` 參數化 | `${job.channel.group-id}` 參數化 | 所有跟平台 API 溝通：拉單、出貨、改價改量、上下架、庫存同步。**同 CODE 不同設定** |
| **OrderProcessJob**（訂單整理） | simpleec-order-job | order.process | order-process-job | 訂單 DB 比對、Hash Dedup 更新、狀態變更路由 |
| **SchedulerJob**（時間管理排程+分發） | simpleec-scheduler-job | scheduler | scheduler-job | HeartbeatTimer + 收心跳，判斷時間→查 DB 已啟用通路→直接發到各目標 topic |
| **BackendJob**（後台工作） | simpleec-backend-job | task.backend | backend-job | 報表/彙整/通知/推薦（BackendActionFactory 分派） |
| **FrontendJob**（前台工作） | simpleec-frontend-job | task.frontend | frontend-job | 前台觸發的業務邏輯 |
| **RetryDispatchJob**（失敗重打） | simpleec-retry-job | task.failed | retry-dispatch-job | ★ fast 一律不重打；slow 依條件判斷能否重打、何時重打 |
| **前端 API** | simpleec-api | — | — | REST + JWT，不消費 Kafka。看平台、塞設定、看資料、丟事件 |
| **對外 API** | simpleec-gateway | — | — | REST + JWT/簽名驗證，不消費 Kafka。Webhook 接收、ERP 串接 |

```
為什麼每支 JOB 獨立 Spring Boot？

  1. 隔離性 — 一支 JOB 掛了不影響其他 JOB
  2. 各自 scale — OrderProcessJob 需要加量只需多起 instance，不影響 SchedulerJob
  3. 各自 deploy — 改了 BackendJob 的邏輯只需重新部署 simpleec-backend-job
  4. 資源隔離 — slow instance 吃大量記憶體不會擠壓 fast instance
  5. 跟舊系統一致 — 舊系統每支 JOB 也是獨立的 Docker image

為什麼 ChannelJob 是同 CODE 不同設定？

  舊系統經驗：
    call-to-user-job     → 一支程式處理所有「快速」平台操作
    call-to-warehouse-job → 同一支程式處理所有「慢速」平台同步
    同一份 Docker image，38 個部署（每平台 fast + slow）

  好處：
    1. 開發邏輯統一 — 所有跟平台溝通的 ActionService 都在同一個專案
    2. 通路需求變動只改一支 — 一個平台有新需求，其他平台跟著加
    3. 測試集中 — 平台相關邏輯都在 ChannelJob，共用 Adapter/Factory/ActionService 測試
```

### 5.3 各 JOB 詳細設計

#### 5.3.1 ChannelJob（平台 JOB — 參考舊系統 action-to-platform）

```
Topics:        環境變數參數化 — 每個 instance 只吃一個平台的一個速度（如 "momo.fast" 或 "shopee.slow"）
Consumer Group: 環境變數參數化 — 對應 "channel-job-momo-fast" 或 "channel-job-shopee-slow" 等
Partition Key:  依 action 不同（見 §3.4 策略表）

核心架構（參考舊系統 action-to-platform）：
  ActionFactory — 依 topic + action 路由到對應的 ActionService 實作
  ActionService — 統一 4 步生命週期介面

ActionService 4 步生命週期：
  1. setting(resource)       — 初始化：載入通路設定、Adapter、必要的 DAO
  2. getPlatformTokens()     — 組合認證憑證（platform credential + channel token）
  3. verifyNeedData()        — 驗證必要資料（缺少就提前失敗，不浪費 API quota）
  4. doAction()              — 執行平台 API 操作 + 自治路由

處理的 Action（ActionFactory 分派）：
  ─── 快速操作（通常來自 {channel}.fast）───
  CHECK_HEALTH       → CheckHealthActionService ★ 獨立健康檢查（寫 channel_sync_logs）
  MODIFY_PRICE       → ModifyPriceActionService
  MODIFY_QUANTITY    → ModifyQuantityActionService
  START_SELLING      → StartSellingActionService
  STOP_SELLING       → StopSellingActionService
  SHIPPING_CONFIRMED → ShippingConfirmedActionService
  ACCEPT_CANCEL      → AcceptCancelActionService

  ─── 慢速操作（通常來自 {channel}.slow）───
  FETCH_ORDERS       → FetchOrdersActionService → Hash Dedup → order.process
  FETCH_PRODUCTS     → FetchProductsActionService → upsert sell_pack + auto-create product
  GET_QUANTITY       → GetQuantityActionService → syncInventory()
  FETCH_RETURNS      → FetchReturnsActionService

DB 讀取（依 action 不同，可能讀取多種表）：
  platform             — credential1~N（第三方認證，各平台不同）
  channels             — token1~token5（各館授權 token）+ 通路設定
  products + product_spec     — 商品資料（改價/改量/上下架時需要）
  sell_pack                   — 賣場檔對照（通路商品 ID ↔ 內部商品）
  orders                      — 訂單資料（出貨/取消時需要）
  不同 ActionService 依需要讀不同的表，不只 channels

輸出（自治路由 — 在 doAction 裡決定）：
  FETCH_ORDERS 完成 → 有變動的訂單發到 order.process (key=channelId:merchantId)
  FETCH_PRODUCTS 完成 → 直接寫 DB（upsert sell_pack + auto-create product），無下游 topic
  SHIPPING_CONFIRMED 完成 → 發到 task.backend (通知狀態更新)
  其他操作 → 無（一步到位）
  失敗 → 發到 task.failed

通路健康檢查邏輯（CheckHealthActionService — ★ 獨立 fast action）：
  ★ 這是獨立的健康檢查，不是附帶在其他操作裡的副作用
  ★ 走 {channel}.fast topic，結果必須寫 channel_sync_logs 記錄
  1. getPlatformTokens() — 組合認證（platform credential + channel token）
  2. doAction() — 用憑證打一個輕量 API（如 getShopInfo / ping）
  3. 判斷結果 → 寫 channel_sync_logs：
     - API 無回應 (timeout / 5xx)  → health=API_DOWN, status=FAILED 🔴
     - API 回應但 Token 錯 (401/403) → health=TOKEN_INVALID, status=FAILED 🟡
     - API + Token 暢通             → health=OK, status=SUCCESS 🟢
  4. 結束（無下游 topic — 純檢查 + 寫 LOG）

  ★ 觸發時機：SchedulerJob 定時對每個 active channel 發 CHECK_HEALTH
  ★ partition key = channelId（同通路排隊）

商品同步邏輯（FetchProductsActionService）：
  1. 呼叫平台 API 拉取該 channel 的所有商品（分頁拉完）
  2. 每個平台商品 → 用 channel_id + channel_product_id 做 key → upsert sell_pack
  3. sell_pack 對應的 product（本）→ 用 SKU (item_number) match 現有 product
     - 找到 → 關聯 product_id
     - 找不到 → 自動建立 product（name/sku 從平台資料取），後續人工修正
  4. 通路對商品以 SKU 為主 — sell_pack.channel_product_id 是平台的「賣編」

訂單定位邏輯（FetchOrdersActionService → OrderProcessJob）：
  訂單裡的商品用「賣編 + 規格編」定位：
  1. channel_product_id + channel_spec_id → match sell_pack
  2. sell_pack → product_id → 知道是哪個商品本
  3. 如果 sell_pack 不存在（還沒同步過商品）→ 訂單照常入庫，sell_pack 欄位暫空
```

```java
@Component
@Slf4j
public class ChannelJob {

    private final ActionFactory actionFactory;
    private final TaskProducer taskProducer;
    private final SyncLogService syncLogService;

    @KafkaListener(
        topics = "#{'${job.channel.topics}'.split(',')}",   // 環境變數參數化
        groupId = "${job.channel.group-id}",
        concurrency = "${job.channel.concurrency:4}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        try {
            // ActionFactory 依 topic + action 路由
            ActionService service = actionFactory.getService(msg.getTopic(), msg.getTaskAction());
            if (service == null) {
                log.error("Unknown action: topic={}, action={}", msg.getTopic(), msg.getTaskAction());
                ack.acknowledge();
                return;
            }

            // 4 步生命週期（參考舊系統 action-to-platform）
            Resource resource = buildResource(msg);
            service.setting(resource);               // 步驟 1: 初始化 + 載入設定
            service.getPlatformTokens();             // 步驟 2: 組合認證憑證（credential + token）
            service.verifyNeedData();                // 步驟 3: 驗證必要資料
            service.doAction();                      // 步驟 4: 執行平台 API + 自治路由

            // 成功：寫 sync log
            syncLogService.log(msg, "SUCCESS", null);
            ack.acknowledge();

        } catch (Exception e) {
            // 失敗：一樣 ack（做了就做了），寫失敗 LOG，發到 task.failed
            syncLogService.log(msg, "FAILED", e.getMessage());
            taskProducer.send("task.failed", null, buildFailedMessage(msg, e));
            ack.acknowledge();
            log.error("ChannelJob failed: action={}, error={}", msg.getTaskAction(), e.getMessage());
        }
    }
}

/**
 * ActionFactory — 依 topic + action 路由到對應的 ActionService
 * 參考舊系統 ActionFactory.java
 */
@Component
public class ActionFactory {

    private final Map<String, ActionService> services;  // Spring 自動注入（key = action name）

    public ActionService getService(String topic, String action) {
        return services.get(action);
        // 也可以依 topic 做平台特殊分派（例如 momo 和 shopee 的 FETCH_ORDERS 邏輯不同）
    }
}

/**
 * ActionService — 統一 4 步生命週期（參考舊系統 ActionService 介面）
 *
 * 舊系統：setting() → getPlatformTokens() → verifyNeedData() → doAction()
 * 每個平台 × 每個動作 = 一個 ActionService 實作
 */
public interface ActionService {
    String getAction();                              // 對應 taskAction（FETCH_ORDERS, MODIFY_PRICE...）
    void setting(Resource resource);                 // 步驟 1: 初始化（載入 adapter、DAO、設定）
    void getPlatformTokens();                        // 步驟 2: 組合認證（platform credential + channel token）
    void verifyNeedData();                           // 步驟 3: 驗證必要資料完整性
    void doAction();                                 // 步驟 4: 執行平台 API + 自治路由到下一個 topic
}

/**
 * Resource — 共用資源 DTO（參考舊系統 Resource.java）
 * 承載 JOB 執行需要的所有共用物件
 */
@Data
public class Resource {
    private TaskMessage msg;
    private TaskProducer taskProducer;
    private ChannelAdapter adapter;
    private Channel channel;                         // 通路設定
    private Map<String, String> platformTokens;      // 認證憑證（platform credential + channel token）
    private OrderService orderService;
    private RedisTemplate<String, String> redis;
    // ... 其他需要的 DAO / Service
}
```

```yaml
# === 每個 instance = 一個平台 × 一個速度（4 平台 × 2 速度 = 8 個 instance）===

# --- instance A: momo fast ---
# JOB_CHANNEL_TOPICS=momo.fast
# JOB_CHANNEL_GROUP_ID=channel-job-momo-fast
# JOB_CHANNEL_CONCURRENCY=8
job:
  channel:
    topics: "momo.fast"
    group-id: "channel-job-momo-fast"
    concurrency: 8

# --- instance B: momo slow ---
# JOB_CHANNEL_TOPICS=momo.slow
# JOB_CHANNEL_GROUP_ID=channel-job-momo-slow
# JOB_CHANNEL_CONCURRENCY=4
job:
  channel:
    topics: "momo.slow"
    group-id: "channel-job-momo-slow"
    concurrency: 4

# --- instance C: shopee fast ---
job:
  channel:
    topics: "shopee.fast"
    group-id: "channel-job-shopee-fast"
    concurrency: 8

# --- instance D: shopee slow ---
job:
  channel:
    topics: "shopee.slow"
    group-id: "channel-job-shopee-slow"
    concurrency: 4

# --- instance E~H: yahoo fast/slow, pchome fast/slow 同理 ---
```

> **通路拉單是「大任務」**— 以時間範圍 + 狀態篩選一次抓完，不能再拆。
> 這是跟隨各平台 API 設計的結果（momo/shopee/yahoo/pchome 都是 list API + 時間 filter）。
> 拉完後在 FetchOrdersActionService 內部做 Hash Dedup，只將有變動的訂單發到下游。

#### 5.3.2 OrderProcessJob（訂單整理）

```
Topic:         order.process
Consumer Group: order-process-job
Partition Key:  channelId:merchantId（同通路+商家的訂單按序處理）
觸發來源:      ChannelJob (FETCH_ORDERS) 產出
輸出:          → task.backend topic（需要後端處理的）

流程：
  1. 收到單筆訂單 TaskMessage（已經過 Hash Dedup，保證是有變動的）
  2. 查 DB：orders 表是否已有這筆（by channel_id + channel_order_id）
  3. 不存在 → INSERT 新訂單 + order_status_log
  4. 已存在但狀態變更 → UPDATE + order_status_log
  5. 更新 Redis hash（order:hash:{merchantId}:{channelId}:{orderId} = newHash）
  6. 如果狀態有變更 → 發到 task.backend（key = merchantId）
  7. ack.acknowledge()（成功/失敗都 ack）
```

```java
@Component
@Slf4j
public class OrderProcessJob {

    private final OrderService orderService;
    private final TaskProducer taskProducer;
    private final RedisTemplate<String, String> redis;

    @KafkaListener(
        topics = "order.process",
        groupId = "order-process-job",
        concurrency = "${job.order-process.concurrency:4}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        try {
            Order order = extractOrder(msg);
            String newHash = (String) msg.getPayload().get("orderHash");

            Order existing = orderService.findByChannelOrderId(
                msg.getOwnerId(), order.getChannelOrderId());

            boolean statusChanged = false;
            if (existing == null) {
                orderService.insertOrder(order);
                statusChanged = true;
            } else if (!existing.getStatus().equals(order.getStatus())) {
                orderService.updateOrderStatus(existing.getId(), order.getStatus());
                statusChanged = true;
            }

            // 更新 hash（同 ChannelJob 的 key 結構：merchantId:channelId:orderId）
            String hashKey = "order:hash:" + msg.getMerchantId() + ":" + msg.getOwnerId() + ":" + order.getPlatformOrderId();
            redis.opsForValue().set(hashKey, newHash, Duration.ofDays(7));

            // 狀態有變 → 自治路由到 task.backend
            if (statusChanged) {
                TaskMessage backendMsg = buildBackendMessage(msg, order);
                taskProducer.send("task.backend", msg.getMerchantId(), backendMsg);
            }

            ack.acknowledge();

        } catch (Exception e) {
            syncLogService.log(msg, "FAILED", e.getMessage());
            taskProducer.send("task.failed", null, buildFailedMessage(msg, e));
            ack.acknowledge();
        }
    }
}
```

#### 5.3.3 SchedulerJob（時間管理排程 + 分發）

```
Topic:         scheduler
Consumer Group: scheduler-job
觸發來源:      心跳 Timer（每 N 秒發一個 tick 到 scheduler topic）

職責：
  收到心跳 tick 後，根據「現在的時間」決定該做什麼
  查 DB 已啟用通路 → 直接發到各目標 topic（不經過中間 topic）
  例如：時間到了 "FETCH_ALL_ORDERS" → 查 DB → 分別發到 momo.slow、shopee.slow、yahoo.slow、pchome.slow
  例如：時間到了 "DAILY_REPORT" → 發到 task.backend
```

（詳見 §6 排程設計）

#### 5.3.4 BackendJob（後台工作）

```
Topic:         task.backend
Consumer Group: backend-job
Partition Key:  merchantId（同商家有序）
觸發來源:      SchedulerJob / 其他 JOB 自治路由
輸出:          視業務需要，可路由到 {channel}.fast（如：庫存更新到通路）

處理的 Action（Factory pattern 分派）：
  DAILY_STATISTICS       ★ 每日統計彙整（寫 daily_statistics 表，依 merchant 時區觸發）
  MANAGE_PARTITIONS      ★ DB Partition 自動管理（建立未來月份 + 清理過期）
  DAILY_SALES_REPORT     每日營收報表（寄送/推播）
  YESTERDAY_ORDERS       昨日訂單彙整
  YESTERDAY_INVENTORY    昨日庫存彙整
  CALCULATE_INVENTORY    庫存計算（安全庫存檢查）
  SEND_NOTIFICATION      發通知（email/LINE/Webhook）
  GENERATE_RESTOCK       補貨建議
  ORDER_STATUS_CHANGED   訂單狀態變更後的後續處理

DailyStatisticsActionService（★ 核心 — 預彙整統計）：
  觸發：SchedulerJob 依 merchant.user_local_time_zone 判斷「昨天結束了」→ 發 task.backend
  流程：
    1. 從 msg 取 merchantId + stat_date（昨天的日期，客戶時區）
    2. 查 orders 表 WHERE merchant_id=? AND channel_created_at 在那一天（客戶時區）
    3. 依 channel_id 分組，彙整：訂單數/營業額/出貨/完成/取消/退貨（count + amount）
    4. 每個 channel 寫一筆 daily_statistics（upsert by merchant_id + stat_date + channel_id）
    5. 再寫一筆 channel_id=NULL 的全通路彙總
  重點：
    - 不即時查 orders 表 → Dashboard 查 daily_statistics 表（輕量）
    - 最小統計單位 = 一天（不做小時/分鐘級別）
    - 時區用 merchant.user_local_time_zone，不是 server timezone

ManagePartitionsActionService（★ DB Partition 自動管理）：
  觸發：SchedulerJob 每天觸發一次（例如 UTC 04:00）→ 發 task.backend
  流程：
    1. 取得需要 partition 的表清單（見 §1.4）
    2. 對每張表：
       a. 計算「下個月」和「下下個月」的 partition 名稱
          例：orders_y2026m03, orders_y2026m04
       b. 檢查 partition 是否存在（查 pg_catalog.pg_class）
       c. 不存在 → CREATE TABLE {name} PARTITION OF {parent}
                    FOR VALUES FROM ('{month_start}') TO ('{next_month_start}');
       d. 已存在 → skip（idempotent）
    3. 可選：清理超過保留期的舊 partition（DROP / DETACH）
       例：保留 12 個月 → DROP orders_y2025m01
  重點：
    - 提前建好兩個月的 partition → 不怕月初寫入時 partition 不存在
    - Idempotent — 重複跑不會出錯
    - 不需要 merchant 參數 — 這是系統級維運，不分 merchant
```

> **事件只告訴 BackendJob「時間到了」+ `taskAction`，JOB 根據 action 決定做什麼。**
> 參考舊系統 `recover-data-job`：`RecoverDataFactory` 依 event 路由到 26+ Worker。
> Worker 也用相同 4 步生命週期：setting → verifyData → getNeedData → doJob。

```java
@Component
@Slf4j
public class BackendJob {

    private final Map<String, BackendActionService> workers;  // Spring 自動注入
    private final TaskProducer taskProducer;

    @KafkaListener(
        topics = "task.backend",
        groupId = "backend-job",
        concurrency = "${job.backend.concurrency:4}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        try {
            BackendActionService worker = workers.get(msg.getTaskAction());
            if (worker == null) {
                log.error("Unknown backend action: {}", msg.getTaskAction());
                ack.acknowledge();
                return;
            }

            worker.setting(msg);
            worker.verify(msg);
            Object result = worker.execute(msg);
            worker.routeNext(taskProducer, msg, result);

            ack.acknowledge();

        } catch (Exception e) {
            taskProducer.send("task.failed", null, buildFailedMessage(msg, e));
            ack.acknowledge();
        }
    }
}

// Worker 介面
public interface BackendActionService {
    String getAction();
    void setting(TaskMessage msg);
    void verify(TaskMessage msg);
    Object execute(TaskMessage msg);
    void routeNext(TaskProducer producer, TaskMessage msg, Object result);
}
```

#### 5.3.5 RetryDispatchJob（失敗重打）

```
Topic:         task.failed
Consumer Group: retry-dispatch-job
觸發來源:      所有 JOB 失敗時發到 task.failed

職責：
  專門把失敗 LOG 轉成要打回去的 TOPIC
  每次重打在訊息上標註 retryCount → 超過上限寫入 DB 失敗 LOG 表（供 RD 定時查看清理）
  所有的開關機制、時間控制、delay、最大重試次數都集中在這一支
  ★ 鐵則：fast topic 的失敗一律不重打

流程：
  0. ★ 鐵則第一關：originalTopic 以 .fast 結尾 → 一律不重打，直接寫 DB LOG（NOT_RETRYABLE_FAST）
     → 庫存、價格、出貨確認、上下架都是 fast — 失敗了就是失敗了，重打可能造成更大問題
  1. 收到失敗事件（含原始 topic/key/action/payload + retryCount + 錯誤資訊）
  2. 檢查 retryCount >= maxRetry → 寫入 DB 失敗 LOG 表（failed_task_logs），供 RD 定時查看清理
  3. 判斷這個 action 能不能重打（retryable 白名單，有些 action 不能打回去）
  4. 判斷是否在允許的時間範圍內（有些操作只能營業時間重打）
  5. 判斷 delay 策略（間隔多久才重打）
  6. 符合條件 → retryCount + 1 → 重新 produce 到原始 topic + 原始 key
  7. 不符合條件 → 寫入 DB 失敗 LOG 表，等 RD 人工處理

  參考舊系統 sending-localfile-backtoqueue-job：flow-retry-times <= 3
  超過 maxRetry 的不是丟掉 — 是寫 DB，讓 RD 定時來看、定時清理
  ★ fast 永遠不重打 — 這是鐵則，商務判斷（CAP 理論 AP 取向），不是程式邏輯
```

```java
@Component
@Slf4j
public class RetryDispatchJob {

    private final TaskProducer taskProducer;
    private final RetryPolicyService retryPolicy;

    @KafkaListener(
        topics = "task.failed",
        groupId = "retry-dispatch-job",
        concurrency = "${job.retry-dispatch.concurrency:2}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        try {
            String originalTopic = (String) msg.getPayload().get("originalTopic");
            String originalKey = (String) msg.getPayload().get("originalKey");
            String originalAction = (String) msg.getPayload().get("originalAction");
            int retryCount = msg.getRetryCount();

            // 0. ★ 鐵則：fast topic 一律不重打（商務判斷，CAP AP 取向）
            if (originalTopic != null && originalTopic.endsWith(".fast")) {
                log.info("Fast topic {} never retries (iron rule), writing to failed_task_logs",
                    originalTopic);
                failedTaskLogService.save(msg, "NOT_RETRYABLE_FAST");
                ack.acknowledge();
                return;
            }

            // 1. 超過最大重試次數？→ 寫 DB LOG 表供 RD 查看
            int maxRetry = retryPolicy.getMaxRetry(originalAction);  // 預設 3
            if (retryCount >= maxRetry) {
                log.warn("Action {} exceeded max retry ({}>={}), writing to failed_task_logs",
                    originalAction, retryCount, maxRetry);
                failedTaskLogService.save(msg, "MAX_RETRY_EXCEEDED");
                ack.acknowledge();
                return;
            }

            // 2. 能不能重打？（有些 action 不能打回去）
            if (!retryPolicy.isRetryable(originalAction, originalTopic)) {
                log.info("Action {} on topic {} is not retryable, writing to failed_task_logs",
                    originalAction, originalTopic);
                failedTaskLogService.save(msg, "NOT_RETRYABLE");
                ack.acknowledge();
                return;
            }

            // 3. 是否在允許的時間？
            if (!retryPolicy.isWithinRetryWindow(originalAction)) {
                log.info("Action {} outside retry window, skipped", originalAction);
                ack.acknowledge();
                return;
            }

            // 4. delay 策略
            Duration delay = retryPolicy.getDelay(originalAction, retryCount);
            if (delay.toMillis() > 0) {
                Thread.sleep(delay.toMillis());
            }

            // 5. 重打 — retryCount + 1 標註在訊息上
            TaskMessage retryMsg = rebuildOriginalMessage(msg);
            retryMsg.setRetryCount(retryCount + 1);
            taskProducer.send(originalTopic, originalKey, retryMsg);
            log.info("Retry dispatched: action={}, topic={}, attempt={}/{}",
                originalAction, originalTopic, retryCount + 1, maxRetry);

            ack.acknowledge();

        } catch (Exception e) {
            log.error("RetryDispatch itself failed: {}", e.getMessage());
            ack.acknowledge();  // 就算失敗也 ack，不能無限循環
        }
    }
}
```

### 5.4 JOB 間自治路由（取代 PipelineExecutor）

> **沒有中央 PipelineExecutor。** 每支 JOB 自己決定處理完後往哪發。
> 這是舊系統的核心模式：`doQueueJob()` 裡面的 `SendQueueHelper.send(topic, key, msg)`。

```
自治路由表：

ChannelJob (FetchOrdersActionService)
  └── 有變動的訂單 → order.process (key=channelId:merchantId)

ChannelJob (ShippingConfirmedActionService)
  └── 出貨成功 → task.backend (通知狀態更新)

ChannelJob (CheckHealthActionService)
  └── 完成 → 無（結束，寫 channel_sync_logs 記錄健康度）
  ★ 入口 partition key = channelId
  ★ 三層判斷：OK / TOKEN_INVALID / API_DOWN

ChannelJob (FetchProductsActionService)
  └── 完成 → 無（結束，upsert sell_pack + product 後即完成）
  ★ 入口 partition key = channelId（同通路排隊，不能並行跑兩次同步）

OrderProcessJob
  └── 狀態有變更 → task.backend (key=merchantId)

BackendJob (視 Worker 而定)
  └── 庫存計算完成 → {channel}.fast (key=sellPackId)  // 更新通路庫存
  └── 報表完成 → 無（結束）
  └── 通知完成 → 無（結束）
  └── DAILY_STATISTICS → 無（結束，寫入 daily_statistics 表即完成）
  └── MANAGE_PARTITIONS → 無（結束，CREATE PARTITION IF NOT EXISTS）

所有 JOB 失敗 → task.failed
  ★ 鐵則：*.fast topic → 直接寫 failed_task_logs，不重打
  ★ *.slow topic → RetryDispatchJob 依 retryCount 判斷是否重打

好處：
  1. 邏輯內聚 — 路由邏輯在 JOB 裡面
  2. 靈活 — 根據執行結果動態決定路由
  3. 簡單 — 沒有 pipelineId、stepIndex
  4. 可測試 — 每支 JOB 單獨測試
```

### 5.5 統一訊息格式 — TaskMessage

```java
/**
 * 所有任務的統一 MQ 訊息格式
 */
@Data
@Builder
public class TaskMessage {
    // === 基本資訊 ===
    private String messageId;          // UUID，每條訊息唯一
    private String taskType;           // channel_action / backend / scheduler / dispatch
    private String taskAction;         // FETCH_ORDERS / DAILY_SALES_REPORT / PROCESS_ORDER / ...
    private String sourceJobType;      // 發送此訊息的 JOB 類型（用於 debug 追蹤來源）
    private String merchantId;
    private String ownerType;          // channel / account / merchant / system
    private String ownerId;            // channel_id / account_id / ...
    private String timezone;
    private Map<String, Object> payload;
    private Instant createdAt;
    private int retryCount;

    // === Kafka routing ===
    private String topic;              // 目標 topic
    private String partitionKey;       // Kafka partition key（null → round-robin）
}
```

> **注意：** 移除了 `pipelineId` 和 `stepIndex`。新增了 `sourceJobType`。
> 失敗時 payload 會帶 `originalTopic`、`originalKey`、`originalAction`、`error` 供 RetryDispatchJob 使用。

### 5.6 部署管理（各自獨立 + ChannelJob 參數化）

```
核心概念：
  每支 JOB 是獨立的 Spring Boot application（獨立 JAR、獨立 Dockerfile、獨立 container）
  JOB 之間唯一的耦合是 Kafka topic — 不互相呼叫、不共享 process

  唯一特例 — ChannelJob（平台 JOB）：
    同一份程式部署成多個 instance，每個 instance 吃不同 topic
    這是為了開發邏輯性的統一（所有平台 ActionService 在同一個專案）

  部署方式不限定：
    Docker Compose — 每個 JOB 一個 service（ChannelJob 多個 service 同 image 不同 env）
    K8s / Helm     — 每個 JOB 一個 Deployment
    裸機 / systemd — 每個 JOB 一個 systemd unit
```

```yaml
# docker-compose.yml 服務對照（見 §9.1）

# === simpleec-api（前端 API）===
# 獨立 Spring Boot，不消費 Kafka
SERVER_PORT=8080

# === simpleec-gateway（對外 API）===
# 獨立 Spring Boot，不消費 Kafka（Webhook + ERP）
SERVER_PORT=8081

# === simpleec-channel-job（同 CODE 不同設定，每個平台 × 每個速度 = 一個 service）===
# service: simpleec-channel-momo-fast
JOB_CHANNEL_TOPICS=momo.fast
JOB_CHANNEL_GROUP_ID=channel-job-momo-fast
JOB_CHANNEL_CONCURRENCY=8

# service: simpleec-channel-momo-slow
JOB_CHANNEL_TOPICS=momo.slow
JOB_CHANNEL_GROUP_ID=channel-job-momo-slow
JOB_CHANNEL_CONCURRENCY=4

# service: simpleec-channel-shopee-fast
JOB_CHANNEL_TOPICS=shopee.fast
JOB_CHANNEL_GROUP_ID=channel-job-shopee-fast
JOB_CHANNEL_CONCURRENCY=8

# service: simpleec-channel-shopee-slow
JOB_CHANNEL_TOPICS=shopee.slow
JOB_CHANNEL_GROUP_ID=channel-job-shopee-slow
JOB_CHANNEL_CONCURRENCY=4

# ... yahoo-fast/slow, pchome-fast/slow 同理（共 8 個 ChannelJob service）

# === 以下各自獨立 Spring Boot ===

# service: simpleec-order-job
JOB_ORDER_PROCESS_CONCURRENCY=4

# service: simpleec-scheduler-job
SCHEDULER_HEARTBEAT_INTERVAL_MS=1000

# service: simpleec-backend-job
JOB_BACKEND_CONCURRENCY=4

# service: simpleec-frontend-job
JOB_FRONTEND_CONCURRENCY=2

# service: simpleec-retry-job
JOB_RETRY_DISPATCH_CONCURRENCY=2
```

```
擴容方式：
  momo 限速被打 → docker compose scale simpleec-channel-momo-slow=2
  雙十一訂單暴增 → docker compose scale simpleec-order-job=3
  同一個 consumer group → Kafka 自動 rebalance → 不需要改 code

  核心都一樣：同 consumer group + 同 topics → Kafka 自動分配 partition
```

### 5.7 完整流程圖（各 JOB 獨立 Spring Boot，Kafka topic 介接）

```
用戶瀏覽器                            外部（通路平台 / ERP）
  │                                      │
  ▼                                      ▼
┌──────────────────────┐  ┌──────────────────────────┐
│  simpleec-admin      │  │  simpleec-gateway        │
│  (Nginx)             │  │  (對外 API :8081)        │
│  / → Vue 3 SPA      │  │  Webhook 驗簽 → Kafka    │
│  /api → simpleec-api │  │  ERP JWT → CRUD / Kafka  │
└──────┬───────────────┘  └──────┬───────────────────┘
       │ /api                    │ TaskProducer
       ▼                         │
┌──────────────────────┐         │
│  simpleec-api        │         │
│  (前端 API :8080)    │         │
│  看平台/塞設定/看資料 │         │
│  丟事件 → Kafka      │         │
└──────┬───────────────┘         │
       │ TaskProducer            │
       └──────────┬──────────────┘
                  ▼
═══════════════════════════════════════════════════════════════
  Kafka Topics（所有 JOB 之間唯一的耦合）
═══════════════════════════════════════════════════════════════

┌─ simpleec-scheduler-job（獨立 Spring Boot）─────────────────┐
│  HeartbeatTimer @Scheduled 每 N 秒                       │
│  → send "scheduler" topic                                │
│  SchedulerJob @KafkaListener("scheduler")                │
│  → 判斷時間該做什麼                                       │
│  → 查 DB 已啟用通路                                       │
│  → 直接發到各目標 topic（momo.slow / task.backend / ...）  │
└──────────────────────────────────────────────────────────┘
       │
       ▼
  ┌─ per-channel Topics ──────────────────────────────────────┐
  │ momo.fast    (8p) ──┐                                     │
  │ shopee.fast  (8p) ──┤                                     │
  │ yahoo.fast   (8p) ──┤                                     │
  │ pchome.fast  (8p) ──┘                                     │
  │                      ▼                                     │
  │         ┌─ simpleec-channel-job fast instance ──────────┐    │
  │         │  ChannelJob → ActionFactory → ActionService │    │
  │         │  改價/改量/出貨/上下架                        │    │
  │         └────────────────────────────────────────────┘    │
  │                                                           │
  │ momo.slow   (8p) → simpleec-channel-job (momo-slow instance) │
  │ shopee.slow (8p) → simpleec-channel-job (shopee-slow inst.)  │
  │ yahoo.slow  (8p) → simpleec-channel-job (yahoo-slow inst.)   │
  │ pchome.slow (8p) → simpleec-channel-job (pchome-slow inst.)  │
  │                     拉單/庫存同步/拉退貨                    │
  └───────────────────────────────────────────────────────────┘
       │ FetchOrders → Hash Dedup → send "order.process"
       ▼
  order.process (8p)
       │
       ▼
┌─ simpleec-order-job（獨立 Spring Boot）────────────────────┐
│  OrderProcessJob @KafkaListener("order.process")         │
│  → DB upsert + Hash 更新 + 狀態變更 → send "task.backend"│
└──────────────────────────────────────────────────────────┘
       │
       ▼
  task.backend (8p)
       │
       ▼
┌─ simpleec-backend-job（獨立 Spring Boot）──────────────────┐
│  BackendJob → BackendActionFactory → BackendActionService│
│  報表/彙整/通知（Factory 分派 25+ Action）               │
└──────────────────────────────────────────────────────────┘

  task.frontend (8p) → simpleec-frontend-job（獨立 Spring Boot）
  task.failed   (4p) → simpleec-retry-job（獨立 Spring Boot）

  ★ 自治路由: JOB 完成 → 自主決定 TaskProducer.send() 到下一個 topic
  ★ 失敗: 所有 JOB 失敗 → ack + send "task.failed"
  ★ API 不等 JOB 結果，完全非同步
```

### 5.8 Redis Key（完整）

```
jwt:blacklist:{tokenHash}                         → "1"          (JWT 登出黑名單，TTL = token 剩餘有效期)
order:hash:{merchantId}:{channelId}:{orderId}     → SHA-256 hash (訂單去重，TTL 7 天，ChannelJob + OrderProcessJob 兩邊 SYNC)

注意：
  - 移除了 task:lock / task:schedules 相關 key（排程不再靠 DB + Redis 鎖）
  - 排程邏輯在 SchedulerJob 程式裡，不需要外部快取
  - Kafka partition key 天然保證同 key 有序，不需要額外 dedup key
```

---

## 6. 排程設計（事件驅動，無資料表）

> **心跳那一支相當簡單，它是單純的發送者，不受任何限制。**
> 心跳發送訊號後（送到 scheduler topic），對應的 consumer 就會知道這個時間該做什麼。
> 不需要資料表。工作內容和流程依賴在 TOPIC 上的事件和邏輯，不是 SQL。

### 6.1 心跳 = 純發送者

```java
/**
 * 心跳 Timer — 純發送者，不受任何限制
 *
 * 唯一的 @Scheduled — 心跳頻率可設定
 * 它只做一件事：每 N 毫秒發一個 tick 到 scheduler topic
 * 不判斷時間、不查 DB、不做任何邏輯
 *
 * 參考舊系統 while + sleep 模式：
 *   MainManager extends Thread {
 *       while(true) { poll(); process(); Thread.sleep(heartbeat); }
 *   }
 * 新系統用 @Scheduled 取代 while+sleep，心跳一樣可設定。
 */
@Component
@Slf4j
public class HeartbeatTimer {

    private final TaskProducer taskProducer;

    @Scheduled(fixedDelayString = "${scheduler.heartbeat.interval-ms:1000}")
    public void tick() {
        TaskMessage tick = TaskMessage.builder()
            .messageId(UUID.randomUUID().toString())
            .taskType("heartbeat")
            .taskAction("TICK")
            .sourceJobType("heartbeat-timer")
            .createdAt(Instant.now())
            .build();

        taskProducer.send("scheduler", null, tick);  // 無 key，round-robin
    }
}
```

```yaml
# application.yml
scheduler:
  heartbeat:
    interval-ms: 1000    # 預設 1 秒，可調成 500ms 或 2000ms
```

### 6.2 SchedulerJob — 收到心跳後決定時間→直接分發到各目標 topic

> **SchedulerJob 同時負責「判斷時間」和「分發到各目標 topic」。**
> 不需要中間的 task.dispatch topic 和 TaskDispatchJob — 二合一。
>
> **★ 時區很重要：** 統計/報表的觸發時間看的是**客戶時區**（merchant.user_local_time_zone），
> 不是 server 的 crontab。每個 merchant 的「午夜」時間點不同，
> 所以 SchedulerJob 用心跳逐一判斷每個 merchant 的本地時間，而不是 crontab 統一觸發。

```java
/**
 * 排程 JOB — 收到心跳 tick，根據「現在的時間」決定該做什麼
 * → 查 DB 已啟用通路 → 直接發到各目標 topic
 *
 * 排程邏輯寫在程式裡（ScheduleConfig + ScheduleRule）
 * 分發邏輯也在這支（查 channelService → 發到各平台 topic / task.backend）
 */
@Component
@Slf4j
public class SchedulerJob {

    private final TaskProducer taskProducer;
    private final ScheduleConfig config;
    private final ChannelService channelService;  // 查 DB 已啟用的通路

    // 記錄上次觸發時間（memory-first，不需要 Redis）
    private final ConcurrentHashMap<String, Long> lastTriggerCache = new ConcurrentHashMap<>();

    @KafkaListener(
        topics = "scheduler",
        groupId = "scheduler-job",
        concurrency = "${job.scheduler.concurrency:1}")  // 排程只需要 1 個 consumer
    public void handle(TaskMessage tick, Acknowledgment ack) {
        Instant now = tick.getCreatedAt();
        long nowEpoch = now.getEpochSecond();

        // 遍歷所有排程規則，判斷哪些該觸發
        for (ScheduleRule rule : config.getRules()) {
            try {
                Long lastEpoch = lastTriggerCache.get(rule.getId());

                if (lastEpoch != null && (nowEpoch - lastEpoch) < rule.getMinGapSeconds()) {
                    continue;
                }

                if (shouldTrigger(rule, now, lastEpoch, nowEpoch)) {
                    // 直接分發到各目標 topic（不經過中間 topic）
                    dispatch(rule, now);
                    lastTriggerCache.put(rule.getId(), nowEpoch);

                    log.info("Schedule triggered: {} at {} (tz={})",
                        rule.getAction(), now.atZone(ZoneId.of(rule.getTimezone())).toLocalTime(),
                        rule.getTimezone());
                }
            } catch (Exception e) {
                log.error("Schedule error for {}: {}", rule.getId(), e.getMessage());
            }
        }

        ack.acknowledge();
    }

    /**
     * 分發邏輯 — 判斷完「該做什麼」後，直接發到目標 topic
     */
    private void dispatch(ScheduleRule rule, Instant now) {
        switch (rule.getAction()) {
            case "FETCH_ALL_ORDERS" -> {
                // 查 DB 所有已啟用的通路 → 分別發到各平台的 slow topic
                List<Channel> channels = channelService.listEnabled(rule.getMerchantId());
                for (Channel ch : channels) {
                    String topic = ch.getChannelType().name().toLowerCase() + ".slow";
                    TaskMessage fetchMsg = TaskMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .taskType("channel_action")
                        .taskAction("FETCH_ORDERS")
                        .sourceJobType("scheduler-job")
                        .merchantId(rule.getMerchantId())
                        .ownerId(String.valueOf(ch.getId()))
                        .createdAt(now)
                        .build();
                    taskProducer.send(topic, null, fetchMsg);  // 拉單無 key
                }
            }
            case "CHECK_ALL_HEALTH" -> {
                // ★ 通路健康檢查 — 獨立 fast action，結果寫 channel_sync_logs
                List<Channel> channels = channelService.listEnabled(rule.getMerchantId());
                for (Channel ch : channels) {
                    String topic = ch.getChannelType().name().toLowerCase() + ".fast";
                    TaskMessage healthMsg = TaskMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .taskType("channel_action")
                        .taskAction("CHECK_HEALTH")
                        .sourceJobType("scheduler-job")
                        .merchantId(rule.getMerchantId())
                        .ownerId(String.valueOf(ch.getId()))
                        .createdAt(now)
                        .build();
                    taskProducer.send(topic, ch.getId(), healthMsg);  // key=channelId 排隊
                }
            }
            case "SYNC_ALL_INVENTORY" -> {
                List<Channel> channels = channelService.listEnabled(rule.getMerchantId());
                for (Channel ch : channels) {
                    String topic = ch.getChannelType().name().toLowerCase() + ".slow";
                    TaskMessage syncMsg = TaskMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .taskType("channel_action")
                        .taskAction("GET_QUANTITY")
                        .sourceJobType("scheduler-job")
                        .merchantId(rule.getMerchantId())
                        .ownerId(String.valueOf(ch.getId()))
                        .createdAt(now)
                        .build();
                    taskProducer.send(topic, ch.getId() + ":" + rule.getMerchantId(), syncMsg);
                }
            }
            case "DAILY_STATISTICS" -> {
                // ★ 每日統計 — 依 merchant 時區判斷「哪個 merchant 的昨天結束了」
                // 不是用 server crontab！每個 merchant 的 timezone 不同，「午夜」時間點不同
                List<Merchant> merchants = merchantService.listActive();
                for (Merchant m : merchants) {
                    ZoneId tz = ZoneId.of(m.getUserLocalTimeZone());
                    ZonedDateTime localNow = now.atZone(tz);
                    // 只在客戶時區的 00:05~00:10 之間觸發（給 5 分鐘 buffer）
                    if (localNow.getHour() == 0 && localNow.getMinute() >= 5 && localNow.getMinute() <= 10) {
                        LocalDate yesterday = localNow.toLocalDate().minusDays(1);
                        TaskMessage statMsg = TaskMessage.builder()
                            .messageId(UUID.randomUUID().toString())
                            .taskType("backend")
                            .taskAction("DAILY_STATISTICS")
                            .sourceJobType("scheduler-job")
                            .merchantId(m.getMerchantId())
                            .timezone(m.getUserLocalTimeZone())
                            .payload(Map.of("statDate", yesterday.toString()))
                            .createdAt(now)
                            .build();
                        taskProducer.send("task.backend", m.getMerchantId(), statMsg);
                    }
                }
            }
            case "MANAGE_PARTITIONS" -> {
                // ★ DB Partition 自動管理 — 系統級，不分 merchant
                TaskMessage partMsg = TaskMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .taskType("backend")
                    .taskAction("MANAGE_PARTITIONS")
                    .sourceJobType("scheduler-job")
                    .createdAt(now)
                    .build();
                taskProducer.send("task.backend", "system", partMsg);
            }
            case "DAILY_REPORT", "YESTERDAY_ORDERS", "YESTERDAY_INVENTORY" -> {
                TaskMessage backendMsg = TaskMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .taskType("backend")
                    .taskAction(rule.getAction())
                    .sourceJobType("scheduler-job")
                    .merchantId(rule.getMerchantId())
                    .createdAt(now)
                    .build();
                taskProducer.send("task.backend", rule.getMerchantId(), backendMsg);
            }
            // ... 其他排程事件
        }
    }

    private boolean shouldTrigger(ScheduleRule rule, Instant now, Long lastEpoch, long nowEpoch) {
        if ("interval".equals(rule.getMode())) {
            if (lastEpoch == null) return true;
            return (nowEpoch - lastEpoch) >= rule.getIntervalSeconds();
        } else {
            ZoneId zone = ZoneId.of(rule.getTimezone());
            ZonedDateTime localNow = now.atZone(zone);
            LocalDateTime thisMinute = localNow.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES);

            String springCron = "0 " + rule.getCronExpression();
            CronExpression cron = CronExpression.parse(springCron);
            LocalDateTime nextAfterPrev = cron.next(thisMinute.minusMinutes(1));

            if (nextAfterPrev != null && nextAfterPrev.equals(thisMinute)) {
                if (lastEpoch == null) return true;
                return (nowEpoch - lastEpoch) >= 60;
            }
            return false;
        }
    }
}
```

### 6.3 排程規則設定（程式裡，不是 DB）

```java
/**
 * 排程設定 — 可以是 @Configuration + @Bean，也可以從 application.yml 讀
 * 重點：不需要 task_schedule 資料表
 * 如果需要動態修改，可以透過 API 重載設定
 */
@Configuration
@ConfigurationProperties(prefix = "schedule")
@Data
public class ScheduleConfig {

    private List<ScheduleRule> rules;
}

@Data
public class ScheduleRule {
    private String id;
    private String action;            // FETCH_ALL_ORDERS / CHECK_ALL_HEALTH / SYNC_ALL_INVENTORY / DAILY_REPORT / ...
    private String mode;              // "interval" / "cron"
    private int intervalSeconds;      // interval 模式
    private String cronExpression;    // cron 模式
    private String timezone;          // Asia/Taipei / Asia/Tokyo / ...
    private int minGapSeconds;        // 防抖
    private String merchantId;
    private Map<String, Object> payloadTemplate;
}
```

```yaml
# application.yml — 排程規則
schedule:
  rules:
    # === 通路拉單（24小時不間斷）===
    - id: fetch-orders
      action: FETCH_ALL_ORDERS
      mode: interval
      intervalSeconds: 300        # 每 5 分鐘
      timezone: Asia/Taipei
      minGapSeconds: 60
      merchantId: M001

    # === 通路健康檢查（每 10 分鐘）===
    - id: check-health
      action: CHECK_ALL_HEALTH
      mode: interval
      intervalSeconds: 600        # 每 10 分鐘
      timezone: Asia/Taipei
      minGapSeconds: 300
      merchantId: M001

    # === DB Partition 自動管理（每天一次）===
    - id: manage-partitions
      action: MANAGE_PARTITIONS
      mode: cron
      cronExpression: "0 4 * * *"     # UTC 04:00（台灣中午 12:00）
      timezone: UTC
      minGapSeconds: 3600
      # 系統級，不分 merchant — merchantId 不填

    # === 庫存同步（一天兩次）===
    - id: sync-inventory
      action: SYNC_ALL_INVENTORY
      mode: cron
      cronExpression: "0 8,14 * * *"   # 08:00, 14:00
      timezone: Asia/Taipei
      minGapSeconds: 3600
      merchantId: M001

    # === 每日營收報表 ===
    - id: daily-report
      action: DAILY_REPORT
      mode: cron
      cronExpression: "0 20 * * *"     # 每天 20:00
      timezone: Asia/Taipei
      minGapSeconds: 3600
      merchantId: M001
      payloadTemplate:
        reportType: daily_sales
        format: email

    # === 昨日訂單彙整 ===
    - id: yesterday-orders
      action: YESTERDAY_ORDERS
      mode: cron
      cronExpression: "0 8 * * *"      # 每天 08:00
      timezone: Asia/Taipei
      minGapSeconds: 3600
      merchantId: M001

    # === 昨日庫存彙整 ===
    - id: yesterday-inventory
      action: YESTERDAY_INVENTORY
      mode: cron
      cronExpression: "0 8 * * *"
      timezone: Asia/Taipei
      minGapSeconds: 3600
      merchantId: M001
```

```
為什麼不需要 task_schedule 資料表？

  1. 排程規則變動頻率很低 — 不像訂單那種高頻 CRUD
  2. 邏輯依賴在 TOPIC 上的事件 — SchedulerJob 只負責「時間到了發事件」
  3. 更新排程 = 改 yml + 重啟（或 API 重載），不需要動態 SQL
  4. 減少系統複雜度 — 少一張表、少一個 Entity、少一個 Mapper、少一個 Service
  5. 舊系統也不是用 DB 存排程 — 排程邏輯寫在程式裡

  如果未來需要 UI 動態修改排程：
    方案 A：加 API → 修改 yml → 觸發 @RefreshScope 重載
    方案 B：此時再加 task_schedule 表也不遲
```

### 6.4 時區差異的具體範例

```
場景 1：通路拉單 — interval 模式，24小時不間斷

  SchedulerJob 收到 tick
  → 檢查 "fetch-orders" rule: mode=interval, intervalSeconds=300
  → 上次觸發 5 分鐘前 → 該觸發了
  → SchedulerJob 查 DB 所有已啟用通路 → 分別發到 momo.slow, shopee.slow...

場景 2：業務報表 — cron 模式，時區各異

  SchedulerJob 收到 tick，now = UTC 12:00
  → 檢查 "daily-report" rule: cron="0 20 * * *", tz=Asia/Taipei
  → 台北 20:00 = UTC 12:00 → 匹配！
  → SchedulerJob 直接發 DAILY_REPORT 到 task.backend

場景 3：同樣 cron 不同時區

  rule A: tz=Asia/Taipei, cron="0 20 * * *" → UTC 12:00 觸發
  rule B: tz=Asia/Tokyo,  cron="0 20 * * *" → UTC 11:00 觸發（東京比台北早 1 小時）
```

### 6.5 為什麼不用 Quartz / xxl-job

| | Quartz | xxl-job | 本設計 (Heartbeat→SchedulerJob→各目標 topic) |
|---|---|---|---|
| 複雜度 | 高（11 張 DB 表） | 中（獨立服務） | 低（純程式 + yml） |
| 時區支援 | 要自己處理 | 要自己處理 | 核心設計 |
| 秒級精度 | ✅ | ✅ | ✅ |
| 分散式 | 需要 DB 鎖 | 內建 | 單 consumer（scheduler topic partition=1 保證） |
| 動態修改 | 複雜 | API 改 | 改 yml + 重載 |
| 額外依賴 | quartz-scheduler | 獨立 JVM | 無（純 Spring） |
| 適合場景 | 企業級大量排程 | 微服務叢集 | OMS 業務排程 |

**本設計的優勢：零額外依賴，時區是一等公民，無 DB 表，邏輯在程式裡。**

---

## 7. 通路串接設計

### 7.1 ChannelAdapter 介面（已有，完整）

```java
public interface ChannelAdapter {
    ChannelType getChannelType();
    boolean validateConnection(Map<String, String> credentials);

    // 商品
    String createListing(String channelId, SellPack sellPack, Map<String, Object> extraData);
    void updateListing(String channelId, SellPack sellPack, Map<String, Object> extraData);
    void updatePrice(String channelId, String channelProductId, BigDecimal price);
    void updateQuantity(String channelId, String channelProductId, int quantity);
    void startSelling(String channelId, String channelProductId);
    void stopSelling(String channelId, String channelProductId);

    // 訂單
    List<Order> fetchOrders(String channelId, Instant from, Instant to);
    void confirmShipment(String channelId, String channelOrderId, String trackingNumber, String logisticsCompany);
    void acceptCancellation(String channelId, String channelOrderId);
    String getShippingLabel(String channelId, String channelOrderId);
}
```

### 7.2 四家通路的 API 特性差異

| | momo | Shopee | Yahoo | PChome |
|---|---|---|---|---|
| **認證** | vendor_code + sign (HMAC) | partner_id + shop_id + sign (HMAC-SHA256) | app_key + access_token (OAuth 2.0) | vendor_code + HMAC |
| **拉單** | POST /VendorApi/OrderQuery | GET /api/v2/order/get_order_list | GET /api/order/list | GET /api/order/list |
| **出貨確認** | POST /VendorApi/ShipConfirm | POST /api/v2/logistics/ship_order | POST /api/order/ship | POST /api/order/ship |
| **庫存更新** | POST /VendorApi/GoodsStockModify | POST /api/v2/product/update_stock | PUT /api/product/stock | PUT /api/product/qty |
| **Webhook** | 無（只能輪詢） | 有（push 通知） | 有 | 有 |
| **Rate Limit** | 低（需注意） | 高（有明確限制文件） | 中 | 中 |
| **特殊** | 轉單模式 | 有 shop-level token refresh | OAuth token refresh | 寄倉模式 |

### 7.3 每家 Adapter 結構（以 momo 為例，已有骨架）

```
simpleec-channel/src/main/java/com/simpleec/channel/
├── ChannelAdapter.java              ← 介面
├── ChannelAdapterFactory.java       ← 工廠（自動注冊）
│
├── momo/
│   ├── MomoChannelAdapter.java      ← 已有骨架，需填實作
│   ├── MomoApiClient.java           ← HTTP 呼叫（用 OkHttp）
│   └── MomoDataMapper.java          ← momo JSON ↔ 統一 DTO
│
├── shopee/
│   ├── ShopeeChannelAdapter.java
│   ├── ShopeeApiClient.java
│   └── ShopeeDataMapper.java
│
├── yahoo/
│   ├── YahooChannelAdapter.java
│   ├── YahooApiClient.java
│   └── YahooDataMapper.java
│
└── pchome/
    ├── PchomeChannelAdapter.java
    ├── PchomeApiClient.java
    └── PchomeDataMapper.java
```

**每家三個檔案，職責清楚：**
- `XxxChannelAdapter` — 實作 ChannelAdapter 介面，編排流程
- `XxxApiClient` — 純 HTTP 呼叫 + 簽名計算（OkHttp）
- `XxxDataMapper` — 通路 JSON ↔ 統一 Entity 的轉換

### 7.4 通路任務粒度說明

> 通路任務有大有小。不是所有任務都能拆成最小粒度。

```
「小任務」— 單一操作，一步到位：
  CHECK_HEALTH        健康檢查（獨立）→ 1 次輕量 API call → 寫 LOG
  MODIFY_PRICE        改一個 SKU 的價格 → 1 次通路 API call
  MODIFY_QUANTITY     改一個 SKU 的庫存 → 1 次通路 API call
  START_SELLING       上架一個商品     → 1 次通路 API call
  STOP_SELLING        下架一個商品     → 1 次通路 API call
  SHIPPING_CONFIRMED  確認一筆出貨     → 1 次通路 API call

  特點：partition key 細（sellPackId / orderId / channelId），Kafka 天然保證同 key 有序

「大任務」— 批次操作，不能再拆：
  FETCH_ORDERS        以時間範圍 + 狀態篩選拉取訂單
                      → 1 次通路 API call（可能回傳 1~1000+ 筆）
                      → 不能拆成「每筆訂單一個事件」，因為平台 API 就是 list API
  GET_QUANTITY        拉取所有商品庫存
                      → 1~N 次 API call（視平台分頁）

  特點：partition key 粗（無 key 或 channelId:merchantId），一次完成

為什麼拉單不能拆？
  1. 各平台 API 設計就是「時間範圍 + 狀態」→ 批次回傳
     momo:   POST /VendorApi/OrderQuery  body: { dateFrom, dateTo, status }
     shopee: GET  /api/v2/order/get_order_list?time_from=&time_to=&status=
  2. 拆成「每筆訂單一個事件」→ 需要先知道有哪些訂單 → 還是要先拉一次
  3. 所以拉單就是以「時間點 + 狀態」為單位的大任務，一次抓完
  4. 抓完後在 FetchOrdersActionService 內部逐筆做 Hash Dedup → 只發有變動的到 order.process
```

### 7.5 Consumer 調度邏輯

```java
// ChannelJob 透過 ActionFactory 路由（不是 switch/case）
// 以 FetchOrdersActionService 為例：

public class FetchOrdersActionService implements ActionService {

    @Override
    public void setting(Resource resource) {
        this.adapter = resource.getAdapter();
        this.channel = resource.getChannel();
        this.redis = resource.getRedis();
        this.taskProducer = resource.getTaskProducer();
    }

    @Override
    public void getPlatformTokens() {
        // platform 的 credential1~N + channel 的 token1~token5 → 組合成 API 認證
        this.tokens = adapter.getTokens(channel);
    }

    @Override
    public void verifyNeedData() {
        // 確認 channel 已啟用、認證憑證有效
    }

    @Override
    public void doAction() {
        List<Order> orders = adapter.fetchOrders(channel.getId(), from, to);
        // Hash Dedup → 只發有變動的到 order.process
        for (Order order : orders) {
            String hashKey = "order:hash:" + merchantId + ":" + channelId + ":" + order.getPlatformOrderId();
            String newHash = DigestUtils.sha256Hex(JSONMapper.toJSON(order));
            if (!newHash.equals(redis.opsForValue().get(hashKey))) {
                taskProducer.send("order.process", channelId + ":" + merchantId, orderMsg);
            }
        }
    }
}
```

---

## 8. 前端設計（Vue 3）

### 8.1 技術選型

| 項目 | 選擇 | 理由 |
|------|------|------|
| 框架 | Vue 3 (Composition API) | 設計文件已定 |
| 建置 | Vite | 快 |
| UI 元件 | PrimeVue | DataTable 是 OMS 核心 |
| 狀態管理 | Pinia | 只管全域狀態（auth） |
| HTTP | Axios | 標配 |
| Router | Vue Router 4 | 標配 |
| 語言 | TypeScript | 型別安全 |

### 8.2 專案結構

```
simpleec-admin/
├── index.html
├── vite.config.ts
├── tsconfig.json
├── package.json
├── src/
│   ├── main.ts
│   ├── App.vue
│   │
│   ├── router/index.ts
│   │
│   ├── stores/
│   │   ├── auth.ts                  # JWT + 登入狀態
│   │   └── app.ts                   # sidebar, loading
│   │
│   ├── api/
│   │   ├── client.ts               # Axios instance + interceptors
│   │   ├── auth.ts                  # login, me, refresh
│   │   ├── channel.ts              # CRUD + test
│   │   ├── product.ts              # CRUD
│   │   ├── sellPack.ts             # CRUD + publish/unpublish
│   │   └── order.ts                # list, detail, ship, cancel
│   │
│   ├── types/
│   │   ├── auth.ts
│   │   ├── channel.ts
│   │   ├── product.ts
│   │   ├── sellPack.ts
│   │   └── order.ts
│   │
│   ├── views/
│   │   ├── LoginView.vue
│   │   ├── channel/
│   │   │   └── ChannelListView.vue
│   │   ├── product/
│   │   │   └── ProductListView.vue
│   │   ├── sellpack/
│   │   │   └── SellPackListView.vue
│   │   └── order/
│   │       ├── OrderListView.vue
│   │       └── OrderDetailView.vue
│   │
│   ├── components/
│   │   ├── layout/
│   │   │   ├── AppLayout.vue       # sidebar + topbar + router-view
│   │   │   ├── Sidebar.vue
│   │   │   └── Topbar.vue
│   │   └── common/
│   │       ├── StatusBadge.vue     # 狀態標籤（通用）
│   │       └── ChannelIcon.vue     # 通路 logo
│   │
│   ├── composables/
│   │   └── useDataTable.ts         # 分頁/排序/篩選封裝
│   │
│   └── utils/
│       ├── format.ts               # 金額、日期
│       └── constants.ts            # enum mapping
│
└── public/
    └── img/
        └── channels/               # momo.svg, shopee.svg, ...
```

### 8.3 頁面設計

#### 首頁 Dashboard（DashboardView.vue）— 登入後首頁

```
┌────────┬─────────────────────────────────────────────────────────┐
│ Sidebar│  Dashboard                   日期: [2024/02/01] ~ [2024/02/08] │
│        │                                                         │
│ 首頁 ◄─│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│ 訂單   │  │ 營業額    │ │ 訂單數    │ │ 已出貨    │ │ 退貨/取消 │  │
│ 商品   │  │ $125,800 │ │   83     │ │   71     │ │   5 / 3  │  │
│ 賣場   │  │ ↑12%     │ │ ↑8%      │ │          │ │          │  │
│ 通路   │  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
│        │                                                         │
│        │  ─── 每日營業額趨勢 ───                                  │
│        │  ┌─────────────────────────────────────────────┐       │
│        │  │  $30k ─                    ╱╲               │       │
│        │  │  $20k ─              ╱─╲╱╱  ╲              │       │
│        │  │  $10k ─    ╱─╲╱─╲╱╱         ╲             │       │
│        │  │       ──┴───┴───┴───┴───┴───┴───┴──        │       │
│        │  │       02/01 02/02 ... 02/07 02/08          │       │
│        │  └─────────────────────────────────────────────┘       │
│        │                                                         │
│        │  ─── 通路健康狀態（三層判斷）───                            │
│        │  ┌──────┬──────┬──────────┬────────────┬──────┐       │
│        │  │通路   │健康度 │原因       │最後同步     │啟用   │       │
│        │  ├──────┼──────┼──────────┼────────────┼──────┤       │
│        │  │momo冷凍│ 🟢   │ 暢通     │ 3 分前     │ ✓    │       │
│        │  │momo一般│ 🟡   │ Token過期│ 3 分前     │ ✓    │       │
│        │  │pchome │ 🔴   │ API 無回應│ 2 小時前   │ ✓    │       │
│        │  └──────┴──────┴──────────┴────────────┴──────┘       │
│        │  ※ 🟢 OK = API+Token 暢通                              │
│        │  ※ 🟡 TOKEN_INVALID = API 活著但 Token 不對（要重新授權）  │
│        │  ※ 🔴 API_DOWN = 通路 API 掛了（等平台恢復）              │
└────────┴─────────────────────────────────────────────────────────┘
```

> **Dashboard 不即時查 orders 表** — 查 daily_statistics 表（BackendJob 每日預彙整）。
> 日期區間選擇器 = SUM daily records。最小單位 = 一天。
> 今天的資料會等明天凌晨彙整（客戶時區），所以「今天」的數字是到昨天為止的。
> 通路健康狀態三層判斷（★ 由獨立的 CHECK_HEALTH action 檢查，不是附帶在其他操作裡）：
> - SchedulerJob 每 10 分鐘 → {channel}.fast → CheckHealthActionService
> - 結果寫 channel_sync_logs（sync_type=CHECK_HEALTH, health=OK/TOKEN_INVALID/API_DOWN）
> - Dashboard 查 channel_sync_logs 最近一筆 CHECK_HEALTH 記錄 + channel.is_active
> - 🟢 OK = API + Token 暢通  🟡 TOKEN_INVALID = Token 不對  🔴 API_DOWN = API 掛了

#### 登入頁（LoginView.vue）

```
┌──────────────────────────────┐
│                              │
│         SimpleEC OMS            │
│                              │
│    ┌──────────────────┐      │
│    │ 帳號             │      │
│    └──────────────────┘      │
│    ┌──────────────────┐      │
│    │ 密碼             │      │
│    └──────────────────┘      │
│    ┌──────────────────┐      │
│    │     登入          │      │
│    └──────────────────┘      │
│                              │
└──────────────────────────────┘
```

#### 訂單列表（OrderListView.vue）— 最重要的頁面

```
┌────────┬─────────────────────────────────────────────┐
│ Sidebar│  訂單管理                                    │
│        │                                             │
│ 訂單 ◄─│  [momo] [shopee] [yahoo] [pchome] [全部]    │  ← 通路篩選 chips
│ 商品   │  狀態: [全部 ▼]  日期: [____] ~ [____]      │  ← 篩選列
│ 賣場   │                                             │
│ 通路   │  ┌─────┬──────┬────┬────┬──────┬────┐      │
│        │  │通路  │訂單編號│狀態 │金額  │下單時間│操作│      │
│        │  ├─────┼──────┼────┼────┼──────┼────┤      │
│        │  │momo │M2024..│新訂單│$599 │02/08..│ 詳情│      │
│        │  │shopee│SH024..│已出貨│$1200│02/07..│ 詳情│      │
│        │  │yahoo│YA024..│已完成│$350 │02/06..│ 詳情│      │
│        │  └─────┴──────┴────┴────┴──────┴────┘      │
│        │                                             │
│        │  ◄ 1 2 3 ... 10 ►   共 195 筆  每頁 20 ▼   │
└────────┴─────────────────────────────────────────────┘
```

#### 訂單詳情（OrderDetailView.vue）

```
┌────────┬─────────────────────────────────────────────┐
│ Sidebar│  訂單詳情 #M20240208001                      │
│        │                                             │
│        │  通路: momo    狀態: [新訂單]  ← StatusBadge │
│        │                                             │
│        │  ─── 收件人 ───                              │
│        │  姓名: 王小明                                │
│        │  電話: 0912-345-678                          │
│        │  地址: 台北市...                              │
│        │                                             │
│        │  ─── 商品明細 ───                             │
│        │  ┌────────┬────┬──────┬──────┐              │
│        │  │商品名稱  │數量│單價   │小計   │              │
│        │  ├────────┼────┼──────┼──────┤              │
│        │  │保濕面膜  │ 2  │$299  │$598  │              │
│        │  └────────┴────┴──────┴──────┘              │
│        │  運費: $60   折扣: -$59   合計: $599         │
│        │                                             │
│        │  ┌──────────┐ ┌──────────┐                  │
│        │  │  確認出貨  │ │  取消訂單  │                  │
│        │  └──────────┘ └──────────┘                  │
└────────┴─────────────────────────────────────────────┘
```

#### 通路管理（ChannelListView.vue）

```
★ 階層：先平台（platform）再通路（channel）
  平台 = momo / shopee / yahoo / pchome（API 憑證）
  通路 = momo冷凍 / momo一般 / pchome電子（具體賣場）

┌────────┬─────────────────────────────────────────────────────┐
│ Sidebar│  通路管理             [+ 新增平台]                    │
│        │                                                     │
│        │  ▼ momo（平台）            [編輯平台] [+ 新增通路]     │
│        │  ┌──────────┬──────┬──────┬───────────┬─────┐       │
│        │  │通路       │狀態   │健康度 │最後同步    │操作  │       │
│        │  ├──────────┼──────┼──────┼───────────┼─────┤       │
│        │  │momo冷凍   │已啟用 │ 🟢   │ 3 分前    │編輯  │       │
│        │  │momo一般   │已啟用 │ 🟢   │ 3 分前    │編輯  │       │
│        │  └──────────┴──────┴──────┴───────────┴─────┘       │
│        │                                                     │
│        │  ▼ pchome（平台）          [編輯平台] [+ 新增通路]     │
│        │  ┌──────────┬──────┬──────┬───────────┬─────┐       │
│        │  │通路       │狀態   │健康度 │最後同步    │操作  │       │
│        │  ├──────────┼──────┼──────┼───────────┼─────┤       │
│        │  │pchome電子 │已啟用 │ 🔴   │ 2 小時前  │編輯  │       │
│        │  └──────────┴──────┴──────┴───────────┴─────┘       │
│        │                                                     │
│        │  ▶ yahoo（平台，未展開，尚無通路）                     │
│        │                                                     │
│        │  點「編輯平台」→ Dialog 顯示平台認證設定                │
│        │  ┌─────────────────────────────┐                   │
│        │  │  momo 平台設定（platform）       │                │
│        │  │  認證1: [*****]  [顯示]       │                  │
│        │  │  認證2: [*****]  [顯示]       │                  │
│        │  │  （各平台欄位數不同，可擴充）   │                  │
│        │  │  [測試連線]  [儲存]              │                  │
│        │  └─────────────────────────────┘                   │
│        │  ★ platform 存第三方認證（credential1~N，先 2 欄）    │
│        │                                                     │
│        │  點「編輯」通路 → Dialog 顯示通路設定 + Token          │
│        │  ┌─────────────────────────────┐                   │
│        │  │  momo冷凍 通路設定（channel）  │                   │
│        │  │  通路名稱: [momo冷凍]          │                  │
│        │  │  通路編號: [CH001]             │                  │
│        │  │  Token 1:  [*****]  [顯示]    │                  │
│        │  │  Token 2:  [*****]             │                  │
│        │  │  [同步商品]  [儲存]             │                  │
│        │  └─────────────────────────────┘                   │
│        │  ★ token 存在 channel 層級（各館各自的授權 token）    │
│        │  「同步商品」→ 發到 {platform}.slow (key=channelId)  │
│        │  → ChannelJob FETCH_PRODUCTS → upsert sell_pack    │
└────────┴─────────────────────────────────────────────────────┘
```

#### 排程狀態（平台設定 Dialog Tab — 唯讀顯示）

```
┌─────────────────────────────────────────────────────┐
│ momo 平台設定                                        │
│                                                     │
│ [基本設定] [排程狀態]                                  │
│                                                     │
│ 排程設定在 application.yml 管理，此處為唯讀顯示       │
│                                                     │
│ ┌──────────┬──────┬───────────┬────────┐            │
│ │任務       │模式   │設定        │上次觸發 │            │
│ ├──────────┼──────┼───────────┼────────┤            │
│ │拉取訂單   │間隔   │每 300 秒   │2分前    │            │
│ │同步庫存   │Cron  │8:00,14:00 │6小時前  │            │
│ └──────────┴──────┴───────────┴────────┘            │
│                                                     │
│ [手動觸發拉單]                                       │
└─────────────────────────────────────────────────────┘
```

#### 商品列表 / 賣場檔列表

```
商品列表（ProductListView.vue）
  DataTable: 料號、商品名稱、成本、總庫存、安全庫存、狀態
  操作: 編輯（inline 或 dialog）、查看賣場檔

賣場檔列表（SellPackListView.vue）
  DataTable: 通路、商品名稱、通路商品名、售價、庫存、上架狀態、同步狀態
  操作: 上架/下架、編輯價格庫存
  篩選: 通路 chips + 上架狀態
```

### 8.4 核心 Composable — useDataTable

```typescript
// composables/useDataTable.ts
import { ref, reactive, onMounted } from 'vue'

interface QueryParams {
  page: number
  size: number
  sortField?: string
  sortOrder?: 'asc' | 'desc'
  filters: Record<string, any>
}

interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export function useDataTable<T>(
  fetchFn: (params: QueryParams) => Promise<PageResult<T>>
) {
  const data = ref<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  const totalRecords = ref(0)

  const params = reactive<QueryParams>({
    page: 0,
    size: 20,
    filters: {},
  })

  async function load() {
    loading.value = true
    try {
      const result = await fetchFn(params)
      data.value = result.records
      totalRecords.value = result.total
    } finally {
      loading.value = false
    }
  }

  function onPage(event: { page: number; rows: number }) {
    params.page = event.page
    params.size = event.rows
    load()
  }

  function onSort(event: { sortField: string; sortOrder: number }) {
    params.sortField = event.sortField
    params.sortOrder = event.sortOrder === 1 ? 'asc' : 'desc'
    load()
  }

  function setFilter(key: string, value: any) {
    params.filters[key] = value
    params.page = 0
    load()
  }

  function refresh() {
    load()
  }

  onMounted(load)

  return {
    data, loading, totalRecords, params,
    onPage, onSort, setFilter, refresh
  }
}
```

### 8.5 API 層範例

```typescript
// api/client.ts
import axios from 'axios'
import { useAuthStore } from '@/stores/auth'

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 30000,
})

apiClient.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

apiClient.interceptors.response.use(
  (res) => res.data,
  (error) => {
    if (error.response?.status === 401) {
      const auth = useAuthStore()
      auth.sessionExpired = true  // 不自動登出，顯示 banner
    }
    return Promise.reject(error)
  }
)

export default apiClient
```

```typescript
// api/order.ts
import apiClient from './client'
import type { Order, OrderItem } from '@/types/order'
import type { PageResult, QueryParams } from '@/types/common'

export async function listOrders(params: QueryParams): Promise<PageResult<Order>> {
  return apiClient.get('/orders', { params: {
    merchantId: params.filters.merchantId,
    channelId: params.filters.channelId,
    status: params.filters.status,
    page: params.page,
    size: params.size,
    sortField: params.sortField,
    sortOrder: params.sortOrder,
  }})
}

export async function getOrder(id: string): Promise<Order> {
  return apiClient.get(`/orders/${id}`)
}

export async function shipOrder(orderId: string, trackingNumber: string, company: string) {
  return apiClient.post(`/orders/${orderId}/ship`, { trackingNumber, company })
}

export async function cancelOrder(orderId: string, reason: string) {
  return apiClient.post(`/orders/${orderId}/cancel`, { reason })
}
```

### 8.6 路由

```typescript
// router/index.ts
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('@/components/layout/AppLayout.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('@/views/dashboard/DashboardView.vue') },
        { path: 'orders', component: () => import('@/views/order/OrderListView.vue') },
        { path: 'orders/:id', component: () => import('@/views/order/OrderDetailView.vue') },
        { path: 'products', component: () => import('@/views/product/ProductListView.vue') },
        { path: 'sell-packs', component: () => import('@/views/sellpack/SellPackListView.vue') },
        { path: 'channels', component: () => import('@/views/channel/ChannelListView.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.token) return '/login'
})

export default router
```

---

## 9. 部署架構

### 9.1 docker-compose（各 JOB 獨立 service）

```yaml
# === 共用環境變數 ===
x-common-env: &common-env
  DB_HOST: postgres
  DB_PORT: 5432
  DB_NAME: simpleec
  DB_USER: simpleec
  DB_PASSWORD: simpleec123
  REDIS_HOST: redis
  REDIS_PORT: 6379
  KAFKA_BOOTSTRAP_SERVERS: kafka:9092

x-common-depends: &common-depends
  postgres: { condition: service_healthy }
  redis: { condition: service_healthy }
  kafka: { condition: service_healthy }

services:
  # ═══ 基礎設施 ═══
  postgres:
    image: postgres:16-alpine
    ports: ["5433:5432"]
    environment:
      POSTGRES_DB: simpleec
      POSTGRES_USER: simpleec
      POSTGRES_PASSWORD: simpleec123
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./docker/init-db:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U simpleec"]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]

  kafka:
    image: bitnami/kafka:3.7
    ports: ["9092:9092", "9093:9093"]
    environment:
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_PROCESS_ROLES: broker,controller
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CFG_LOG_RETENTION_MS: -1
      KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: false
    volumes:
      - kafka_data:/bitnami/kafka
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"]

  # ═══ 前端 API（獨立 Spring Boot）═══
  simpleec-api:
    build:
      context: .
      dockerfile: simpleec-api/Dockerfile
    ports: ["8080:8080"]
    environment:
      <<: *common-env
      SERVER_PORT: 8080
    depends_on: *common-depends

  # ═══ 對外 API — Webhook + ERP（獨立 Spring Boot）═══
  simpleec-gateway:
    build:
      context: .
      dockerfile: simpleec-gateway/Dockerfile
    ports: ["8081:8081"]
    environment:
      <<: *common-env
      SERVER_PORT: 8081
    depends_on: *common-depends

  # ═══ ChannelJob — 同 CODE 不同設定（每個平台 × 每個速度 = 一個 service）═══
  simpleec-channel-momo-fast:
    build:
      context: .
      dockerfile: simpleec-channel-job/Dockerfile
    environment:
      <<: *common-env
      JOB_CHANNEL_TOPICS: momo.fast
      JOB_CHANNEL_GROUP_ID: channel-job-momo-fast
      JOB_CHANNEL_CONCURRENCY: 8
    depends_on: *common-depends

  simpleec-channel-shopee-fast:
    build:
      context: .
      dockerfile: simpleec-channel-job/Dockerfile
    environment:
      <<: *common-env
      JOB_CHANNEL_TOPICS: shopee.fast
      JOB_CHANNEL_GROUP_ID: channel-job-shopee-fast
      JOB_CHANNEL_CONCURRENCY: 8
    depends_on: *common-depends

  simpleec-channel-yahoo-fast:
    build:
      context: .
      dockerfile: simpleec-channel-job/Dockerfile
    environment:
      <<: *common-env
      JOB_CHANNEL_TOPICS: yahoo.fast
      JOB_CHANNEL_GROUP_ID: channel-job-yahoo-fast
      JOB_CHANNEL_CONCURRENCY: 8
    depends_on: *common-depends

  simpleec-channel-pchome-fast:
    build:
      context: .
      dockerfile: simpleec-channel-job/Dockerfile
    environment:
      <<: *common-env
      JOB_CHANNEL_TOPICS: pchome.fast
      JOB_CHANNEL_GROUP_ID: channel-job-pchome-fast
      JOB_CHANNEL_CONCURRENCY: 8
    depends_on: *common-depends

  simpleec-channel-momo-slow:
    build:
      context: .
      dockerfile: simpleec-channel-job/Dockerfile
    environment:
      <<: *common-env
      JOB_CHANNEL_TOPICS: momo.slow
      JOB_CHANNEL_GROUP_ID: channel-job-momo-slow
      JOB_CHANNEL_CONCURRENCY: 4
    depends_on: *common-depends

  simpleec-channel-shopee-slow:
    build:
      context: .
      dockerfile: simpleec-channel-job/Dockerfile
    environment:
      <<: *common-env
      JOB_CHANNEL_TOPICS: shopee.slow
      JOB_CHANNEL_GROUP_ID: channel-job-shopee-slow
      JOB_CHANNEL_CONCURRENCY: 4
    depends_on: *common-depends

  simpleec-channel-yahoo-slow:
    build:
      context: .
      dockerfile: simpleec-channel-job/Dockerfile
    environment:
      <<: *common-env
      JOB_CHANNEL_TOPICS: yahoo.slow
      JOB_CHANNEL_GROUP_ID: channel-job-yahoo-slow
      JOB_CHANNEL_CONCURRENCY: 4
    depends_on: *common-depends

  simpleec-channel-pchome-slow:
    build:
      context: .
      dockerfile: simpleec-channel-job/Dockerfile
    environment:
      <<: *common-env
      JOB_CHANNEL_TOPICS: pchome.slow
      JOB_CHANNEL_GROUP_ID: channel-job-pchome-slow
      JOB_CHANNEL_CONCURRENCY: 4
    depends_on: *common-depends

  # ═══ 各自獨立的 JOB ═══
  simpleec-order-job:
    build:
      context: .
      dockerfile: simpleec-order-job/Dockerfile
    environment:
      <<: *common-env
      JOB_ORDER_PROCESS_CONCURRENCY: 4
    depends_on: *common-depends

  simpleec-scheduler-job:
    build:
      context: .
      dockerfile: simpleec-scheduler-job/Dockerfile
    environment:
      <<: *common-env
      SCHEDULER_HEARTBEAT_INTERVAL_MS: 1000
    depends_on: *common-depends

  simpleec-backend-job:
    build:
      context: .
      dockerfile: simpleec-backend-job/Dockerfile
    environment:
      <<: *common-env
      JOB_BACKEND_CONCURRENCY: 4
    depends_on: *common-depends

  simpleec-frontend-job:
    build:
      context: .
      dockerfile: simpleec-frontend-job/Dockerfile
    environment:
      <<: *common-env
      JOB_FRONTEND_CONCURRENCY: 2
    depends_on: *common-depends

  simpleec-retry-job:
    build:
      context: .
      dockerfile: simpleec-retry-job/Dockerfile
    environment:
      <<: *common-env
      JOB_RETRY_DISPATCH_CONCURRENCY: 2
    depends_on: *common-depends

  # ═══ 前端 ═══
  simpleec-admin:
    build:
      context: ./simpleec-admin
      dockerfile: Dockerfile
    ports: ["80:80"]
    depends_on: [simpleec-api]

volumes:
  pgdata:
  kafka_data:
```

### 9.2 架構圖（各 JOB 獨立 container，Kafka topic 介接）

```
    ┌───────────────────────────┐       ┌──────────────────────────────┐
    │  simpleec-admin (Nginx)   │       │  外部流量                      │
    │  :80                      │       │  通路平台 Webhook / ERP 系統   │
    │  / → Vue 3 SPA            │       └──────────┬───────────────────┘
    │  /api → simpleec-api      │                  │
    └──────────┬────────────────┘                  │
               │                                   │
               ▼                                   ▼
    ┌───────────────────────┐       ┌───────────────────────────┐
    │  simpleec-api         │       │  simpleec-gateway         │
    │  (前端 API)           │       │  (對外 API)               │
    │  :8080                │       │  :8081                    │
    │  REST + JWT           │       │  Webhook + ERP            │
    │  看平台/塞設定/看資料  │       │  驗簽/JWT → 丟 Kafka      │
    │  丟事件 → Kafka       │       │                           │
    └──────────┬────────────┘       └──────────┬────────────────┘
               │ TaskProducer                  │ TaskProducer
               └──────────┬────────────────────┘
                          ▼
          ┌────────────────────────────────────────────────────┐
          │              Kafka (KRaft :9092)                    │
          │                                                    │
          │  ┌─ per-channel topics ────────────────────────┐   │
          │  │  momo.fast / shopee.fast / yahoo.fast / ... │   │
          │  │  momo.slow / shopee.slow / yahoo.slow / ... │   │
          │  └─────────────────────────────────────────────┘   │
          │  scheduler / order.process                         │
          │  task.backend / task.frontend / task.failed         │
          └────────┬───────┬──────┬──────┬──────┬──────┬───────┘
                   │       │      │      │      │      │
      ┌────────────┘       │      │      │      │      └────────────┐
      ▼                    ▼      ▼      ▼      ▼                   ▼
┌──────────────┐  ┌────────┐ ┌────┐ ┌────┐ ┌────────┐  ┌──────────┐
│ChannelJob    │  │Order   │ │Sche│ │Back│ │Front   │  │Retry     │
│(fast)        │  │Process │ │dule│ │end │ │end     │  │Dispatch  │
│              │  │Job     │ │rJob│ │Job │ │Job     │  │Job       │
│ChannelJob    │  │        │ │    │ │    │ │        │  │          │
│(momo-slow)   │  │        │ │HB  │ │Back│ │        │  │RetryPol. │
│              │  │        │ │Timr│ │end │ │        │  │Service   │
│ChannelJob    │  │        │ │    │ │Act.│ │        │  │          │
│(shopee-slow) │  │        │ │Chan│ │Fact│ │        │  │          │
│ ...          │  │        │ │Svc │ │    │ │        │  │          │
│              │  │        │ │    │ │    │ │        │  │          │
│ActionFactory │  │        │ │    │ │    │ │        │  │          │
│ActionService │  │        │ │    │ │    │ │        │  │          │
└──────┬───────┘  └────┬───┘ └──┬─┘ └──┬─┘ └───┬────┘  └─────┬────┘
       │               │       │      │       │              │
       └───────┬───────┴───────┴──────┴───────┴──────────────┘
               │
  ┌────────────┴────────────┬──────────┐
  │                         │          │
  ▼                         ▼          ▼
┌──────────────┐  ┌─────────┐  ┌──────────────┐
│ PostgreSQL   │  │ Redis   │  │ 通路 API      │
│ :5432        │  │ :6379   │  │ momo/shopee   │
│ 21 tables    │  │ cache   │  │ yahoo/pchome  │
│              │  │ jwt-bl  │  │               │
│              │  │ dedup   │  │               │
└──────────────┘  └─────────┘  └──────────────┘
```

### 9.3 docker compose up 服務清單

```
docker compose up 啟動後的 container 清單：

  基礎設施（3）：
    postgres              :5432
    redis                 :6379
    kafka                 :9092

  前端 API（1）：
    simpleec-api             :8080    ← 前端用（看平台/塞設定/看資料/丟事件）

  對外 API（1）：
    simpleec-gateway         :8081    ← 對外用（Webhook + ERP）

  ChannelJob — 同 image 不同 env（8 = 4 平台 × 2 速度）：
    simpleec-channel-momo-fast         topics=momo.fast
    simpleec-channel-momo-slow         topics=momo.slow
    simpleec-channel-shopee-fast       topics=shopee.fast
    simpleec-channel-shopee-slow       topics=shopee.slow
    simpleec-channel-yahoo-fast        topics=yahoo.fast
    simpleec-channel-yahoo-slow        topics=yahoo.slow
    simpleec-channel-pchome-fast       topics=pchome.fast
    simpleec-channel-pchome-slow       topics=pchome.slow

  獨立 JOB（5）：
    simpleec-order-job                 order.process
    simpleec-scheduler-job             scheduler（含 HeartbeatTimer + 分發邏輯）
    simpleec-backend-job               task.backend
    simpleec-frontend-job              task.frontend
    simpleec-retry-job                 task.failed

  前端（1）：
    simpleec-admin                     :80 (Nginx + Vue 3)

  可觀測性（7）：                                                ← NEW（見 §13）
    otel-collector                     :4317/:4318 (OTLP 收集器)
    tempo                              :3200 (Trace 儲存)
    loki                               :3100 (Log 聚合)
    prometheus                         :9090 (Metrics 儲存)
    grafana                            :3000 (統一儀表板)
    kafka-ui                           :8088 (Topic 瀏覽/管理)

  總計：26 個 container（19 業務 + 7 可觀測性）
```

---

## 10. 開發順序（建議）

> **最終目標：`docker compose up` 一次啟動 26 個 container（見 §9.3）。**
> 每個 Phase 結束都要確認 `docker compose up` 能正常跑，逐步加入新的 service 直到完整。

> **依據：** 現有 codebase 盤點（§0）。已有 Entity/Mapper/Service/Controller 骨架可用，
> 但 MQ 層是 RabbitMQ 需整套換成 Kafka，JOB 架構、排程、Redis 快取全部不存在。
> 各 JOB 是各自獨立的 Spring Boot application（見 §5.2），需重建 Gradle module 結構。

### MVP 定義

```
docker compose up 啟動後能看到：

  基礎設施（3 container）：
    postgres              DB 可連、schema 已建
    redis                 快取可用
    kafka                 broker 可連、topics 自動建立

  simpleec-api（1 container）：
    前端 API (REST + JWT)
    看平台、塞設定、看資料、丟事件到 Kafka

  simpleec-gateway（1 container）：
    對外 API (Webhook + ERP)
    Webhook 接收 → 驗簽 → 丟 Kafka
    ERP 串接 → JWT 認證 → CRUD / 丟 Kafka

  simpleec-channel-job（8 container = 4 平台 × 2 速度，同 image 不同 env）：
    simpleec-channel-momo-fast         topics = momo.fast
    simpleec-channel-momo-slow         topics = momo.slow
    simpleec-channel-shopee-fast       topics = shopee.fast
    simpleec-channel-shopee-slow       topics = shopee.slow
    simpleec-channel-yahoo-fast        topics = yahoo.fast
    simpleec-channel-yahoo-slow        topics = yahoo.slow
    simpleec-channel-pchome-fast       topics = pchome.fast
    simpleec-channel-pchome-slow       topics = pchome.slow

  獨立 JOB（5 container）：
    simpleec-order-job                 消費 order.process
    simpleec-scheduler-job             HeartbeatTimer + 消費 scheduler + 分發到各目標 topic
    simpleec-backend-job               消費 task.backend
    simpleec-frontend-job              消費 task.frontend
    simpleec-retry-job                 消費 task.failed

  simpleec-admin（1 container）：
    Nginx + Vue 3 SPA，/api proxy → simpleec-api

  API / Gateway 和 JOB 之間唯一的耦合是 Kafka topic。
  JOB 之間唯一的耦合也是 Kafka topic。
  每個 JOB 獨立 Spring Boot process、獨立 container。
```

### Phase 0：基礎設施 + Gradle 重構 + Docker Compose（2 天）

```
目標：Gradle multi-module 結構就位 + docker compose up 跑出基礎設施 + simpleec-api + simpleec-gateway
驗收：docker compose up → PG + Redis + Kafka + simpleec-api + simpleec-gateway 都 healthy
      curl :8080/api/v1/health 200 (前端 API)
      curl :8081/api/v1/health 200 (對外 API)
```

- [ ] `git init` + `.gitignore` + initial commit（保存現有骨架）
- [ ] Gradle 模組重構（見 §2.2）：
  - 共用：simpleec-common, simpleec-core, simpleec-channel（保留）
  - Boot：simpleec-api（從現有 simpleec-web + simpleec-app 合併）
  - Boot：simpleec-gateway（對外 API — Webhook + ERP，共用 simpleec-core）
  - 其他 Boot module 先建空殼（simpleec-channel-job, simpleec-order-job 等）
- [ ] 每個 Boot module 各自 Dockerfile（multi-stage：gradle build → JRE runtime）
- [ ] docker-compose.yml：
  - postgres + redis + kafka（見 §9.1）
  - simpleec-api + simpleec-gateway（depends_on 三個基礎設施）
  - 移除 RabbitMQ
- [ ] 確認 SQL：platform 表有 credential1~2，channel 表有 token1~token5
- [ ] `docker compose up --build` 確認 5 個 container 都起來
- [ ] `curl http://localhost:8080/api/v1/health` 確認 200（前端 API）
- [ ] `curl http://localhost:8081/api/v1/health` 確認 200（對外 API）

### Phase 1：Kafka 基礎 + TaskMessage + TaskProducer（2 天）

```
目標：RabbitMQ 替換成 Kafka，simpleec-core 有 TaskMessage + TaskProducer 供所有 module 使用
驗收：simpleec-api 啟動後 Kafka topics 自動建立
      用 API 發一筆訊息到 momo.fast 確認 topic 有訊息
```

需要刪除的（RabbitMQ）：
- 各 module 的 `spring-boot-starter-amqp` 依賴
- `RabbitMQConfig.java` / `ChannelActionProducer.java` / `ChannelActionConsumer.java` / `ChannelActionMessage.java`
- `application.yml` 的 `spring.rabbitmq` 區段

需要新增的（在 simpleec-core，供所有 Boot module 共用）：
- `spring-kafka` 依賴
- `KafkaConfig.java` — per-channel topics + 業務 topics + 排程 topics（見 §3.7）
- `TaskMessage.java` — 統一訊息格式（見 §5.5）
- `TaskProducer.java` — KafkaTemplate + partition key（見 §3.8）
- `application.yml` 的 `spring.kafka` 區段（見 §3.10）

- [ ] `docker compose up --build`
- [ ] `kafka-topics.sh --list` 看到所有 topics
- [ ] API 呼叫 TaskProducer 發一筆到 `momo.fast`

### Phase 2：ChannelJob（獨立 Spring Boot）（3 天）

```
目標：simpleec-channel-job 可用，docker compose 加入 ChannelJob container
驗收：docker compose up → simpleec-channel-momo-fast 啟動 → 發訊息到 momo.fast → 消費 → SyncLog 寫入 DB
```

- [ ] simpleec-channel-job module：
  - ChannelJob.java — @KafkaListener 參數化 + ActionFactory 分派
  - ActionService 介面 — 4 步生命週期
  - ActionFactory — 依 topic + action 路由
  - Resource DTO
  - SyncLogService — channel_sync_logs 寫入
  - CheckHealthActionService — ★ 獨立健康檢查（三層判斷，寫 channel_sync_logs）
  - 至少一個業務 ActionService 骨架（如 ModifyPriceActionService）
- [ ] Dockerfile for simpleec-channel-job
- [ ] docker-compose.yml 加入 simpleec-channel-momo-fast service（先一個平台驗證）
- [ ] `docker compose up --build` → simpleec-channel-momo-fast 啟動
- [ ] 端到端：API 發訊息 → momo.fast → ChannelJob 消費 → SyncLog 寫入

### Phase 3：其他 JOB（各自獨立 Spring Boot）（3 天）

```
目標：5 支獨立 JOB 全部就位，docker compose 加入所有 JOB container
驗收：docker compose up → 26 個 container 全部啟動（含可觀測性，見 §13）
```

- [ ] simpleec-order-job：OrderProcessJob（消費 order.process，DB upsert + 自治路由）
- [ ] simpleec-scheduler-job：HeartbeatTimer + SchedulerJob（消費 scheduler + 分發到各目標 topic）
- [ ] simpleec-backend-job：BackendJob + BackendActionFactory（消費 task.backend）
  - DailyStatisticsActionService（時區感知 UPSERT daily_statistics）
  - ManagePartitionsActionService（自動建立/清理 DB Partition）
- [ ] simpleec-frontend-job：FrontendJob（消費 task.frontend，骨架）
- [ ] simpleec-retry-job：RetryDispatchJob（消費 task.failed，retryCount + maxRetry → 超過寫 DB LOG）
- [ ] 每個 JOB 各自 Dockerfile
- [ ] docker-compose.yml 加入：
  - simpleec-channel-{shopee,yahoo,pchome}-fast（Phase 2 已加 momo-fast）
  - simpleec-channel-{momo,shopee,yahoo,pchome}-slow
  - simpleec-order-job / simpleec-scheduler-job
  - simpleec-backend-job / simpleec-frontend-job / simpleec-retry-job
- [ ] ScheduleConfig + application.yml 排程規則
- [ ] `docker compose up --build` → 26 個 container 全部啟動
- [ ] 看 log 確認排程：心跳 → SchedulerJob → 各平台 topic → ChannelJob

### Phase 4：Redis 快取 + JWT + CRUD + Gateway（3 天）

```
目標：Redis 快取可用 + simpleec-api 有 JWT + 完整 CRUD + simpleec-gateway 有 Webhook/ERP
驗收：docker compose up → curl login 拿 JWT → 用 JWT 呼叫所有 CRUD API
      curl :8081 Webhook / ERP endpoint 可用
```

- [ ] RedisConfig + @EnableCaching（放 simpleec-core，所有 module 共用）
- [ ] @Cacheable 加到 ProductService、ChannelService
- [ ] Order Hash Dedup 工具 — `order:hash:{merchantId}:{channelId}:{orderId}` + SHA-256
- [ ] JwtTokenProvider + JwtAuthenticationFilter（simpleec-core，api + gateway 共用）
- [ ] AuthController (login / me / refresh)（simpleec-api）
- [ ] SecurityConfig 改為 JWT 驗證（simpleec-api + simpleec-gateway 各自 SecurityConfig）
- [ ] account 表插入測試帳號（init.sql）
- [ ] SellPackService + SellPackController（CRUD + publish/unpublish → TaskProducer）
- [ ] PlatformService + PlatformController（CRUD + testConnection，credential 脫敏）
- [ ] ChannelService + ChannelController（CRUD + token 脫敏 + activate + sync-products）
- [ ] OrderController 補 ship / cancel（→ TaskProducer 發到 {channel}.fast）
- [ ] WebhookHandler 介面 + WebhookController（simpleec-gateway）
- [ ] ErpController（simpleec-gateway，共用 Service 層）
- [ ] `docker compose up --build` → curl 驗證全部 API（:8080 前端 + :8081 對外）

### Phase 5：ActionService 實作 + 端到端流程（3 天）

```
目標：ChannelJob 的所有 ActionService 實作 + 拉單完整流程端到端
驗收：docker compose up → 排程觸發 → FetchOrders → Hash Dedup → OrderProcessJob → DB 有訂單
```

- [ ] FetchOrdersActionService（拉單 → Hash Dedup → order.process）
- [ ] FetchProductsActionService（同步商品 → upsert sell_pack + auto-create product）
- [ ] ModifyPriceActionService / ModifyQuantityActionService
- [ ] StartSellingActionService / StopSellingActionService
- [ ] ShippingConfirmedActionService / AcceptCancelActionService
- [ ] GetQuantityActionService / FetchReturnsActionService
- [ ] 確認自治路由正確（§5.4 路由表全部覆蓋）
- [ ] 確認 partition key ordering
- [ ] `docker compose up --build` → 端到端驗證

### Phase 6：Vue 3 前端 + Nginx 容器（5 天）

```
目標：simpleec-admin 容器加入 docker-compose，瀏覽器能操作整套系統
驗收：docker compose up (26 container) → http://localhost → 登入 → 操作通路/商品/訂單 ← MVP 完成
```

- [ ] Vue 3 + Vite + PrimeVue + TypeScript 初始化（simpleec-admin/）
- [ ] Dockerfile（multi-stage：npm build → Nginx serve + /api proxy）
- [ ] docker-compose.yml 加入 simpleec-admin service
- [ ] Layout（Sidebar + Topbar）
- [ ] 登入頁 + auth store + JWT interceptor
- [ ] api/ + types/ + useDataTable composable
- [ ] DashboardView 首頁（統計卡片 + 趨勢圖 + 通路健康狀態表）
- [ ] 通路管理頁（雙層：平台展開 → 通路列表，平台 Dialog + 通路 Dialog 分開）
- [ ] 商品列表頁
- [ ] 賣場檔列表頁（上架/下架）
- [ ] 訂單列表 + 詳情（出貨確認/取消）
- [ ] `docker compose up --build` → 瀏覽器 http://localhost 確認全流程可用

### Phase 7：通路 Adapter 實作（每家 3~5 天）

```
目標：真實通路 API 串接
驗收：docker compose up → 排程觸發 → 真實打通路 API → 訂單入庫
```

- [ ] MomoAdapter — 填入實際 API 呼叫（目前是 placeholder）
- [ ] ShopeeAdapter — 新增
- [ ] YahooAdapter — 新增
- [ ] PchomeAdapter — 新增
- [ ] 每家 3 個檔案：XxxChannelAdapter + XxxApiClient + XxxDataMapper

### Phase 8：壓力驗證 + 調優（2 天）

```
目標：端到端 + 壓力驗證
驗收：1000 筆訂單突波 → Hash Dedup + partition key 正常 → 無重複 / 無亂序
```

- [ ] 端到端：排程觸發拉單 → ChannelJob → Hash Dedup → OrderProcessJob → DB
- [ ] 端到端：前端改價 → API → TaskProducer → ChannelJob → Adapter → 通路 API
- [ ] 壓力測試：模擬雙十一突波（1000 筆訂單）
- [ ] 驗證 scale：docker compose scale simpleec-order-job=3 → Kafka rebalance 正常

### 開發路線圖

```
Phase 0 (2天)           基礎設施 + Gradle 重構 + Docker Compose
  │                     ✅ docker compose up → PG + Redis + Kafka + simpleec-api + simpleec-gateway
  ▼
Phase 1 (2天)           Kafka 基礎 + TaskMessage + TaskProducer
  │                     ✅ Kafka topics 自動建立 + 能發訊息
  ▼
Phase 2 (3天)           ChannelJob（獨立 Spring Boot）
  │                     ✅ docker compose up → simpleec-channel-momo-fast 消費訊息
  ▼
Phase 3 (3天)           其他 5 支 JOB（各自獨立 Spring Boot）+ 可觀測性基礎建設
  │                     ✅ docker compose up → 26 個 container 全部啟動
  │                     ✅ 排程 → 分發 → 消費 完整鏈路
  ▼
Phase 4 (3天)           Redis 快取 + JWT + CRUD + Gateway
  │                     ✅ 前端 API 完整可用 + JWT 認證
  │                     ✅ 對外 API Webhook + ERP 可用
  ▼
Phase 5 (3天)           ActionService 實作 + 端到端流程
  │                     ✅ 排程 → 拉單 → Dedup → 整理 → DB
  ▼
Phase 6 (5天)           Vue 3 前端 + Nginx 容器
  │                     ✅ 瀏覽器操作整套系統 ← MVP 完成（26 container）
  ▼
Phase 7 (每家3~5天)     通路 Adapter 實作
  │                     ✅ 真實打通路 API
  ▼
Phase 8 (2天)           壓力驗證 + 調優
                        ✅ 雙十一突波驗證
```

### 時間估算

```
Phase 0~6（MVP 完成）：2 + 2 + 3 + 3 + 3 + 3 + 5 = 21 天
Phase 7（通路 Adapter）：每家 3~5 天，4 家 = 12~20 天
Phase 8（壓力驗證）：2 天

1 人開發到 MVP：~21 天（docker compose up 26 個 container 整套系統可用）
1 人開發到完整：~37 天（含 4 家通路 Adapter + 壓力驗證）

前端（Phase 6）可以先在本地 dev server 開發，最後才包成 Docker image。
```

---

## 11. 技術決策紀錄

| 決策 | 選擇 | 替代方案 | 理由 |
|------|------|---------|------|
| 架構 | 各 JOB 獨立 Spring Boot + 共用 module | 全部合一個 JAR | JOB 隔離互不影響，各自 scale/deploy，Kafka topic 介接 |
| MQ | Kafka | RabbitMQ | Partition key 天然保證同 key 有序，每筆都處理不跳過 |
| MQ Topic | per-channel fast/slow | 統一 channel.fast/slow | 每個平台限速/規則不同，per-channel 完全隔離 |
| JOB 粒度 | 大粒度收斂（6 支 JOB） | 細粒度（20+ 支 JOB） | 管理簡單，通路需求變動只改一支，參數化部署 |
| ChannelJob 部署 | 唯一同 CODE + 環境變數吃不同 topic | 拆成多支獨立程式碼 | 開發邏輯統一，參考舊系統 call-to-user-job |
| 其他 JOB | 各自獨立 Spring Boot application | 合併到一個 JAR | 隔離性、各自 scale、各自 deploy |
| API 角色 | 前端 API + 對外 API 分開部署 | 合在同一個 simpleec-api | 前端客變頻繁/流量混雜，對外 Webhook 必須穩定在線，安全邊界不同，獨立 scale |
| 前端 API | simpleec-api — 看平台/塞設定/看資料/丟事件 | — | 前端用，不消費 Kafka，用戶 JWT |
| 對外 API | simpleec-gateway — Webhook + ERP | — | 對外用，不消費 Kafka，ERP JWT / Webhook 簽名驗證 |
| 訂單處理 | 拉單(ChannelJob) + 整理(OrderProcessJob) 分開 | 合併在一支 JOB | 雙十一突波一次 1000 張，分開 + Hash Dedup 避免重複 |
| 去重 | Producer-side Hash Dedup（Redis SHA-256） | Consumer-side dedup | 在源頭過濾，減少下游訊息量 |
| 失敗處理 | 做了就做了 + RetryDispatchJob（retryCount 追蹤） | ack + re-produce retry | 打通路失敗不代表稍後會成功，超過次數寫 DB LOG 表供 RD 查看清理 |
| fast 不重打 | *.fast topic 失敗一律不重打（鐵則） | fast 也進 retry 流程 | 快慢分離是 CAP 理論的商務應用：fast=AP（要快/失敗算了），slow=CP（可等/可重試）；改價改量重打可能造成更大問題 |
| ChannelJob 模式 | ActionFactory + ActionService 4 步生命週期 | switch/case | 參考舊系統 action-to-platform，40+ action 統一管理 |
| 訂單去重 key | `merchantId:channelId:orderId` | `channelId:orderId` | 多商家場景需要 merchantId，ChannelJob + OrderProcessJob 兩邊 SYNC |
| Redis 鎖 | 幾乎不需要（Kafka partition 保證） | Redis SETNX | Kafka partition key 天然保證同 key 有序，不需要額外鎖 |
| 調度引擎 | Heartbeat → SchedulerJob（判斷+分發一體） | Quartz / xxl-job / DB 表 | 零 DB 表、邏輯在程式裡、改 yml 即生效、不需中間 topic |
| 心跳頻率 | 可設定（yml）| 硬編 1 秒 | 不同環境需求不同 |
| 庫存同步 | 一天兩次（cron） | 每 10 分鐘 | 即時變動走 {channel}.fast，不需要高頻同步 |
| 拉單時段 | 24 小時不間斷 | active_hours 限制 | 物流變動在任何時間發生 |
| DB | PostgreSQL | MySQL | 已有 schema，支援 JSONB、Partition |
| ORM | MyBatis-Plus | JPA | 對 SQL 控制力高，適合多表 join |
| 快取 | Redis | 本地 Caffeine | Hash Dedup 需要跨 instance 共享 |
| 記憶體快取 | ConcurrentHashMap | Caffeine | SchedulerJob 只需 simple get/put |
| 前端 UI | PrimeVue | Ant Design Vue | DataTable 功能最完整，舊系統已用 |
| HTTP Client | OkHttp | RestTemplate | 通路 API 需要精確控制 header、簽名 |
| JWT 庫 | jjwt 0.12.6 | Spring Security OAuth2 | 輕量，夠用 |
| 統計資料 | 預彙整 daily_statistics（每天一筆） | 即時查 orders 表 | 選區間即時查對 DB 壓力極大；預彙整以天為最小單位，前端選角度聚合即可 |
| 統計時區 | 依 merchant.user_local_time_zone | 系統 crontab UTC | 客戶看的是「他的一天」不是伺服器的一天；SchedulerJob 逐商家判斷本地午夜 |
| 商品同步 | 前端按鈕 → slow topic → FetchProducts | 定時全量同步 | CP 取向，非即時需求；訂單自帶商品資料可先跑，商品檔建檔可後補 |
| 後台 API | simpleec-admin-api 獨立（非 MVP） | 塞進 simpleec-api | 業務後台 = 開商家帳號，跟前台邏輯完全不同，流量/權限/變動頻率都不同 |
| 通路健康檢查 | 獨立 CHECK_HEALTH fast action（每 10 分鐘） | 附帶在其他操作裡判斷 | 健康度必須獨立記錄（寫 LOG），不是其他操作的副作用；三層：OK / TOKEN_INVALID / API_DOWN |
| DB Partition | BackendJob 每天自動建立未來 2 個月 partition | 手動 DDL / DEFAULT 打底 | 時序表會膨脹，提前建 partition 避免寫入失敗；idempotent；可配合清理舊 partition |

---

## 12. 商務流程走讀（端到端）

> 從簽約到日常營運，走一次完整的商務流程，對照系統設計的每一步。

### Step 1：簽約 — 業務後台開帳號

```
業務人員操作 simpleec-admin-api（非 MVP，初期可用 SQL / 管理腳本替代）

  1. INSERT merchant（公司資料、user_local_time_zone）
  2. INSERT account（init 帳號，role = admin，密碼 bcrypt）
  3. 把帳號密碼給客戶
```

**對應設計：**
- §1 merchant 表、account 表
- §2.1 simpleec-admin-api（獨立系統，非 MVP）

### Step 2：客戶設定平台與通路（★ 先平台再通路）

```
客戶用 init 帳號登入前端（simpleec-admin SPA → simpleec-api）

  1. 新增平台設定（platform）
     → POST /api/v1/platforms
     → 填入 momo 的第三方認證資料（credential1~N，各平台所需欄位不同）
     → 再建一個 pchome 的平台設定

  2. 在平台下新增通路（channel）
     → POST /api/v1/channels { platformId: "momo", channelName: "momo冷凍" }
     → 填入該館的授權 token（token1~token5）
     → 再建 "momo一般"、"pchome電子"，各填各自的 token
     → 每個通路掛在對應的平台下

  3. 啟動通路
     → PUT /api/v1/channels/{id}/activate
     → channel.actived = true

  階層關係：
    merchant
      └── platform: momo（平台 — API 憑證）
      │     ├── channel: momo冷凍（通路 — 賣場）
      │     └── channel: momo一般（通路 — 賣場）
      └── platform: pchome（平台 — API 憑證）
            └── channel: pchome電子（通路 — 賣場）
```

**對應設計：**
- §2.3.1 Controller — PlatformController（平台 CRUD）+ ChannelController（通路 CRUD）
- §2.4 API — POST /platforms（平台）, POST /channels（通路）, PUT /channels/{id}/activate
- §8.3 前端 — ChannelView（通路管理頁面）

### Step 3：訂單自動流入（啟動後 5 分鐘）

```
啟動後，SchedulerJob 心跳觸發 FETCH_ORDERS：

  ┌─────────────────────────────────────────────────────┐
  │  HeartbeatTimer (每秒)                               │
  │    └── SchedulerJob 判斷 FETCH_ORDERS 規則到期        │
  │         └── 對每個 active channel 發送 TaskMessage    │
  │              topic = {channel}.slow                   │
  │              action = FETCH_ORDERS                    │
  └──────────────┬──────────────────────────────────────┘
                 ▼
  ┌─────────────────────────────────────────────────────┐
  │  ChannelJob ({channel}.slow consumer)                │
  │    └── FetchOrdersActionService                      │
  │         1. validate — 檢查 channel 設定              │
  │         2. execute — 打通路 API 拉訂單                │
  │         3. postProcess — Hash Dedup 過濾重複          │
  │         4. route — 有變動訂單 → order.process          │
  └──────────────┬──────────────────────────────────────┘
                 ▼
  ┌─────────────────────────────────────────────────────┐
  │  OrderProcessJob (order.process consumer)            │
  │    └── 解析訂單 → upsert orders 表                    │
  │         └── 狀態有變 → task.backend                   │
  └─────────────────────────────────────────────────────┘
```

**對應設計：**
- §6 SchedulerJob + HeartbeatTimer
- §5.3.1 ChannelJob — FetchOrdersActionService
- §5.3.2 OrderProcessJob
- §3.2 快慢分離 — FETCH_ORDERS 走 *.slow topic

### Step 4：同步通路商品資料

```
客戶在通路管理頁面，按「同步商品」按鈕：

  前端 POST /channels/{channelId}/sync-products
    └── simpleec-api 發 TaskMessage
         topic = {channel}.slow
         action = FETCH_PRODUCTS
         key = channelId  ← 同通路排隊（按兩下不會並行跑）
              ▼
  ChannelJob (FetchProductsActionService)
    1. 打通路 API 拉商品列表
    2. 逐筆比對 SKU：
       - sell_pack 存在 → 更新（名稱、價格、狀態）
       - sell_pack 不存在 → 新增
         - product 以 SKU 查找 → 找到就關聯
         - product 找不到 → 自動建立（以 SKU 為 item_number）
    3. 結束（無下游 topic）
```

**商品 vs 賣場的關係：**
```
  product（本 — 倉庫概念）
    └── product_spec（SKU 規格）
         └── sell_pack（平台上架 — 同一商品可在多通路上架）
              ├── momo 冷凍的上架
              ├── momo 一般的上架
              └── pchome 電子的上架

  一個 SKU 在倉庫是一個 product，在 3 個通路就有 3 個 sell_pack
```

**訂單如何定位商品：**
```
  訂單自帶 channel_product_id + channel_spec_id（賣編 + 規格編）
    → match sell_pack.channel_product_id + sell_pack.channel_spec_id
    → 找到 product_spec → 找到 product
```

**對應設計：**
- §5.3.1 ChannelJob — FetchProductsActionService
- §2.4 API — POST /channels/{channelId}/sync-products
- §8.3 前端 — 通路設定 Dialog 的「同步商品」按鈕
- §3.2 快慢分離 — FETCH_PRODUCTS 走 *.slow topic（CP 取向，可重試）

### Step 5：每日統計預彙整

```
SchedulerJob 每秒心跳，判斷每個商家的本地時區：

  for each merchant:
    localNow = now.atZone(merchant.user_local_time_zone)
    if localNow.hour == 0 && localNow.minute in [5,10]:
      → 發 DAILY_STATISTICS to task.backend
        payload = { merchantId, statDate = yesterday }
              ▼
  BackendJob (DailyStatisticsActionService)
    1. 用商家時區算出 yesterday 的 UTC 起訖
    2. SELECT COUNT/SUM FROM orders WHERE created_at BETWEEN ...
       GROUP BY channel_id
    3. UPSERT daily_statistics（per channel + 彙總）
    4. 結束（無下游 topic）
```

**為什麼不即時查：**
```
  ✗ 客戶選「最近 30 天」→ 即時 SELECT orders → 對 DB 壓力極大
  ✓ 客戶選「最近 30 天」→ 查 daily_statistics（30 筆） → 瞬間回應

  最小單位 = 一天
  時區 = 客戶的，不是伺服器的
```

**對應設計：**
- §5.3.4 BackendJob — DailyStatisticsActionService
- §6.2 SchedulerJob — DAILY_STATISTICS 分發邏輯
- §1.3 daily_statistics 表
- §2.4 API — GET /statistics/summary, /statistics/daily, /statistics/by-channel

### Step 6：首頁 Dashboard — 一目了然

```
客戶登入後看到 Dashboard：

  ┌──────────────────────────────────────────────────┐
  │  統計卡片（今日/本週/本月/自訂）                    │
  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐   │
  │  │ 訂單 │ │ 出貨 │ │ 完成 │ │ 取消 │ │ 退貨 │   │
  │  │  32  │ │  28  │ │  25  │ │  2   │ │  1   │   │
  │  │$128k │ │$112k │ │$100k │ │ $8k  │ │ $4k  │   │
  │  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘   │
  │                                                   │
  │  每日趨勢折線圖                                    │
  │  ═══════════════════════════════                   │
  │                                                   │
  │  通路健康狀態（三層判斷）                             │
  │  ┌──────────────────────────────────────────────┐ │
  │  │ momo冷凍  │ 🟢 暢通      │ 最後同步: 3分鐘前 │ │
  │  │ momo一般  │ 🟡 Token過期  │ 最後同步: 3分鐘前 │ │
  │  │ pchome電子│ 🔴 API 無回應 │ 最後同步: 2小時前 │ │
  │  └──────────────────────────────────────────────┘ │
  │  🟢 OK = 暢通  🟡 TOKEN_INVALID = 要重新授權       │
  │  🔴 API_DOWN = 平台掛了                            │
  └──────────────────────────────────────────────────┘

  資料來源：
    統計卡片 → GET /statistics/summary?from=&to=
    趨勢圖   → GET /statistics/daily?from=&to=
    通路健康 → GET /channels/health
```

**對應設計：**
- §8.3 前端 — DashboardView
- §8.6 路由 — / → /dashboard（預設首頁）
- §2.4 API — statistics + channel health endpoints

### 流程總結

```
  [業務後台]                    [客戶前台]
       │                            │
  1. 開帳號                    2. 設平台+通路
       │                            │
       └──────────── 啟動 ───────────┘
                      │
              SchedulerJob 心跳
              ┌───────┴───────┐
              ▼               ▼
         FETCH_ORDERS    DAILY_STATISTICS
         (*.slow)        (task.backend)
              │               │
         ChannelJob      BackendJob
              │               │
         order.process   daily_statistics表
              │
         OrderProcessJob
              │
         task.backend
              │
         BackendJob (庫存/通知/報表)
              │
         {channel}.fast (庫存推回)

  ★ 客戶手動觸發：
    - 同步商品 → {channel}.slow → FetchProducts (key=channelId 排隊)
    - Dashboard 看統計 → 查 daily_statistics 表
    - 通路健康（三層）→ 查 channel_sync_logs 最近一筆：
        🟢 OK = API + Token 暢通
        🟡 TOKEN_INVALID = API 活著但 Token 不對
        🔴 API_DOWN = 通路 API 掛了
```

---

## 13. 可觀測性設計

> **核心目標：** 在 Kafka 流式架構下快速找到問題——知道 trace ID 走到哪、死在哪裡、有沒有做資料變動。

### 13.1 架構總覽

```
                         ┌─────────────┐
                         │  Grafana     │ :3000
                         │  (Dashboard) │
                         └──┬──┬──┬────┘
                            │  │  │
              ┌─────────────┘  │  └──────────────┐
              ▼                ▼                  ▼
        ┌──────────┐   ┌───────────┐       ┌──────────┐
        │Prometheus│   │   Loki    │       │  Tempo   │
        │ (Metrics)│   │  (Logs)   │       │ (Traces) │
        └────▲─────┘   └────▲─────┘       └────▲─────┘
             │               │                  │
             └───────────────┼──────────────────┘
                             │
                    ┌────────┴────────┐
                    │ OTEL Collector  │ :4317/:4318
                    └────────▲────────┘
                             │ OTLP
              ┌──────────────┼──────────────┐
              │              │              │
         [API/Gateway]  [ChannelJobs]  [Other JOBs]
         (OTEL Agent)   (OTEL Agent)  (OTEL Agent)
```

### 13.2 技術選型

| 需求 | 選型 | 理由 |
|------|------|------|
| Trace 跨 JOB 追蹤 | OpenTelemetry Java Agent v2.10.0 | 零 code 自動注入 W3C `traceparent` 到 Kafka header |
| Trace 儲存 | Grafana Tempo | 與 Loki/Grafana 原生整合 |
| 集中式日誌 | Grafana Loki | 比 ELK 輕量，與 Tempo trace 原生關聯 |
| Metrics 儲存 | Prometheus | 業界標準，Grafana 原生 |
| 統一儀表板 | Grafana | 整合 Metrics + Logs + Traces |
| OTLP 中轉 | OTEL Collector (contrib) | 統一接收所有服務的 telemetry，轉發到各 backend |
| Kafka 管理 | Kafka UI (provectuslabs) | Topic 瀏覽、Consumer Group 管理、訊息查看 |

> **為什麼 Loki 而不是 ELK？**
> 1. ELK 需要 3 個重服務（Elasticsearch + Logstash + Kibana），資源需求高
> 2. Loki 只索引 label、不索引 log 內容，儲存成本低
> 3. Loki 與 Tempo 原生關聯：trace_id → 一鍵跳轉對應 log
> 4. 全部在 Grafana 一個介面完成（Metrics + Logs + Traces）

### 13.3 OTEL Java Agent（零 code 自動化）

所有 8 個 Dockerfile 都加入 OTEL Agent：

```dockerfile
ADD https://github.com/.../opentelemetry-javaagent.jar /app/otel-agent.jar
ENTRYPOINT ["java", "-javaagent:/app/otel-agent.jar", "-jar", "app.jar"]
```

**Agent 自動做的事：**

| 能力 | 說明 |
|------|------|
| Kafka trace 傳播 | 自動在 Kafka record header 注入/讀取 W3C `traceparent` → trace 跨 JOB 串接 |
| HTTP trace | Spring MVC 進出自動 span |
| JDBC trace | PostgreSQL query 自動 span |
| Redis trace | Redis 命令自動 span |
| MDC 注入 | 自動注入 `trace_id` + `span_id` 到 SLF4J MDC |

**環境變數（docker-compose.yml x-common-env）：**

```yaml
OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
OTEL_EXPORTER_OTLP_PROTOCOL: http/protobuf
OTEL_LOGS_EXPORTER: otlp
OTEL_METRICS_EXPORTER: otlp
OTEL_TRACES_EXPORTER: otlp
```

### 13.4 TaskMessage traceId 欄位

```java
private String traceId;                    // 業務層面冗餘副本
@Builder.Default
private int schemaVersion = 1;             // 訊息格式版本（見 §14.1）
```

**雙保險設計：**
- **主要**：OTEL Agent 自動透過 Kafka header `traceparent` 傳播 trace（不需任何 code）
- **冗餘**：`TaskProducer.send()` 額外把 `Span.current().getSpanContext().getTraceId()` 寫入 `TaskMessage.traceId`，方便 DB 查詢和 log 搜尋

### 13.5 MDC 結構化日誌

每個 JOB 的 `handle()` 入口注入業務 MDC：

```java
public void handle(TaskMessage msg, Acknowledgment ack) {
    TaskMdcHelper.set(msg);   // 注入 MDC
    try {
        // ... 業務邏輯 ...
    } finally {
        TaskMdcHelper.clear(); // 清除 MDC
    }
}
```

**MDC 欄位：**

| MDC Key | 來源 |
|---------|------|
| `trace_id` | OTEL Agent 自動注入 |
| `span_id` | OTEL Agent 自動注入 |
| `messageId` | TaskMessage.messageId |
| `merchantId` | TaskMessage.merchantId |
| `taskAction` | TaskMessage.taskAction |
| `sourceJobType` | TaskMessage.sourceJobType |
| `businessTraceId` | TaskMessage.traceId |

**logback-spring.xml 雙模式：**
- **本地開發**：人類可讀 pattern（含 traceId/msgId/merchant/action）
- **Docker 環境**：JSON 格式（LogstashEncoder 8.0），Loki 友善

### 13.6 Grafana 三大查詢場景

#### 場景 1：Trace ID 走到哪？

1. 打開 Grafana → Tempo
2. 輸入 trace ID → 看到完整 waterfall：

```
API (POST /channels/actions)
  └── Kafka Produce: momo.fast
       └── ChannelJob.handle()
            └── Platform API call (momo)
            └── Kafka Produce: order.process
                 └── OrderProcessJob.handle()
                      └── Redis SET order:hash:...
                      └── Kafka Produce: task.backend
                           └── BackendJob.handle()
```

#### 場景 2：死在哪裡？

```logql
# 所有 ERROR 日誌
{service_name=~"simpleec-.*"} |= "ERROR"

# 特定 trace 的所有日誌（跨所有 JOB）
{service_name=~"simpleec-.*"} | json | trace_id="<your-trace-id>"

# Poison pill 事件
{service_name=~"simpleec-.*"} |= "Poison pill"

# DLT 路由事件
{service_name=~"simpleec-.*"} |= "routing to DLT"
```

#### 場景 3：有沒有做資料變動？

```logql
# 特定商家的訂單處理
{service_name="simpleec-order-job"} | json | merchantId="M001"

# Retry 活動
{service_name="simpleec-retry-job"} | json | taskAction="RETRY_DISPATCH"

# Channel Job 失敗
{service_name="simpleec-channel-momo-fast"} |= "FAILED"
```

### 13.7 設定檔清單

| 檔案 | 用途 |
|------|------|
| `docker/otel/otel-collector-config.yml` | OTLP receiver → Tempo + Loki + Prometheus |
| `docker/tempo/tempo.yml` | Trace 本地儲存 |
| `docker/loki/loki.yml` | Log 本地儲存，30 天 retention |
| `docker/prometheus/prometheus.yml` | Scrape otel-collector + api + gateway |
| `docker/grafana/provisioning/datasources/datasources.yml` | 三個 datasource + trace↔log 關聯 |
| `docker/grafana/provisioning/dashboards/dashboards.yml` | Dashboard 自動載入 |
| `simpleec-core/src/main/resources/logback-spring.xml` | 雙模式結構化日誌 |
| `simpleec-core/.../observability/TaskMdcHelper.java` | Kafka consumer MDC 注入 |

### 13.8 Metrics 來源

| 服務類型 | Metrics 來源 | 方式 |
|---------|------------|------|
| API / Gateway | Spring Boot Actuator `/actuator/prometheus` | Prometheus scrape |
| 所有 JOB | OTEL Agent 透過 OTLP | Agent → Collector → Prometheus |
| Kafka broker | Kafka 內建 JMX | Prometheus scrape（可選） |

> **JOB 不需要 Actuator web endpoint**（它們是 `spring.main.web-application-type: none`）。
> JOB 的 metrics 由 OTEL Agent 透過 OTLP 直接送到 Collector。

---

## 14. Queue 管理

### 14.1 Schema Version（訊息版本控制）

```java
public final class SchemaVersionHandler {
    public static final int CURRENT_VERSION = 1;
    public static int normalize(int v) { return v <= 0 ? 1 : v; }
    public static boolean isSupported(int v) {
        return normalize(v) >= 1 && normalize(v) <= CURRENT_VERSION;
    }
}
```

**向後相容：** Jackson 反序列化舊訊息（缺少 `schemaVersion` 欄位）→ int 預設值 0 → `normalize(0) → 1`。

**所有 JOB handle() 入口的版本檢查：**

```java
if (!SchemaVersionHandler.isSupported(msg.getSchemaVersion())) {
    log.warn("Unsupported schemaVersion={}, routing to DLT", msg.getSchemaVersion());
    taskProducer.send("task.dlt", null, msg);
    ack.acknowledge();
    return;
}
```

**使用場景：** 滾動升級時，新版 consumer 需要處理舊版訊息（normalize），舊版 consumer 收到新版訊息（unsupported → DLT）。

### 14.2 Dead Letter Topic (task.dlt)

```
task.dlt — 4 partitions, 30 天 retention

★ 終點站：不被任何 consumer 消費
★ 只供人工查看（Kafka UI 或 CLI）
★ 30 天後自動清除
```

**進入 DLT 的路徑：**

```
1. Schema 版本不支援    → 任何 JOB 收到不支援的 schemaVersion → task.dlt
2. Fast topic 失敗      → RetryDispatchJob 收到 originalTopic=*.fast → task.dlt（鐵則：fast 永不重打）
3. 超過重打上限          → retryCount >= maxRetry → task.dlt
4. 不可重打的 action     → RetryPolicyService 判斷不可重打 → task.dlt
```

**與 task.failed 的區別：**

| | task.failed | task.dlt |
|---|-----------|---------|
| 消費者 | RetryDispatchJob | 無（人工查看） |
| 用途 | 判斷是否重打 | 永久失敗記錄 |
| Retention | 永久 | 30 天 |
| 訊息來源 | 所有 JOB 失敗時 | RetryDispatchJob + Schema gate |

### 14.3 Poison Pill 防護

```java
// KafkaConfig.java
factory.setCommonErrorHandler(new DefaultErrorHandler(
    (record, ex) -> log.error("Poison pill: topic={}, partition={}, offset={}",
        record.topic(), record.partition(), record.offset()),
    new FixedBackOff(0L, 0L)  // 不重試，直接跳過
));
```

**場景：** 訊息無法反序列化（壞 JSON、class 不存在、欄位型別不相容）。不跳過會永遠卡住 partition。

### 14.4 Feature Gate（功能開關）

```java
@Configuration
@ConfigurationProperties(prefix = "feature.gates")
@Data
public class FeatureGateConfig {
    private boolean enhancedRetryLogic = false;
    // 未來依需求擴充
}
```

各 `application.yml` 可用 `feature.gates.enhanced-retry-logic: true/false` 控制。用於滾動部署時逐步啟用新功能。

### 14.5 營運工具一覽

| 工具 | URL | 用途 |
|------|-----|------|
| Grafana | http://localhost:3000 | 統一儀表板（Metrics + Logs + Traces） |
| Kafka UI | http://localhost:8088 | Topic 瀏覽、Consumer Group 管理、訊息查看 |
| Prometheus | http://localhost:9090 | 直接查詢 Metrics |
| Tempo | http://localhost:3200 | Trace 查詢（通常透過 Grafana） |
| Loki | http://localhost:3100 | Log 查詢（通常透過 Grafana） |

**詳細營運手冊：** `docs/OPERATIONS_RUNBOOK.md`（含垃圾訊息清理、offset 重置、DLT 查看、Consumer Group 卡住處理等）

### 14.6 訊息流向與失敗處理（完整）

```
正常流程:
  API → {channel}.fast → ChannelJob → SUCCESS → ack

失敗流程:
  ChannelJob → FAILED → task.failed → RetryDispatchJob
    ├── Fast topic (鐵則) → task.dlt (永不重打)
    ├── 超過 maxRetry → task.dlt
    ├── 不可重打的 action → task.dlt
    └── 可重打 → retryCount++ → 原 topic

Schema 不支援:
  任何 JOB 收到 schemaVersion 不支援 → task.dlt → ack

Poison Pill:
  反序列化失敗 → DefaultErrorHandler 跳過 → 記 log（Loki 可搜）
```

---

## 15. Topic 訊息格式 + 事件流範例

> **本章用真實 JSON 呈現每個 topic 裡的訊息長相，搭配端到端事件流。**
> 從最簡單的日常操作開始：改價、改量、商品上下架、出貨確認、退款、檢查平台健康。

**★ Payload 設計原則：帶齊，不回查。**

> API 層發送事件時，一次把 ChannelJob 呼叫通路 API 所需的全部資訊塞進 payload。
> ChannelJob 拿到訊息後**直接打通路 API，不回 DB 查詢**。
>
> 理由：
> 1. **時間一致性** — 分散式系統中，事件到達時 DB 狀態可能已被其他事件改過，查出來的不是客戶當下要求的值
> 2. **減少 query** — ChannelJob 是高併發消費者，每則訊息省一次 DB round-trip
> 3. **訊息自足** — payload 自帶所有資訊，即使 DB 掛了也能從 Kafka 訊息重建完整上下文
> 4. **Payload 大小** — 多帶幾個欄位（商品名、規格名、平台編號）頂多多幾百 bytes，不影響 Kafka 效能

**★ 平台雙 ID + 雙 Name（sell_pack 欄位對照）：**

> 平台通常用自己的 ID 去操作（不是我方的商品名），所以 payload 要帶齊平台端的雙 ID 和雙 Name。
> 資料來源是 `sell_pack` 表，API 層發事件時一次查出帶齊。

| Payload 欄位 | DB 欄位 | 說明 |
|-------------|---------|------|
| `channelProductId` | `sell_pack.channel_product_id` | 平台商品編號（平台的「賣編」） |
| `channelSpecId` | `sell_pack.channel_spec_id` | 平台規格編號 |
| `channelProductName` | `sell_pack.channel_product_name` | 平台上顯示的商品名稱 |
| `channelSpecName` | `sell_pack.channel_spec_name` | 平台上顯示的規格名稱 |
| `productName` | `product.name` | 我方商品名稱（LOG / 通知用） |
| `specName` | `product_spec.spec_name` | 我方規格名稱（LOG / 通知用） |

### 15.1 TaskMessage 通用結構

所有 topic 裡的訊息都是同一個 `TaskMessage` JSON 格式：

```json
{
  "messageId":      "uuid",          // 每則訊息唯一 ID
  "taskType":       "channel_action",// channel_action / backend / scheduler / dispatch / dlt
  "taskAction":     "MODIFY_PRICE",  // 具體動作
  "sourceJobType":  "api",           // 發送來源（api / channel-job / order-process-job / ...）
  "merchantId":     "M001",          // 商家 ID
  "ownerType":      "channel",       // channel / account / merchant / system
  "ownerId":        "CH-MOMO-001",   // 通路 ID / 帳號 ID / ...
  "timezone":       "Asia/Taipei",   // 商家時區
  "payload":        { ... },         // 各 action 不同的 payload（見下方）
  "createdAt":      "2026-02-09T10:30:00Z",
  "retryCount":     0,               // 重打次數
  "topic":          "momo.fast",     // 目標 topic
  "partitionKey":   "SP-12345",      // Kafka partition key（null = round-robin）
  "traceId":        "abcdef1234567890abcdef1234567890",  // OTEL trace ID
  "schemaVersion":  1                // 訊息格式版本
}
```

---

### 15.2 改價（MODIFY_PRICE）

**事件流：**

```
客戶在前台改價
  │
  ▼
simpleec-api (POST /api/v1/sell-packs/{id}/price)
  │  產生 TaskMessage，key = sellPackId（同 SKU 嚴格有序）
  ▼
┌─────────────────────────────────────────────────────────┐
│  Topic: momo.fast                                       │
│  Key:   SP-12345                                        │
│                                                         │
│  {                                                      │
│    "messageId":     "a1b2c3d4-...",                     │
│    "taskType":      "channel_action",                   │
│    "taskAction":    "MODIFY_PRICE",                     │
│    "sourceJobType": "api",                              │
│    "merchantId":    "M001",                             │
│    "ownerType":     "channel",                          │
│    "ownerId":       "CH-MOMO-001",                      │
│    "timezone":      "Asia/Taipei",                      │
│    "payload": {                                         │
│      "sellPackId":         "SP-12345",                  │
│      "productId":          "PRD-001",                   │
│      "productName":        "養生雞精禮盒",                │
│      "specName":           "60ml × 12入",               │
│      "channelProductId":   "MOMO-SKU-98765",            │
│      "channelSpecId":      "MOMO-SPEC-98765-A",         │
│      "channelProductName": "MOMO養生雞精禮盒限定組",       │
│      "channelSpecName":    "60ml×12入(單盒)",            │
│      "currentPrice":       350,                         │
│      "newPrice":           299,                         │
│      "currency":           "TWD"                        │
│    },                                                   │
│    "createdAt":     "2026-02-09T10:30:00Z",             │
│    "retryCount":    0,                                  │
│    "topic":         "momo.fast",                        │
│    "partitionKey":  "SP-12345",                         │
│    "traceId":       "abc123...",                        │
│    "schemaVersion": 1                                   │
│  }                                                      │
└─────────────────────────────────────────────────────────┘
  │
  ▼
ChannelJob (simpleec-channel-momo-fast)
  │  ModifyPriceActionService:
  │    setting()          → 從 payload 取得全部欄位（不查 DB）
  │    getPlatformTokens()→ 從 DB 取得 momo access token（唯一的 DB query）
  │    verifyNeedData()   → 檢查 channelProductId, channelSpecId, channelProductName, newPrice 不為空
  │    doAction()         → 直接用 payload 資訊呼叫 momo API 修改價格
  │  結果：
  │    ✅ 成功 → SyncLog SUCCESS → ack（結束，無下游 topic）
  │    ❌ 失敗 → SyncLog FAILED → task.failed → ack
  ▼
（成功時到此結束，無下游）

失敗時 → task.failed:
┌─────────────────────────────────────────────────────────┐
│  Topic: task.failed                                     │
│  Key:   null                                            │
│                                                         │
│  {                                                      │
│    "messageId":     "e5f6g7h8-...",                     │
│    "taskType":      "dispatch",                         │
│    "taskAction":    "RETRY_DISPATCH",                   │
│    "sourceJobType": "channel-job",                      │
│    "merchantId":    "M001",                             │
│    "ownerType":     "channel",                          │
│    "ownerId":       "CH-MOMO-001",                      │
│    "payload": {                                         │
│      "originalTopic":   "momo.fast",                    │
│      "originalKey":     "SP-12345",                     │
│      "originalAction":  "MODIFY_PRICE",                 │
│      "error":           "Momo API returned 503",        │
│      "originalPayload": { ... }                         │
│    },                                                   │
│    "retryCount":    0,                                  │
│    "traceId":       "abc123...",                        │
│    "schemaVersion": 1                                   │
│  }                                                      │
└─────────────────────────────────────────────────────────┘
  │
  ▼
RetryDispatchJob
  │  originalTopic = "momo.fast" → ★ fast 鐵則：永不重打
  ▼
┌─────────────────────────────────────────────────────────┐
│  Topic: task.dlt                                        │
│  Key:   null                                            │
│                                                         │
│  {                                                      │
│    "messageId":     "i9j0k1l2-...",                     │
│    "taskType":      "dlt",                              │
│    "taskAction":    "FAST_TOPIC_NO_RETRY",              │
│    "sourceJobType": "retry-dispatch-job",               │
│    "merchantId":    "M001",                             │
│    "payload": {                                         │
│      "originalTopic":  "momo.fast",                     │
│      "originalAction": "MODIFY_PRICE",                  │
│      "error":          "Momo API returned 503"          │
│    },                                                   │
│    "retryCount":    0,                                  │
│    "traceId":       "abc123...",                        │
│    "schemaVersion": 1                                   │
│  }                                                      │
└─────────────────────────────────────────────────────────┘
  │
  ▼
（終點站，30 天後自動清除，Kafka UI 可查看）
```

---

### 15.3 改量（MODIFY_QUANTITY）

**事件流：** 與改價幾乎相同，只是 payload 不同。

```
Topic: momo.fast
Key:   SP-12345（同 SKU 嚴格有序，防止改價改量交錯）

{
  "messageId":     "b2c3d4e5-...",
  "taskType":      "channel_action",
  "taskAction":    "MODIFY_QUANTITY",
  "sourceJobType": "api",
  "merchantId":    "M001",
  "ownerType":     "channel",
  "ownerId":       "CH-MOMO-001",
  "payload": {
    "sellPackId":         "SP-12345",
    "productId":          "PRD-001",
    "productName":        "養生雞精禮盒",
    "specName":           "60ml × 12入",
    "channelProductId":   "MOMO-SKU-98765",
    "channelSpecId":      "MOMO-SPEC-98765-A",
    "channelProductName": "MOMO養生雞精禮盒限定組",
    "channelSpecName":    "60ml×12入(單盒)",
    "currentQuantity":    100,
    "newQuantity":        50
  },
  "partitionKey":  "SP-12345",
  "schemaVersion": 1
}
```

> **為什麼改價改量用同一個 partitionKey (sellPackId)?**
> 同一個 SKU 的改價和改量會落在同一個 partition → 嚴格有序 → 不會出現「先改量後改價」卻在通路端順序反過來的問題。

---

### 15.4 商品上架 / 下架（START_SELLING / STOP_SELLING）

```
Topic: shopee.fast
Key:   SP-67890（同 SKU 有序，防止上架下架交錯）

── 上架 ──
{
  "taskAction":    "START_SELLING",
  "sourceJobType": "api",
  "merchantId":    "M001",
  "ownerId":       "CH-SHOPEE-001",
  "payload": {
    "sellPackId":         "SP-67890",
    "productId":          "PRD-002",
    "productName":        "有機綠茶粉",
    "specName":           "200g 罐裝",
    "channelProductId":   "SHOPEE-ITEM-54321",
    "channelSpecId":      "SHOPEE-SPEC-54321-A",
    "channelProductName": "蝦皮有機綠茶粉超值組",
    "channelSpecName":    "200g罐裝",
    "currentStatus":      "STOPPED",
    "price":              450,
    "quantity":           200
  },
  "partitionKey":  "SP-67890",
  "topic":         "shopee.fast",
  "schemaVersion": 1
}

── 下架 ──
{
  "taskAction":    "STOP_SELLING",
  "sourceJobType": "api",
  "merchantId":    "M001",
  "ownerId":       "CH-SHOPEE-001",
  "payload": {
    "sellPackId":         "SP-67890",
    "productId":          "PRD-002",
    "productName":        "有機綠茶粉",
    "specName":           "200g 罐裝",
    "channelProductId":   "SHOPEE-ITEM-54321",
    "channelSpecId":      "SHOPEE-SPEC-54321-A",
    "channelProductName": "蝦皮有機綠茶粉超值組",
    "channelSpecName":    "200g罐裝",
    "currentStatus":      "SELLING"
  },
  "partitionKey":  "SP-67890",
  "topic":         "shopee.fast",
  "schemaVersion": 1
}
```

**事件流（同改價）：**

```
API → shopee.fast → ChannelJob → 呼叫 Shopee API
  ├── 成功 → SyncLog SUCCESS → ack（結束）
  └── 失敗 → task.failed → RetryDispatchJob → task.dlt（fast 不重打）
```

---

### 15.5 出貨確認（SHIPPING_CONFIRMED）— 設計預留

> **⚠️ 出貨是獨立的大議題，本節僅列出基本框架。**
> 實際出貨涉及：分包（一張訂單拆多箱）、物流商串接、封箱、包裝、檢貨（揀貨核對）。
> 這些流程需要獨立的出貨管理表（order_shipments 的擴充）和倉儲作業流程，後續另行設計。

**簡化版事件流（單包出貨）：**

```
客戶在前台點「確認出貨」
  │
  ▼
simpleec-api (POST /api/v1/orders/{id}/ship)
  │  key = orderId（同一張訂單的出貨操作有序）
  ▼
┌─────────────────────────────────────────────────────────┐
│  Topic: yahoo.fast                                      │
│  Key:   ORD-20260209-001                                │
│                                                         │
│  {                                                      │
│    "taskAction":    "SHIPPING_CONFIRMED",               │
│    "sourceJobType": "api",                              │
│    "merchantId":    "M001",                             │
│    "ownerId":       "CH-YAHOO-001",                     │
│    "payload": {                                         │
│      "orderId":           "ORD-20260209-001",           │
│      "channelOrderId":    "YAHOO-ORD-88888",            │
│      "orderItems": [                                    │
│        {                                                │
│          "channelItemId":  "YAHOO-ITEM-001",            │
│          "productName":    "養生雞精禮盒",                │
│          "specName":       "60ml × 12入",               │
│          "quantity":       2                             │
│        }                                                │
│      ],                                                 │
│      "shipmentInfo": {                                  │
│        "trackingNumber":   "7711234567890",             │
│        "shippingCompany":  "黑貓宅急便",                 │
│        "shipDate":         "2026-02-09"                 │
│      }                                                  │
│    },                                                   │
│    "partitionKey":  "ORD-20260209-001",                 │
│    "topic":         "yahoo.fast",                       │
│    "schemaVersion": 1                                   │
│  }                                                      │
└─────────────────────────────────────────────────────────┘
  │
  ▼
ChannelJob → 呼叫 Yahoo API 回報出貨
  ├── 成功 → task.backend (ORDER_STATUS_CHANGED)
  └── 失敗 → task.failed → task.dlt（fast 不重打）
```

**後續需設計的出貨子議題：**

```
┌────────────────────────────────────────────────────────────────┐
│  出貨完整流程（待設計）                                          │
│                                                                │
│  1. 分包（Split Shipment）                                      │
│     一張訂單多個商品 → 拆成多箱出貨 → 每箱各自追蹤號               │
│     需要 order_shipment_items 關聯表                              │
│                                                                │
│  2. 物流商串接                                                   │
│     各物流商 API 不同 → 取號、列印面單、追蹤狀態回寫               │
│     可能需要獨立的 logistics adapter 層                           │
│                                                                │
│  3. 封箱 / 包裝                                                  │
│     記錄每箱的尺寸重量 → 物流商計費依據                            │
│                                                                │
│  4. 檢貨 / 揀貨核對                                              │
│     倉庫作業：揀貨清單 → 掃碼核對 → 確認無誤 → 封箱               │
│     需要 picking_list / packing_list 相關表                      │
│                                                                │
│  5. 平台出貨規則差異                                              │
│     momo: 需回傳物流單號 + 出貨時間                               │
│     shopee: 需先取得 shipping order → 再 confirm ship            │
│     yahoo: 回傳追蹤號即可                                        │
│     pchome: 需回傳物流商代碼 + 追蹤號                             │
│                                                                │
│  ★ 先不開發，但 payload 結構預留 orderItems + shipmentInfo        │
│    以便後續擴充為分包模式                                         │
└────────────────────────────────────────────────────────────────┘
```

---

### 15.6 退款拉取（FETCH_REFUND_ORDERS）

**事件流：**

```
SchedulerJob 排程觸發（每 30 分鐘）
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│  Topic: pchome.slow                                     │
│  Key:   null（round-robin，最大吞吐）                     │
│                                                         │
│  {                                                      │
│    "taskAction":    "FETCH_REFUND_ORDERS",              │
│    "sourceJobType": "scheduler-job",                    │
│    "merchantId":    "M001",                             │
│    "ownerId":       "CH-PCHOME-001",                    │
│    "payload": {                                         │
│      "channelId":   "CH-PCHOME-001",                    │
│      "fromDate":    "2026-02-09T00:00:00Z",             │
│      "toDate":      "2026-02-09T10:30:00Z"              │
│    },                                                   │
│    "partitionKey":  null,                               │
│    "topic":         "pchome.slow",                      │
│    "schemaVersion": 1                                   │
│  }                                                      │
└─────────────────────────────────────────────────────────┘
  │
  ▼
ChannelJob (simpleec-channel-pchome-slow)
  │  FetchRefundOrdersActionService:
  │    doAction() → 呼叫 PChome API 拉退款單
  │    每筆退款 → Hash Dedup → 有變化才送下游
  ▼
┌─────────────────────────────────────────────────────────┐
│  Topic: order.process                                   │
│  Key:   CH-PCHOME-001:M001                              │
│                                                         │
│  {                                                      │
│    "taskAction":    "REFUND_ORDER",                     │
│    "sourceJobType": "channel-job",                      │
│    "merchantId":    "M001",                             │
│    "ownerId":       "CH-PCHOME-001",                    │
│    "payload": {                                         │
│      "channelOrderId": "PC-REFUND-55555",               │
│      "orderStatus":    "REFUNDED",                      │
│      "refundAmount":   1200,                            │
│      "refundReason":   "商品瑕疵",                       │
│      "orderHash":      "sha256:e3b0c44298fc..."         │
│    },                                                   │
│    "partitionKey":  "CH-PCHOME-001:M001",               │
│    "topic":         "order.process",                    │
│    "schemaVersion": 1                                   │
│  }                                                      │
└─────────────────────────────────────────────────────────┘
  │
  ▼
OrderProcessJob
  │  更新 Redis hash → DB upsert → 狀態有變 → 送 task.backend
  ▼
┌─────────────────────────────────────────────────────────┐
│  Topic: task.backend                                    │
│  Key:   M001                                            │
│                                                         │
│  {                                                      │
│    "taskAction":    "ORDER_STATUS_CHANGED",             │
│    "sourceJobType": "order-process-job",                │
│    "merchantId":    "M001",                             │
│    "payload": {                                         │
│      "channelOrderId": "PC-REFUND-55555",               │
│      "orderStatus":    "REFUNDED",                      │
│      "refundAmount":   1200                             │
│    },                                                   │
│    "partitionKey":  "M001",                             │
│    "topic":         "task.backend",                     │
│    "schemaVersion": 1                                   │
│  }                                                      │
└─────────────────────────────────────────────────────────┘
  │
  ▼
BackendJob → 更新 DB + 發通知（結束）
```

> **slow topic 失敗時可以重打：**
> pchome.slow 失敗 → task.failed → RetryDispatchJob 判斷 retryCount < maxRetry → retryCount++ → 重新送回 pchome.slow

---

### 15.7 檢查平台健康（CHECK_HEALTH）

**事件流：**

```
SchedulerJob 排程觸發（每 10 分鐘）
  │  對每個啟用的通路各發一則
  ▼
┌─────────────────────────────────────────────────────────┐
│  Topic: momo.fast                                       │
│  Key:   CH-MOMO-001（同通路健康檢查排隊）                  │
│                                                         │
│  {                                                      │
│    "taskAction":    "CHECK_HEALTH",                     │
│    "sourceJobType": "scheduler-job",                    │
│    "merchantId":    "M001",                             │
│    "ownerId":       "CH-MOMO-001",                      │
│    "payload": {                                         │
│      "channelId":   "CH-MOMO-001",                      │
│      "channelType": "MOMO"                              │
│    },                                                   │
│    "partitionKey":  "CH-MOMO-001",                      │
│    "topic":         "momo.fast",                        │
│    "schemaVersion": 1                                   │
│  }                                                      │
└─────────────────────────────────────────────────────────┘
  │
  ▼
ChannelJob (simpleec-channel-momo-fast)
  │  CheckHealthActionService:
  │    setting()          → 取得 channelId
  │    getPlatformTokens()→ 從 DB 取得 momo token
  │    verifyNeedData()   → 確認 token 存在
  │    doAction()         → 呼叫 momo API 驗證連線
  │      ├── API 回應正常 + Token 有效 → health = "OK"         🟢
  │      ├── API 回應正常 + Token 過期 → health = "TOKEN_INVALID" 🟡
  │      └── API 無回應               → health = "API_DOWN"    🔴
  │    寫入 channel_sync_logs（health 欄位）
  ▼
（結束，無下游 topic。前端 Dashboard 查 channel_sync_logs 最後一筆）
```

> **CHECK_HEALTH 是獨立 action**，不附加在其他操作上。每 10 分鐘獨立巡檢一次，結果寫進 DB，前端 Dashboard 查詢顯示三色燈號。

---

### 15.8 接受買家取消（ACCEPT_BUYER_CANCELLATION）

```
Topic: shopee.fast
Key:   ORD-20260209-002（同訂單有序）

{
  "taskAction":    "ACCEPT_BUYER_CANCELLATION",
  "sourceJobType": "api",
  "merchantId":    "M001",
  "ownerId":       "CH-SHOPEE-001",
  "payload": {
    "orderId":          "ORD-20260209-002",
    "channelOrderId":   "SHOPEE-ORD-77777",
    "buyerName":        "王小明",
    "orderAmount":      1580,
    "cancelReason":     "買家要求取消",
    "orderItems": [
      {
        "channelItemId": "SHOPEE-ITEM-002",
        "productName":   "有機綠茶粉",
        "specName":      "200g 罐裝",
        "quantity":      2,
        "unitPrice":     450
      }
    ]
  },
  "partitionKey":  "ORD-20260209-002",
  "topic":         "shopee.fast",
  "schemaVersion": 1
}
```

**事件流：**

```
API → shopee.fast → ChannelJob → Shopee API (接受取消)
  ├── 成功 → task.backend (ORDER_STATUS_CHANGED, newStatus=CANCELLED)
  │            └── BackendJob → 更新 DB + 退庫存 + 通知
  └── 失敗 → task.failed → task.dlt（fast 不重打）
```

---

### 15.9 排程心跳 + 分發（scheduler topic）

```
HeartbeatTimer 每 1 秒發一則
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│  Topic: scheduler                                       │
│  Key:   null                                            │
│                                                         │
│  {                                                      │
│    "taskAction":    "TICK",                             │
│    "sourceJobType": "heartbeat-timer",                  │
│    "merchantId":    null,                               │
│    "payload": {                                         │
│      "tickTime": "2026-02-09T10:30:01Z"                 │
│    },                                                   │
│    "topic":         "scheduler",                        │
│    "schemaVersion": 1                                   │
│  }                                                      │
└─────────────────────────────────────────────────────────┘
  │
  ▼
SchedulerJob 消費
  │  拿 tickTime → 對照 ScheduleConfig 裡的規則
  │  例：每 300 秒 FETCH_ORDERS，每 600 秒 CHECK_HEALTH
  │  時間到 → 從 DB 查所有啟用的通路 → 每個通路發一則到對應 topic
  ▼
  ├── momo.slow:  { taskAction: "FETCH_ORDERS", ownerId: "CH-MOMO-001", ... }
  ├── shopee.slow: { taskAction: "FETCH_ORDERS", ownerId: "CH-SHOPEE-001", ... }
  ├── momo.fast:  { taskAction: "CHECK_HEALTH", ownerId: "CH-MOMO-001", ... }
  ├── shopee.fast: { taskAction: "CHECK_HEALTH", ownerId: "CH-SHOPEE-001", ... }
  └── task.backend: { taskAction: "DAILY_STATISTICS", merchantId: "M001", ... }
      （僅在商家當地時間 00:05~00:10 時觸發）
```

---

### 15.10 Topic 一覽速查表

| Topic | 消費者 | 典型 Action | Partition Key | 失敗處理 |
|-------|--------|------------|---------------|---------|
| `momo.fast` | ChannelJob | MODIFY_PRICE, MODIFY_QUANTITY, START/STOP_SELLING, SHIPPING_CONFIRMED, CHECK_HEALTH, ACCEPT_BUYER_CANCELLATION | sellPackId / orderId / channelId | ❌ 不重打 → DLT |
| `momo.slow` | ChannelJob | FETCH_ORDERS, FETCH_PRODUCTS, FETCH_REFUND_ORDERS, GET_QUANTITY | null / channelId | ♻️ 可重打 |
| `shopee.fast` | ChannelJob | 同 momo.fast | 同上 | ❌ 不重打 → DLT |
| `shopee.slow` | ChannelJob | 同 momo.slow | 同上 | ♻️ 可重打 |
| `yahoo.fast` | ChannelJob | 同 momo.fast | 同上 | ❌ 不重打 → DLT |
| `yahoo.slow` | ChannelJob | 同 momo.slow | 同上 | ♻️ 可重打 |
| `pchome.fast` | ChannelJob | 同 momo.fast | 同上 | ❌ 不重打 → DLT |
| `pchome.slow` | ChannelJob | 同 momo.slow | 同上 | ♻️ 可重打 |
| `order.process` | OrderProcessJob | 訂單/退款整理 | channelId:merchantId | ♻️ 可重打 |
| `task.backend` | BackendJob | ORDER_STATUS_CHANGED, DAILY_STATISTICS, MANAGE_PARTITIONS | merchantId | ♻️ 可重打 |
| `task.frontend` | FrontendJob | 前台任務 | merchantId | ♻️ 可重打 |
| `scheduler` | SchedulerJob | TICK | null | — |
| `task.failed` | RetryDispatchJob | RETRY_DISPATCH | null | 判斷後 → 重打或 DLT |
| `task.dlt` | 無（人工查看） | 各種死信 | null | 30 天自動清除 |
