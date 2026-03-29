# Kafka Event Bus 深度解析

> 本文從兩個角度梳理 SimpleEC OMS 的 Kafka 事件匯流排：
> 1. **功能性視角** — 每條訊息的來源、目的地、處理邏輯
> 2. **工程性視角** — CAP 理論在非同步系統中如何落地
>
> 讀完本文，你應該能回答：「一筆訂單是怎麼從電商平台進來、被存到資料庫的？」以及「系統掛掉一部分，資料會怎樣？」

---

## 目錄

1. [系統全貌：Event Bus 拓撲圖](#1-系統全貌-event-bus-拓撲圖)
2. [Topic 職責與分層設計](#2-topic-職責與分層設計)
3. [完整事件流程圖](#3-完整事件流程圖)
   - 3.1 [訂單拉取流程（Mode B — Shopee）](#31-訂單拉取流程mode-b--shopee)
   - 3.2 [訂單拉取流程（Mode A — Easystore）](#32-訂單拉取流程mode-a--easystore)
   - 3.3 [退貨入庫流程](#33-退貨入庫流程)
   - 3.4 [出貨回寫流程（商家主動出貨）](#34-出貨回寫流程商家主動出貨)
   - 3.5 [失敗重試 → DLT 流程](#35-失敗重試--dlt-流程)
   - 3.6 [每日統計重算流程](#36-每日統計重算流程)
   - 3.7 [套包同步流程](#37-套包同步流程)
4. [訊息結構解析](#4-訊息結構解析)
5. [CAP 理論在本系統的實現](#5-cap-理論在本系統的實現)
   - 5.1 [我們的選擇：AP 系統](#51-我們的選擇ap-系統)
   - 5.2 [一致性如何最終達成](#52-一致性如何最終達成)
   - 5.3 [各元件掛掉時的行為](#53-各元件掛掉時的行為)
   - 5.4 [我們不保證什麼](#54-我們不保證什麼)
6. [關鍵設計決策摘要](#6-關鍵設計決策摘要)

---

## 1. 系統全貌：Event Bus 拓撲圖

```
                         ┌─────────────────────────────────────────────────────────┐
                         │                   KAFKA EVENT BUS                       │
                         │                                                         │
  PRODUCERS              │  TOPICS                      CONSUMERS                  │
  ─────────              │  ──────                      ─────────                  │
                         │                                                         │
  scheduler-job ─────────┼──► scheduler (HEARTBEAT) ───► scheduler-job            │
       │                 │                                    │                    │
       │ dispatch        │                                    │ dispatch           │
       └────────────┐    │                                    │                    │
                    │    │  {platform}.slow ◄─────────────────┘                    │
                    │    │  (FETCH_ORDERS,                                         │
                    │    │   FETCH_ORDER_DETAIL,  ─────────► channel-job-*-slow   │
                    │    │   FETCH_RETURNS,                       │                │
                    │    │   SYNC_PACK)                           │ calls          │
                    │    │                                        │ platform API   │
                    │    │  {platform}.fast ◄──── api/user ──────┘                │
                    │    │  (SHIP_ORDER,                     channel-job-*-fast   │
                    │    │   UPDATE_PRICE,        ────────►       │                │
                    │    │   UPDATE_INVENTORY,                    │ writes back    │
                    │    │   APPROVE_RETURN)                      │ to platform    │
                    │    │                                        │                │
                    │    │                    channel-job publishes after transform│
                    │    │                                        │                │
                    │    │  order.process ◄───────────────────────┘                │
                    │    │  (ORDER_UPSERT)     ─────────► order-job               │
                    │    │                                    │ upsert to DB       │
                    │    │  return.process ◄──────────────────┘                   │
                    │    │  (RETURN_UPSERT)    ─────────► order-job               │
                    │    │                                    │ upsert refund      │
                    │    │                                    │                    │
  api / webhook ────┼────┼──► task.backend ──────────────────►  backend-job       │
  channel-job       │    │  (SYNC_PRODUCT,                   │ stats, reports     │
                    │    │   STATS_RECALC,                    │                    │
                    │    │   *_REPORT)                        │                    │
                    │    │                                    │                    │
  api (user) ───────┼────┼──► task.frontend ─────────────────►  frontend-job      │
                    │    │  (EXPORT, BATCH)                   │ exports, batch ops │
                    │    │                                    │                    │
  any consumer ─────┼────┼──► task.failed ───────────────────►  retry-job         │
  (on error)        │    │  (retryable)        re-publish ───┘    │               │
                    │    │                     to original topic   │               │
                    │    │                                         │ max retries   │
                    │    │  task.dlt ◄─────────────────────────────┘               │
                    │    │  (dead letter)      ─────────►  retry-job (DLT)        │
                    │    │                                    │ persist to DB      │
                    └────┴────────────────────────────────────┴────────────────────┘
```

**三大層次**：

| 層次 | Topics | 職責 |
|------|--------|------|
| **通路層** | `{platform}.fast`, `{platform}.slow` | 平台 API 溝通（拉取 / 回寫） |
| **業務層** | `order.process`, `return.process`, `task.backend`, `task.frontend` | 業務資料的標準化處理 |
| **可靠性層** | `task.failed`, `task.dlt` | 失敗容錯、死信持久化 |

---

## 2. Topic 職責與分層設計

### 為什麼 fast / slow 分開？

```
.fast 消費者需要在 < 5 秒內完成（SHIP_ORDER 出貨標籤是時間敏感的）
.slow 消費者可以跑幾分鐘（FETCH_ORDERS 需要翻頁 + rate limit 等待）

如果放在同一個 topic：
  慢速的 FETCH_ORDERS 會佔住 thread pool
  → SHIP_ORDER 堆積 → 出貨延遲 → 商家投訴

分開之後：
  .fast consumer pool 永遠清空
  .slow 的 lag 不影響 .fast
```

### scheduler 為什麼是獨立 topic？

```
Heartbeat 每秒 1 條，volume 極高
Scheduler Consumer 讀到 HEARTBEAT 後，根據時間決定要不要派發任務

如果 heartbeat 混進業務 topic：
  → 淹沒真正的業務訊息
  → Consumer offset 飛快前進，難以追蹤

獨立 topic + retention 1d：
  → 任何服務重啟後都能從最近的 heartbeat 繼續
```

### order.process 為什麼 retention 只有 2 小時？

```
order.process 是訊息的「傳遞管道」，不是「儲存層」
                                    ─────────────────
一旦 OrderUpsertConsumer 消費並寫入 DB，Kafka 那份就沒用了
DB（PostgreSQL）才是 source of truth

retention 2h = 足夠讓 order-job 重啟後重新消費
              + 不會無限堆積（每小時可能有幾千筆）

task.dlt = 30d 是因為需要人工審查和補跑
```

---

## 3. 完整事件流程圖

### 3.1 訂單拉取流程（Mode B — Shopee）

> Mode B：通路 list API 只回傳摘要，需要額外打 detail API

```
時間軸
──────

T+0s   Scheduler (HeartbeatTimer)
       │
       │  每秒發一條 HEARTBEAT 到 scheduler topic
       │  ┌─────────────────────────────────────────────┐
       │  │ header.taskType = "HEARTBEAT"                │
       │  │ header.timestamp = "2026-03-28T10:00:00Z"   │
       │  └─────────────────────────────────────────────┘
       │
       ▼
T+0s   SchedulerEventHandler.handle()
       │
       │  檢查 timestamp：
       │    - 每 5 分鐘 → dispatch FETCH_ORDERS
       │    - 每 10 分鐘 → dispatch FETCH_RETURNS
       │
       │  對每個啟用中的 channel 發送：
       │  ┌─────────────────────────────────────────────┐
       │  │ topic: shopee.slow                          │
       │  │ header.taskType   = "FETCH_ORDERS"          │
       │  │ header.merchantId = "abc123"                │
       │  │ header.platformId = "shopee"                │
       │  │ header.channelId  = "SHOPEE_001"            │
       │  │ header.timestamp  = "2026-03-28T10:00:00Z"  │
       │  │ body: {}  ← 刻意為空，Channel Job 自決時間窗 │
       │  └─────────────────────────────────────────────┘
       │
       ▼
T+1s   ChannelJobConsumer (shopee-slow container)
       │
       │  ModeBOrderListHandler.handle()
       │  │
       │  │  自主計算時間窗口（不依賴 body）：
       │  │    PENDING:           [T-1h,  T]
       │  │    AWAITING_SHIPMENT: [T-3d,  T]
       │  │    SHIPPED:           [T-5d,  T]
       │  │    COMPLETED:         [T-7d,  T]
       │  │
       │  │  ➡ 呼叫 Shopee API（含分頁 + rate limit）
       │  │  ➡ 回傳訂單摘要列表（缺 items / payment / shipping）
       │  │
       │  │  對每筆訂單：檢查是否需要 detail
       │  │    ↳ 大多數需要 → 發 FETCH_ORDER_DETAIL
       │  │
       │  └──► publish to shopee.slow：
       │       ┌─────────────────────────────────────────┐
       │       │ header.taskType   = "FETCH_ORDER_DETAIL"│
       │       │ header.channelId  = "SHOPEE_001"        │
       │       │ body.channelOrderId = "shopee-order-99" │
       │       └─────────────────────────────────────────┘
       │
       ▼
T+3s   ChannelJobConsumer (同一 container，不同 thread)
       │
       │  ModeBOrderDetailHandler.handle()
       │  │
       │  │  ➡ 呼叫 Shopee detail API（1 筆訂單）
       │  │  ➡ 組合完整訂單資料
       │  │  ➡ 計算 orderHash = SHA-256(完整訂單 JSON)
       │  │  ➡ 標記 isRollback（依時間差判斷）
       │  │
       │  └──► publish to order.process：
       │       ┌─────────────────────────────────────────────────────┐
       │       │ header.taskType   = "ORDER_UPSERT"                  │
       │       │ header.merchantId = "abc123"                        │
       │       │ header.platformId = "shopee"                        │
       │       │ header.isRollback = false                           │
       │       │ body.orderHash = "sha256-abc..."                    │
       │       │ body.orderData = {                                  │
       │       │   channelOrderId: "shopee-order-99",                │
       │       │   status: "confirmed",                              │
       │       │   totalAmount: 1580.00,                             │
       │       │   buyerName: "張三",      ← 待加密                  │
       │       │   items: [{sku, qty, price}],                       │
       │       │   ...                                               │
       │       │ }                                                   │
       │       └─────────────────────────────────────────────────────┘
       │
       ▼
T+4s   OrderUpsertConsumer (order-job container)
       │
       │  [1] SchemaVersionHandler.validate(json)
       │       version != 1 → 直接丟 task.dlt，停止處理
       │
       │  [2] TaskMdcHelper.set(json)
       │       MDC: merchantId, taskType, channelId → 所有 log 自動帶上
       │
       │  [3] 驗證必填欄位（merchantId, channelId, channelOrderId, orderHash）
       │       缺欄位 → 丟 task.failed（FORMAT_ERROR）
       │
       │  [4] Redis 去重（第一層）
       │       key = "order:hash:{merchantId}:{channelOrderId}"
       │       ├── hit  → log "already processed (Redis)" → return
       │       └── miss → continue（Redis 失敗也 continue）
       │
       │  [5] EncryptionContext.setMerchantId(merchantId)
       │
       │  [6] DB 去重（第二層，authoritative）
       │       SELECT id FROM orders WHERE channel_order_id = ? AND merchant_id = ?
       │       ├── 存在 → UPDATE（狀態、金額等欄位可能更新）
       │       └── 不存在 → INSERT（PII 欄位透過 TypeHandler 加密）
       │
       │  [7] Redis dirty marker（統計層）
       │       ZADD stats:dirty <timestamp> "abc123:shopee:SHOPEE_001:2026-03-28"
       │
       │  [8] EncryptionContext.clear()
       │      TaskMdcHelper.clear()
       │
       ▼
T+4s   DB 寫入完成，訂單持久化 ✅

T+30s  DailyStatisticsService (scheduled task，每 30 秒一次)
       │
       │  ZSCAN stats:dirty → 取出所有 dirty member
       │  對每個 member 重算 daily_statistics
       │  ZREM 清除已處理的 member
       │
       ▼
       統計數據更新完成 ✅（最終一致性，最多延遲 ~30 秒）
```

---

### 3.2 訂單拉取流程（Mode A — Easystore）

> Mode A：list API 一次回傳完整訂單，不需要 detail API

```
Scheduler → dispatch FETCH_ORDERS to easystore.slow

ChannelJobConsumer
  ModeAOrderListHandler.handle()
  │
  │  時間窗口：固定 7 天（easystore 一次取 50 筆完整訂單）
  │  ➡ 呼叫 Easystore API
  │  ➡ 回傳完整訂單（含 items, payment, shipping）
  │
  │  ← 不需要 FETCH_ORDER_DETAIL →
  │
  └──► 每筆訂單直接 publish ORDER_UPSERT to order.process

其餘流程與 Mode B 完全相同（order.process → order-job → DB）
```

**Mode A vs Mode B 差異**：

```
Mode B（Shopee, Momo, Yahoo, PChome, Cyberbiz）：
  shopee.slow
    FETCH_ORDERS ──► list（摘要）
                          └──► FETCH_ORDER_DETAIL（for each）
                                    └──► ORDER_UPSERT

  Kafka messages per order：2（FETCH_ORDER_DETAIL + ORDER_UPSERT）

Mode A（Shopify, Easystore）：
  easystore.slow
    FETCH_ORDERS ──► list（完整）
                          └──► ORDER_UPSERT（直接）

  Kafka messages per order：1（ORDER_UPSERT）
```

---

### 3.3 退貨入庫流程

```
Scheduler → dispatch FETCH_RETURNS to {platform}.slow

ChannelJobConsumer
  FetchReturnsHandler.handle()
  │
  │  ➡ 呼叫平台退貨 API
  │  ➡ 轉換格式
  │  ➡ 計算 returnHash = SHA-256(退貨資料)
  │
  └──► publish to return.process：
       ┌──────────────────────────────────────────┐
       │ header.taskType   = "RETURN_UPSERT"      │
       │ body.channelRefundId = "refund-123"      │
       │ body.returnHash = "sha256-xyz..."        │
       │ body.returnData = { ...退貨完整資料... } │
       └──────────────────────────────────────────┘

ReturnUpsertConsumer (order-job container)
  │
  │  [1] 同 OrderUpsertConsumer 的驗證邏輯
  │  [2] Redis 去重（key 含 channelRefundId）
  │  [3] DB 去重 → INSERT or UPDATE refund_orders
  │  [4] Redis dirty marker（統計含退貨金額）
  │
  ▼
refund_orders 寫入完成 ✅
daily_statistics.refund_count / refund_amount 30 秒內更新 ✅
```

---

### 3.4 出貨回寫流程（商家主動出貨）

```
商家在 user-app 點「出貨」
  │
  ▼
POST /api/user/orders/{orderId}/ship
  │
  API Controller
  ├── 驗證 merchantId（來自 JWT，非請求參數）
  ├── 驗證 orderId 屬於此 merchant
  └──► publish to {platform}.fast：
       ┌──────────────────────────────────────────────┐
       │ topic: shopee.fast                           │
       │ header.taskType   = "SHIP_ORDER"             │
       │ header.merchantId = "abc123"                 │
       │ header.platformId = "shopee"                 │
       │ header.channelId  = "SHOPEE_001"             │
       │ body.orderId       = "oms-order-nano-id"     │
       │ body.trackingNumber = "7112345678"           │
       │ body.shippingMethod = "711"                  │
       └──────────────────────────────────────────────┘

ChannelJobConsumer (shopee-fast container)
  ShipOrderHandler.handle()
  │
  │  ➡ 呼叫 Shopee 出貨 API（< 5 秒目標）
  │  ➡ 拿到平台確認
  │  ➡ 更新 order_shipments 表
  │  ➡ 更新 orders.status = "shipped"
  │
  ▼
出貨完成，商家 dashboard 更新 ✅
```

**注意**：`.fast` 的目標延遲 < 5 秒，所以 concurrency 要夠高，不能被 `.slow` 的長跑任務拖住。

---

### 3.5 失敗重試 → DLT 流程

```
任何 Consumer 處理失敗
  │
  │  catch (Exception e)
  │  │
  │  │  判斷錯誤類型：
  │  │  ├── 欄位缺失（IllegalArgumentException）
  │  │  │       → errorType = "FORMAT_ERROR"
  │  │  │       → 直接送 task.failed（不值得重試，但要記錄）
  │  │  │
  │  │  └── 業務錯誤（DB 連線失敗、外部 API 超時...）
  │  │          → errorType = "SERVER_ERROR_5XX"
  │  │          → 送 task.failed
  │  │
  │  └──► publish to task.failed：
  │       ┌───────────────────────────────────────────────┐
  │       │ 原始訊息 + 追加 body.errorInfo：              │
  │       │ {                                             │
  │       │   "errorType": "SERVER_ERROR_5XX",           │
  │       │   "errorMessage": "Connection refused",      │
  │       │   "retryCount": 0                            │
  │       │ }                                            │
  │       └───────────────────────────────────────────────┘

RetryJobConsumer (retry-job container)
  │
  │  讀取 errorInfo.retryCount
  │  │
  │  ├── retryCount < 3（可重試）
  │  │     retryCount++
  │  │     重新 publish 到 原始 topic（從 header 取得）
  │  │     → 下游 Consumer 再試一次
  │  │
  │  └── retryCount >= 3 或 FORMAT_ERROR
  │        → publish to task.dlt
  │
  ▼
DltConsumer (retry-job container)
  │
  │  persist to failed_task_logs table：
  │  ┌────────────────────────────────────┐
  │  │ id           = NanoID             │
  │  │ task_type    = "ORDER_UPSERT"     │
  │  │ merchant_id  = "abc123"           │
  │  │ error_type   = "SERVER_ERROR_5XX" │
  │  │ error_message = "..."            │
  │  │ payload      = 原始訊息 JSON      │
  │  │ created_at   = now()             │
  │  └────────────────────────────────────┘
  │
  ▼
等待人工審查，可從 DB 手動補跑 ✅

重試時間線（最壞情況）：
  T+0    第 1 次失敗 → task.failed (retryCount=0)
  T+?    RetryJob 重試（retryCount=1）→ 再失敗
  T+??   RetryJob 重試（retryCount=2）→ 再失敗
  T+???  RetryJob 重試（retryCount=3）→ 再失敗
  T+???? retryCount=3 → task.dlt → 寫 DB → 告警
```

---

### 3.6 每日統計重算流程

```
                      ┌──────────────────────────────────┐
                      │  Redis ZSet: stats:dirty          │
                      │                                  │
  OrderUpsertConsumer ├── ZADD "abc123:shopee:CH001:      │
  ReturnUpsertConsumer│         2026-03-28"  <timestamp> │
                      └──────────────────────────────────┘
                                      │
                                      │ 每 30 秒
                                      ▼
                      DailyStatisticsService.recalcDirty()
                      │
                      │  ZSCAN stats:dirty（取出所有 dirty）
                      │
                      │  對每個 dirty member：
                      │    parseMember → merchantId, platformId, channelId, statDate
                      │
                      │    SELECT COUNT, SUM FROM orders
                      │    WHERE merchant_id = ?
                      │      AND channel_id = ?
                      │      AND created_at BETWEEN statDate 00:00 AND 23:59
                      │
                      │    SELECT COUNT, SUM FROM refund_orders
                      │    WHERE merchant_id = ?
                      │      AND channel_id = ?
                      │      AND requested_at BETWEEN ...
                      │
                      │    UPSERT daily_statistics（ON CONFLICT UPDATE）
                      │
                      │  ZREM stats:dirty（清除已處理）
                      │
                      ▼
                      daily_statistics 更新完成（最終一致性）✅

注意：如果 Redis 掛掉，dirty marker 寫不進去
      → 統計暫時不更新（stale）
      → 下一次 Redis 恢復後，新的訂單會重新觸發
      → 舊的這段空窗可透過人工 STATS_RECALC 補跑
```

---

### 3.7 套包同步流程

```
Scheduler → dispatch SYNC_PACK to {platform}.slow

ChannelJobConsumer
  SyncPackHandler.handle()
  │
  │  ➡ 呼叫平台商品/套包 API
  │  ➡ 取得通路上的「套包」清單（平台沒有「商品」概念）
  │
  └──► publish to task.backend：
       ┌──────────────────────────────────────────┐
       │ header.taskType = "SYNC_PACK"            │
       │ body.packData = { channelPackId, name,   │
       │                   sku, price, stock }    │
       └──────────────────────────────────────────┘

TaskBackendConsumer (backend-job container)
  SyncPackTaskHandler.handle()
  │
  │  UPSERT sell_pack 表
  │
  └──► publish to task.backend：
       ┌──────────────────────────────────────────┐
       │ header.taskType = "SYNC_PRODUCT"         │
       │ 觸發 Pack → Product 映射建立             │
       └──────────────────────────────────────────┘

SyncProductTaskHandler.handle()
  │
  │  依 sell_pack.sku 查 products 表
  │  ├── 找到 → 建立 sell_pack.product_id FK
  │  └── 找不到 → 建立新 product 記錄
  │
  ▼
商品主檔更新完成 ✅
```

---

## 4. 訊息結構解析

### 通用 Envelope

```json
{
  "header": {
    "taskType":   "ORDER_UPSERT",
    "merchantId": "abc123456789",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-uuid-here",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": { /* TaskType 決定的 payload */ }
}
```

**header 欄位職責表**：

| 欄位 | 用途 | 誰用 |
|------|------|------|
| `taskType` | 路由到正確 Handler | 所有 Consumer |
| `merchantId` | 多租戶隔離 | 所有 Handler（查 DB 時的 WHERE 條件） |
| `platformId` | 選擇正確的平台 Adapter | Channel Job |
| `channelId` | 指定哪一個商店實例 | Channel Job、統計層 |
| `timestamp` | Channel Job 計算時間窗口的基準點 | Channel Job |
| `source` | 追蹤訊息來源（for debugging） | Log / MDC |
| `version` | Schema 版本控制 | SchemaVersionHandler |
| `isRollback` | 區分即時訂單 vs 歷史補跑 | OrderUpsertHandler（統計歸因日期） |

### isRollback 的意義

```
isRollback = false（正常同步）：
  訂單業績歸屬 → 今日（系統時間）
  統計 dirty marker → 今日的 stats

isRollback = true（歷史補跑）：
  訂單業績歸屬 → channelCreatedAt 那天
  統計 dirty marker → channelCreatedAt 對應的 stats

  用途：
  - 第一次串接新通路時的歷史訂單補匯
  - 系統停機後的補跑
  - 通路資料修正後的重算
```

---

## 5. CAP 理論在本系統的實現

### 5.1 我們的選擇：AP 系統

**CAP 三角**：

```
        C（一致性）
       Consistency
           △
           │
    CP     │     CA
    ───────┼────────
           │
    ───────┼────────
    AP     │
           │
           ▼
P ─────────────────── A
Partition            Availability
Tolerance
```

**網路分區（P）在分散式系統中無法避免**，所以實際選擇是：

- **CP**：確保一致性，分區時犧牲可用性（銀行轉帳、金融交易）
- **AP**：確保可用性，分區時犧牲強一致性（換取最終一致性）

**SimpleEC OMS 選擇 AP**，原因：

```
問題：如果 Kafka 掛掉，我們應該怎麼做？

CP 的回答：拒絕所有寫入，等 Kafka 恢復，保證資料一致
AP 的回答：允許已到 DB 的訂單繼續被讀取，
            新訂單等 Kafka 恢復後繼續補跑

我們選 AP，因為：
1. 電商訂單允許幾分鐘的延遲，但不允許完全不可用
2. 統計數據延遲 30 秒是可接受的
3. 強一致性的代價（分散式鎖、2PC）遠超業務需求
```

---

### 5.2 一致性如何最終達成

#### 機制一：兩層去重（Idempotency）

```
Kafka 保證「at-least-once delivery」= 訊息可能重複投遞

問題：同一筆 ORDER_UPSERT 被消費兩次 → 訂單重複插入

解法：兩層去重
              ┌─────────────────────────────────────────┐
              │                                         │
消費到訊息    │  第一層：Redis（fast path）              │
    │         │  key = order:hash:{merchantId}:{orderId}│
    ├─ hit ──►│  → skip（~1ms，大部分情況走這裡）       │
    │         │                                         │
    └─ miss ──┤  第二層：DB（authoritative）            │
              │  SELECT WHERE channel_order_id = ?      │
              │  ├── 存在 → UPDATE                     │
              │  └── 不存在 → INSERT                   │
              │                                         │
              └─────────────────────────────────────────┘

Redis 掛掉時：
  第一層失效 → 自動 fallback 到第二層
  所有訊息都查 DB → 效能略降，但正確性不受影響
```

#### 機制二：orderHash 內容雜湊

```
orderHash = SHA-256(完整訂單 JSON)

作用：
1. 去重鍵（上面說的）
2. 變更偵測（hash 不同 = 訂單有更新 → 觸發 UPDATE）

舉例：
  第一次同步：status=confirmed, hash=A
  平台更新狀態：status=shipped
  下次同步：新 hash=B ≠ A → 觸發 UPDATE orders SET status='shipped'

  如果 hash 沒變（平台沒更新）：
    Redis hit → skip（不打 DB，省資源）
```

#### 機制三：最終一致性統計

```
                     一致性階段
                     ──────────

寫入訂單（strong consistency）：
  INSERT orders ... （ACID transaction）
  ↓ 完成後立即一致

寫 dirty marker（best effort）：
  ZADD stats:dirty ...
  ↓ Redis 可能失敗，但無關正確性

讀統計（eventual consistency）：
  DailyStatisticsService 從 DB 重算
  ↓ 最多 ~30 秒後與 orders 表一致

一致性保證：
  ✅ 訂單記錄本身：強一致（DB ACID）
  ✅ 統計數字：最終一致（30 秒內）
  ❌ 即時統計：不保證（設計取捨）
```

#### 機制四：Kafka 訊息持久化

```
Kafka broker 收到訊息後 commit ack 給 producer：
  生產者設定 acks=all（等所有 ISR 副本確認）
  → 即使 broker 立刻掛掉，訊息已在副本上

Consumer 的 offset commit：
  enable.auto.commit = false（預設）
  只有處理成功後才 commit offset
  → Consumer 重啟後從上次 committed offset 繼續
  → 結合兩層去重，保證不丟、不重複寫入 DB
```

---

### 5.3 各元件掛掉時的行為

| 元件 | 掛掉時 | 影響 | 恢復後 |
|------|--------|------|--------|
| **Kafka Broker** | 新訊息無法發送/消費 | Channel Job 停止拉取；出貨延遲 | Consumer lag 累積，恢復後自動追趕 |
| **Redis** | dirty marker / dedup 失敗 | DB 層 dedup 接管；stats 暫時不更新 | 新訂單恢復 dirty marker，舊空窗需手動補 |
| **order-job** | ORDER_UPSERT 堆積在 order.process | 訂單不入庫，但訊息在 Kafka 等待 | 重啟後從 committed offset 繼續處理 |
| **channel-job** | 不拉取新訂單 | 該通路訂單不更新（stale） | 重啟後 Scheduler 會繼續派發 FETCH_ORDERS |
| **PostgreSQL** | 所有服務無法讀寫 DB | 整體服務降級 | 重啟後各 Consumer 重試，兩層去重保護 |
| **scheduler-job** | 不派發 FETCH_ORDERS 等任務 | 訂單不主動同步（只能靠 webhook） | 重啟後立即恢復派發 |

**最壞情況：Kafka + Redis 同時掛掉**

```
新訂單：
  Channel Job 無法發訊息 → 訂單暫時不入庫
  商家 UI 讀 DB → 舊訂單仍可查看（AP 的 A）

已在 DB 的訂單：完全可用

恢復後：
  Kafka 恢復 → Channel Job 重新拉取，訊息重發
  Redis 恢復 → dirty marker 重新可用
  兩層去重 → 即使訊息重複，DB 不重複插入
```

---

### 5.4 我們不保證什麼

```
❌ 跨 Partition 的訊息順序
   同一個 merchantId 的兩筆訂單可能在不同 partition
   → ORDER_A 可能比 ORDER_B 晚處理（即使 ORDER_A 先發）
   → 因為我們是「被動同步方」，不做狀態機，所以這沒關係

❌ 即時統計（< 1 秒）
   統計最多落後 ~30 秒
   → 不適合用來做即時競賽排名（請用 stream processing）

❌ Redis dedup 的永久記憶
   Redis 有 TTL / 可能重啟清空
   → 長時間後的重複訊息可能繞過第一層
   → 第二層 DB 仍保護（但多一次 DB query）

❌ DLT 訊息的自動補跑
   DLT 只做持久化 + 告警
   → 需要人工 or 腳本從 failed_task_logs 重發
```

---

## 6. 關鍵設計決策摘要

| 決策 | 原因 | 取捨 |
|------|------|------|
| **AP 而非 CP** | 電商允許秒級延遲，不接受完全不可用 | 犧牲強一致性，換取高可用 |
| **fast / slow 分離** | 出貨等操作時間敏感，不能被慢任務堵住 | 多開容器，但互不干擾 |
| **Channel Job 自決時間窗口** | 各平台 API 特性完全不同 | Scheduler 更簡單，但 Channel Job 更複雜 |
| **兩層去重** | Redis 掛掉時不能丟資料，DB 是最後防線 | 多一次 DB query，但正確性有保障 |
| **orderHash** | at-least-once delivery 保護 + 變更偵測 | SHA-256 計算開銷極小，值得 |
| **isRollback 標籤** | 補跑訂單不能污染當日業績 | Consumer 判斷邏輯略複雜 |
| **order.process retention 2h** | DB 是 source of truth，Kafka 只是管道 | 2 小時夠 order-job 重啟後追趕 |
| **task.dlt retention 30d** | 人工審查需要時間，30 天是工程師的緩衝期 | 多佔磁碟，但失敗有跡可查 |
| **stats 最終一致** | 強一致需要分散式鎖，代價過高 | 統計落後 30 秒，業務可接受 |

---

> 如果你只能記住一句話：
>
> **Kafka 是傳遞管道，PostgreSQL 是 Source of Truth，Redis 是加速層。**
>
> 任何一層掛掉，系統降級但不崩潰，恢復後資料最終一致。
