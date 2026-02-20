# SimpleEC OMS 系統設計規劃 - 完整概覽

## 系統定位

SimpleEC OMS（簡易電商訂單管理系統）是一個**多通路訂單聚合與管理平台**，核心目標是：
- **統一管理多個電商通路的訂單**（Shopify、Shopee、Momo、Yahoo、PChome、Cyberbiz、easystore）
- **實現通路資料聚合**（商品、庫存、訂單、退貨）
- **建立通用的訂單履行流程**（接單 → 分揀 → 打包 → 出貨 → 追蹤）

**技術棧**：
- Java 17 + Spring Boot 3.5.0 + Gradle 8.14.4
- Kafka 3.7.1（KRaft mode）+ PostgreSQL 16 + Redis 7
- MyBatis-Plus + OTEL 可觀測性

---

## 核心設計原則

### 1️⃣ **Heartbeat + Scheduler 時間驅動**
系統沒有「實時」的動作。所有主動工作都由中央 HeartbeatJob 觸發：
- **HeartbeatJob** 每秒發送一條消息到 `scheduler` 主題
- **SchedulerConsumer** 讀取時間戳，根據分鐘位置（modulo 運算）決策發起哪些任務
- 範例：
  - `timestamp.minute % 5 == 0` → 觸發「通路訂單抓取」
  - `timestamp.minute % 15 == 0` → 觸發「商品同步」

**優點**：單一時間源、可重現、易於測試、支援暫停/恢復

### 2️⃣ **Mode A / Mode B 平台分類**
不同通路的 API 能力差異大，系統透過兩種模式適配：

| 模式 | 特性 | 流程 | 平台 |
|------|------|------|------|
| **Mode A** | 訂單列表 API 返回完整資訊 | `FETCH_ORDERS` → 直接 `PROCESS_ORDER` | Shopify、easystore |
| **Mode B** | 訂單列表 API 只有概要 | `FETCH_ORDERS` → `FETCH_ORDER_DETAIL` → `PROCESS_ORDER` | Shopee、Momo、Yahoo、PChome、Cyberbiz |

**關鍵**：Channel Job Slow 會根據平台 Mode 決定是否執行 `FETCH_ORDER_DETAIL` 步驟。

### 3️⃣ **多生產者 + TaskType 路由設計**
- **多生產者容忍**：一個 Topic 可有多個 Producer（分散式特性），透過 **taskType** 欄位區分操作
- **粒度設計**：Topic 按業務邊界分（而非按操作分），內部用 taskType 細分
- **ACID 下沈**：Consumer 層根據 taskType 做不同邏輯，並透過 Redis + 分鎖保證冪等性

範例：
```
{platform}.slow 包含多個 taskType：
  ├─ FETCH_ORDERS (SchedulerConsumer 發)
  ├─ FETCH_ORDER_DETAIL (Channel Job Slow 發，Mode B only)
  ├─ SYNC_PACK (UI/Gateway 發)
  └─ ...

Channel Job Slow Consumer：
  switch(msg.taskType) {
    case FETCH_ORDERS:
      // Mode A/B 邏輯 → order.process
    case SYNC_PACK:
      // 打包同步 → task.backend
  }
```

**優勢**：
- 避免 Topic 爆炸（16 個而非幾十個）
- 多個模組可獨立往同一 Topic 發送不同操作
- Consumer 聚焦一個業務邊界，內部路由清晰

### 4️⃣ **兩層 Redis 去重**
資料流經多個 Consumer 時，用 Redis 防止重複處理：

```
Layer 1（Channel Job）：
  讀 Redis，檢查訂單是否已處理 → 速度最快，減少下游負載

Layer 2（OrderUpsertHandler）：
  讀 Redis 分佈式鎖，確保並行安全 → DB 寫入後更新 Redis

Result：
  - 重複訊息到達 → Layer 1 秒速丟棄
  - 並行訊息到達 → Layer 2 使用鎖保護 DB 操作
```

### 5️⃣ **加密 & 隱私**
- 所有 19 張表 PII 欄位（客戶名、電話、地址、電郵）AES-256-GCM 加密存儲
- 僅解密時檢視，絕不以明文存入 Kafka
- 符合個資法 & GDPR 要求

---

## 17 個 Kafka Topic 概覽

### 通路 Topic（10 個）
```
{platform}.fast   ← 快速同步（商品、庫存、運費）
{platform}.slow   ← 慢速同步（訂單、退貨詳情）
```
**平台**：momo, shopee, yahoo, pchome, cyberbiz

### 業務 Topic（7 個）
```
scheduler         ← HeartbeatJob 發時間脈衝（進入點）
order.process     ← 訂單待處理隊列（核心業務）
return.process    ← 退貨待處理隊列
task.backend      ← 後端非同步任務（報表、商品同步、出貨）
task.frontend     ← 前端非同步任務（UI 驅動，轉發到 task.backend）
task.failed       ← 失敗重試隊列
task.dlt          ← 死信隊列（最終棄置）
```

---

## 資料庫表設計原則

### 三維商品觀點
1. **客戶視角**：orders（客戶買了什麼）→ items 引用 products
2. **平台視角**：platform_products（各平台商品映射）
3. **倉庫視角**：packs（出貨單位、庫存）→ product_pack_mappings SKU 對應

### 訂單/退貨設計
- `orders` 包含 `items` **JSON 欄位**（訂單項目）
- **退貨**：orders.return_items 欄位（而非獨立 returns 表）
  - 原因：有訂單才有退貨，應作為 orders 的子項
- **待出貨**：orders.status 狀態判定（無需 warehouse_queues 表）

---

## 19 張資料庫表總覽

### 平台管理（2 張）
- `platform_account` — SimpleEC 平台管理員帳號（獨立）
- `global_config` — 全域系統設定（獨立）

### 商家體系（3 張）
- `merchant` — 商家（租戶）
- `account` — 商家操作帳號（多人）
- `merchant_options` — 商家自訂選項

### 商品管理（4 張）
- `product_group` — 商品群組（前端管理用，共享描述/圖片/品牌）
  - FK: merchant_id

- `product` — **商品 = SKU 級別**（倉庫的一個位置，「我們有什麼」）
  - PK: product_id (NanoID)
  - 重要欄位: merchant_id, sku (unique per merchant), name, quantity (庫存), cost_price, suggest_price
  - FK: product_group_id

- `product_barcode` — 產品條碼（多國產線）
  - FK: product_id

- `sell_pack` — **通路上架映射**（product × channel，「平台上怎麼賣」）
  - PK: sell_pack_id (NanoID)
  - 重要欄位: merchant_id, product_id, channel_id, sku, channel_product_id, channel_spec_id, channel_product_name, channel_spec_name, channel_product_url, selling_price, quantity
  - FK: product_id, channel_id
  - ⚠️ 這是「上架映射」，不是物理打包單位

### 通路管理（3 張）
- `platform` — 電商平台（momo/shopee/yahoo/pchome 等）
  - PK: platform_id (NanoID)
  - 重要欄位: platform_name, credential1/2 (AES 加密), queue_topic, ship_options (JSON)
  - ⚠️ Mode A/B 判定在代碼層面（ChannelAdapter），不在資料庫配置

- `channel_api_versions` — 平台 API 版本管理
  - FK: platform_id

- `channel` — 通路/館（樞紐：FK merchant + FK platform）
  - PK: channel_id (NanoID)
  - 重要欄位: merchant_id, platform_id, channel_sn, token 1-5 (多個認證), enable_sync

### 訂單表（3 張）
- `orders` — **聚合訂單**（items JSONB）
  - PK: order_id (NanoID)
  - 重要欄位: merchant_id, channel_id, channel_order_id, order_status, buyer_* (PII, AES 加密), items (JSONB), total_amount, shipping_fee, discount_amount
  - Unique: (channel_id, channel_order_id)

- `order_status_logs` — 訂單狀態變更記錄（審計追蹤）
  - FK: order_id

- `order_shipments` — 出貨物流追蹤
  - FK: order_id

### 退款表（1 張）
- `refund_orders` — **退款單**（items JSONB，獨立表，非嵌入 orders）
  - PK: refund_order_id (NanoID)
  - 重要欄位: order_id, refund_status, refund_amount, items (JSONB)
  - FK: order_id

### 同步與監控（2 張）
- `channel_sync_logs` — 通路同步/健康檢查記錄
  - FK: merchant_id, channel_id

- `failed_task_logs` — Kafka 失敗任務 LOG（可重試的訊息緩衝）
  - 重要欄位: task_type, task_action, retry_count, payload (JSONB)

### 統計表（1 張）
- `daily_statistics` — 日統計（按 stat_date 分區）
  - PK: (id, stat_date)

**表設計特點**：
- **PK**: NanoID（VARCHAR(20)），自動生成、有序、分散式安全
- **多租戶隔離**: 所有業務表都有 merchant_id FK，確保數據隔離
- **items JSONB**: orders.items 和 refund_orders.items 分別存儲訂單項和退款項
- **sell_pack 的角色**: 不是物理打包單位，而是產品在特定通路的「上架映射」，包含通路特定的 SKU/規格/價格
- **PII 加密**: 客戶名、電話、地址、電郵用 AES-256-GCM 加密存儲在 credential 欄位
- **狀態驅動**: orders.order_status 管理訂單生命週期；refund_orders.refund_status 管理退款狀態
- **分區**: daily_statistics 按月份分區（2026-01 ~ 2026-12+）
- **Token 多重性**: channel 表支援 token1~5，適配不同平台的複雜認證機制

---

## 11 個 Gradle 模組設計

### 第一層：基礎層
- **simpleec-common** — 常數、DTO、工具類（無依賴）
- **simpleec-core** — Entity、Mapper、Service、Kafka 設定（依賴 common）

### 第二層：介面適配層
- **simpleec-channel** — ChannelAdapter 介面 + 各平台實現（依賴 core）
- **simpleec-api** — REST API 端點（依賴 core）
- **simpleec-gateway** — Webhook 入口（依賴 core + channel）

### 第三層：工作層
- **simpleec-channel-job** — 通路同步 Job（Channel Job Fast/Slow，依賴 channel）
- **simpleec-order-job** — 訂單入庫 Job（OrderUpsertHandler，依賴 core）
- **simpleec-scheduler-job** — 時間源 Job（HeartbeatJob + SchedulerConsumer，依賴 core）
- **simpleec-backend-job** — 後端非同步任務（報表、同步觸發，依賴 core + channel）
- **simpleec-frontend-job** — UI 事件 Job（依賴 core）
- **simpleec-retry-job** — 重試與死信 Job（ErrorHandler + DltHandler，依賴 core）

---

## 接下來該讀什麼？

### 👤 架構師 / 設計師
→ **[01-KAFKA-TOPOLOGY.md](./01-KAFKA-TOPOLOGY.md)**
- 完整的 Producer/Consumer/Topic 拓撲圖
- 訊息流向與邊界清晰化

### 👨‍💻 Java 後端工程師
→ **[02-MODULE-DESIGN.md](./02-MODULE-DESIGN.md)**
- 11 個模組的詳細設計
- 類別結構 & 責任清分
- 依賴關係圖

→ **[04-INTERFACE-CONTRACTS.md](./04-INTERFACE-CONTRACTS.md)**
- Consumer/Producer 介面定義
- Kafka 訊息契約（Schema）

### 👷 專案經理 / Scrum Master
→ **[03-IMPLEMENTATION-ORDER.md](./03-IMPLEMENTATION-ORDER.md)**
- 建議開發順序
- 5 個里程碑（MVP → Production）
- 每階段交付物 & 測試計畫

### 🎓 新人 / 學習者
1. 本文件（00-OVERVIEW.md） — 5 分鐘快速理解
2. [01-KAFKA-TOPOLOGY.md](./01-KAFKA-TOPOLOGY.md) — 理解訊息流
3. [02-MODULE-DESIGN.md](./02-MODULE-DESIGN.md) — 理解模組結構
4. 回到 `../docs/` 資料夾深入細節

---

## 核心問題 & 回答

### Q：為什麼要用 Kafka？
**A**：
- **解耦**：通路、訂單、報表各自獨立消費
- **重放能力**：訊息可重新消費（debug 時很有用）
- **可觀測性**：每筆訊息都有完整追蹤
- **可靠性**：支援故障恢復、exactly-once 語義

### Q：HeartbeatJob 每秒發怎會不卡？
**A**：
- Heartbeat 訊息極小（只有 timestamp）
- SchedulerConsumer 秒速處理（通常 < 1ms）
- 訊息量可預測：86,400 條/天
- 不是實時動作，而是「時間刻度」

### Q：Mode A vs Mode B 怎麼判別？
**A**：
1. 檢查平台 API 文檔（或試呼叫）
2. 如果「訂單列表 API」返回完整欄位（金額、配送地址、客戶資訊）→ Mode A
3. 如果只有 ID、基本資訊 → Mode B
4. **在 Adapter 代碼中實現**（不在資料庫配置）
   - ShopifyAdapter.fetchOrders() 返回完整資訊
   - ShopeeAdapter.fetchOrders() 返回概要，並實現 fetchOrderDetail()
5. **ChannelJobSlowConsumer 運行時檢測**：根據返回資訊完整性決定是否呼叫 fetchOrderDetail()

### Q：Redis 兩層去重怎麼用？
**A**：
```
Channel Job Slow（Layer 1）：
  if redis.exists(orderId) → SKIP（已有人處理）
  else → 發送訊息到 order.process

OrderUpsertHandler（Layer 2）：
  distributedLock = redis.lock(orderId, 30s)
  if distributedLock → DB INSERT/UPDATE + redis.set(orderId, ...)
  else → 等待後重試（同 Consumer group 內負載均衡）
```

---

## 快速檢查表

- [ ] 理解 Heartbeat + Scheduler 驅動模式
- [ ] 能區分 Mode A 與 Mode B 平台的特徵
- [ ] 知道 Consumer/Producer/Topic 三者的責任邊界
- [ ] 理解 Redis 兩層去重的設計目的
- [ ] 能繪製 Kafka 拓撲圖（至少 3 個主要流程）
- [ ] 知道 11 個模組的大致職責

**達成 ✅**→ 可以開始看 [01-KAFKA-TOPOLOGY.md](./01-KAFKA-TOPOLOGY.md)

---

**上次更新**：2026-02-20
**版本**：1.0 - PLAN Phase I Release
