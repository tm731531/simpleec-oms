# Kafka Event Bus 深度解析

> 本文從三個角度梳理 SimpleEC OMS 的 Kafka 事件匯流排：
> 1. **選型視角** — 為什麼一定要用 Kafka，不能用輪詢、其他 MQ 或 API 直呼
> 2. **功能性視角** — 每條訊息的來源、目的地、處理邏輯
> 3. **工程性視角** — CAP 理論在非同步系統中如何落地
>
> 讀完本文，你應該能回答三個問題：「為什麼選 Kafka？」「一筆訂單是怎麼從電商平台進來、被存到資料庫的？」「系統掛掉一部分，資料會怎樣？」

---

## 目錄

1. [為什麼一定要用 Kafka](#1-為什麼一定要用-kafka)
2. [系統全貌：Event Bus 拓撲圖](#2-系統全貌-event-bus-拓撲圖)
3. [Topic 職責與分層設計](#3-topic-職責與分層設計)
3. [完整事件流程圖](#3-完整事件流程圖)
   - 3.1 [訂單拉取流程（Mode B — Shopee）](#31-訂單拉取流程mode-b--shopee)
   - 3.2 [訂單拉取流程（Mode A — Easystore）](#32-訂單拉取流程mode-a--easystore)
   - 3.3 [退貨申請同步流程](#33-退貨申請同步流程未實作入庫確認)
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

## 1. 為什麼一定要用 Kafka

### 1.1 先理解問題的規模

SimpleEC OMS 需要同時對接 6 個電商平台，每個平台底下有多個通路實例（channel），每個通路需要定期拉取訂單、退貨、同步庫存，並且能即時接收 webhook。

先做一個粗估：

```
6 個平台 × 平均 3 個通路 = 18 個通路實例
每個通路每 5 分鐘觸發一次 FETCH_ORDERS
每次 FETCH_ORDERS 需要拉 4 個時間窗口（PENDING/CONFIRMED/SHIPPED/COMPLETED）
每個時間窗口可能產生 50-200 筆訂單
每筆 Mode B 訂單需要一次額外的 detail API 呼叫

高峰期（促銷日）：
  18 通路 × 4 窗口 × 200 筆 = 14,400 筆訂單 / 5 分鐘
  14,400 筆 × Mode B detail = 14,400 次額外 API 呼叫 / 5 分鐘
  = 每秒 48 次 API 呼叫（平台 rate limit 的邊緣）
```

這個規模決定了架構的選擇。

---

### 1.2 為什麼不用輪詢（Polling）

最直覺的做法：寫一個 cron job，每 5 分鐘呼叫一次平台 API。

```
❌ 輪詢方案：

CronJob (每5分鐘)
  for each channel:
    orders = platform.fetchOrders()
    for each order:
      db.upsert(order)
```

**問題一：無法彈性擴縮**

```
輪詢是單執行緒的順序執行：
  通路A → 通路B → 通路C → ...（串行）

促銷日訂單量 ×10：
  → cron job 執行時間從 3 分鐘變成 30 分鐘
  → 下一輪 cron 觸發時上一輪還沒跑完
  → 訂單積壓、延遲

Kafka 方案：
  → 增加 consumer concurrency 即可（改一個環境變數）
  → 水平擴展不影響其他服務
```

**問題二：rate limit 無法協調**

```
各平台都有 rate limit（例如 Shopee: 10 req/sec）

輪詢方案：
  如果同時有 10 個通路在跑，每個都在打 Shopee API
  → 瞬間超過 rate limit → 429 Too Many Requests → 全部失敗

Kafka 方案：
  Channel Job consumer concurrency 直接控制並發數
  每個 platform 的 fast/slow 分開，各自的 consumer group 隔離
  → rate limit 控制精確
```

**問題三：失敗沒有自然的重試機制**

```
輪詢方案失敗：
  try:
    orders = platform.fetchOrders()   ← 網路超時
  except:
    log.error("failed")               ← 只能記 log
    # 下次 cron 再試，但已經錯過這個時間窗口了

Kafka 方案失敗：
  → 訊息進 task.failed
  → RetryJob 自動 1min / 5min / 30min 重試
  → 超過 3 次 → task.dlt → 持久化到 DB + 告警
  → 可以手動補跑
```

**問題四：資源浪費**

```
輪詢永遠在跑，不管有沒有訂單：

凌晨 3 點：
  輪詢：依然每 5 分鐘查一次，回傳 0 筆 → 白打 API
  Kafka：Scheduler 發 FETCH_ORDERS，Channel Job 呼叫 API
         → 同樣跑，但可以在 Scheduler 層加邏輯跳過離峰

（注意：這個問題兩者差異不大，但 Kafka 的 Scheduler 更容易加判斷邏輯）
```

---

### 1.3 為什麼不用其他 MQ（RabbitMQ / ActiveMQ / AWS SQS）

這些 MQ 都是優秀的工具，選 Kafka 的理由是以下幾個**這個題目特有的需求**：

**需求一：訊息必須可以重播（Replay）**

```
傳統 MQ（RabbitMQ / SQS）：
  消費完 → 訊息消失 → 無法重播

Kafka：
  訊息保留在 log 中（retention 期間內）
  任何 consumer group 都可以從任意 offset 重新消費

場景：
  order-job 部署了一個有 bug 的版本，消費了 1 小時後才發現
  → 修好 bug 後，把 consumer group offset 重置
  → 重新消費那 1 小時的訊息
  → 訂單資料修正完成

  用 RabbitMQ：那 1 小時的訊息已經 ack 刪除，無法補救
```

**需求二：多個獨立消費者讀同一份訊息**

```
order.process topic 的訊息需要被：
  1. order-job：寫入 DB
  2. （未來）analytics-job：寫入資料倉儲
  3. （未來）notification-job：發推播通知

Kafka Consumer Group 機制：
  每個 group 各自維護 offset，互不干擾
  新增 consumer group 不影響現有消費者

RabbitMQ：
  一條訊息只能被一個 consumer 消費（需要 fanout exchange 複製訊息）
  新增消費者需要改 topology，侵入性較高
```

**需求三：嚴格的訊息順序（per partition）**

```
同一個 channelOrderId 的訂單可能被更新多次：
  T1: status=confirmed
  T2: status=shipped
  T3: status=completed

如果 T3 比 T1 先處理 → 訂單狀態倒退 → 錯誤資料

Kafka：
  同一個 key（channelOrderId）的訊息永遠落在同一個 partition
  同一個 partition 內的訊息嚴格有序消費
  → 保證同一訂單的更新按時間順序處理

SQS Standard：不保證順序（需要改用 FIFO queue，有吞吐量上限）
RabbitMQ：需要額外設計才能保證順序
```

**需求四：高吞吐 + 持久化**

```
Kafka 的底層是 append-only log：
  寫入速度極快（順序寫磁碟比隨機寫快 100 倍）
  可達到每秒百萬級訊息
  訊息預設持久化到磁碟（不會因 broker 重啟而丟失）

這個系統高峰期估計：
  每秒最多 ~50 筆 ORDER_UPSERT
  → 對 Kafka 來說是九牛一毛
  → 但如果未來接入更多平台 / 做即時資料流分析，擴充空間充裕
```

**什麼情況應該用 RabbitMQ 而非 Kafka？**

```
✅ 適合 RabbitMQ 的場景：
  - 訊息量小（每秒 < 1000）
  - 複雜的 routing 邏輯（direct/topic/fanout/headers exchange）
  - 需要訊息優先級（priority queue）
  - 消費完立即刪除、不需要 replay
  - 任務分發（work queue）型場景

❌ 這個系統需要 replay、高吞吐、嚴格順序 → Kafka 更合適
```

---

### 1.4 為什麼不用 API 直呼（微服務 REST/gRPC）

另一個常見的替代方案：Channel Job 處理完訂單後，直接 HTTP 呼叫 Order Service 的 API。

```
❌ API 直呼方案：

channel-job  ──HTTP POST──►  order-service  ──HTTP POST──►  stats-service
                              (處理訂單)                     (更新統計)
```

**問題一：同步耦合 → 級聯失敗**

```
channel-job 呼叫 order-service：
  order-service 回應慢（DB 壓力大）
  → channel-job 的 thread 被阻塞等待
  → 所有通路的訂單處理全部卡住
  → Shopee API 的 rate limit window 過了
  → 這批訂單丟失

Kafka 方案：
  channel-job 把訊息寫進 order.process（極快，< 5ms）
  order-job 慢慢消費、可以 lag
  → 兩者完全解耦，互不影響
```

**問題二：沒有自然的 Buffer**

```
促銷日瞬間湧入 10,000 筆訂單：

API 直呼：
  channel-job 同時發 10,000 個 HTTP request 給 order-service
  → order-service 記憶體爆炸 / 503 / OOM
  → 資料全部遺失（沒有 buffer）

Kafka 方案：
  10,000 筆訊息進 Kafka（已持久化）
  order-job 以自己的速度消費（consumer lag 暫時增加）
  → 最終一致，資料不會遺失
```

**問題三：重試邏輯要自己實作**

```
API 直呼失敗：
  你需要自己寫：指數退避、重試次數限制、circuit breaker、
  失敗日誌、死信儲存...

Kafka 方案：
  task.failed → RetryJob（指數退避已內建）
  → task.dlt（持久化 + 告警已內建）
  重試邏輯集中在一個地方，所有服務共用
```

**問題四：沒辦法 replay**

```
order-service 有 bug，處理了錯誤的訂單資料：

API 直呼：
  訊息已發出並被消費 → 無法重播 → 需要人工補資料

Kafka：
  reset consumer group offset → 重新消費 → 資料自動修正
```

**什麼情況應該用 API 直呼？**

```
✅ 適合 API 直呼的場景：
  - 需要即時回應（使用者等待結果）
  - 業務強依賴同步確認（付款、扣款）
  - 資料量小、呼叫頻率低

這個系統：
  - 訂單同步是背景作業，不需要即時回應 → ✅ 適合非同步
  - 規模大（每5分鐘幾千筆）→ ✅ 需要 buffer
  - 需要 replay → ✅ 需要 Kafka
```

---

### 1.5 選型總結

```
                    輪詢        其他 MQ      API 直呼     Kafka
                    ────────    ─────────    ─────────    ──────
可水平擴展           △ 難        ✅           ✅           ✅
訊息 Replay          ❌          ❌           ❌           ✅
多獨立消費者         ❌          △ 需設計      ❌           ✅
嚴格順序（per key）  △           △            ❌           ✅
失敗重試 / DLT       ❌ 要自寫   △ 部分支援   ❌ 要自寫    ✅ 內建
Rate limit 控制      ❌ 難協調   △            ❌           ✅ 精確
促銷日流量洪峰緩衝   ❌          ✅           ❌           ✅
平台掛掉不影響其他   ❌          ✅           ❌           ✅
高吞吐持久化         △           △            ❌           ✅
```

**結論：這個題目的核心需求是「多平台、高並發、需要 Replay、嚴格順序、失敗容錯」，Kafka 是唯一能同時滿足所有需求的選項。**

---

## 2. 系統全貌：Event Bus 拓撲圖

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

## 3. Topic 職責與分層設計

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

## 4. 完整事件流程圖

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

### 3.3 退貨流程（⚠️ 尚未實作）

> ⚠️ **目前狀態：Stub / 佔位符**
>
> 退貨整條流程尚未實作。`FetchReturnsHandler` 目前是一個空跑佔位符：
> 收到 `FETCH_RETURNS` 任務後，**不呼叫任何平台 API**，只發一筆
> `body.stub = true` 的假訊息到 `return.process`，防止訊息被靜默丟棄。

```
【現況：佔位符空跑】

Scheduler → dispatch FETCH_RETURNS to {platform}.slow

ChannelJobConsumer
  FetchReturnsHandler.handle()   ← stub，什麼都沒做
  │
  │  // TODO: call adapter.fetchReturnsByTimestamp()
  │  // once platform adapters expose a return-list API
  │
  └──► publish to return.process（placeholder）：
       ┌──────────────────────────────────────────────────┐
       │ header.taskType = "RETURN_UPSERT"                │
       │ body.stub = true                                 │
       │ body.note = "FETCH_RETURNS not yet implemented"  │
       └──────────────────────────────────────────────────┘

ReturnUpsertConsumer 收到後…
  目前沒有對 stub 訊息做特殊處理（可能直接 log 然後跳過）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

【待實作的完整退貨流程】

第一階段：平台退貨申請同步（自動）
  FetchReturnsHandler → 呼叫平台 API → 拉取退貨申請
  → publish RETURN_UPSERT（含真實 returnData）
  → ReturnUpsertConsumer → INSERT refund_orders（status = "requested"）

第二階段：倉庫入庫確認（手動）
  倉庫人員實體驗收商品
  ├── 通過 → 人工操作系統確認入庫
  │           refund_orders.status = "received"
  │           inventory 回補、退款啟動
  └── 拒絕 → refund_orders.status = "rejected"

注意：入庫確認涉及實體商品驗收，不會由系統自動化。
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

## 5. 訊息結構解析

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

## 6. CAP 理論在本系統的實現

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

## 7. 關鍵設計決策摘要

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
