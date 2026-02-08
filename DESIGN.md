# ONEEC OMS — 最簡完整設計文件

> 基於現有 oneec-oms 骨架，補齊所有缺口的完整設計
> 目標：登入 → 通路設定 → 三大表（訂單/商品/賣場）+ MQ + JOB + Cache

---

## 0. 現狀盤點

### 已完成（現有 code）

| 層 | 已完成 | 狀態 |
|----|--------|------|
| **Gradle 骨架** | 5 模組 (common/core/channel/web/app) | ✅ 可用 |
| **DB Schema** | 01-original-schema.sql + 02-new-tables.sql（~20 表） | ✅ 可用 |
| **Entity** | Product, ProductSpec, SellPack, Order, OrderItem, OrderStatusLog | ✅ 可用 |
| **Mapper** | 6 個 MyBatis-Plus BaseMapper | ✅ 可用 |
| **Service** | ProductService, OrderService（基本 CRUD） | ✅ 可用 |
| **Controller** | ProductController, OrderController, ChannelActionController, Health | ✅ 可用 |
| **MQ Config** | Exchange + Queue + DLX + Retry topology | ✅ 可用 |
| **MQ Producer** | ChannelActionProducer | ✅ 可用 |
| **MQ Consumer** | ChannelActionConsumer（dispatch to adapter） | ✅ 可用 |
| **Channel 介面** | ChannelAdapter + Factory + MomoAdapter（placeholder） | ✅ 骨架 |
| **Enum** | ChannelType(4), ActionType(34), OrderStatus(9) | ✅ 可用 |
| **Docker** | docker-compose + Dockerfile + nginx.conf | ✅ 可用 |
| **Security** | SecurityConfig（permit all, JWT 預留） | ⚠️ 需完成 |

### 缺口（本文件要補齊）

| 缺口 | 優先度 |
|------|--------|
| **JWT 認證** — account 表有但沒有 login/token 邏輯 | P0 |
| **Redis 快取策略** — Redis 已在 docker-compose 但沒用到 | P1 |
| **任務調度引擎** — Timer(秒級)→Dispatcher→MQ 三層，支援時區 | P0 |
| **task_schedule 表** — 統一排程表（通路/報表/彙整，含時區） | P0 |
| **通路 Adapter 實作** — 4 家都是 placeholder | P1 |
| **SellPack CRUD** — 沒有 SellPackService/Controller | P1 |
| **前端** — 完全沒有 | P0 |
| **Channel CRUD** — 通路連接管理沒有 Service/Controller | P1 |
| **Webhook 接收** — 通路主動推訂單狀態 | P2 |

---

## 1. 資料庫設計（已有，補充說明）

### 1.1 表關係概覽

```
account ←─── 登入用
merchant ──┬── channel ──── channel_setting (momo/shopee/yahoo/pchome)
           └── product ──┬── product_spec (SKU)
                         └── sell_pack (商品×通路上架)

orders ──┬── order_items
         ├── order_status_logs
         ├── order_shipments
         └── refund_orders ── refund_order_items

channel_sync_logs (同步紀錄)
channel_api_versions (API 版本)
```

### 1.2 核心表已建（02-new-tables.sql）

- `product` — 商品主檔（merchant_id + item_number 唯一）
- `product_spec` — 商品規格/SKU
- `sell_pack` — 賣場檔（商品在通路的上架資訊）
- `orders` — 訂單主檔（channel_id + channel_order_id 唯一）
- `order_items` — 訂單明細
- `order_status_logs` — 狀態變更紀錄
- `order_shipments` — 出貨物流
- `refund_orders` / `refund_order_items` — 退貨退款
- `channel_sync_logs` — 通路同步紀錄
- `channel_api_versions` — API 版本追蹤

### 1.3 需新增的表

```sql
-- =============================================
-- 系統設定表（通路 token 的加解密 key 等）
-- =============================================
-- 已有 global_config、merchant_config，暫不需新增

-- =============================================
-- 需確認 account 表的密碼欄位
-- 現有 account 表有 account_password + totp_secret
-- 足以支撐 JWT 登入，不需新增表
-- =============================================
```

**新增 1 張表：`task_schedule`**（見 §5.2 統一排程表 — 服務所有定時任務）

其餘 DB 層不需要改動，現有 schema 已足夠。

---

## 2. 後端架構

### 2.1 模組總覽（現有 + 需補）

```
oneec-oms/
├── oneec-common/          ✅ 已有 — enum, dto, exception
├── oneec-core/            ✅ 已有 — entity, mapper, service（需補 SellPack/Channel service）
├── oneec-channel/         ✅ 已有 — adapter 骨架（需補 4 家實作）
├── oneec-web/             ✅ 已有 — controller（需補 auth/sellpack/channel）
├── oneec-app/             ✅ 已有 — config（需補 JWT filter + Timer/Dispatcher + Redis config）
└── oneec-admin/           ❌ 需新建 — Vue 3 前端
```

### 2.2 需補的後端 Code

#### 2.2.1 JWT 認證（oneec-web + oneec-app）

**新增檔案：**

```
oneec-web/src/main/java/com/oneec/web/
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

#### 2.2.2 SellPack CRUD（oneec-core + oneec-web）

**新增：**

```java
// oneec-core
public class SellPackService {
    PageResult<SellPack> list(merchantId, channelId, status, page, size)
    SellPack getById(id)
    void save(sellPack)
    void updateStatus(id, status)
    List<SellPack> getByProductId(productId)
}

// oneec-web
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

#### 2.2.3 Channel 管理（oneec-core + oneec-web）

**新增：**

```java
// oneec-core
public class ChannelService {
    List<Channel> listByMerchant(merchantId)
    Channel getById(channelId)
    void save(channel)                  // 含 token1~token5 加密
    boolean testConnection(channelId)   // 呼叫 adapter.validateConnection
}

// oneec-web
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {
    GET    /                           → list
    GET    /{id}                       → detail（token 脫敏顯示）
    POST   /                           → create（新增通路連接）
    PUT    /{id}                       → update（更新 token）
    POST   /{id}/test                  → 測試連線
}
```

### 2.3 完整 API 端點清單

```
認證
  POST   /api/v1/auth/login            → JWT token
  GET    /api/v1/auth/me               → 當前帳號
  POST   /api/v1/auth/refresh          → 續期

通路
  GET    /api/v1/channels              → 通路列表
  POST   /api/v1/channels              → 新增通路
  PUT    /api/v1/channels/{id}         → 更新 token
  POST   /api/v1/channels/{id}/test    → 測試連線

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
  GET    /api/v1/orders                → 訂單列表（可篩 channel/status/日期）
  GET    /api/v1/orders/{id}           → 訂單詳情（含 items）
  POST   /api/v1/orders/{id}/ship      → 出貨確認（發 MQ）
  POST   /api/v1/orders/{id}/cancel    → 取消（發 MQ）

通路動作（底層）
  POST   /api/v1/channel-actions/send  → 手動發 MQ 訊息（除錯用）

系統
  GET    /api/v1/health                → 健康檢查
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

### 3.2 快慢分離（JOB 層級）

快慢不是「每支 JOB 同時聽 fast 和 slow」，而是 **JOB 本身就是為快或慢而存在的**：

- **Fast JOB**：用戶觸發、即時性要求高（改價、出貨確認、上下架）。獨立 consumer group，高 concurrency。
- **Slow JOB**：排程觸發、批次處理（拉單、庫存同步）。獨立 consumer group，低 concurrency。

```
舊系統（微服務）：
  call-to-user-job   → 專門處理 fast 事件，Helm 管 pod 數
  call-to-warehouse-job → 專門處理 slow 事件，Helm 管 pod 數
  兩支完全獨立的 pod，各自 scale

新系統（模組化單體）：
  UpdatePriceQtyJob   → @KafkaListener(topics="channel.fast", concurrency="${job.fast.concurrency:8}")
  FetchOrderJob       → @KafkaListener(topics="channel.slow", concurrency="${job.slow.concurrency:4}")
  兩支完全獨立的 @Component，各自 concurrency

場景：slow JOB 正在拉 momo 的 1000 筆訂單（要 30 秒）
      → 用戶此時按「確認出貨」
      → 出貨確認進 fast topic → 不同 JOB、不同 consumer group → 秒級處理
```

### 3.3 Topic 與 Partition 設計

```
─── 通路 Topic（fast + slow）───

channel.fast        16 partitions     用戶操作：改價、改量、出貨、上下架
channel.slow        16 partitions     排程同步：拉單、拉庫存、拉退貨

─── 訂單處理 Topic ───

order.process        8 partitions     訂單整理（與拉單分開！見 §5.3）

─── 業務 JOB Topic ───

task.backend         8 partitions     後端任務：庫存計算、報表、彙整、通知、推薦
task.orderstatus     8 partitions     訂單狀態流轉

─── 基礎設施 ───

task.dlq             4 partitions     死信（永久保留，人工處理）

共 6 個 Topic，60 partitions
```

> **為什麼 report/summary/recommend 合併到 task.backend？**
> 這些都是「後端 JOB」— 事件只告訴它「時間到了」，JOB 根據 `taskAction` 決定做什麼。
> 一支 BackendJob 靠 Factory pattern 分派到不同 Worker，不需要各自獨立 topic。
> 參考舊系統 `recover-data-job`：單一 `call-to-backend` topic，內部 `RevocerDataFactory` 依 event 路由到 25+ 個 Worker。

> **為什麼新增 order.process？**（修正 #4）
> 收訂單（FetchOrderJob）和整理訂單（ProcessOrderJob）必須分開。
> 原因：一次抓三天完成訂單，一家就可能 1000 張，雙十一更是突波。
> 混在一起會造成重複訂單、處理阻塞。分開後中間有 Hash 快取去重（見 §4.6）。

### 3.4 Partition Key 策略（依場景分層）

**核心原則：細 key 穩準確，粗 key（或無 key）穩系統完整。**

| 場景 | Topic | Partition Key | 粒度 | 理由 |
|------|-------|---------------|------|------|
| 拉取訂單 | channel.slow | **無 key**（round-robin） | 最粗 | 最大吞吐，不需要排序 |
| 庫存同步 | channel.slow | `channelId:merchantId` | 粗 | 同通路+商家有序即可 |
| 訂單整理 | order.process | `channelId:merchantId` | 中 | 同通路+商家的訂單按序處理 |
| 改價/改量 | channel.fast | `sellPackId` | **最細** | 同一 SKU 的價量操作必須嚴格有序 |
| 出貨確認 | channel.fast | `orderId` | 細 | 同一訂單的出貨操作有序 |
| 取消訂單 | channel.fast | `orderId` | 細 | 同上 |
| 上下架 | channel.fast | `sellPackId` | 細 | 同商品上下架有序 |
| 訂單狀態更新 | task.orderstatus | `orderId` | 細 | 同一訂單狀態變更有序 |
| 報表/彙整/通知 | task.backend | `merchantId` | 粗 | 同商家有序即可 |

```
為什麼拉單不需要 key？
  拉單事件由排程觸發，每個通路一支定時 → 本身就不會衝突
  round-robin 分散到所有 partition → 最大機器效能
  後面的「訂單整理」才需要 key 確保有序

為什麼改價要細到 sellPackId？
  同一個 SKU 在同一通路上的改價 +5, -3, +10 必須按序
  如果 key 太粗（如 channelId），不同 SKU 的改價會互相卡住
  sellPackId 是賣場檔的 DB ID，最細粒度
```

### 3.5 Partition 數與 Consumer 數

```
原則：
  - partition 數 >= consumer 數（否則有 consumer 閒置）
  - partition 數決定最大並行度
  - 後續擴容只能增加 partition，不能減少

channel.fast    16 partitions × FetchOrderJob 不聽 / UpdatePriceQtyJob concurrency=8 / ShipConfirmJob concurrency=4
channel.slow    16 partitions × FetchOrderJob concurrency=4 / InventorySyncJob concurrency=2
order.process    8 partitions × ProcessOrderJob concurrency=4
task.backend     8 partitions × BackendJob concurrency=4
task.orderstatus 8 partitions × OrderStatusJob concurrency=2

初期 modular monolith（單 JVM）：
  所有 JOB 在同一個 Spring Boot 裡
  每支 JOB 獨立 @KafkaListener + 獨立 consumer group
  concurrency 可透過 application.yml 調整

擴容（未來拆微服務）：
  任何一支 JOB 可以抽出來獨立部署（獨立 Spring Boot app）
  → 同一個 consumer group → Kafka 自動 rebalance
  → 不需要改 code，只需要多起 instance
```

### 3.6 快慢通道分流規則

| 來源 | 動作類型 | Topic | Partition Key | 理由 |
|------|----------|-------|---------------|------|
| Dispatcher 排程 | FETCH_ORDERS | channel.slow | **無**（round-robin） | 最大吞吐，後面整理才需要有序 |
| Dispatcher 排程 | GET_QUANTITY | channel.slow | channelId:merchantId | 同通路+商家有序 |
| Dispatcher 排程 | FETCH_RETURNS | channel.slow | **無**（round-robin） | 同拉單邏輯 |
| FetchOrderJob 產出 | PROCESS_ORDER | order.process | channelId:merchantId | 同通路+商家的訂單按序整理 |
| 用戶操作 | SHIPPING_CONFIRMED | channel.fast | orderId | 同訂單出貨有序 |
| 用戶操作 | MODIFY_PRICE | channel.fast | sellPackId | 同 SKU 改價嚴格有序 |
| 用戶操作 | MODIFY_QUANTITY | channel.fast | sellPackId | 同 SKU 改量嚴格有序 |
| 用戶操作 | START_SELLING | channel.fast | sellPackId | 同商品上下架有序 |
| 用戶操作 | STOP_SELLING | channel.fast | sellPackId | 同商品上下架有序 |
| JOB 自治路由 | * | 由 JOB 決定 | 由 JOB 決定 | 每支 JOB 自己決定往哪個 topic 發 |

### 3.7 KafkaConfig 實作

```java
@Configuration
public class KafkaConfig {

    // ===== Topic 定義 =====
    @Bean
    public NewTopic channelFast() {
        return TopicBuilder.name("channel.fast")
            .partitions(16)
            .replicas(1)         // 開發環境 1，生產環境 3
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")  // 永不刪除
            .build();
    }

    @Bean
    public NewTopic channelSlow() {
        return TopicBuilder.name("channel.slow")
            .partitions(16).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    @Bean
    public NewTopic taskBackend() {
        return TopicBuilder.name("task.backend")
            .partitions(8).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    @Bean
    public NewTopic taskOrderstatus() {
        return TopicBuilder.name("task.orderstatus")
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

    @Bean
    public NewTopic taskDlq() {
        return TopicBuilder.name("task.dlq")
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
     * @param topic  目標 topic（channel.fast / channel.slow / task.backend / ...）
     * @param key    partition key（保證同 key 有序）
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

### 3.9 失敗處理

```
Consumer 收到訊息
  │
  ├── 執行成功
  │   ├── 手動 ack.acknowledge()
  │   ├── 回寫 DB (channel_sync_logs)
  │   └── JOB 自治路由：根據業務邏輯決定是否發送到下一個 topic（見 §5.4）
  │
  └── 執行失敗
      ├── retryCount < 3 → retryCount++ → 重新發到同 topic 同 key（保持順序）
      │   注意：不是 nack，是 ack 原訊息 + 重新 produce 帶 retryCount
      │   這樣不會卡住 partition 裡後面的訊息
      └── retryCount >= 3 → 發到 task.dlq topic → 寫 sync log (FAILED)
                             → ack 原訊息（放行後面的）
                             → DLQ 永久保留，人工介入

重點：
  - 失敗後 ack + re-produce（不是 nack 不 commit）
  - 因為 Kafka 的 partition 是有序的，如果不 ack 就會卡住整個 partition
  - re-produce 到同 topic 同 key → 排到該 partition 最後面 → 繼續有序
  - 後面的訊息不會因為前面失敗被卡住
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
        spring.json.trusted.packages: "com.oneec.*"
```

---

## 4. Redis 快取設計

### 4.1 快取什麼

OMS 的讀取熱點：

| Key Pattern | 資料 | TTL | 理由 |
|-------------|------|-----|------|
| `jwt:blacklist:{token_hash}` | "1" | = token 剩餘有效期 | JWT 登出黑名單 |
| `channel:setting:all` | List\<ChannelSetting\> | 1 小時 | 通路定義很少改 |
| `channel:{merchantId}:list` | List\<Channel\> | 10 分鐘 | 通路列表常讀 |
| `product:{id}` | Product | 5 分鐘 | 商品詳情 |
| `sellpack:{channelId}:{channelProductId}` | SellPack | 5 分鐘 | 通路下單時反查賣場檔 |
| `task:lock:{taskId}` | "1" | = minGapSeconds | Dispatcher 防重複觸發鎖 |
| `task:schedules:enabled` | JSON list | 5 秒 | 所有 enabled 排程快取 |

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
// oneec-app/config/RedisConfig.java（需新增）
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
            .withCacheConfiguration("channelSettings",
                config.entryTtl(Duration.ofHours(1)))
            .withCacheConfiguration("channels",
                config.entryTtl(Duration.ofMinutes(10)))
            .build();
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        // 給 JWT blacklist、distributed lock 用
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

### 4.5 分散式鎖（防重複同步）

```java
// 用 Redis SETNX 實現簡易鎖
@Component
public class DistributedLock {
    private final RedisTemplate<String, String> redis;

    public boolean tryLock(String key, Duration ttl) {
        return Boolean.TRUE.equals(
            redis.opsForValue().setIfAbsent(key, "1", ttl)
        );
    }

    public void unlock(String key) {
        redis.delete(key);
    }
}

// Dispatcher 用法（見 §5.5 ScheduleDispatcher）
String lockKey = "task:lock:" + task.getId();
if (lock.tryLock(lockKey, Duration.ofSeconds(task.getMinGapSeconds()))) {
    taskProducer.send(msg.getTopic(), msg.getPartitionKey(), msg);
    // 不 unlock — TTL 自然過期，防止頻繁觸發
}
```

### 4.6 Order Hash Dedup Cache（拉單→整理之間的去重）

> **修正 #10, #13：** 收訂單和整理訂單是兩支 JOB。中間靠 Hash 快取去重。
> 一次抓三天完成訂單，一家就可能 1000 張。雙十一突波更恐怖。
> 沒有去重 → 每 5 分鐘整理 JOB 都收到一模一樣的 1000 張 → 浪費。

```
Key Pattern:  order:hash:{channelId}:{platformOrderId}
Value:        SHA-256 hash of order data JSON
TTL:          7 天（覆蓋典型 3 天抓取範圍 + 餘裕）

流程：
  1. FetchOrderJob 從通路 API 拉取訂單（如：3 天內的完成訂單）
  2. 對每筆訂單：計算 order data 的 SHA-256 hash
  3. GET order:hash:{channelId}:{platformOrderId}
  4. hash 相同 → SKIP（訂單無變動，不發到 order.process）
  5. hash 不同或不存在 → SEND 到 order.process topic
  6. ProcessOrderJob 處理完後 → SET order:hash:{channelId}:{platformOrderId} = new hash

為什麼是 Producer-side dedup（不是 Consumer-side）：
  - 在 FetchOrderJob 這一端就過濾掉沒變的訂單
  - ProcessOrderJob 只收到真正有變動/新增的訂單
  - 減少 order.process topic 的訊息量，減少 ProcessOrderJob 負擔
  - 和舊系統一樣：通路 JOB 用同樣算法做 hash → 整理 JOB 處理後更新 hash
```

```java
// FetchOrderJob 裡的去重邏輯
for (Order order : fetchedOrders) {
    String hashKey = "order:hash:" + channelId + ":" + order.getPlatformOrderId();
    String newHash = DigestUtils.sha256Hex(JSONMapper.toJSON(order));
    String existingHash = redis.opsForValue().get(hashKey);

    if (!newHash.equals(existingHash)) {
        // 有變動或新訂單 → 發到 order.process
        taskProducer.send("order.process", channelId + ":" + merchantId, orderMsg);
    }
    // hash 相同 → skip，不發
}

// ProcessOrderJob 處理完後更新 hash
redis.opsForValue().set(hashKey, newHash, Duration.ofDays(7));
```

---

## 5. JOB 架構（核心設計）

> **每一支 JOB 都是自治單元。** 參考舊系統 `recover-data-job` / `call-to-user-job` 模式：
> - JOB 消費特定 Kafka topic
> - 內部 Factory pattern 根據 event 路由到對應 Worker
> - Worker 完成後自主決定往哪個 topic 發（不是中央 PipelineExecutor）
> - JOB 本身就是為快或慢而存在，不是同一支 JOB 同時聽快慢
>
> 排程引擎（Timer → Dispatcher）只負責「時間到了就發事件」，具體做什麼由 JOB 自己決定。

### 5.1 JOB = 自治單元（舊系統模式參考）

```
舊系統（微服務，每支 JOB 獨立 pod）：

  ┌─────────────────────────────────────────────┐
  │  recover-data-job (call-to-backend topic)    │
  │                                              │
  │  MainManager extends Thread                  │
  │  while(true) {                               │
  │      records = consumer.poll(1000);           │
  │      for (record : records) {                 │
  │          service = Factory.getService(event); │  ← Factory pattern 路由
  │          service.setting(resource);           │  ← 4 步驟生命週期
  │          service.verifyData();                │
  │          service.getNeedData();               │
  │          service.doQueueJob();                │  ← 可以 send 到其他 topic
  │      }                                        │
  │      consumer.commitAsync();                  │
  │  }                                            │
  │                                              │
  │  25+ Worker 實作（報表、庫存、統計、出貨...）   │
  │  Helm 管 pod 數量                             │
  └─────────────────────────────────────────────┘

新系統（模組化單體，每支 JOB 是 @Component）：

  ┌─────────────────────────────────────────────┐
  │  @Component BackendJob                       │
  │                                              │
  │  @KafkaListener(topics="task.backend",       │
  │    groupId="backend-job",                    │
  │    concurrency="${job.backend.concurrency:4}")│
  │  void handle(TaskMessage msg, Ack ack) {     │
  │      worker = WorkerFactory.get(msg.action); │  ← 同樣 Factory pattern
  │      worker.execute(msg);                    │
  │      ack.acknowledge();                      │
  │      worker.routeNext(taskProducer);         │  ← 自主路由到下一個 topic
  │  }                                           │
  │                                              │
  │  concurrency 可透過 yml 調整（替代 Helm pod） │
  └─────────────────────────────────────────────┘
```

### 5.2 資料模型 — 統一排程表

**不叫 `channel_schedule`，叫 `task_schedule`** — 因為它服務所有類型的定時任務。

```sql
CREATE TABLE public.task_schedule
(
    id                  VARCHAR(20) NOT NULL PRIMARY KEY,

    -- 任務分類
    task_type           VARCHAR(30) NOT NULL,        -- 'channel_action' / 'backend'
    task_action         VARCHAR(60) NOT NULL,        -- 具體動作：'FETCH_ORDERS' / 'DAILY_SALES_REPORT' / ...

    -- 任務歸屬（誰的任務）
    merchant_id         VARCHAR(20) NOT NULL,
    owner_type          VARCHAR(20) NOT NULL DEFAULT 'channel',  -- 'channel' / 'account' / 'merchant' / 'system'
    owner_id            VARCHAR(20),                 -- channel_id / account_id / null(system)

    -- 任務目標
    target_topic        VARCHAR(60) NOT NULL,        -- Kafka topic: 'channel.slow' / 'channel.fast' / 'task.backend'

    -- 排程模式
    schedule_mode       VARCHAR(10) NOT NULL DEFAULT 'interval',  -- 'interval' / 'cron'
    interval_seconds    INT,                         -- interval 模式：每 N 秒
    cron_expression     VARCHAR(64),                 -- cron 模式：'0 8 * * *'（分 時 日 月 週）

    -- 時區（核心！每個任務獨立時區）
    timezone            VARCHAR(40) NOT NULL DEFAULT 'Asia/Taipei',

    -- 防抖控制
    min_gap_seconds     INT DEFAULT 10,              -- 同一任務最小間隔（秒）

    -- 任務 payload 模板（JSON，每次觸發帶入 MQ 訊息）
    payload_template    JSONB DEFAULT '{}',

    -- 狀態
    enabled             BOOLEAN NOT NULL DEFAULT true,
    last_triggered_at   TIMESTAMPTZ,
    last_completed_at   TIMESTAMPTZ,
    last_error          TEXT,

    insert_time         TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified_time       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT task_schedule_unique UNIQUE (owner_type, owner_id, task_action)
);

COMMENT ON TABLE public.task_schedule IS '統一任務排程表 — 所有定時任務的設定';
COMMENT ON COLUMN public.task_schedule.task_type IS '任務分類：channel_action=通路操作, backend=後端任務（報表/彙整/通知）';
COMMENT ON COLUMN public.task_schedule.owner_type IS '任務歸屬：channel=某通路, account=某帳號, merchant=某商家, system=全域';
COMMENT ON COLUMN public.task_schedule.interval_seconds IS 'interval 模式以秒計';
COMMENT ON COLUMN public.task_schedule.timezone IS '判斷 cron 用的時區';

CREATE INDEX task_schedule_enabled_idx ON public.task_schedule (enabled, task_type);
CREATE INDEX task_schedule_owner_idx ON public.task_schedule (owner_type, owner_id);
```

### 5.3 各類任務範例（seed data）

```sql
-- ==============================
-- 通路任務：拉單（24小時不間斷）、庫存同步（一天兩次）
-- ==============================
INSERT INTO task_schedule (id, task_type, task_action, merchant_id,
    owner_type, owner_id, target_topic,
    schedule_mode, interval_seconds, cron_expression, timezone, min_gap_seconds)
VALUES
    -- momo: 每 300 秒 (5分鐘) 拉一次單，24小時不間斷
    ('ts_001', 'channel_action', 'FETCH_ORDERS', 'M001',
     'channel', 'ch_momo_001', 'channel.slow',
     'interval', 300, null, 'Asia/Taipei', 60),

    -- momo: 庫存同步，一天兩次（08:00, 14:00）
    ('ts_002', 'channel_action', 'GET_QUANTITY', 'M001',
     'channel', 'ch_momo_001', 'channel.slow',
     'cron', null, '0 8,14 * * *', 'Asia/Taipei', 3600),

    -- shopee: 每 300 秒拉單，24小時不間斷（曼谷時間）
    ('ts_003', 'channel_action', 'FETCH_ORDERS', 'M001',
     'channel', 'ch_shopee_001', 'channel.slow',
     'interval', 300, null, 'Asia/Bangkok', 60),

    -- yahoo: cron 模式，每天 8:00 14:00 20:00（台北時間）
    ('ts_004', 'channel_action', 'FETCH_ORDERS', 'M001',
     'channel', 'ch_yahoo_001', 'channel.slow',
     'cron', null, '0 8,14,20 * * *', 'Asia/Taipei', 60);

-- ==============================
-- 報表/彙整任務 → 都走 task.backend（事件只告訴時間，JOB 根據 action 決定做什麼）
-- ==============================
INSERT INTO task_schedule (id, task_type, task_action, merchant_id,
    owner_type, owner_id, target_topic,
    schedule_mode, cron_expression, timezone, payload_template)
VALUES
    -- 老闆 A (台北): 每天 20:00 收日報
    ('ts_010', 'backend', 'DAILY_SALES_REPORT', 'M001',
     'account', 'acc_boss_A', 'task.backend',
     'cron', '0 20 * * *', 'Asia/Taipei',
     '{"reportType": "daily_sales", "format": "email"}'),

    -- 老闆 B (東京辦公室): 同樣「每天 20:00」，但是東京時間
    ('ts_011', 'backend', 'DAILY_SALES_REPORT', 'M001',
     'account', 'acc_boss_B', 'task.backend',
     'cron', '0 20 * * *', 'Asia/Tokyo',
     '{"reportType": "daily_sales", "format": "email"}'),

    -- 商家 M001 整體：每天 08:00 (台北) 產昨日彙整
    ('ts_020', 'backend', 'YESTERDAY_ORDERS', 'M001',
     'merchant', 'M001', 'task.backend',
     'cron', '0 8 * * *', 'Asia/Taipei',
     '{"summaryType": "yesterday_orders"}'),

    ('ts_021', 'backend', 'YESTERDAY_INVENTORY', 'M001',
     'merchant', 'M001', 'task.backend',
     'cron', '0 8 * * *', 'Asia/Taipei',
     '{"summaryType": "yesterday_inventory"}');
```

> **庫存同步一天兩次就夠了**（修正 #2），不是每 10 分鐘。
> **拉單 24 小時不間斷**（修正 #3），無 active_hours 限制。物流變動在任何時間發生。
> **報表/彙整都走 task.backend**（修正 #5），BackendJob 根據 `taskAction` 分派。

### 5.4 Timer — 可設定心跳

> **修正 #6：** 心跳頻率可設定。舊系統是 `while(true) { sleep(SLEEP_TIME); sendJobToQueue(); }`

```java
/**
 * 心跳計時器 — 頻率可透過 yml 設定
 *
 * 參考舊系統 CreatingScheduleJobServiceImpl:
 *   while (true) { service.sendJobToQueue(); Thread.sleep(SLEEP_TIME * 1000); }
 *
 * 新系統用 @Scheduled + yml 設定，效果一樣但更 Spring-native
 */
@Component
@Slf4j
public class ScheduleTimer {

    private final ScheduleDispatcher dispatcher;

    @Scheduled(fixedRateString = "${scheduler.heartbeat-ms:1000}")
    public void tick() {
        dispatcher.evaluate(Instant.now());
    }
}
```

```yaml
# application.yml
scheduler:
  heartbeat-ms: 1000    # 預設 1 秒，可調整
```

### 5.5 Dispatcher — 調度中間層（核心引擎）

```java
/**
 * 系統級任務調度引擎
 *
 * 被 Timer 定期呼叫：
 * 1. 從 Redis 快取載入所有 enabled 的 task_schedule（TTL 5s，每 5 秒刷新一次 DB）
 * 2. 對每條排程：Memory-first 檢查 → 轉時區 → 匹配 cron/interval
 * 3. 該發動 → 取 Redis 鎖 → 發 Kafka → 記錄觸發時間
 * 4. 不該發動 → skip（純記憶體運算，成本 ≈ 0）
 *
 * 效能考量（Memory-first short-circuit）：
 * - ConcurrentHashMap 存每個 task 的 lastTriggerEpoch
 * - 100 條排程 × 每秒 → 100 次 HashMap.get() → 比 Redis 快 1000 倍
 * - 99% 的 tick 在 memory min_gap check 就 short-circuit 了，完全不碰 Redis
 * - 只有真正要觸發時才碰 Redis（取鎖 + 記錄）
 * - 1000 條排程每秒處理 < 1ms
 */
@Component
@Slf4j
public class ScheduleDispatcher {

    private final TaskScheduleService scheduleService;
    private final TaskProducer taskProducer;            // Kafka producer（見 §3.8）
    private final DistributedLock lock;

    /**
     * Memory-first: 本地記憶體快取每個 task 的上次觸發時間
     * Key = taskId, Value = epochSecond
     *
     * 為什麼用 ConcurrentHashMap 而不是每次查 Redis？
     * - Timer 每秒 tick，100 條排程 = 每秒 100 次 Redis GET → 浪費
     * - 大多數 tick，距上次觸發 < min_gap_seconds → 直接跳過
     * - 記憶體查 HashMap.get() < 1μs，Redis GET ~ 0.5ms → 差 500 倍
     * - 只有通過 memory check 的才需要 Redis 鎖（極少數）
     *
     * 多實例場景：每個 JVM 各自維護自己的 map，Redis 鎖保證不重複觸發
     */
    private final ConcurrentHashMap<String, Long> lastTriggerCache = new ConcurrentHashMap<>();

    public void evaluate(Instant now) {
        List<TaskSchedule> schedules = scheduleService.listEnabled(); // Redis 快取，TTL 5s

        for (TaskSchedule task : schedules) {
            try {
                if (shouldTrigger(task, now)) {
                    dispatch(task, now);
                }
            } catch (Exception e) {
                log.error("Dispatch error for task {}: {}", task.getId(), e.getMessage());
            }
        }
    }

    private boolean shouldTrigger(TaskSchedule task, Instant now) {
        long nowEpoch = now.getEpochSecond();

        // ★ Memory-first: 最快 short-circuit，不碰 Redis
        Long lastEpoch = lastTriggerCache.get(task.getId());
        if (lastEpoch != null && (nowEpoch - lastEpoch) < task.getMinGapSeconds()) {
            return false;  // 99% 的 tick 在這裡結束
        }

        // 1. 轉時區
        ZoneId zone = ZoneId.of(task.getTimezone());
        ZonedDateTime localNow = now.atZone(zone);

        // 2. 排程模式判斷（用 memory cache 的 lastEpoch）
        if ("interval".equals(task.getScheduleMode())) {
            return shouldTriggerInterval(task, nowEpoch, lastEpoch);
        } else {
            return shouldTriggerCron(task, localNow, lastEpoch, nowEpoch);
        }
    }

    /**
     * interval 模式：距上次觸發 >= interval_seconds
     */
    private boolean shouldTriggerInterval(TaskSchedule task, long nowEpoch, Long lastEpoch) {
        if (lastEpoch == null) return true;  // 從沒觸發過
        return (nowEpoch - lastEpoch) >= task.getIntervalSeconds();
    }

    /**
     * cron 模式：當前分鐘是否匹配 cron（在本地時區下）
     *
     * 用 Spring CronExpression，以本地時區計算
     * cron 存 5 欄位（分 時 日 月 週），轉 Spring 6 欄位時前加 "0 "
     *
     * 因為 Timer 每秒 tick，cron 在匹配的那一分鐘內只應觸發一次
     * → 靠 min_gap_seconds >= 60 保證不重複
     */
    private boolean shouldTriggerCron(TaskSchedule task, ZonedDateTime localNow, Long lastEpoch, long nowEpoch) {
        String springCron = "0 " + task.getCronExpression();
        CronExpression cron = CronExpression.parse(springCron);

        LocalDateTime thisMinute = localNow.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime nextAfterPrev = cron.next(thisMinute.minusMinutes(1));

        if (nextAfterPrev != null && nextAfterPrev.equals(thisMinute)) {
            // 匹配！確認這分鐘還沒觸發過
            if (lastEpoch == null) return true;
            return (nowEpoch - lastEpoch) >= 60;
        }
        return false;
    }

    /**
     * 發動：取鎖 → 記錄 → 發 Kafka
     */
    private void dispatch(TaskSchedule task, Instant now) {
        // Redis 鎖，防止多實例重複發
        String lockKey = "task:lock:" + task.getId();
        if (!lock.tryLock(lockKey, Duration.ofSeconds(task.getMinGapSeconds()))) {
            return;
        }

        // ★ 更新本地 memory cache
        lastTriggerCache.put(task.getId(), now.getEpochSecond());

        // 目標 topic 直接從 task_schedule 讀取
        String topic = task.getTargetTopic();
        String partitionKey = buildPartitionKey(task);

        // 組裝 payload
        Map<String, Object> payload = buildPayload(task, now);

        TaskMessage msg = TaskMessage.builder()
            .messageId(UUID.randomUUID().toString())
            .taskType(task.getTaskType())
            .taskAction(task.getTaskAction())
            .merchantId(task.getMerchantId())
            .ownerType(task.getOwnerType())
            .ownerId(task.getOwnerId())
            .timezone(task.getTimezone())
            .payload(payload)
            .createdAt(now)
            .retryCount(0)
            .topic(topic)
            .partitionKey(partitionKey)
            .build();

        // 發送到 Kafka（partition key 保證同 key 有序）
        taskProducer.send(topic, partitionKey, msg);

        // 非同步回寫 DB
        scheduleService.updateLastTriggered(task.getId(), now);

        log.info("Dispatched [{}] {} → {} key={} (tz={}, local={})",
            task.getTaskType(), task.getTaskAction(), topic, partitionKey,
            task.getTimezone(), now.atZone(ZoneId.of(task.getTimezone())).toLocalTime());
    }

    /** 依場景建立 partition key（見 §3.4 策略表） */
    private String buildPartitionKey(TaskSchedule task) {
        if ("channel".equals(task.getOwnerType())) return task.getOwnerId();
        return task.getMerchantId();
    }

    private Map<String, Object> buildPayload(TaskSchedule task, Instant now) {
        Map<String, Object> payload = new HashMap<>(task.getPayloadTemplate());
        payload.put("triggeredAt", now.toString());
        payload.put("timezone", task.getTimezone());

        // 通路拉單特殊處理：帶入時間範圍
        if ("FETCH_ORDERS".equals(task.getTaskAction())) {
            Instant from = task.getLastTriggeredAt() != null
                ? task.getLastTriggeredAt().minus(Duration.ofSeconds(120))
                : now.minus(Duration.ofSeconds(
                    task.getIntervalSeconds() != null ? task.getIntervalSeconds() + 120 : 720));
            payload.put("from", from.toString());
            payload.put("to", now.toString());
        }

        return payload;
    }
}
```

### 5.6 統一訊息格式 — TaskMessage

```java
/**
 * 所有任務的統一 MQ 訊息格式
 * 不管是通路拉單、報表生成、還是每日彙整，都用這個格式
 * JOB 收到後根據 taskAction 分派到對應 Worker
 */
@Data
@Builder
public class TaskMessage {
    // === 基本資訊 ===
    private String messageId;          // UUID，每條訊息唯一
    private String taskType;           // channel_action / backend / orderstatus / recommend / report / summary
    private String taskAction;         // FETCH_ORDERS / DAILY_SALES_REPORT / PROCESS_ORDER_STATUS / ...
    private String merchantId;
    private String ownerType;          // channel / account / merchant / system
    private String ownerId;            // channel_id / account_id / ...
    private String timezone;
    private Map<String, Object> payload;
    private Instant createdAt;
    private int retryCount;

    // === Kafka routing ===
    private String topic;              // 目標 topic: "channel.fast" / "channel.slow" / "task.backend" / ...
    private String partitionKey;       // Kafka partition key，保證同 key 有序
                                       // 例: sellPackId → 同 SKU 同 partition

    // === 追蹤 ===
    private String sourceJobType;      // 哪支 JOB 產出的（tracing 用）
}
```

> **注意：** 現有的 `ChannelActionMessage` 由 `TaskMessage` 取代。一個格式走天下。
> 沒有 pipelineId / stepIndex — 每支 JOB 自治路由，不需要中央追蹤（見 §5.10）。

### 5.7 JOB 類型總覽

> 每支 JOB = 獨立 @Component + @KafkaListener，各自消費特定 topic。
> JOB 本身就是為快或慢而存在（修正 #8），不是同一支同時聽快慢。

| JOB | Topic | Consumer Group | Concurrency | Partition Key | 職責 |
|-----|-------|---------------|-------------|---------------|------|
| FetchOrderJob | channel.slow | fetch-order | 4 | 無（round-robin） | 24hr 拉單，hash dedup 後發到 order.process |
| ProcessOrderJob | order.process | process-order | 4 | channelId:merchantId | 訂單整理、入庫、發 task.orderstatus |
| UpdatePriceQtyJob | channel.fast | update-price-qty | 8 | sellPackId | 改價改量，呼叫通路 API |
| ShipConfirmJob | channel.fast | ship-confirm | 4 | orderId | 出貨確認，呼叫通路 API，發 task.orderstatus |
| InventorySyncJob | channel.slow | inventory-sync | 2 | channelId:merchantId | 庫存同步（一天兩次） |
| OrderStatusJob | task.orderstatus | order-status | 2 | orderId | 訂單狀態比對、更新 |
| BackendJob | task.backend | backend | 4 | merchantId | 報表/彙整/通知/推薦（Factory 分派） |

### 5.8 各 JOB 實作

> 每支 JOB 獨立 @Component，各自 @KafkaListener。
> JOB 完成後**自主決定**往哪個 topic 發（不是 PipelineExecutor）。

```java
// ===== FetchOrderJob — 拉單（slow, 24hr, NO key）=====
@Component
@Slf4j
public class FetchOrderJob {

    private final ChannelAdapterFactory factory;
    private final TaskProducer taskProducer;
    private final RedisTemplate<String, String> redis;

    @KafkaListener(topics = "channel.slow", groupId = "fetch-order",
        concurrency = "${job.fetch-order.concurrency:4}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        if (!"FETCH_ORDERS".equals(msg.getTaskAction())) return;  // 不是自己的 action → skip
        try {
            ChannelAdapter adapter = factory.getAdapter(resolveChannelType(msg));
            List<Order> orders = adapter.fetchOrders(msg.getOwnerId(),
                Instant.parse((String) msg.getPayload().get("from")),
                Instant.parse((String) msg.getPayload().get("to")));

            // ★ Hash dedup（見 §4.6）：只發有變動的訂單到 order.process
            for (Order order : orders) {
                String hashKey = "order:hash:" + msg.getOwnerId() + ":" + order.getPlatformOrderId();
                String newHash = DigestUtils.sha256Hex(toJSON(order));
                String existing = redis.opsForValue().get(hashKey);

                if (!newHash.equals(existing)) {
                    TaskMessage processMsg = buildProcessMessage(msg, order, newHash);
                    taskProducer.send("order.process",
                        msg.getOwnerId() + ":" + msg.getMerchantId(), processMsg);
                }
            }
            ack.acknowledge();
        } catch (Exception e) {
            handleFailure(msg, ack, e);
        }
    }
}

// ===== ProcessOrderJob — 訂單整理（order.process, key=channelId:merchantId）=====
@Component
public class ProcessOrderJob {

    @KafkaListener(topics = "order.process", groupId = "process-order",
        concurrency = "${job.process-order.concurrency:4}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        try {
            // 整理訂單：比對本地 → INSERT/UPDATE → 寫 order_status_logs
            orderService.processOrder(msg);

            // 處理完 → 更新 Redis hash
            redis.opsForValue().set(msg.getPayload().get("hashKey").toString(),
                msg.getPayload().get("hash").toString(), Duration.ofDays(7));

            // ★ 自治路由：發到 task.orderstatus 做後續狀態同步
            taskProducer.send("task.orderstatus", msg.getPayload().get("orderId").toString(),
                buildStatusMessage(msg));

            ack.acknowledge();
        } catch (Exception e) {
            handleFailure(msg, ack, e);
        }
    }
}

// ===== UpdatePriceQtyJob — 改價改量（fast, key=sellPackId）=====
@Component
public class UpdatePriceQtyJob {

    @KafkaListener(topics = "channel.fast", groupId = "update-price-qty",
        concurrency = "${job.update-price-qty.concurrency:8}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        if (!Set.of("MODIFY_PRICE","MODIFY_QUANTITY","START_SELLING","STOP_SELLING")
                .contains(msg.getTaskAction())) return;
        try {
            ChannelAdapter adapter = factory.getAdapter(resolveChannelType(msg));
            executeAction(adapter, msg);
            ack.acknowledge();
        } catch (Exception e) {
            handleFailure(msg, ack, e);
        }
    }
}

// ===== ShipConfirmJob — 出貨確認（fast, key=orderId）=====
@Component
public class ShipConfirmJob {

    @KafkaListener(topics = "channel.fast", groupId = "ship-confirm",
        concurrency = "${job.ship-confirm.concurrency:4}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        if (!"SHIPPING_CONFIRMED".equals(msg.getTaskAction())) return;
        try {
            ChannelAdapter adapter = factory.getAdapter(resolveChannelType(msg));
            adapter.confirmShipment(msg.getOwnerId(),
                (String) msg.getPayload().get("channelOrderId"),
                (String) msg.getPayload().get("trackingNumber"),
                (String) msg.getPayload().get("logisticsCompany"));
            ack.acknowledge();

            // ★ 自治路由：發到 task.orderstatus 更新出貨狀態
            taskProducer.send("task.orderstatus", msg.getPayload().get("orderId").toString(),
                buildStatusMessage(msg));
        } catch (Exception e) {
            handleFailure(msg, ack, e);
        }
    }
}

// ===== BackendJob — 報表/彙整/通知/推薦（Factory pattern）=====
@Component
public class BackendJob {

    @KafkaListener(topics = "task.backend", groupId = "backend",
        concurrency = "${job.backend.concurrency:4}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        try {
            // Factory pattern 路由（參考 recover-data-job 的 RevocerDataFactory）
            BackendWorker worker = BackendWorkerFactory.getWorker(msg.getTaskAction());
            worker.execute(msg);
            ack.acknowledge();
        } catch (Exception e) {
            handleFailure(msg, ack, e);
        }
    }
}

// ===== OrderStatusJob — 訂單狀態（key=orderId）=====
@Component
public class OrderStatusJob {

    @KafkaListener(topics = "task.orderstatus", groupId = "order-status",
        concurrency = "${job.order-status.concurrency:2}")
    public void handle(TaskMessage msg, Acknowledgment ack) {
        try {
            orderStatusService.syncStatus(msg);
            ack.acknowledge();
        } catch (Exception e) {
            handleFailure(msg, ack, e);
        }
    }
}

// ===== 共用失敗處理（所有 JOB 都用同一套）=====
private void handleFailure(TaskMessage msg, Acknowledgment ack, Exception e) {
    ack.acknowledge();  // 先放行 partition
    if (msg.getRetryCount() < 3) {
        msg.setRetryCount(msg.getRetryCount() + 1);
        taskProducer.send(msg.getTopic(), msg.getPartitionKey(), msg);
    } else {
        taskProducer.send("task.dlq", msg.getPartitionKey(), msg);
    }
}
```

> **注意 channel.fast 上有兩支 JOB**（UpdatePriceQtyJob + ShipConfirmJob），
> 它們用不同的 `groupId`，所以 Kafka 會把每條訊息都發給兩支。
> 各 JOB 用 `taskAction` 過濾：不是自己的 action 就 return。
> 這等同於舊系統 `call-to-user-job` 跟 `call-to-warehouse-job` 同時聽 `call-to-user` topic。

### 5.9 完整流程圖

```
每秒
  │
  ▼
┌──────────┐
│  Timer   │  @Scheduled(fixedRate = 1000)
│  (1s)    │  zero logic, just tick
└────┬─────┘
     │ dispatcher.evaluate(now)
     ▼
┌──────────────────────────────────────────────────────────────┐
│  Dispatcher（系統級任務引擎）                                  │
│                                                              │
│  for each enabled task_schedule:                             │
│                                                              │
│    ⓪ Memory-first: ConcurrentHashMap 查 lastTrigger         │
│       99% 的 tick 在這裡 short-circuit → skip（< 1μs）       │
│                                                              │
│    ① now → 轉成目標時區                                      │
│    ② interval 或 cron 匹配？                                 │
│    ④ 取 Redis 鎖 → 組裝 TaskMessage → 發 Kafka              │
│      partition key 保證同商品/通路有序                        │
│                                                              │
└──────┬───────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────┐
│  Kafka（6 Topics）                                           │
│                                                              │
│  ┌─ 通路 Topics ────────────────────────────────────────┐   │
│  │ channel.fast  (16p)                                  │   │
│  │   └▶ UpdatePriceQtyJob ×8 / ShipConfirmJob ×4       │   │
│  │ channel.slow  (16p)                                  │   │
│  │   └▶ FetchOrderJob ×4 / InventorySyncJob ×2         │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌─ 訂單處理 + 業務 Topics ───────────────────────────── ┐   │
│  │ order.process    (8p)   ──▶ ProcessOrderJob ×4       │   │
│  │ task.backend     (8p)   ──▶ BackendJob ×4            │   │
│  │ task.orderstatus (8p)   ──▶ OrderStatusJob ×2        │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  task.dlq (永久保留)                                         │
│                                                              │
│  ★ JOB 自治路由：FetchOrder → order.process → orderstatus   │
│    每支 JOB 自己決定往哪發（不是 PipelineExecutor）           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 5.10 時區差異的具體範例

```
場景 1：通路拉單 — 三家通路都設「24hr 拉單」，不同排程模式

通路          時區              排程模式          UTC 08:00 時
──────────────────────────────────────────────────────────────
momo          Asia/Taipei       interval 300s    ✅ 觸發（台北 16:00，距上次 > 300s）
shopee-th     Asia/Bangkok      interval 300s    ✅ 觸發（曼谷 15:00，距上次 > 300s）
yahoo         Asia/Taipei       cron 8,14,20     ❌ skip（台北 16:00 不在 cron 時間）

場景 2：業務報表 — 兩個人都要「晚上八點看日報」

帳號          時區              cron              實際 UTC 觸發
──────────────────────────────────────────────────────────────
老闆 A        Asia/Taipei       0 20 * * *        12:00 UTC
老闆 B        Asia/Tokyo        0 20 * * *        11:00 UTC  ← 東京的 20:00 比台北早 1 小時

場景 3：昨日彙整 — 商家設「早上八點看昨日訂單」

任務          時區              cron              實際 UTC 觸發
──────────────────────────────────────────────────────────────
M001 昨日訂單  Asia/Taipei       0 8 * * *         00:00 UTC
M002 昨日訂單  America/New_York  0 8 * * *         13:00 UTC  ← 紐約 08:00
```

### 5.11 排程管理 API

```
GET    /api/v1/schedules                          → 所有排程（可篩 task_type, owner_type）
GET    /api/v1/schedules?ownerType=channel&ownerId={channelId}  → 某通路的排程
GET    /api/v1/schedules?ownerType=account&ownerId={accountId}  → 某帳號的排程
PUT    /api/v1/schedules/{id}                     → 更新排程設定
POST   /api/v1/schedules/{id}/trigger             → 手動觸發（測試用）
POST   /api/v1/schedules                          → 新增排程
DELETE /api/v1/schedules/{id}                     → 刪除排程
```

### 5.12 前端 UI

**通路設定頁 — 排程 Tab：**

```
┌─────────────────────────────────────────────────────┐
│ momo 通路設定                                        │
│                                                     │
│ [基本設定] [Token] [排程設定]                         │
│                                                     │
│ 時區: [Asia/Taipei ▼]                               │
│                                                     │
│ ┌──────────┬──────┬───────────┬────────┬──────┐    │
│ │任務       │模式   │設定        │上次觸發 │狀態   │    │
│ ├──────────┼──────┼───────────┼────────┼──────┤    │
│ │拉取訂單   │間隔   │每 300 秒   │2分前    │✅啟用 │    │
│ │同步庫存   │Cron  │8,14 時     │6小時前  │✅啟用 │    │
│ │拉取退貨   │Cron  │8,14,20 時 │6小時前  │⬚停用 │    │
│ └──────────┴──────┴───────────┴────────┴──────┘    │
│                                                     │
│ [手動觸發拉單]  [儲存]                               │
└─────────────────────────────────────────────────────┘
```

**報表/彙整設定頁（獨立頁面或在「系統設定」裡）：**

```
┌─────────────────────────────────────────────────────┐
│ 定時任務管理                                          │
│                                                     │
│ ┌──────────────┬──────┬──────────┬────────┬──────┐ │
│ │任務           │對象   │排程       │時區     │狀態   │ │
│ ├──────────────┼──────┼──────────┼────────┼──────┤ │
│ │業績日報       │老闆A  │每天 20:00│Taipei  │✅啟用 │ │
│ │業績日報       │老闆B  │每天 20:00│Tokyo   │✅啟用 │ │
│ │昨日訂單彙整   │商家M001│每天 08:00│Taipei  │✅啟用 │ │
│ │昨日庫存彙整   │商家M001│每天 08:00│Taipei  │✅啟用 │ │
│ └──────────────┴──────┴──────────┴────────┴──────┘ │
│                                                     │
│ [+ 新增排程]                                         │
└─────────────────────────────────────────────────────┘
```

### 5.13 Redis Key（完整）

```
task:lock:{taskId}                          → "1"        (Dispatcher 防重複鎖，TTL = minGapSeconds)
task:schedules:enabled                      → JSON list  (所有 enabled 排程快取，TTL 5s)
jwt:blacklist:{tokenHash}                   → "1"        (JWT 登出黑名單，TTL = token 剩餘有效期)
order:hash:{channelId}:{platformOrderId}    → SHA-256    (訂單 hash dedup，TTL 7 天，見 §4.6)

注意：
  - task:last 已被 ConcurrentHashMap 取代（memory-first），不再存 Redis
  - order:hash 是 Producer-side dedup（FetchOrderJob → ProcessOrderJob 之間），不是 Consumer-side
```

### 5.14 為什麼不用 Quartz / xxl-job

| | Quartz | xxl-job | 本設計 (Timer→Dispatcher) |
|---|---|---|---|
| 複雜度 | 高（需要 11 張 DB 表） | 中（獨立服務） | 低（1 張表 + Redis） |
| 時區支援 | 要自己處理 | 要自己處理 | 核心設計 |
| 秒級精度 | ✅ | ✅ | ✅ |
| 分散式 | 需要 DB 鎖 | 內建 | Redis 鎖 |
| 動態修改 | 複雜 | API 改 | 改 DB 即生效 |
| 額外依賴 | quartz-scheduler | 獨立 JVM | 無（純 Spring + Redis） |
| 適合場景 | 企業級大量排程 | 微服務叢集 | OMS 業務排程 |

**本設計的優勢：零額外依賴，時區是一等公民，改 DB 就生效。**

---

### 5.15 JOB 間自治路由（取代 PipelineExecutor）

> **修正 #11：** 沒有中央 PipelineExecutor。每支 JOB 是自治單元，自己決定往哪個 topic 發。
> Pipeline 是 **emergent** 的 — 從各 JOB 的路由邏輯自然形成的。

JOB 間的路由是自然形成的，不需要資料庫定義，不需要中央編排器：

| JOB | 完成後路由到 | 路由條件 |
|-----|-------------|----------|
| FetchOrderJob | order.process | hash 不同的訂單才發 |
| ProcessOrderJob | task.orderstatus | 訂單整理完，發狀態更新 |
| ShipConfirmJob | task.orderstatus | 出貨確認完，發狀態更新 |
| OrderStatusJob | task.backend（可選） | 如需發通知 |
| UpdatePriceQtyJob | 無 | 改價改量是終端操作 |
| InventorySyncJob | 無 | 庫存同步是終端操作 |
| BackendJob | 依 worker 決定 | Factory 內的 worker 可以發到任何 topic |

```
典型訂單流程（emergent pipeline）：

  Dispatcher → channel.slow (FETCH_ORDERS)
    └─ FetchOrderJob 拉單 → hash dedup → order.process
        └─ ProcessOrderJob 整理 → task.orderstatus
            └─ OrderStatusJob 狀態同步 → task.backend（通知）
                └─ BackendJob 發通知

每一步都是獨立 JOB，自己決定往哪發。
斷在哪裡就停在哪裡，DLQ 保留上下文，管理員可手動 requeue。
不需要 pipelineId、不需要 stepIndex、不需要 PipelineExecutor。
```

### 5.16 JOB 並行度管理（模組化單體 vs Helm pods）

> **修正 #8：** 舊系統用 Helm 管 pod 支數。新系統的替代方案？

```
舊系統（微服務）：
  helm install --set replicaCount=3 call-to-user-job
  helm install --set replicaCount=1 call-to-warehouse-job
  → 每支 JOB 獨立 pod，獨立 scale

新系統（模組化單體）：
  1. @KafkaListener concurrency 參數
     concurrency="${job.fetch-order.concurrency:4}"
     → 效果等同於 4 個 consumer thread，各處理不同 partition

  2. application.yml 環境配置
     job:
       fetch-order:
         concurrency: 4      # slow, 排程觸發
       update-price-qty:
         concurrency: 8      # fast, 用戶觸發, 高並行
       ship-confirm:
         concurrency: 4      # fast
       process-order:
         concurrency: 4
       backend:
         concurrency: 4
       order-status:
         concurrency: 2
       inventory-sync:
         concurrency: 2

  3. 未來拆微服務
     任何一支 JOB 可以抽出來獨立部署
     → 只需要另起一個 Spring Boot app + 同一個 consumer group
     → Kafka 自動 rebalance，零代碼修改
```

---
## 6. 通路串接設計

### 6.1 ChannelAdapter 介面（已有，完整）

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

### 6.2 四家通路的 API 特性差異

| | momo | Shopee | Yahoo | PChome |
|---|---|---|---|---|
| **認證** | vendor_code + sign (HMAC) | partner_id + shop_id + sign (HMAC-SHA256) | app_key + access_token (OAuth 2.0) | vendor_code + HMAC |
| **拉單** | POST /VendorApi/OrderQuery | GET /api/v2/order/get_order_list | GET /api/order/list | GET /api/order/list |
| **出貨確認** | POST /VendorApi/ShipConfirm | POST /api/v2/logistics/ship_order | POST /api/order/ship | POST /api/order/ship |
| **庫存更新** | POST /VendorApi/GoodsStockModify | POST /api/v2/product/update_stock | PUT /api/product/stock | PUT /api/product/qty |
| **Webhook** | 無（只能輪詢） | 有（push 通知） | 有 | 有 |
| **Rate Limit** | 低（需注意） | 高（有明確限制文件） | 中 | 中 |
| **特殊** | 轉單模式 | 有 shop-level token refresh | OAuth token refresh | 寄倉模式 |

### 6.3 每家 Adapter 結構（以 momo 為例，已有骨架）

```
oneec-channel/src/main/java/com/oneec/channel/
├── ChannelAdapter.java              ← 介面
├── ChannelAdapterFactory.java       ← 工廠（自動注冊）
├── ChannelActionMessage.java        ← MQ 訊息
├── ChannelActionProducer.java       ← MQ 發送
├── ChannelActionConsumer.java       ← MQ 消費
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

### 6.4 Consumer 調度邏輯（已有）

```java
// ChannelActionConsumer.handleAction(msg)
ChannelAdapter adapter = factory.getAdapter(msg.getChannelType());

switch (msg.getActionType()) {
    case FETCH_ORDERS:
        List<Order> orders = adapter.fetchOrders(...);
        orderService.saveOrders(orders);  // 批次寫入
        break;
    case MODIFY_QUANTITY:
        adapter.updateQuantity(...);
        break;
    case SHIPPING_CONFIRMED:
        adapter.confirmShipment(...);
        break;
    // ...
}

// 寫同步紀錄
syncLogService.log(msg, "success", null);
```

### 6.5 通路任務粒度（大小有別）

> **修正 #12：** 對通路的工作細度有大有小。小的一次改價、一次查狀態。
> 大的像訂單，有時間區間、訂單狀態、要根據平台 API 設計不同方法。
> 訂單不能再拆什麼「新訂單事件」，必須以一個時間點來抓定義好的所有訂單。

```
小任務（一次一筆，快速）：
  - MODIFY_PRICE       → 一個 API call 改一個 SKU 的價格
  - MODIFY_QUANTITY     → 一個 API call 改一個 SKU 的庫存
  - SHIPPING_CONFIRMED  → 一個 API call 確認一筆訂單出貨
  - START_SELLING       → 一個 API call 上架一個商品
  → 適合 channel.fast topic，高 concurrency

大任務（時間區間批次，耗時）：
  - FETCH_ORDERS        → 呼叫平台 API 拉「某時間範圍內的所有訂單」
    - 有 from/to 時間參數（如過去 3 天的完成訂單）
    - 有訂單狀態篩選（新單、完成、取消）
    - 每家平台 API 不同（momo POST /VendorApi/OrderQuery 需要 date range）
    - 一次可能回傳 1000+ 筆（雙十一更多）
    - 不能拆成「新訂單事件」— 必須以時間點一次抓完
  - GET_QUANTITY        → 拉整個通路的庫存狀態
  → 適合 channel.slow topic，低 concurrency
```

---

## 7. 前端設計（Vue 3）

### 7.1 技術選型

| 項目 | 選擇 | 理由 |
|------|------|------|
| 框架 | Vue 3 (Composition API) | 設計文件已定 |
| 建置 | Vite | 快 |
| UI 元件 | PrimeVue | DataTable 是 OMS 核心 |
| 狀態管理 | Pinia | 只管全域狀態（auth） |
| HTTP | Axios | 標配 |
| Router | Vue Router 4 | 標配 |
| 語言 | TypeScript | 型別安全 |

### 7.2 專案結構

```
oneec-admin/
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

### 7.3 頁面設計

#### 登入頁（LoginView.vue）

```
┌──────────────────────────────┐
│                              │
│         ONEEC OMS            │
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
│        │  │🟠mo │M2024..│新訂單│$599 │02/08..│ 詳情│      │
│        │  │🟢sh │SH024..│已出貨│$1200│02/07..│ 詳情│      │
│        │  │🔵ya │YA024..│已完成│$350 │02/06..│ 詳情│      │
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
┌────────┬─────────────────────────────────────────────┐
│ Sidebar│  通路管理                    [+ 新增通路]     │
│        │                                             │
│        │  ┌──────┬──────┬──────┬──────┬─────┐       │
│        │  │通路   │帳號   │狀態   │最後同步│操作  │       │
│        │  ├──────┼──────┼──────┼──────┼─────┤       │
│        │  │🟠momo│V001  │已連線 │5分前  │編輯  │       │
│        │  │🟢蝦皮 │S001  │已連線 │3分前  │編輯  │       │
│        │  │🔵Yahoo│Y001  │未連線 │ -    │編輯  │       │
│        │  │🟣PCho │P001  │已連線 │8分前  │編輯  │       │
│        │  └──────┴──────┴──────┴──────┴─────┘       │
│        │                                             │
│        │  點「編輯」→ Dialog 顯示 Token 設定          │
│        │  ┌─────────────────────────┐               │
│        │  │  momo 通路設定           │               │
│        │  │  Token 1: [*****] [顯示] │               │
│        │  │  Token 2: [*****]        │               │
│        │  │  [測試連線]  [儲存]       │               │
│        │  └─────────────────────────┘               │
└────────┴─────────────────────────────────────────────┘
```

#### 商品列表（ProductListView.vue）

```
DataTable: 料號、商品名稱、成本、總庫存、安全庫存、狀態
操作: 編輯（inline 或 dialog）、查看賣場檔
```

#### 賣場檔列表（SellPackListView.vue）

```
DataTable: 通路、商品名稱、通路商品名、售價、庫存、上架狀態、同步狀態
操作: 上架/下架、編輯價格庫存
篩選: 通路 chips + 上架狀態
```

### 7.4 核心 Composable — useDataTable

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

### 7.5 API 層範例

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
  (res) => res.data,  // 直接解包 ApiResponse
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

export async function getOrderItems(orderId: string): Promise<OrderItem[]> {
  return apiClient.get(`/orders/${orderId}/items`)
}

export async function shipOrder(orderId: string, trackingNumber: string, company: string) {
  return apiClient.post(`/orders/${orderId}/ship`, { trackingNumber, company })
}

export async function cancelOrder(orderId: string, reason: string) {
  return apiClient.post(`/orders/${orderId}/cancel`, { reason })
}
```

### 7.6 路由

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
        { path: '', redirect: '/orders' },
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

## 8. 部署架構

### 8.1 docker-compose（已有，微調）

```yaml
services:
  # --- 基礎設施 ---
  postgres:
    image: postgres:16-alpine
    ports: ["5433:5432"]
    environment:
      POSTGRES_DB: oneec
      POSTGRES_USER: oneec
      POSTGRES_PASSWORD: oneec123
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./docker/init-db:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U oneec"]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]

  kafka:
    image: bitnami/kafka:3.7
    ports:
      - "9092:9092"
      - "9093:9093"
    environment:
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_PROCESS_ROLES: broker,controller
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CFG_LOG_RETENTION_MS: -1           # 永不刪除
      KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: false
    volumes:
      - kafka_data:/bitnami/kafka
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"]

  # --- 應用 ---
  app:
    build:
      context: .
      dockerfile: docker/Dockerfile
    ports: ["8080:8080"]
    environment:
      DB_HOST: postgres
      DB_PORT: 5432
      REDIS_HOST: redis
      REDIS_PORT: 6379
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
      kafka: { condition: service_healthy }

  nginx:
    image: nginx:alpine
    ports: ["80:80"]
    volumes:
      - ../oneec-admin/dist:/usr/share/nginx/html
      - ./docker/nginx/nginx.conf:/etc/nginx/nginx.conf
    depends_on: [app]

volumes:
  pgdata:
  kafka_data:
```

### 8.2 架構圖

```
                         ┌─────────────────────┐
                         │       Nginx         │
                         │  :80                │
                         │  / → Vue SPA        │
                         │  /api → app:8080    │
                         └──────┬──────────────┘
                                │
                   ┌────────────┴────────────┐
                   │                         │
         ┌─────────▼─────────┐   ┌──────────▼──────────────────────────────┐
         │  Vue 3 (static)   │   │  Spring Boot (Modular Monolith)         │
         │  PrimeVue         │   │  :8080                                  │
         │  by Nginx         │   │                                          │
         └───────────────────┘   │  ┌─────────────────────────────────┐    │
                                 │  │  REST API Layer                 │    │
                                 │  │  JWT Auth + SecurityConfig      │    │
                                 │  └─────────────────────────────────┘    │
                                 │                                          │
                                 │  ┌─────────────────────────────────┐    │
                                 │  │  Timer (1s) → Dispatcher        │    │
                                 │  │  ConcurrentHashMap + Redis Lock │    │
                                 │  └─────────────────────────────────┘    │
                                 │                                          │
                                 │  ┌─────────────────────────────────┐    │
                                 │  │  Kafka JOBs (same JVM, 各自治) │    │
                                 │  │  ┌───────────┐ ┌─────────────┐ │    │
                                 │  │  │ FetchOrder│ │ UpdatePrice │ │    │
                                 │  │  │ Job ×4   │ │ QtyJob ×8   │ │    │
                                 │  │  └───────────┘ └─────────────┘ │    │
                                 │  │  ┌───────────┐ ┌─────────────┐ │    │
                                 │  │  │ Process   │ │ Backend     │ │    │
                                 │  │  │ OrderJob  │ │ Job ×4      │ │    │
                                 │  │  │ ×4        │ │ (Factory)   │ │    │
                                 │  │  └───────────┘ └─────────────┘ │    │
                                 │  └─────────────────────────────────┘    │
                                 │                                          │
                                 │  @Cacheable (Redis)                     │
                                 └──────────┬──────────────────────────────┘
                                            │
                         ┌──────────┬───────┴────────┬──────────┐
                         │          │                │          │
                 ┌───────▼──────┐ ┌▼────────┐ ┌─────▼─────────┐│
                 │ PostgreSQL   │ │ Redis   │ │ Kafka         ││
                 │ :5432        │ │ :6379   │ │ :9092         ││
                 │              │ │         │ │               ││
                 │ 21 tables    │ │ cache   │ │ 6 Topics      ││
                 │ (+task_      │ │ lock    │ │ (60 partitions││
                 │  schedule)   │ │ jwt-bl  │ │  total)       ││
                 │              │ │ order   │ │ + DLQ topic   ││
                 │              │ │  hash   │ │               ││
                 └──────────────┘ └─────────┘ └───────────────┘│
                                                                │
                                                    ┌───────────▼───┐
                                                    │ 通路 API       │
                                                    │ momo / shopee  │
                                                    │ yahoo / pchome │
                                                    └───────────────┘
```

---

## 9. 開發順序（建議）

### Phase 0：環境驗證（1 天）

- [ ] `docker-compose up` 確認 PG + Redis + Kafka 正常
- [ ] `./gradlew :oneec-app:bootRun` 確認 Spring Boot 啟動
- [ ] 打 `/api/v1/health` 確認回應
- [ ] 確認 Kafka broker 可連（`kafka-topics.sh --list`）

### Phase 1：JWT 認證（2 天）

- [ ] JwtTokenProvider + JwtAuthenticationFilter
- [ ] AuthController (login / me / refresh)
- [ ] SecurityConfig 改為 JWT 驗證
- [ ] account 表插入測試帳號

### Phase 2：補齊 CRUD（3 天）

- [ ] SellPackService + SellPackController
- [ ] ChannelService + ChannelController
- [ ] OrderController 補 ship/cancel
- [ ] 所有 API 用 Postman/curl 驗證

### Phase 3：Redis 快取 + 分散式鎖 + Hash Dedup（2 天）

- [ ] RedisConfig + @EnableCaching
- [ ] @Cacheable 加到 Product/Channel Service
- [ ] DistributedLock（Redis SETNX）
- [ ] Order Hash Dedup Cache（SHA-256 + 7 天 TTL）
- [ ] Dedup 單元測試（hash 相同跳過、hash 不同發送、key 過期重發）

### Phase 4：MQ 拓撲 + JOB 架構（3 天）

- [ ] KafkaConfig 建 6 個 Topic（channel.fast/slow, order.process, task.backend, task.orderstatus, task.dlq）
- [ ] TaskMessage 統一訊息格式（含 topic, partitionKey, sourceJobType）
- [ ] TaskProducer（KafkaTemplate send with partition key）
- [ ] FetchOrderJob（channel.slow, round-robin, hash dedup → order.process）
- [ ] ProcessOrderJob（order.process, key=channelId:merchantId）
- [ ] UpdatePriceQtyJob / ShipConfirmJob（channel.fast, 各自 groupId + taskAction 過濾）
- [ ] BackendJob（task.backend, Factory pattern dispatch）
- [ ] OrderStatusJob / InventorySyncJob
- [ ] Manual commit（Acknowledgment.acknowledge()）
- [ ] 確認 partition key ordering + consumer group 正確

### Phase 5：任務調度引擎（3 天）

- [ ] task_schedule 表 DDL + seed data（通路拉單 + 庫存同步 + 後端任務）
- [ ] TaskSchedule Entity + Mapper + Service
- [ ] ScheduleTimer（可設定心跳 `${scheduler.heartbeat-ms:1000}`）
- [ ] ScheduleDispatcher（ConcurrentHashMap memory-first + 時區 + cron/interval）
- [ ] 排程管理 API（CRUD + 手動觸發）
- [ ] 端到端測試：建排程 → 等觸發 → 確認 Topic 收到訊息

### Phase 6：前端 MVP（7 天）

- [ ] Vue 3 + Vite + PrimeVue 初始化
- [ ] Layout (Sidebar + Topbar)
- [ ] 登入頁
- [ ] api/ + types/ + useDataTable
- [ ] 通路管理頁（含排程設定 Tab）
- [ ] 商品列表頁
- [ ] 賣場檔頁
- [ ] 訂單列表 + 詳情
- [ ] 定時任務管理頁（排程管理 + JOB 監控）

### Phase 7：通路 Adapter 實作（每家 3-5 天）

- [ ] MomoAdapter（填入實際 API 呼叫）
- [ ] ShopeeAdapter
- [ ] YahooAdapter
- [ ] PchomeAdapter

### 總計：~36 天（1 人），~20 天（2 人前後端分工）

---

## 10. 技術決策紀錄

| 決策 | 選擇 | 替代方案 | 理由 |
|------|------|---------|------|
| 架構 | 模組化單體 | 微服務 | 人少、快速迭代、MQ 設計相同可後續拆分 |
| MQ | Kafka | RabbitMQ | Partition key 天然保證同 key 有序，每筆都處理不跳過 |
| MQ 策略 | Fast/Slow topic + partition key | RabbitMQ routing | 同 SKU/通路有序，不同 SKU 平行；每則訊息必須按序處理 |
| JOB 路由 | 自治 JOB 路由（每支 JOB 自決下一站） | Pipeline 引擎 (Camunda/Temporal) | 輕量、無中心、參考 recover-data-job 模式 |
| 調度引擎 | Timer→Dispatcher (自建) | Quartz / xxl-job | 零依賴、時區一等公民、改 DB 即生效 |
| Timer 頻率 | 可設定（預設 1 秒） | 硬編 1 秒 | `${scheduler.heartbeat-ms:1000}` 可調 |
| 訂單去重 | Redis SHA-256 Hash Cache（7 天 TTL） | DB unique constraint | 快取快、跨 JOB 共享、雙十一突波也扛得住 |
| 庫存同步 | 一天兩次（cron `0 8,14 * * *`） | 每 10 分鐘 | 通路 API 限制，兩次已夠用 |
| 拉單時段 | 24 小時不間斷 | active_hours 限制 | 物流深夜變動、無法掌控客戶作息 |
| DB | PostgreSQL | MySQL | 已有 schema，支援 JSONB、Partition |
| ORM | MyBatis-Plus | JPA | 對 SQL 控制力高，適合多表 join |
| 快取 | Redis | 本地 Caffeine | 需要分散式鎖，未來多實例共享 |
| 記憶體快取 | ConcurrentHashMap | Caffeine | Dispatcher 只需 simple get/put，夠用 |
| 前端 UI | PrimeVue | Ant Design Vue | DataTable 功能最完整，舊系統已用 |
| HTTP Client | OkHttp | RestTemplate | 通路 API 需要精確控制 header、簽名 |
| JWT 庫 | jjwt 0.12.6 | Spring Security OAuth2 | 輕量，夠用 |
