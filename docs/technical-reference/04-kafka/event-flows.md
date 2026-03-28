# 事件流程

本文件端對端追蹤最重要業務場景在 Kafka 拓撲中的完整訊息流程。
每個流程均展示各服務交換的確切 Kafka 訊息，以及各服務執行的處理步驟。

---

## 1. 訂單寫入流程 — Mode B（Shopee：清單 + 詳細資料）

Shopee 的清單 API 回傳不完整的訂單（不含商品明細、付款及物流資訊）。Channel Job
必須針對每筆訂單另行呼叫 detail API。這是「Mode B」流程。

```
                    ┌─────────────┐
                    │  Scheduler  │  (HeartbeatTimer，每秒觸發一次)
                    └──────┬──────┘
                           │ 每 5 分鐘：針對每個啟用的通路
                           │ 派發 FETCH_ORDERS
                           ▼
                    ┌─────────────┐
                    │  scheduler  │  Kafka topic
                    │    topic    │
                    └──────┬──────┘
                           │
                    ┌──────▼──────────────┐
                    │ SchedulerEventHandler│  (simpleec-scheduler-job)
                    └──────┬──────────────┘
                           │ 發布 FETCH_ORDERS 至 shopee.slow
                           ▼
```

**訊息 1 → `shopee.slow`**：
```json
{
  "header": {
    "taskType":   "FETCH_ORDERS",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-sched-001",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "scheduler",
    "version":    1,
    "isRollback": false
  },
  "body": {}
}
```

```
                    ┌──────────────────────┐
                    │  ChannelJobConsumer  │  (simpleec-channel-job, shopee.slow)
                    │  ModeBOrderList      │
                    │  Handler             │
                    └──────┬───────────────┘
                           │
                           │  Channel Job 從 header.timestamp 計算時間窗口：
                           │  - PENDING:              timestamp-1h  → timestamp
                           │  - AWAITING_SHIPMENT:    timestamp-3d  → timestamp
                           │  - SHIPPED:              timestamp-5d  → timestamp
                           │  - COMPLETED:            timestamp-7d  → timestamp
                           │
                           │  呼叫 Shopee 清單 API（4 次獨立請求）
                           │  收到不完整的訂單 ID
                           │
                           │  針對每筆需要詳細資料的訂單：
                           │  發布 FETCH_ORDER_DETAIL 至 shopee.slow
                           ▼
```

**訊息 2 → `shopee.slow`**（每筆訂單一條）：
```json
{
  "header": {
    "taskType":   "FETCH_ORDER_DETAIL",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-detail-002",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "channelOrderId": "2503281234567890"
  }
}
```

```
                    ┌──────────────────────┐
                    │  ModeBOrderDetail    │
                    │  Handler             │  (shopee.slow consumer)
                    └──────┬───────────────┘
                           │
                           │  呼叫 Shopee detail API 查詢訂單 2503281234567890
                           │  收到完整訂單資料（商品明細、付款、物流）
                           │  正規化為 OMS 格式
                           │  發布 ORDER_UPSERT 至 order.process
                           ▼
```

**訊息 3 → `order.process`**：
```json
{
  "header": {
    "taskType":   "ORDER_UPSERT",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-detail-002",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "channel_job",
    "version":    1,
    "isRollback": false
  },
  "body": {
    "orderHash": "a3f8b2c1d4e5f6a7b8c9d0e1f2a3b4c5",
    "orderData": {
      "orderId":          "V9kMnPqRsT2uWxYz",
      "channelOrderId":   "2503281234567890",
      "channelCreatedAt": "2026-03-28T08:30:00Z",
      "status":           "confirmed",
      "totalAmount":      1580.00,
      "shippingFee":      60.00,
      "buyerName":        "張三",
      "buyerPhone":       "0912345678",
      "shippingAddress":  "台北市信義區信義路五段7號",
      "items": [
        { "channelItemId": "shopee-item-99988877", "sku": "BT-HEADPHONE-BLK", "quantity": 2, "unitPrice": 790.00 }
      ]
    }
  }
}
```

```
                    ┌────────────────────┐
                    │ OrderUpsertConsumer│  (simpleec-order-job, order.process)
                    └──────┬─────────────┘
                           │
                           │  1. SchemaVersionHandler.validate()
                           │  2. Redis: GET orderHash → 命中則跳過；未命中則繼續
                           │  3. DB: SELECT by (channel_id, channel_order_id)
                           │        → 未找到：INSERT 新訂單
                           │        → 已找到：若資料有變更則 UPDATE
                           │  4. EncryptionContext.setMerchantId(merchantId)
                           │     儲存訂單（PII 欄位由 converter 自動加密）
                           │     EncryptionContext.clear()
                           │  5. Redis: SET orderHash（TTL 24h）
                           │  6. Redis ZSet: ZADD stats:dirty {score=now} {key=merchantId:channelId:date}
                           │
                           ▼
                    ┌────────────────────────┐
                    │ DailyStatisticsService │  (排程執行，讀取 Redis dirty 標記)
                    └──────┬─────────────────┘
                           │
                           │  重新計算商家／通路／日期的 daily_statistics
                           │  Upsert daily_statistics（分區資料表）的資料列
                           ▼
                         (完成)
```

---

## 2. 訂單寫入流程 — Mode A（Cyberbiz：完整清單）

Cyberbiz 在清單回應中直接回傳完整訂單資料，無需呼叫 detail API。
這是「Mode A」流程，相對簡單。

```
Scheduler → FETCH_ORDERS → cyberbiz.slow

                    ┌──────────────────────┐
                    │  ModeAOrderList      │
                    │  Handler             │  (cyberbiz.slow consumer)
                    └──────┬───────────────┘
                           │
                           │  Channel Job 從 header.timestamp 計算 7 天時間窗口
                           │  呼叫 Cyberbiz /v1/orders API（分頁，最多 50 筆/頁）
                           │  每筆訂單資料已完整，無需呼叫 detail API
                           │  針對每筆訂單：發布 ORDER_UPSERT 至 order.process
                           ▼

→ ORDER_UPSERT → order.process → OrderUpsertConsumer（後續流程與 Mode B 相同）
```

---

## 3. 退貨處理流程

```
Scheduler → FETCH_RETURNS → {platform}.slow

                    ┌──────────────────────┐
                    │  FetchReturnsHandler │  (channel-job)
                    └──────┬───────────────┘
                           │
                           │  呼叫平台退貨 API
                           │  對需要詳細資料的平台：發布 FETCH_RETURN_DETAIL
                           │  完成後：發布 RETURN_UPSERT 至 return.process
                           ▼
                    ┌──────────────────────┐
                    │ ReturnUpsertConsumer │  (simpleec-order-job 或 return-job，
                    │                      │   return.process)
                    └──────┬───────────────┘
                           │
                           │  1. SchemaVersionHandler.validate()
                           │  2. DB: SELECT refund_orders by (order_id, channel_refund_id)
                           │        → 未找到：INSERT refund_order
                           │        → 已找到：UPDATE 退款狀態
                           │  3. UPDATE orders SET has_refund=true, refund_amount=...
                           │     WHERE id = orderId
                           │  4. Redis ZSet: ZADD stats:dirty（標記日期待重新計算）
                           ▼
                    DailyStatisticsService 重新計算 refund_count、refund_amount、net_amount
```

---

## 4. 出貨更新流程

當商家在 OMS 介面將訂單標記為已出貨時觸發。

```
[商家在介面點擊「出貨」]
        │
        ▼
┌───────────────┐
│  simpleec-api │  POST /api/orders/{id}/ship
└───────┬───────┘
        │  發布 SHIP_ORDER 至 {platform}.fast
        ▼
{platform}.fast（例如 shopee.fast）

        │
        ▼
┌──────────────────────┐
│  ShipOrderHandler    │  (channel-job, fast consumer)
└──────┬───────────────┘
        │
        │  攜帶追蹤號碼呼叫平台出貨 API
        │  成功：發布 ORDER_UPSERT 至 order.process
        │        status=shipped，trackingNumber=...
        │  失敗：發布至 task.failed
        ▼
order.process → OrderUpsertConsumer → DB UPDATE orders SET order_status='shipped', shipped_at=now()
```

---

## 5. 失敗訊息重試流程

```
任意 consumer 拋出例外
        │
        ▼
DefaultErrorHandler（FixedBackOff 0, 0 — 不進行進程內重試）
        │
        │  判斷：可重試或不可重試？
        │
        ├─ 可重試（網路錯誤、逾時、暫時性 DB 錯誤）
        │       │
        │       ▼
        │   task.failed
        │       │
        │       ▼
        │  ┌────────────────────┐
        │  │  RetryJobConsumer  │  (simpleec-retry-job)
        │  └────────┬───────────┘
        │           │
        │           │  1. 從訊息中提取 errorInfo.retryCount
        │           │  2. retryCount < maxRetries（3）？
        │           │       是：遞增 retryCount，重新發布至 originalTopic
        │           │       否：發布至 task.dlt
        │           ▼
        │
        └─ 不可重試（UNSUPPORTED_VERSION、MALFORMED_MESSAGE、業務邏輯錯誤）
                │
                ▼
            task.dlt
                │
                ▼
        ┌────────────────────┐
        │    DltConsumer     │  (simpleec-retry-job)
        └────────┬───────────┘
                │
                │  持久化至 failed_task_logs 資料表：
                │    - original_topic, task_type, merchant_id
                │    - error_message, reason, retry_count
                │    - 完整 payload（JSONB）
                │
                ▼
        透過管理主控台或直接查詢 DB 進行人工排查
```

**重試訊息信封**（`task.failed` 中的訊息）：

```json
{
  "header": {
    "taskType":   "ORDER_UPSERT",
    "merchantId": "abc123456789012345",
    "platformId": "shopee",
    "channelId":  "SHOPEE_001",
    "requestId":  "req-original",
    "timestamp":  "2026-03-28T10:00:00Z",
    "source":     "order_job",
    "version":    1,
    "isRollback": false
  },
  "body": { "...": "original body preserved" },
  "errorInfo": {
    "originalTopic": "order.process",
    "retryCount":    1,
    "errorMessage":  "Connection timeout to PostgreSQL",
    "failedAt":      "2026-03-28T10:00:05Z"
  }
}
```

---

## 6. 統計重新計算流程

`DailyStatisticsService` 使用 Redis 作為 dirty 標記系統，避免全資料表掃描：

```
訂單／退貨儲存至 DB
        │
        ▼
Redis ZADD stats:dirty {score=epochMs} {member="merchantId:channelId:YYYY-MM-DD"}

        │  （非同步，每 30 秒排程執行一次）
        ▼
DailyStatisticsService.recalculateDirtyStats()
        │
        │  1. Redis ZRANGEBYSCORE stats:dirty 0 now → 取得所有 dirty key
        │  2. 針對每個 key（merchantId, channelId, date）：
        │       SELECT COUNT(*), SUM(total_amount), ... FROM orders
        │         WHERE merchant_id=? AND channel_id=?
        │           AND channel_created_at >= date AND channel_created_at < date+1day
        │           AND order_status != 'cancelled'
        │       → UPDATE daily_statistics（透過唯一索引執行 UPSERT）
        │  3. Redis ZREM stats:dirty {已處理的 key}
        ▼
daily_statistics 資料表更新完成（查詢具備分區感知能力）
```

**為何使用 dirty 標記而非同步重算？**

- 訂單以突發方式到達（scheduler 一次派發大量訊息）
- 同步重算會造成針對同一日期的 N 筆訂單進行 N 次 DB 寫入
- dirty 標記將大量訂單寫入合併為單次統計重算
- 取捨：統計數據相對訂單寫入最多落後 30 秒

---

## 7. 套包同步流程

通路銷售的是「套包」（Listing）。OMS 將這些套包映射至內部商品。

```
Scheduler → SYNC_PACK → {platform}.slow

        │
        ▼
┌──────────────────────┐
│  SyncPackHandler     │  (channel-job)
└──────┬───────────────┘
        │  呼叫平台商品 Listing API
        │  回傳正規化的套包資料
        │  發布 SYNC_PACK 結果至 task.backend
        ▼
task.backend

        │
        ▼
┌──────────────────────┐
│  BackendJobConsumer  │  (simpleec-backend-job)
└──────┬───────────────┘
        │
        │  1. Upsert sell_pack 資料列（通路 Listing 資料）
        │  2. 發布 SYNC_PRODUCT 至 task.backend
        ▼
SYNC_PRODUCT → BackendJobConsumer
        │
        │  1. 比對 pack.sku → product.sku（在商家範圍內）
        │  2. 若找到商品：關聯 sell_pack.product_id = product.id
        │  3. 若未找到商品：建立商品 stub
        ▼
sell_pack 與 product 資料表更新完成
```
