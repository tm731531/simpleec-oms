# 通路整合概覽

SimpleEC OMS 中的「通路（Channel）」是一個整合實例，將特定商家與單一電商平台連接（例如：一家 Shopee 店鋪、一家 Momo 商店）。本文件說明如何讓所有平台整合在外部保持一致，同時在內部容納各平台獨特的 API 行為。

---

## 1. Channel Job 的職責

Channel Job 是一個以 Docker 容器形式運行的 Spring Boot 應用程式。它負責：

1. **消費**來自平台專屬 Kafka 主題的任務訊息（例如：`shopee.slow`）
2. **呼叫**外部平台 API 以抓取訂單、退貨，或推送出貨／庫存更新
3. **轉換**平台特定的回應格式為統一的 OMS 格式
4. **發布**標準化訊息至 `order.process` 或 `return.process`

Channel Job 不會直接存取 OMS 資料庫，它是純粹的資料適配層。

```
Kafka (shopee.slow)
    → ChannelJobConsumer
        → 依 taskType 路由至對應 handler
            → handler 呼叫 ChannelAdapter（Shopee API）
                → handler 發布至 order.process
                    → OrderUpsertConsumer 寫入 DB
```

---

## 2. Docker 部署 — 10 個 Channel Job 容器

所有容器共用一個 JAR 映像（`simpleec-channel-job`），每個容器透過環境變數設定，對應特定平台與速度分級：

| 容器名稱 | 消費的主題 | Consumer Group ID |
|---------------|-----------------|----------|
| `simpleec-channel-momo-fast` | `momo.fast` | `channel-job-momo` |
| `simpleec-channel-momo-slow` | `momo.slow` | `channel-job-momo` |
| `simpleec-channel-shopee-fast` | `shopee.fast` | `channel-job-shopee` |
| `simpleec-channel-shopee-slow` | `shopee.slow` | `channel-job-shopee` |
| `simpleec-channel-yahoo-fast` | `yahoo.fast` | `channel-job-yahoo` |
| `simpleec-channel-yahoo-slow` | `yahoo.slow` | `channel-job-yahoo` |
| `simpleec-channel-pchome-fast` | `pchome.fast` | `channel-job-pchome` |
| `simpleec-channel-pchome-slow` | `pchome.slow` | `channel-job-pchome` |
| `simpleec-channel-cyberbiz-fast` | `cyberbiz.fast` | `channel-job-cyberbiz` |
| `simpleec-channel-cyberbiz-slow` | `cyberbiz.slow` | `channel-job-cyberbiz` |

**fast 主題：** 短時間推送操作 — `SHIP_ORDER`、`UPDATE_PRICE`、`UPDATE_INVENTORY`、`APPROVE_RETURN`、`REJECT_RETURN`，預計在 5 秒內完成。

**slow 主題：** 長時間抓取操作 — `FETCH_ORDERS`、`FETCH_ORDER_DETAIL`、`FETCH_RETURNS`，因分頁和 API 速率限制可能需要數分鐘。

容器透過解析 `JOB_CHANNEL_GROUP_ID`（例如：`channel-job-cyberbiz` → `cyberbiz`）判斷自身服務的平台。

---

## 3. 訊息路由（taskType 分發）

`ChannelJobConsumer.consumeChannelMessage()` 依據 `header.taskType` 將每條 Kafka 訊息路由至對應的 handler：

| `taskType` | Handler | 主題分級 |
|-----------|---------|-----------|
| `FETCH_ORDERS` | `ModeAOrderListHandler` 或 `ModeBOrderListHandler`（依 adapter 模式決定） | slow |
| `FETCH_ORDER_DETAIL` | `ModeBOrderDetailHandler` | slow |
| `FETCH_RETURNS` | `FetchReturnsHandler` | slow |
| `SHIP_ORDER` | `ShipOrderHandler` | fast |
| `UPDATE_INVENTORY` | `UpdateInventoryHandler` | fast |
| `UPDATE_PRICE` | `UpdatePriceHandler` | fast |
| `APPROVE_RETURN` | `ApproveReturnHandler` | fast |
| `REJECT_RETURN` | `RejectReturnHandler` | fast |
| `CHECK_HEALTH` | `HealthCheckService` | fast |
| `CHECK_HEALTH_PLATFORM` | `HealthCheckService` | fast |

路由完成後，handler 會依據從 `JOB_CHANNEL_GROUP_ID` 提取的平台代碼選取對應的 `ChannelAdapter`。

---

## 4. Mode A 與 Mode B

各平台的訂單列表 API 回傳的資料量不同，這決定了每筆訂單需要呼叫幾次 API。

| 面向 | Mode A | Mode B |
|-----------|--------|--------|
| 範例平台 | Shopify、Easystore | Shopee、Momo、Yahoo、PChome、Cyberbiz |
| 列表 API | 回傳完整訂單（所有欄位、所有明細項目） | 只回傳訂單 ID 或摘要資訊 |
| 需要 Detail API？ | 否 | 需要 — 每個訂單 ID 各呼叫一次 |
| 流程 | `FETCH_ORDERS` → `ORDER_UPSERT` | `FETCH_ORDERS` → N 次 `FETCH_ORDER_DETAIL` → N 次 `ORDER_UPSERT` |
| 每筆訂單的 Kafka 訊息數 | 1 | 2 |

`ChannelAdapter.getMode()` 回傳 `ModeEnum.A` 或 `ModeEnum.B`，`ChannelJobConsumer.handleFetchOrders()` 會依此路由至對應的 handler。

### Mode A 流程
```
FETCH_ORDERS 訊息
  → ModeAOrderListHandler.handleModeAOrders()
      → adapter.fetchOrdersByTimestamp(channelId, baseTimestamp)
          → 回傳 List<Map>（完整訂單）
      → 逐筆訂單處理：
          → 計算業務欄位的 SHA-256 雜湊值
          → Redis 去重檢查（若內容未變更則略過）
          → 發布 ORDER_UPSERT 至 order.process
```

### Mode B 流程
```
FETCH_ORDERS 訊息
  → ModeBOrderListHandler.handleModeBOrderList()
      → adapter.fetchOrderListByTimestamp(channelId, baseTimestamp)
          → 回傳 List<String>（僅訂單 ID）
      → 逐個訂單 ID：
          → 發布 FETCH_ORDER_DETAIL 至 {platform}.slow

FETCH_ORDER_DETAIL 訊息（每個訂單 ID 一條）
  → ModeBOrderDetailHandler.handleModeBOrderDetail()
      → adapter.fetchOrderDetail(channelId, orderId)
          → 回傳 Map（完整訂單）
      → 發布 ORDER_UPSERT 至 order.process
```

---

## 5. 去重（Redis 雜湊快取）

在發布 `ORDER_UPSERT` 之前，Mode A 會透過 Redis 檢查，避免傳送未變更的訂單：

1. 對訂單的業務欄位（狀態、總金額、明細項目、出貨資訊、買家資訊）計算 SHA-256 雜湊值
2. 查詢 Redis 的 `order:hash:{merchantId}:{channelId}:{channelOrderId}` 鍵
3. 若雜湊值相符 → 略過（無變更）
4. 若雜湊值不同或鍵不存在 → 發布 `ORDER_UPSERT` 並更新 Redis 鍵

Redis 故障為非致命性：若 Redis 無法連線，去重檢查將被跳過（記錄警告），訊息仍照常發布。這意味著下游的 `OrderUpsertConsumer` 可能收到重複訊息，但它透過資料庫 upsert 語意加以處理。

---

## 6. 時間窗口決策 — Channel Job 自主性

**Scheduler 只傳送單一時間戳。** Channel Job 完全負責決定查詢的時間範圍，這是刻意的設計 — 每個平台有不同的 API 語意。

```
Scheduler 訊息 body：
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "channelId": "CHANNEL_SHOPEE_001",
    "timestamp": "2026-03-28T10:00:00Z"   ← 單一時間點
  },
  "body": {}   ← 不含 fromTime，不含 toTime
}
```

Channel Job handler 呼叫 `adapter.fetchOrdersByTimestamp(channelId, baseTimestamp)`，由 adapter 在內部決定時間窗口：

| 平台 | 策略 | 時間窗口邏輯 |
|----------|----------|-------------------|
| Shopee | Mode B，依狀態分批 | PENDING：`[ts-1h, ts]`；AWAITING_SHIPMENT：`[ts-3d, ts]`；SHIPPED：`[ts-5d, ts]`；COMPLETED：`[ts-7d, ts]` |
| Momo | Mode B，聚合 | 新訂單：`[ts-1h, ts]`；回補：`[ts-3d, ts]` |
| Yahoo | Mode B，更新時間查詢 | `updated_after: ts-1d`（不分狀態） |
| Easystore | Mode A，7 天窗口 | `from_date: ts-7d`、`to_date: ts`（每頁 50 筆完整訂單） |
| Cyberbiz | Mode B，雙查詢 | 建立時間：`[ts-7d, ts]`；更新時間：`[ts-1d, ts]`，合併後去重 |
| Shopify | Mode A，雙查詢 | 新訂單：`[ts-7d, ts]`；更新：`[ts-1d, ts]`，合併後去重 |

---

## 7. ChannelAdapter 介面

每個平台的 adapter 都必須實作 `com.simpleec.channel.adapter.ChannelAdapter`：

```java
public interface ChannelAdapter {
    String getPlatformCode();     // 例如："shopee"
    ModeEnum getMode();           // ModeEnum.A 或 ModeEnum.B

    // Mode A：依時間戳取完整訂單
    List<Map<String, Object>> fetchOrdersByTimestamp(String channelId, long baseTimestamp) throws Exception;

    // Mode B：依時間戳取訂單 ID 列表
    List<String> fetchOrderListByTimestamp(String channelId, long baseTimestamp) throws Exception;

    // Mode B：取單筆訂單詳情
    Map<String, Object> fetchOrderDetail(String channelId, String orderId) throws Exception;

    // 退貨
    List<Map<String, Object>> fetchReturns(String channelId, String timeRange) throws Exception;

    // 推送操作
    void shipOrder(String orderId, Map<String, Object> shippingInfo) throws Exception;
    void updateInventory(String productId, int quantity) throws Exception;

    // 健康檢查
    boolean testConnection() throws Exception;
}
```

Adapter 存放於 `simpleec-channel/src/main/java/com/simpleec/channel/adapter/`。

---

## 8. ORDER_UPSERT 訊息格式

Mode A 與 Mode B 的 handler 皆在 `order.process` 上產生以下格式的訊息：

```json
{
  "header": {
    "messageId": "msg_NANOID",
    "requestId": "req_NANOID",
    "taskType": "ORDER_UPSERT",
    "platformId": "cyberbiz",
    "channelId": "CHANNEL_CYBERBIZ_001",
    "merchantId": "mrc_NANOID",
    "timestamp": "2026-03-28T10:00:00Z",
    "source": "channel_job",
    "version": 1,
    "isRollback": false
  },
  "body": {
    "channelOrderId": "49004286",
    "channelOrderNumber": "#1001",
    "orderHash": "a3f9c2...",
    "orderData": {
      "orderStatus": "PENDING",
      "totalAmount": 1299.0,
      "shippingFee": 60.0,
      "discountAmount": 0.0,
      "channelCreatedAt": "2026-03-28T09:30:00Z",
      "items": [...],
      "buyerName": "王小明",
      "buyerPhone": "0912345678",
      "buyerEmail": "user@example.com",
      "shippingAddress": "台北市信義區...",
      "paymentMethod": "credit_card",
      "shippingMethod": "7-11 便利店取貨"
    }
  }
}
```

`channelOrderId` 作為 Kafka 分區鍵使用，以確保同一筆訂單的訊息按順序處理。

---

## 9. 錯誤處理

- handler 內部的單筆訂單處理失敗不會中止整個批次，錯誤會被記錄日誌，處理繼續至下一筆訂單。
- 無法復原的 consumer 錯誤會在 `consumeChannelMessage` 的頂層 try/catch 中被捕捉並記錄日誌。
- 含有不支援 schema 版本的訊息會被路由至 `task.dlt`。
- 缺少 `header` 或 `body` 的訊息會被路由至 `task.dlt`。
- 未知的 `taskType` 值會以警告記錄並丟棄（不重試）。
