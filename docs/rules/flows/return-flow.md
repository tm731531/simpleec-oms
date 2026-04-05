# 退貨退款流程規則 (Return Flow)

> **架構重點：**
> - 退貨事件**不**在 Channel Job 直接處理（不同於訂單主流程）
> - Channel Job 從平台抓退貨資訊 → 送至 `return.process` topic
> - Return Process Job（ReturnUpsertHandler）處理 RETURN_UPSERT
> - 商家審核動作（核准/拒絕）：API → `{platform}.fast` topic
>   → Channel Job（ApproveReturnHandler / RejectReturnHandler）→ 呼叫平台 API

---

## 1. 業務規則

### 1.1 退貨模型

| 概念 | 說明 |
|------|------|
| `refund_orders` | OMS 退貨記錄的唯一資料來源（source of truth） |
| ReturnStatus | 由平台驅動，OMS 接受任何狀態轉移，**不做狀態驗證拒絕** |
| Items | 退貨明細存於 JSONB，不建立獨立 items 資料表 |

### 1.2 OMS 被動接受退貨狀態

- OMS 不主動建立退貨單；退貨由平台通知觸發
- OMS **接受任何平台推來的退貨狀態轉移**（包含跳轉），不驗證狀態流轉合法性
- 目的：避免 OMS 狀態與平台不同步

### 1.3 商家審核（核准/拒絕）

| 支援平台 | 說明 |
|----------|------|
| Shopee | 支援 confirm / reject |
| Cyberbiz | 支援 manual_returning / manual_return_refuse |
| Shopline | 透過 fulfillment order API |
| Shopify | 手動流程 / refund API，OMS 側無法自動驅動 |

### 1.4 退貨抓取觸發時機

| 平台 | 觸發方式 | 時間窗口 |
|------|----------|---------|
| Shopee | Scheduler 定時掃描訂單 TO_RETURN 狀態 | Channel Job 自主決定 |
| Cyberbiz | `refund_at` 時間窗口查詢 | 1 小時窗口（Channel Job 自主決定） |
| Shopify | Webhook（refund/return 事件） | 即時推送 |
| Shopline | Webhook 或定時輪詢 | Channel Job 自主決定 |

### 1.5 去重規則

- 同一 `returnId + status` 組合若已處理過，跳過（不重複 INSERT/UPDATE）
- 去重邏輯與訂單去重一致（查詢 `refund_orders` 現有狀態比較）

---

## 2. 前端 / API

### 2.1 端點清單

| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/returns` | 退貨訂單列表（分頁、篩選） |
| GET | `/api/returns/{id}` | 退貨詳情（含 items、退款金額） |
| POST | `/api/returns/{id}/approve` | 核准退貨（送 APPROVE_RETURN 至 Kafka） |
| POST | `/api/returns/{id}/reject` | 拒絕退貨（送 REJECT_RETURN 至 Kafka） |

### 2.2 GET /api/returns 回應結構（範例）

```json
{
  "data": [
    {
      "id": "ret_nanoId20chars",
      "orderId": "ord_nanoId20chars",
      "merchantId": "merch_nanoId20chars",
      "channelId": "chan_nanoId20chars",
      "channelReturnId": "shopee-return-sn",
      "status": "PENDING_REVIEW",
      "refundAmount": 399.00,
      "currency": "TWD",
      "reason": "商品瑕疵",
      "items": [
        {
          "sellPackId": "pack_nanoId20chars",
          "packName": "紅色 / L",
          "quantity": 1,
          "refundAmount": 399.00
        }
      ],
      "createdAt": "2026-04-05T09:00:00Z",
      "updatedAt": "2026-04-05T09:05:00Z"
    }
  ],
  "pagination": { "page": 1, "pageSize": 20, "total": 5 }
}
```

### 2.3 POST /api/returns/{id}/approve

- 更新 DB 狀態 → `APPROVED`，**然後**發送 `APPROVE_RETURN` Kafka 事件至 `{platform}.fast` topic
- Channel context 解析路徑：`returnOrder.orderId → order.channelId → channel.platformId → platform.platformName → topic`
- Response: `{ "requestId": "...", "message": "Approve request sent" }`（HTTP 200，回傳更新後的退貨物件）
- 非同步：Channel Job 收到訊息後才呼叫平台 API；實際平台狀態由後續 RETURN_ACTION_CONFIRMED 回寫
- Kafka 發送失敗時只記 error log，不回滾 DB 狀態（避免 UI 不一致）

### 2.4 POST /api/returns/{id}/reject

- 更新 DB 狀態 → `REJECTED`，**然後**發送 `REJECT_RETURN` Kafka 事件至 `{platform}.fast` topic
- Channel context 解析路徑同上（approve）
- Request Body（選填）: `{ "reason": "拒絕原因" }`（reason 目前存 DB，不帶入 Kafka body）
- Response: HTTP 200，回傳更新後的退貨物件

### 2.5 UI 顯示要求

- 列表顯示：退貨單號、訂單號、狀態、退款金額、建立時間
- 詳情頁顯示：退貨原因、商品明細（含圖片）、平台操作按鈕（核准/拒絕）
- 不支援核准/拒絕的平台：按鈕不顯示或 disabled + tooltip

---

## 3. Kafka 契約

### 3.1 Topic 對應

| 方向 | Topic | taskType |
|------|-------|----------|
| Channel Job（抓退貨） → Return Process | `return.process` | `RETURN_UPSERT` |
| API → Channel Job（核准） | `{platform}.fast` | `APPROVE_RETURN` |
| API → Channel Job（拒絕） | `{platform}.fast` | `REJECT_RETURN` |
| Channel Job（操作結果） → Backend | `task.backend` | `RETURN_ACTION_CONFIRMED` |

> **注意：退貨訊息送至 `return.process`，不是 `order.process` 或 `task.backend`**

### 3.2 Channel Job → return.process（RETURN_UPSERT）

```json
{
  "header": {
    "taskType": "RETURN_UPSERT",
    "merchantId": "merch_nanoId20chars",
    "platformId": "shopee",
    "channelId": "chan_nanoId20chars",
    "requestId": "req_nanoId20chars",
    "timestamp": "2026-04-05T09:00:00Z",
    "source": "channel-job",
    "version": "1.0",
    "isRollback": false
  },
  "body": {
    "returnData": {
      "returnId": "ret_nanoId20chars",
      "channelOrderId": "shopee-order-sn-12345",
      "channelReturnId": "shopee-return-sn-67890",
      "status": "PENDING_REVIEW",
      "refundAmount": 399.00,
      "currency": "TWD",
      "reason": "商品瑕疵",
      "items": [
        {
          "channelProductId": "shopee-item-id",
          "channelSpecId": "shopee-model-id",
          "packName": "紅色 / L",
          "quantity": 1,
          "refundAmount": 399.00
        }
      ],
      "returnedAt": "2026-04-05T08:55:00Z"
    }
  }
}
```

**關鍵規則：**
- `returnId`：若已存在 OMS 記錄填入 NanoID；首次抓取可為 `null`（由 ReturnUpsertHandler 建立）
- `channelOrderId`：平台原始訂單 ID（ReturnUpsertHandler 用此查詢 `orders` 表取得 `orderId`）
- `channelReturnId`：平台退貨單號（去重依據之一）

### 3.3 API → {platform}.fast（APPROVE_RETURN / REJECT_RETURN）

```json
{
  "header": {
    "taskType": "APPROVE_RETURN",
    "merchantId": "merch_nanoId20chars",
    "platformId": "platform_nanoId20ch",
    "channelId": "chan_nanoId20chars",
    "requestId": "req_nanoId20chars",
    "timestamp": "2026-04-05T09:10:00Z",
    "source": "api",
    "version": 1,
    "isRollback": false
  },
  "body": {
    "returnId": "ret_nanoId20chars"
  }
}
```

**關鍵規則：**
- `body.returnId` 為 **OMS NanoID**，**不是** channelReturnId
- Channel Job 翻譯：`returnId` → `channelReturnId`（查 `refund_orders` 表）
- `header.version` 為整數 `1`（不是字串 `"1.0"`）
- REJECT_RETURN：header.taskType 改為 `"REJECT_RETURN"`，body 結構相同（reason 選填，但目前不帶入 Kafka body）
- Topic 解析：`ReturnController` 透過 `returnOrder.orderId → order.channelId → channel.platformId → platform.platformName` 取得平台名稱，再呼叫 `TopicConstants.platformFastTopic(platformName)`

### 3.4 Channel Job → task.backend（RETURN_ACTION_CONFIRMED）

```json
{
  "header": {
    "taskType": "RETURN_ACTION_CONFIRMED",
    ...
  },
  "body": {
    "returnId": "ret_nanoId20chars",
    "action": "APPROVE",
    "platformStatus": "PROCESSING",
    "confirmedAt": "2026-04-05T09:11:00Z"
  }
}
```

---

## 4. Channel Job

### 4.1 Handler 清單

| Handler | 觸發 | 職責 |
|---------|------|------|
| `FetchReturnsHandler` | Scheduler → `scheduler` topic | 從平台抓退貨資訊，送 RETURN_UPSERT |
| `ApproveReturnHandler` | API → `{platform}.fast` topic | 翻譯 returnId，呼叫平台核准 API |
| `RejectReturnHandler` | API → `{platform}.fast` topic | 翻譯 returnId，呼叫平台拒絕 API |

### 4.2 FetchReturnsHandler 流程

```
1. 接收 Scheduler 訊息（含 timestamp，無時間窗口）
2. 自主決定查詢策略（依 platform.capabilities）：
   - Cyberbiz：以 refund_at 1 小時窗口查詢
   - Shopee：掃描訂單列表中 status = TO_RETURN 的訂單
3. 呼叫平台退貨 API
4. 轉換每筆退貨為 RETURN_UPSERT 結構
5. 批次送至 return.process topic
```

### 4.3 ApproveReturnHandler / RejectReturnHandler 流程

```
1. 接收 {platform}.fast topic 訊息（含 returnId NanoID）
2. 翻譯：查 refund_orders 表取得 channelReturnId
3. 依平台呼叫核准/拒絕 API
4. 成功後送 RETURN_ACTION_CONFIRMED 至 task.backend
5. 失敗後送 RETURN_ACTION_FAILED（含 errorCode）
```

### 4.4 平台能力查詢

```java
// 判斷平台是否支援退貨核准操作
boolean supportsApproveReturn = platform.capabilities
    .path("return.approveReject").asBoolean(false);

// Cyberbiz 退貨查詢使用 refund_at 時間窗口
boolean usesRefundAtWindow = platform.capabilities
    .path("return.fetchByRefundAt").asBoolean(false);
```

### 4.5 Cyberbiz 退貨抓取流程

```
1. GET /v1/orders?refund_at_min={windowStart}&refund_at_max={windowEnd}
   → 取得有退貨的訂單列表
2. 對每筆訂單：GET /v1/orders/{id}/returns
   → 取得退貨詳情
3. 轉換為 RETURN_UPSERT
```

> **重要：** Cyberbiz 用 `refund_at` 作為時間窗口，**不是** `updated_at`

---

## 5. 後端 Job

### 5.1 ReturnUpsertHandler

**觸發：** 監聽 `return.process` topic，`taskType = RETURN_UPSERT`

**流程：**
```
1. 以 (channelId + channelReturnId) 查詢 refund_orders
2. 去重檢查：若 status 相同 → 跳過（log INFO），不重複處理
3. 若不存在 → 查詢 orders 表取得 orderId（by channelOrderId + channelId）
4. 生成 NanoID，INSERT refund_orders
5. 若已存在 → UPDATE status, items, refundAmount, updatedAt
6. 若 channelOrderId 對應不到 orderId → 記錄 warn log，INSERT 時 order_id 暫為 null
```

### 5.2 ReturnActionConfirmedHandler

**觸發：** 監聽 `task.backend` topic，`taskType = RETURN_ACTION_CONFIRMED`

**流程：**
```
1. 查詢 refund_orders（by returnId）
2. 更新 status（依 platformStatus）
3. 記錄操作 log（可記錄在 JSONB status_history 或獨立 log 表）
```

### 5.3 去重邏輯

```java
// 查詢現有退貨記錄
Optional<RefundOrder> existing = refundOrderRepository
    .findByChannelIdAndChannelReturnId(channelId, channelReturnId);

if (existing.isPresent() && existing.get().getStatus().equals(incomingStatus)) {
    log.info("RETURN_UPSERT skipped (same status): returnId={}, status={}", returnId, incomingStatus);
    return;  // 去重跳過
}
```

---

## 6. DB

### 6.1 refund_orders 資料表

```sql
CREATE TABLE refund_orders (
    id                VARCHAR(20)    PRIMARY KEY,  -- NanoID
    order_id          VARCHAR(20),                 -- FK to orders（可能為 null，若訂單不存在）
    merchant_id       VARCHAR(20)    NOT NULL,
    channel_id        VARCHAR(20)    NOT NULL,
    channel_return_id VARCHAR(100)   NOT NULL,     -- 平台退貨單號
    channel_order_id  VARCHAR(100),               -- 平台訂單號（備存，用於查找 order_id）
    status            VARCHAR(50)    NOT NULL,
    refund_amount     NUMERIC(12,2),
    currency          VARCHAR(10),
    reason            TEXT,
    items             JSONB,                       -- 退貨明細（見下方結構）
    status_history    JSONB,                       -- 狀態變更歷史（append-only array）
    created_at        TIMESTAMPTZ    DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    DEFAULT NOW()
);

-- items JSONB 結構
-- [
--   {
--     "channelProductId": "shopee-item-id",
--     "channelSpecId": "shopee-model-id",
--     "sellPackId": "pack_nanoId",
--     "packName": "紅色 / L",
--     "quantity": 1,
--     "refundAmount": 399.00
--   }
-- ]

-- status_history JSONB 結構（append-only）
-- [
--   {
--     "fromStatus": null,
--     "toStatus": "PENDING_REVIEW",
--     "source": "channel-job",
--     "timestamp": "2026-04-05T09:00:00Z"
--   },
--   {
--     "fromStatus": "PENDING_REVIEW",
--     "toStatus": "APPROVED",
--     "source": "api",
--     "timestamp": "2026-04-05T09:10:00Z"
--   }
-- ]
```

### 6.2 Upsert 模式

```sql
-- 新增（首次）
INSERT INTO refund_orders (id, order_id, merchant_id, channel_id, channel_return_id, ...)
VALUES (...)
ON CONFLICT (channel_id, channel_return_id)
DO UPDATE SET
    status          = EXCLUDED.status,
    refund_amount   = EXCLUDED.refund_amount,
    items           = EXCLUDED.items,
    status_history  = refund_orders.status_history || EXCLUDED.status_history,
    updated_at      = NOW();
```

> `status_history` 使用 `||` append，保留完整歷史，不覆蓋。

### 6.3 必要索引

```sql
-- 去重查詢
CREATE UNIQUE INDEX idx_refund_orders_channel_return
    ON refund_orders (channel_id, channel_return_id);

-- 按訂單查退貨
CREATE INDEX idx_refund_orders_order_id
    ON refund_orders (order_id);

-- 按商家 + 通路查詢
CREATE INDEX idx_refund_orders_merchant_channel
    ON refund_orders (merchant_id, channel_id);
```

---

## 7. Platform API

### 7.1 退貨核准/拒絕 API 差異

| 平台 | 核准 API | 拒絕 API | 備注 |
|------|----------|----------|------|
| Shopee | `POST /v2/returns/confirm` body: `{ return_sn }` | `POST /v2/returns/reject` body: `{ return_sn, reason }` | return_sn 為平台退貨單號 |
| Cyberbiz | `PUT /v1/orders/{orderId}/manual_return?operation=manual_returning` | `PUT /v1/orders/{orderId}/manual_return?operation=manual_return_refuse` | orderId 為平台訂單號 |
| Shopline | 透過 fulfillment order API 處理 | — | 需查 Shopline 最新文件 |
| Shopify | 無直接核准 API；透過 refund API 手動建立退款 | — | OMS 端無法自動驅動，`supportsApproveReturn = false` |

### 7.2 Shopee 退貨清單 API

```
GET /v2/returns/get_return_list
  ?page_no={page}
  &page_size=50
  &response_optional_fields=item_list,logistics_info

→ 回傳有退貨申請的訂單，含 return_sn, status, item_list
```

### 7.3 Cyberbiz refund_at 查詢

```
GET /v1/orders
  ?refund_at_min=2026-04-05T08:00:00+08:00
  &refund_at_max=2026-04-05T09:00:00+08:00
  &fields=id,order_number,refund_at

→ 取得訂單 ID 清單
↓
GET /v1/orders/{id}/returns
→ 取得退貨詳情
```

---

## 9. QA Checklist

### Kafka 契約

- [ ] RETURN_UPSERT 送至 `return.process` topic（不是 `order.process` 或 `task.backend`）
- [ ] APPROVE_RETURN / REJECT_RETURN 送至 `{platform}.fast` topic
- [ ] APPROVE_RETURN / REJECT_RETURN body 中 `returnId` 為 OMS NanoID（不是 channelReturnId）
- [ ] Channel Job 翻譯：`returnId` → `channelReturnId`（查 `refund_orders` 表）
- [ ] `header.version` 為整數 `1`（不是字串 `"1.0"`）

### 核准 / 拒絕事件發送

- [ ] `POST /api/returns/{id}/approve` 在更新 DB 狀態為 APPROVED 後，確實送出 `APPROVE_RETURN` Kafka 事件
- [ ] `POST /api/returns/{id}/reject` 在更新 DB 狀態為 REJECTED 後，確實送出 `REJECT_RETURN` Kafka 事件
- [ ] 事件送至正確的 `{platformName}.fast` topic（驗證 platformName 解析路徑：orderId → channelId → platformId → platformName）
- [ ] Kafka 發送失敗時：error log 記錄，不拋出例外，不回滾 DB 狀態
- [ ] Channel Job 確認收到 APPROVE_RETURN / REJECT_RETURN 後呼叫平台 API，再回送 RETURN_ACTION_CONFIRMED

### 資料正確性

- [ ] Cyberbiz 退貨抓取使用 `refund_at` 時間窗口（不是 `updated_at`）
- [ ] 去重：同一 `channelReturnId + status` 相同時不重複處理
- [ ] OMS 接受任何退貨狀態轉移，不驗證狀態合法性（不拋出 InvalidStateTransitionException）
- [ ] `status_history` JSONB 為 append-only，不覆蓋歷史記錄
- [ ] 首次 RETURN_UPSERT 時，`channel_return_id` 正確存入（作為去重鍵）

### 邊緣情境

- [ ] 退貨對應的 `channelOrderId` 在 `orders` 表不存在時：記錄 warn log，`order_id = null`，不拋出例外
- [ ] 不支援核准/拒絕的平台（Shopify）：API 返回 422，前端按鈕 disabled
- [ ] 同一退貨單抓取兩次（Channel Job 重試）→ `refund_orders` 只有一筆記錄
- [ ] ReturnUpsertHandler 處理時 `return.process` topic 消費冪等（重送 Kafka 訊息不造成重複建立）
