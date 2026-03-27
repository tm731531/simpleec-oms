# 一天的 Code Review、架構修復、與一個 Seeder 的三次重寫

**日期**：2026-03-27
**分支**：`feature/stats-pipeline`
**系統**：SimpleEC OMS — 多平台電商訂單管理系統

---

## 背景

今天的工作在一個已有相當規模的 Spring Boot OMS 系統上進行。這個系統整合了 Momo、Shopee、Yahoo 等多個電商平台，透過 Kafka 事件流處理訂單同步、退貨、統計等業務。

主要任務：完成 `feature/stats-pipeline` 分支上所有待辦的 code review 修復，確保系統能夠 `docker compose up` 起來，並且有完整的端對端資料流可驗證。

---

## 三輪 Code Review，共修復 20+ 個問題

### 第一輪：已知問題清單（任務 #14–#20）

進入狀態之前就有一份待辦清單，包含從前幾次 review 累積下來的問題。逐一修復：

**Critical**
- `OrderUpsertConsumer`：`body.get()` → `body.path()` 防 NPE 連鎖，加 `orderDataJson` null 守衛
- `daily_statistics` schema：`id` 欄位缺 `NOT NULL` 約束
- `ReturnUpsertConsumer`：未寫 stats dirty marker；`ChannelJobConsumer` FETCH_ORDER_DETAIL 用 `.get()` 有 NPE 風險

**Warning**
- `DailyStatisticsService`：early return 時沒刪除過時的 stats 列，會保留舊資料
- schema `order_status` / `refund_status` 預設值用小寫 `'pending'`，但 JPA 的 `@Enumerated(EnumType.STRING)` 搭配 `Enum.valueOf()` 是 case-sensitive，必須大寫
- `RetryJobConsumer`：`path()` 回傳 `MissingNode`，直接 cast 成 `ObjectNode` 會 ClassCastException

### 第二輪：種子資料 (02-seed-data.sql) 的雷

Schema 修了，但種子資料是另一個地雷區：

1. **BCrypt hash 是假的**：`$2a$10$dummyhashfordevonly...` 不是有效的 BCrypt，登入永遠失敗。
2. **訂單狀態小寫**：`'completed'`、`'shipped'`、`'pending'` — 同樣的 case-sensitive 問題。
3. **`daily_statistics` INSERT 欄位名稱全錯**：用了 `order_count`、`total_amount` 這種不存在的欄位，資料庫初始化會直接失敗。還有一筆 `_ALL_` aggregate row 不應該存在。

這些問題在本地跑 `docker compose up` 之前不容易被發現，因為沒有測試覆蓋，schema validate 模式也只檢查 entity 對應的欄位方向，不會反向驗證 SQL 腳本。

### 第三輪：記憶體 & 容器配置

系統有 21 個 Java 容器，沒有任何 JVM 記憶體限制。在一台只有 7.4GB 可用 RAM 的開發機上，JVM ergonomic sizing 很快就會讓系統 OOM。

解法：在 `docker-compose.yml` 加 `JAVA_TOOL_OPTIONS`。這個環境變數是 JVM 標準，不需要改 Dockerfile 的 `ENTRYPOINT`，是最小侵入的做法。

```yaml
environment:
  JAVA_TOOL_OPTIONS: "-Xmx256m -XX:+ExitOnOutOfMemoryError"
```

`-XX:+ExitOnOutOfMemoryError` 讓容器在 OOM 時立刻崩潰而不是卡死，對 Docker 的 restart policy 友好。

---

## Seeder 的三次重寫：一個關於架構理解的故事

這是今天最有趣的插曲。

### 第一版：直接打 Kafka

目標：「準備一個 Docker 服務，專門用來打假訂單資料，確認整體資料流順暢」。

第一直覺是直接發 Kafka 訊息。用 `kafka-python` 連 `kafka:9092`，組好 `ORDER_UPSERT` 訊息，直接送到 `order.process` topic。

**問題**：被用戶糾正。系統對外只有 API，不應該繞過它直接操作內部基礎設施。

### 第二版：打 `POST /api/orders`（直接寫 DB）

改成用 `requests` 呼叫 REST API。先 login 拿 JWT，再 `POST /api/orders`。

**問題**：`OrderController.createOrder()` 是直接寫資料庫，完全跳過 Kafka pipeline。Stats dirty marker 不會被寫入，`DailyStatisticsService` 不會被觸發。整個事件流形同虛設。

用戶點出：「API 的訂單也是打 Kafka 走流程到 ORDER_PROCESS 阿」

### 第三版：新增 `POST /api/user/orders` 端點

正確做法：在 `UserOrderController` 新增一個端點，接收訂單資料後，組成完整的 `ORDER_UPSERT` Kafka 訊息，發到 `order.process`。

```
POST /api/user/orders (帶 JWT)
  → 查 Channel → Platform（取得 platformId）
  → 組 ORDER_UPSERT 訊息
  → kafkaTemplate.send("order.process", ...)
  → 回傳 202 Accepted
```

這樣 seeder 的完整流就是：

```
seed_orders.py
  → POST /api/auth/login      （拿 JWT）
  → POST /api/user/orders ×N  （帶 Bearer token）
       → Kafka order.process
           → OrderUpsertConsumer
               → orders 表 INSERT/UPDATE
               → stats dirty marker → Redis ZSET
                   → StatsRecalcHandler（定時掃）
                       → DailyStatisticsService.recalculate()
                           → daily_statistics 更新
```

端到端，一條不少。

---

## 今天學到的幾件事

### 1. 「能走事件流就走事件流」不只是口號

第一直覺把 seeder 做成直接打 Kafka，是「能快就快」的思維。但在一個設計良好的事件驅動系統裡，這樣做其實繞過了：
- 去重邏輯（Redis + DB 兩層）
- 錯誤處理（task.failed → task.dlt 路由）
- Stats pipeline
- Kafka topic 的可觀測性（Kafka UI 看不到 seeder 的訊息從哪來）

正確的路徑雖然多繞了一圈（API → Kafka → Consumer → DB），但每一層都有意義。

### 2. 資料庫 Schema 與種子資料是兩個獨立的測試對象

很多人寫了 schema migration 之後就認為完成了。但種子資料 (`02-seed-data.sql`) 是另一份需要被維護的契約。今天的案例：
- Schema 改了欄位名，種子資料沒跟著改
- BCrypt hash 用假值（可能是「先跑起來再說」的壞習慣遺留）
- Enum 大小寫規範不一致

這些問題都不會在 compile time 被抓到，要在實際啟動 + 測試時才爆。

### 3. `ddl-auto: validate` 只單向驗證

Hibernate 的 `ddl-auto: validate` 只檢查「Entity 中有 mapping 的欄位是否存在於 DB」，不會反向檢查 DB 裡有沒有多餘欄位。這個方向性很重要，避免誤以為 validate 會保護所有情況。

### 4. `JAVA_TOOL_OPTIONS` 是最小侵入的 JVM 配置方式

不需要改 Dockerfile，不需要重 build image，只要在 `docker-compose.yml` 的 `environment` 加一行，JVM 啟動時自動讀取。在 CI/CD 流程還沒完善的早期開發階段，這是非常實用的做法。

---

## 今日修改的主要檔案

| 檔案 | 改動類型 | 說明 |
|------|---------|------|
| `docker/init-db/01-schema.sql` | bug fix | `daily_statistics.id` NOT NULL；加 DEFAULT partition；enum 大小寫 |
| `docker/init-db/02-seed-data.sql` | bug fix | BCrypt hash、訂單狀態大小寫、daily_statistics 欄位名稱 |
| `simpleec-order-job/.../OrderUpsertConsumer.java` | bug fix | `.get()` → `.path()`、移除 unused import/param |
| `simpleec-order-job/.../ReturnUpsertConsumer.java` | bug fix | 加 stats dirty marker、移除 unused import/param |
| `simpleec-core/.../DailyStatisticsService.java` | bug fix | early return 時刪除過時 stats 列 |
| `simpleec-core/.../OrderService.java` | bug fix | NOT NULL 欄位的 null 守衛 |
| `simpleec-channel-job/.../ChannelJobConsumer.java` | bug fix | FETCH_ORDER_DETAIL `.get()` → `.path()` |
| `simpleec-retry-job/.../RetryJobConsumer.java` | bug fix | MissingNode cast ClassCastException |
| `docker-compose.yml` | infra | JAVA_TOOL_OPTIONS 記憶體限制 |
| `simpleec-api/.../UserOrderController.java` | feature | 新增 `POST /api/user/orders` → Kafka pipeline |
| `docker/test-data-generator/` | feature | 測試資料產生器（seeder） |

---

## 結語

今天花最多時間的其實不是 code，而是「把對的事情弄清楚」。Seeder 寫了三個版本，不是因為技術複雜，而是因為對架構意圖的理解在逐漸深化。

一個好的事件驅動系統，它的「正確入口」只有一個。找到那個入口，比把功能快速做出來更重要。

這條原則同樣適用於大系統的任何地方：**追蹤路徑比結果更重要**，因為你下次出問題的時候，你需要知道訊息從哪裡來、往哪裡去。
