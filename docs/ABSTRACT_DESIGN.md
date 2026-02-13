# 抽象設計規格書 — 模組合約與介面定義

> **目的**：定義每個模組 / JOB / Service 的 interface-level 合約，描述「每一步做什麼」而非「怎麼做」。
> 為 Level 2~4 實作提供藍圖。
>
> **最後更新**：2026-02-09

---

## 1. 系統總覽：模組 × 訊息流

### 1.1 七個 Spring Boot 應用

| 應用 | 角色 | 監聽 Topic | 說明 |
|------|------|-----------|------|
| **simpleec-api** | REST API 入口 | — | 接收前端 HTTP 請求，發 Kafka 任務 |
| **simpleec-gateway** | 閘道 | — | 路由 + 安全（未來） |
| **simpleec-channel-job** | 通路操作執行器 | `{platform}.fast`, `{platform}.slow` | 10 個 instance (5 平台 × fast/slow) |
| **simpleec-order-job** | 訂單整理入庫 | `order.process` | 接收 ChannelJob 的訂單 → DB upsert |
| **simpleec-backend-job** | 後台任務 | `task.backend` | 商品/SellPack 建立、退款同步、統計聚合、分區管理 |
| **simpleec-frontend-job** | 前端通知 | `task.frontend` | WebSocket 推送通知 |
| **simpleec-scheduler-job** | 排程觸發器 | `scheduler` | HeartbeatTimer 產生 tick → 判斷排程規則 → 發任務 |

### 1.2 十六個 Kafka Topic

```
              ┌─────── scheduler ────────┐
              │                          │
              │    HeartbeatTimer (1s)    │
              │         │                │
              │    SchedulerJob          │
              │    ├─ FETCH_ORDERS ──────┼──→ {platform}.slow ──→ ChannelJob
              │    ├─ CHECK_HEALTH ──────┼──→ {platform}.fast ──→ ChannelJob
              │    ├─ DAILY_STATISTICS ──┼──→ task.backend ──→ BackendJob
              │    └─ MANAGE_PARTITIONS ─┼──→ task.backend ──→ BackendJob
              └──────────────────────────┘

前端手動操作:
  simpleec-api ──→ {platform}.fast ──→ ChannelJob
    (出貨確認、取消、改價、改量、上下架)

  simpleec-api ──→ {platform}.slow ──→ ChannelJob
    (手動拉單、手動同步商品)

拉單後的訂單處理:
  ChannelJob ──→ order.process ──→ OrderProcessJob ──→ task.backend ──→ BackendJob
                                                     ──→ task.frontend ──→ FrontendJob (WebSocket)

同步商品後的明細抓取+建立/更新:
  ChannelJob (FETCH_PRODUCTS on fast) ──→ {platform}.slow (FETCH_PRODUCT_DETAIL)
  ChannelJob (FETCH_PRODUCT_DETAIL on slow) ──→ task.backend (CREATE_PRODUCT → routeNext → CREATE_SELL_PACK)
                                             ──→ task.backend (CREATE_SELL_PACK)

商品匯入（CSV）:
  simpleec-api ──→ task.backend ──→ BackendJob (CREATE_PRODUCT，無 routeNext)

失敗處理:
  任何 JOB ──→ task.failed ──→ RetryDispatchJob
    ├─ 可重打 → 原 topic (retryCount++)
    └─ 不可重打 → task.dlt (終點)
```

### 1.3 Topic 清單

| Topic | Partitions | 生產者 | 消費者 | 用途 |
|-------|-----------|--------|--------|------|
| `momo.slow` | 8 | SchedulerJob, API | ChannelJob (momo-slow) | Momo 重操作 |
| `momo.fast` | 8 | SchedulerJob, API | ChannelJob (momo-fast) | Momo 即時操作 |
| `shopee.slow` | 8 | SchedulerJob, API | ChannelJob (shopee-slow) | Shopee 重操作 |
| `shopee.fast` | 8 | SchedulerJob, API | ChannelJob (shopee-fast) | Shopee 即時操作 |
| `yahoo.slow` | 8 | SchedulerJob, API | ChannelJob (yahoo-slow) | Yahoo 重操作 |
| `yahoo.fast` | 8 | SchedulerJob, API | ChannelJob (yahoo-fast) | Yahoo 即時操作 |
| `pchome.slow` | 8 | SchedulerJob, API | ChannelJob (pchome-slow) | PChome 重操作 |
| `pchome.fast` | 8 | SchedulerJob, API | ChannelJob (pchome-fast) | PChome 即時操作 |
| `cyberbiz.slow` | 8 | SchedulerJob, API | ChannelJob (cyberbiz-slow) | Cyberbiz 重操作 |
| `cyberbiz.fast` | 8 | SchedulerJob, API | ChannelJob (cyberbiz-fast) | Cyberbiz 即時操作 |
| `order.process` | 8 | ChannelJob | OrderProcessJob | 訂單入庫處理 |
| `task.backend` | 8 | OrderProcessJob, ChannelJob, SchedulerJob | BackendJob | 後台任務（商品建立、退款同步、統計） |
| `task.frontend` | 8 | BackendJob | FrontendJob | 前端通知 |
| `scheduler` | 4 | HeartbeatTimer | SchedulerJob | 排程心跳 |
| `task.failed` | 4 | 任何 JOB | RetryDispatchJob | 失敗重打調度 |
| `task.dlt` | 4 | RetryDispatchJob, 任何 JOB | — (不消費) | 死信 (30天 retention) |

---

## 2. ChannelJob — 通路操作執行器

### 2.1 架構

```
{platform}.fast / {platform}.slow
  │
  ▼
ChannelJob.handle(TaskMessage msg)
  │
  ├─ SchemaVersionHandler.isSupported(msg.schemaVersion)
  │    → 不支援 → task.dlt
  │
  ├─ ActionFactory.getService(msg.topic, msg.taskAction)
  │    → null → log + ack
  │
  ├─ Resource resource = buildResource(msg)
  │
  ├─ service.setting(resource)
  ├─ service.getPlatformTokens()
  ├─ service.verifyNeedData()
  ├─ service.doAction()
  │
  ├─ SyncLogService.log(msg, "SUCCESS")
  └─ ack
```

**現有程式碼**: `simpleec-channel-job/.../ChannelJob.java` (已實作完成，不需修改)

### 2.2 ActionService 介面

```java
// simpleec-channel-job/.../action/ActionService.java (已存在)
public interface ActionService {
    String getAction();           // 回傳 action 名稱，用於 ActionFactory 路由
    void setting(Resource resource);    // 從 msg/resource 取參數
    void getPlatformTokens();     // 查 DB 取平台 + 通路認證
    void verifyNeedData();        // 前置驗證（通路啟用、認證有效、參數合理）
    void doAction();              // 核心業務邏輯
}
```

### 2.3 需要實作的 ActionService 清單

| Action | Topic 類型 | 觸發來源 | 職責 |
|--------|-----------|---------|------|
| `FETCH_ORDERS` | slow | SchedulerJob（排程自動） | 拉取訂單（分段抓取策略） |
| `FETCH_PRODUCTS` | **fast** | API（手動，客戶逐通路點擊） | 列表+差異比對，發散 FETCH_PRODUCT_DETAIL 到 `{platform}.slow` |
| `FETCH_PRODUCT_DETAIL` | slow | FETCH_PRODUCTS 發散 | 單筆商品明細抓取 + 路由到 task.backend |
| `FETCH_REFUND_ORDERS` | slow | SchedulerJob（排程自動） | 拉取退貨/退款單（多層時間窗口） |
| `CHECK_HEALTH` | fast | SchedulerJob | 驗證通路 API 連線 |
| `SHIPPING_CONFIRMED` | fast | API (前端) | 確認出貨（帶物流單號） |
| `ORDER_CANCELED` | fast | API (前端) | 接受取消訂單 |
| `MODIFY_PRICE` | fast | API (前端) | 修改商品價格 |
| `MODIFY_QUANTITY` | fast | API (前端) | 修改商品庫存 |
| `START_SELLING` | fast | API (前端) | 商品上架 |
| `STOP_SELLING` | fast | API (前端) | 商品下架 |

### 2.4 FetchOrdersActionService — 合約

> 抓取策略的完整定義見 `FETCH_STRATEGY.md`。

**生命週期:**

```
① setting(resource)
   ├─ this.msg = resource.getMsg()
   ├─ this.requestTime = msg.getCreatedAt()     ← 用於判斷 minute
   ├─ this.channelId = payload.get("channelId")
   ├─ this.adapter = resource 中取得對應平台的 ChannelAdapter
   ├─ this.redis = resource 中取得 RedisTemplate
   └─ this.taskProducer = resource.getTaskProducer()

② getPlatformTokens()
   ├─ 查 DB: platform.credential1~N (平台級認證)
   ├─ 查 DB: channel.token1~token5 (通路級 token)
   └─ 組合成 adapter 可用的認證 Map

③ verifyNeedData()
   ├─ 確認 channel.actived = true
   ├─ 確認 channel.enable_sync = true
   ├─ 確認認證資訊非空
   └─ 確認 requestTime 合理

④ doAction()  ★ 核心邏輯
   │
   │  // Step 1: 根據 minute 判斷要跑哪些段
   │  int minute = requestTime.atZone(tz).getMinute();
   │
   │  // Step 2: 快速刷新 (minute % 10 >= 5)
   │  if (minute % 10 >= 5) {
   │    fetch(新訂單, 1hr 窗口)
   │    fetch(待出貨, 3天窗口)
   │    ...
   │  }
   │
   │  // Step 3: 慢速刷新 (minute > 53)
   │  if (minute > 53) {
   │    fetch(已出貨, 7天窗口)
   │    fetch(已完成, 分段 2+7+14天)
   │    ...
   │  }
   │
   │  // Step 4: 對每次 fetch 的結果做 dedup
   │  for (ChannelOrder order : fetchedOrders) {
   │    String hashKey = "order:hash:{merchantId}:{channelId}:{channelOrderId}";
   │    String newHash = SHA256(order);
   │    String existingHash = redis.GET(hashKey);
   │
   │    if (!newHash.equals(existingHash)) {
   │      // 有變動 → 送 order.process
   │      taskProducer.send("order.process",
   │        channelId + ":" + merchantId, orderMsg);
   │    }
   │    // hash 相同 → skip
   │  }
   │
   │  // Step 5: SyncLog
   │  syncLogService.log(msg, "SUCCESS")
```

**要點:**
- **ChannelJob 只 READ Redis hash**，不寫。寫入由 OrderProcessJob 負責。
- **每個平台各自實作 FetchOrdersActionService**（Momo、Shopee、Yahoo、PChome 策略不同）。
- **fetch 的次數和參數由平台策略決定**（詳見 FETCH_STRATEGY.md §3）。

### 2.5 FetchProductsActionService — 合約

> 端到端事件流見 `FETCH_PRODUCTS.md`。
>
> **★ 重要設計**：ChannelJob 不直接 upsert 商品。
> ChannelJob 負責拉取 + 查 DB 判斷路由 → 送 task.backend → BackendJob 負責建立/更新。
> 這是為了**唯一性保證**：分散架構下多個 worker 可能同時處理同一商品，
> 用 Kafka key 排隊確保同一商品有序寫入，避免併發衝突。

```
④ doAction()
   │
   │  // Step 1: 取列表（GET LIST）
   │  //   一般平台: adapter.fetchProductList(channelId) → 商品編號 + 規格編號 列表
   │  //   Yahoo 特殊: API 請求 + 附帶 callbackUrl → Yahoo 異步回打 CSV
   │  //              callbackUrl 帶 merchantId: /webhook/yahoo/{merchantId}
   │  //              從 URI path 識別是誰的商品 → 解析 CSV → 商品編號列表
   │  List<ChannelProductRef> productRefs = adapter.fetchProductList(channelId);
   │
   │  // Step 2: 逐筆取明細（GET DETAIL）
   │  for (ChannelProductRef ref : productRefs) {
   │    ChannelProduct detail = adapter.fetchProductDetail(
   │      channelId, ref.channelProductId, ref.channelSpecId);
   │
   │    // 多規: 一個 product → N 個 spec → 各自處理
   │    // 單規: 一個 product → 1 筆處理
   │
   │    for (每個規格或整體) {
   │
   │      // Step 3: 查 DB 判斷路由
   │      Product product = productService.findByMerchantAndSku(merchantId, sku);
   │
   │      if (product == null) {
   │        // ★ 沒有 product → 送 CREATE_PRODUCT
   │        //   BackendJob 建好 product 後，routeNext 接著發 CREATE_SELL_PACK
   │        taskProducer.send("task.backend",
   │          channelId + ":" + channelProductId + ":" + channelSpecId,
   │          TaskMessage{ action=CREATE_PRODUCT, payload={sku, name, specSummary,
   │            barcode, channelId, channelProductId, channelSpecId, ...商品明細} });
   │      } else {
   │        // ★ 有 product → 直接送 CREATE_SELL_PACK
   │        taskProducer.send("task.backend",
   │          channelId + ":" + channelProductId + ":" + channelSpecId,
   │          TaskMessage{ action=CREATE_SELL_PACK, payload={productId=product.id,
   │            channelId, channelProductId, channelSpecId, ...商品明細} });
   │      }
   │    }
   │  }
   │
   │  // Step 4: SyncLog
   │  syncLogService.log(msg, "SUCCESS")
```

**要點:**
- **ChannelJob 不寫 DB**（sell_pack/product 的建立都由 BackendJob 負責）
- **Kafka key = `channelId:channelProductId:channelSpecId`** → 同一商品+規格在同一 partition → 有序 → 不併發衝突
- **Yahoo 特殊**: fetchProductList() 走「請求 → webhook 回打 CSV」模式，其他平台走正常 API
- **兩段式 API**: GET LIST（列表）→ GET DETAIL（明細），前端跳提示「同步中，請稍候」

### 2.6 FetchRefundOrdersActionService — 合約

```
④ doAction()
   │
   │  // Step 1: 根據平台策略拉取退貨單
   │  //   Shopee: 7-15 天窗口 + 回溯待出貨訂單
   │  //   Momo:   類似訂單，按物流分段
   │  //   Shopify: updated_at 已涵蓋
   │  List<ChannelRefundOrder> refunds = adapter.fetchRefundOrders(request);
   │
   │  // Step 2: 逐筆處理
   │  for (ChannelRefundOrder refund : refunds) {
   │    // 查 orders 表找到原始訂單
   │    // upsert refund_orders 表
   │    // 發 order.process (taskAction=PROCESS_REFUND) → 由 OrderProcessJob 處理退款同步
   │  }
   │
   │  // Step 3: SyncLog
```

### 2.7 即時操作 (fast topic) — 通用合約

```
SHIPPING_CONFIRMED / ORDER_CANCELED / MODIFY_PRICE / MODIFY_QUANTITY / START_SELLING / STOP_SELLING

④ doAction()
   │
   │  // Step 1: 從 payload 取得單筆操作參數
   │  String channelOrderId = payload.get("channelOrderId");
   │  // 或 String channelProductId = payload.get("channelProductId");
   │
   │  // Step 2: 呼叫 adapter 對應方法
   │  adapter.confirmShipment(channelId, channelOrderId, trackingNumber, logisticsCompany);
   │  // 或 adapter.updatePrice(channelId, channelProductId, newPrice);
   │  // 或 adapter.startSelling(channelId, channelProductId);
   │  // ...
   │
   │  // Step 3: 成功 → 更新本地 DB
   │  //   出貨確認: UPDATE orders SET order_status='shipped', shipped_at=now()
   │  //   取消: UPDATE orders SET order_status='cancelled'
   │  //   改價: UPDATE sell_pack SET selling_price=newPrice
   │  //   改量: UPDATE sell_pack SET quantity=newQuantity
   │  //   上架: UPDATE sell_pack SET status='active'
   │  //   下架: UPDATE sell_pack SET status='inactive'
   │
   │  // Step 4: SyncLog 記錄
   │
   │  // ★ 失敗 → 拋異常 → ChannelJob catch → task.failed
   │  //   → RetryDispatchJob → 但 fast topic 永不重打 → DLT
```

### 2.8 CheckHealthActionService — 合約

```
④ doAction()
   │
   │  // 呼叫 adapter.validateConnection(credentials)
   │  boolean isHealthy = adapter.validateConnection(credentials);
   │
   │  // 記錄結果
   │  syncLogService.log(msg, isHealthy ? "HEALTHY" : "UNHEALTHY")
   │
   │  // 如果不健康: 更新 channel.actived = false? 或只記錄，人工處理
```

---

## 3. OrderProcessJob — 訂單整理入庫

### 3.1 架構

```
order.process
  │
  ▼
OrderProcessJob.handle(TaskMessage msg)
  │
  ├─ SchemaVersionHandler.isSupported → 不支援 → task.dlt
  │
  ├─ Step 1: 提取訂單資料
  ├─ Step 2: 查 DB 是否存在
  ├─ Step 3A/3B: INSERT 或 UPDATE
  ├─ Step 4: 寫 Redis hash
  └─ Step 5: 路由到 task.backend
```

**現有程式碼**: `simpleec-order-job/.../OrderProcessJob.java` (skeleton 已存在，需填充業務邏輯)

### 3.2 五步合約

```
Step 1: 提取訂單資料
  │  從 msg.payload 解析:
  │    channelOrderId, orderStatus, buyerName, buyerPhone, buyerEmail,
  │    shippingAddress, shippingMethod, paymentMethod,
  │    totalAmount, shippingFee, discountAmount,
  │    channelCreatedAt, paidAt, shippedAt,
  │    items[], orderHash
  │
  │  從 msg 本體取:
  │    merchantId, ownerId (= channelId)

Step 2: 查 DB 是否存在
  │  SELECT * FROM orders
  │  WHERE channel_id = ? AND channel_order_id = ?
  │
  │  ├─ 不存在 → Step 3A (新建)
  │  └─ 存在   → Step 3B (更新)

Step 3A: 新建訂單
  │  // 產生 orderId
  │  String orderId = IdGenerator.nanoid();
  │
  │  // 對每個 item 做 sell_pack match
  │  for (item in items) {
  │    SellPack sp = sellPackService.findByChannelAndProductSpec(
  │      channelId, item.channelProductId, item.channelSpecId);
  │    if (sp != null) {
  │      item.sellPackId = sp.getId();
  │      item.productId  = sp.getProductId();
  │    }
  │    // 找不到 → sellPackId/productId = null（訂單先入，商品同步後再補）
  │  }
  │
  │  // INSERT orders 表（含 items JSONB）
  │  // PII 欄位由 MyBatis TypeHandler 自動加密
  │  orderService.insert(order);
  │
  │  // INSERT order_status_logs: from=null, to=orderStatus
  │  orderStatusLogService.insert(orderId, null, orderStatus, "SYSTEM");
  │
  │  statusChanged = true;

Step 3B: 更新既有訂單
  │  Order existing = 查詢結果;
  │
  │  if (existing.orderStatus != orderStatus) {
  │    // 狀態有變
  │    existing.orderStatus = orderStatus;
  │    statusChanged = true;
  │
  │    // INSERT order_status_logs
  │    orderStatusLogService.insert(orderId, existing.oldStatus, orderStatus, "SYSTEM");
  │  }
  │
  │  // 更新其他可能變更的欄位
  │  // shippedAt, paidAt, items (如果平台有更新) ...
  │  orderService.update(existing);

Step 4: 寫 Redis hash
  │  // ★ 只有入庫成功才寫 — 確保下次拉單能正確判斷
  │  String hashKey = "order:hash:{merchantId}:{channelId}:{channelOrderId}";
  │  redis.SET(hashKey, orderHash, 7天 TTL);

Step 5: 路由到 task.backend
  │  if (statusChanged) {
  │    TaskMessage backendMsg = TaskMessage.builder()
  │      .taskAction("ORDER_STATUS_CHANGED")
  │      .payload({
  │        orderId, channelOrderId, channelId,
  │        fromStatus, toStatus,
  │        totalAmount, buyerName
  │      })
  │      .build();
  │    taskProducer.send("task.backend", merchantId, backendMsg);
  │  }
```

### 3.3 冪等性保證

| 機制 | 說明 |
|------|------|
| Hash Dedup (Producer-side) | ChannelJob 用 SHA-256 比對 → 無變動不送 |
| UNIQUE 約束 (DB) | `(channel_id, channel_order_id)` → 插入衝突轉 update |
| Hash 寫入 (Consumer-side) | 只有入庫成功才寫 → 失敗時下次自然重送 |
| ORDER_STATUS_CHANGED | 重複收到同狀態 → 無副作用 |

---

## 4. BackendJob — 後台任務處理器

### 4.1 架構

```
task.backend
  │
  ▼
BackendJob.handle(TaskMessage msg)
  │
  ├─ SchemaVersionHandler.isSupported → 不支援 → task.dlt
  │
  ├─ workers.get(msg.taskAction)
  │    → null → log + ack
  │
  ├─ worker.setting(msg)
  ├─ worker.verify(msg)
  ├─ result = worker.execute(msg)
  └─ worker.routeNext(taskProducer, msg, result)
```

**現有程式碼**: `simpleec-backend-job/.../BackendJob.java` (已實作完成，不需修改)

### 4.2 BackendActionService 介面

```java
// simpleec-backend-job/.../action/BackendActionService.java (已存在)
public interface BackendActionService {
    String getAction();                                // action 名稱
    void setting(TaskMessage msg);                     // 從 payload 取參數
    void verify(TaskMessage msg);                      // 前置驗證
    Object execute(TaskMessage msg);                   // 核心邏輯，回傳結果
    void routeNext(TaskProducer p, TaskMessage m, Object result);  // 路由下游
}
```

### 4.3 需要實作的 ActionService

| Action | 觸發來源 | 職責 |
|--------|---------|------|
| `ORDER_STATUS_CHANGED` | OrderProcessJob | 退款同步 + 全退判斷 + 前端通知 |
| `CREATE_PRODUCT` | ChannelJob (FETCH_PRODUCTS) / API (CSV 匯入) | 建立 product（+ barcode），條件式 routeNext → CREATE_SELL_PACK |
| `CREATE_SELL_PACK` | ChannelJob / CREATE_PRODUCT routeNext | 建立或更新 sell_pack，掛上 productId |
| `DAILY_STATISTICS` | SchedulerJob (cron) | 多角色統計聚合 |
| `MANAGE_PARTITIONS` | SchedulerJob (cron) | daily_statistics 分區管理 |

### 4.4 OrderStatusChangedActionService — 合約

> 退款相關設計見 `STATISTICS_DESIGN.md` §5。

```
setting(msg):
  │  從 payload 取:
  │    orderId, channelOrderId, channelId,
  │    fromStatus, toStatus,
  │    totalAmount, buyerName,
  │    refundOrderId (nullable), refundAmount (nullable)

verify(msg):
  │  確認 orderId 在 DB 中存在

execute(msg):
  │
  │  // 1. 更新 orders.order_status
  │  //    ★ 不驗證 fromStatus → toStatus 是否符合「理想流程」
  │  //    因為平台資料有 gap（區間沒覆蓋、webhook 漏接、跳過中間狀態）
  │  //    接受任何合法的狀態轉換
  │  orderService.updateStatus(orderId, toStatus);
  │
  │  // 2. 寫 order_status_logs
  │  orderStatusLogService.insert(orderId, fromStatus, toStatus, "SYSTEM");
  │
  │  // 3. 退款處理（如果 refundOrderId != null）
  │  if (refundOrderId != null) {
  │    // 累加退款金額
  │    UPDATE orders
  │      SET refund_amount = refund_amount + refundAmount,
  │          has_refund = true
  │      WHERE id = orderId;
  │
  │    // 全退判斷（不管當前 status 是什麼，只看金額）
  │    Order order = orderService.getById(orderId);
  │    if (order.refundAmount >= order.totalAmount) {
  │      orderService.updateStatus(orderId, "refunded");
  │    }
  │  }
  │
  │  return toStatus;

routeNext(producer, msg, result):
  │  // 發前端通知
  │  TaskMessage frontendMsg = TaskMessage.builder()
  │    .taskAction("NOTIFY_STATUS_CHANGE")
  │    .payload({ orderId, fromStatus, toStatus, buyerName })
  │    .build();
  │  producer.send("task.frontend", merchantId, frontendMsg);
```

### 4.5 DailyStatisticsActionService — 合約

> 統計口徑定義見 `STATISTICS_DESIGN.md` §3.3。

```
setting(msg):
  │  merchantId = msg.getMerchantId()
  │  timezone = msg.getTimezone() ?? "Asia/Taipei"
  │  statDate = 昨天（依 timezone 計算）

verify(msg):
  │  確認 merchantId 有效

execute(msg):
  │
  │  // 1. 查詢 merchant 的所有 active channel
  │  List<Channel> channels = channelService.listActiveByMerchant(merchantId);
  │
  │  // 2. 對每個 channel 做聚合
  │  for (Channel ch : channels) {
  │    DailyStatistics stats = new DailyStatistics();
  │    stats.merchantId = merchantId;
  │    stats.platformId = ch.platformId;
  │    stats.channelId  = ch.id;
  │    stats.statDate   = statDate;
  │
  │    // ★ 業務視角：當日新增訂單
  │    stats.newOrderCount  = COUNT(*) WHERE channel_created_at IN statDate
  │    stats.newOrderAmount = SUM(total_amount) WHERE channel_created_at IN statDate
  │
  │    // ★ 老闆視角：營業額（排除 cancelled）
  │    stats.grossOrderCount = COUNT(*) WHERE status != cancelled
  │    stats.grossAmount     = SUM(total_amount) WHERE status != cancelled
  │
  │    // ★ 財務視角：實收（confirmed 以上狀態）
  │    stats.receivedCount  = COUNT(*) WHERE status IN (confirmed, processing, shipped, delivered, completed)
  │    stats.receivedAmount = SUM(total_amount) WHERE 同上
  │
  │    // ★ RMA：退款
  │    stats.refundCount  = COUNT(*) FROM refund_orders WHERE created_at IN statDate
  │    stats.refundAmount = SUM(refund_amount) FROM refund_orders WHERE 同上
  │
  │    // ★ 計算
  │    stats.netAmount = stats.receivedAmount - stats.refundAmount
  │
  │    // ★ 物流
  │    stats.shippedCount   = COUNT(*) WHERE status IN (shipped, delivered, completed)
  │    stats.completedCount = COUNT(*) WHERE status = completed
  │    stats.cancelledCount = COUNT(*) WHERE status = cancelled
  │
  │    // ★ 商品
  │    stats.itemSoldCount  = SUM(jsonb_array_length(items)) WHERE status != cancelled
  │
  │    // UPSERT daily_statistics
  │    dailyStatisticsService.upsert(stats);
  │  }
  │
  │  // 3. 全平台匯總（channel_id = '_ALL_'）
  │  // 4. 全商家匯總（platform_id = '_ALL_', channel_id = '_ALL_'）

routeNext(producer, msg, result):
  │  // 無下游（統計是終點）
```

### 4.6 ManagePartitionsActionService — 合約

```
execute(msg):
  │  // 檢查 daily_statistics 表的分區
  │  // 如果未來 3 個月的分區不存在 → 自動建立
  │  // CREATE TABLE daily_statistics_y2026m03 PARTITION OF daily_statistics
  │  //   FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
```

### 4.7 CreateProductActionService — 合約

> **觸發來源（多種）：**
> 1. ChannelJob FETCH_PRODUCTS — 發現 product 不存在時
> 2. API — 客戶 CSV 匯入商品（只建 product，不建 sell_pack）
> 3. 未來可能的其他來源
>
> **routeNext 是條件式的** — 只有 payload 帶有 sell_pack 相關欄位時才接力發 CREATE_SELL_PACK。
> CSV 匯入只建商品，不需要建 sell_pack。

```
setting(msg):
  │  從 payload 取:
  │    // ★ product 建立必要欄位
  │    merchantId, sku, name, specSummary, barcode (nullable)
  │
  │    // ★ sell_pack 相關欄位（可選 — CSV 匯入時不帶）
  │    channelId (nullable), channelProductId (nullable), channelSpecId (nullable)
  │    channelProductName, channelSpecName, channelProductUrl
  │    sellingPrice, quantity, status

verify(msg):
  │  確認 merchantId 有效

execute(msg):
  │
  │  // 1. 再次查 product（可能在排隊期間已被建立）
  │  Product product = productService.findByMerchantAndSku(merchantId, sku);
  │
  │  if (product == null) {
  │    // 2. 建立 product
  │    product = new Product();
  │    product.id = NanoID();
  │    product.merchantId = merchantId;
  │    product.sku = sku;
  │    product.name = name;
  │    product.specSummary = specSummary;
  │    product.status = "active";
  │    productService.insert(product);
  │
  │    // 3. 建立 barcode（如果有）
  │    if (barcode != null) {
  │      productBarcodeService.insert(product.id, barcode, true);
  │    }
  │  }
  │
  │  return product;  // 帶 productId 給 routeNext

routeNext(producer, msg, result):
  │  // ★ 條件式：只有 payload 帶 channelId 時才接力建 sell_pack
  │  //   來源 1: FETCH_PRODUCTS → 帶 channelId → 接力 CREATE_SELL_PACK
  │  //   來源 2: CSV 匯入 → 不帶 channelId → 到此結束
  │
  │  String channelId = payload.get("channelId");
  │  if (channelId == null) {
  │    return;  // 純建商品，不需建 sell_pack
  │  }
  │
  │  Product product = (Product) result;
  │  Map payload = msg.getPayload();
  │  payload.put("productId", product.getId());
  │
  │  TaskMessage spMsg = TaskMessage.builder()
  │    .taskAction("CREATE_SELL_PACK")
  │    .merchantId(msg.getMerchantId())
  │    .payload(payload)
  │    .build();
  │  producer.send("task.backend",
  │    msg.getPartitionKey(),  // 同一個 key → 同 partition → 有序
  │    spMsg);
```

### 4.8 CreateSellPackActionService — 合約

> 兩種觸發來源：
> 1. ChannelJob 發現 product 已存在 → 直接送 CREATE_SELL_PACK
> 2. CreateProductActionService.routeNext → 建完 product 後接力送來
>
> Kafka key 保證同一商品+規格有序，不會併發建出重複的 sell_pack。

```
setting(msg):
  │  從 payload 取:
  │    merchantId, productId, channelId
  │    channelProductId, channelSpecId
  │    channelProductName, channelSpecName, channelProductUrl
  │    sku, sellingPrice, quantity, status

verify(msg):
  │  確認 productId 存在（防禦性檢查）
  │  確認 channelId 存在

execute(msg):
  │
  │  // 1. 查 sell_pack 是否已存在
  │  SellPack existing = sellPackService.findByChannelAndProductSpec(
  │    channelId, channelProductId, channelSpecId);
  │
  │  if (existing != null) {
  │    // 2A. 更新
  │    existing.channelProductName = channelProductName;
  │    existing.channelSpecName = channelSpecName;
  │    existing.channelProductUrl = channelProductUrl;
  │    existing.sellingPrice = sellingPrice;
  │    existing.quantity = quantity;
  │    existing.status = status;
  │    existing.lastSyncAt = now();
  │    // ★ 補上 productId（如果之前是 null）
  │    if (existing.productId == null) {
  │      existing.productId = productId;
  │    }
  │    sellPackService.update(existing);
  │  } else {
  │    // 2B. 建立
  │    SellPack sp = new SellPack();
  │    sp.id = NanoID();
  │    sp.merchantId = merchantId;
  │    sp.productId = productId;
  │    sp.channelId = channelId;
  │    sp.sku = sku;
  │    sp.channelProductId = channelProductId;
  │    sp.channelSpecId = channelSpecId;
  │    sp.channelProductName = channelProductName;
  │    sp.channelSpecName = channelSpecName;
  │    sp.channelProductUrl = channelProductUrl;
  │    sp.sellingPrice = sellingPrice;
  │    sp.quantity = quantity;
  │    sp.status = status;
  │    sp.lastSyncAt = now();
  │    sellPackService.insert(sp);
  │  }
  │
  │  return existing != null ? "updated" : "created";

routeNext(producer, msg, result):
  │  // 無下游（商品同步終點）
```

---

## 5. SchedulerJob — 排程觸發器

### 5.1 架構

```
HeartbeatTimer (每秒 tick)
  │
  ├─ 產生 TaskMessage (taskAction=TICK)
  └─ taskProducer.send("scheduler", null, tick)

scheduler topic
  │
  ▼
SchedulerJob.handle(TaskMessage tick)
  │
  ├─ now = tick.createdAt
  │
  ├─ for (ScheduleRule rule : config.rules) {
  │    if (shouldTrigger(rule, now)) {
  │      dispatch(rule, now)
  │    }
  │  }
  │
  └─ ack
```

**現有程式碼**: `simpleec-scheduler-job/.../SchedulerJob.java` (已實作，需要從硬編碼改為查 DB)

### 5.2 排程規則 (ScheduleConfig)

```yaml
scheduler:
  rules:
    - id: fetch-orders
      action: FETCH_ALL_ORDERS
      mode: interval
      intervalSeconds: 300        # 每 5 分鐘
      minGapSeconds: 280
      merchantId: M001
    - id: check-health
      action: CHECK_ALL_HEALTH
      mode: interval
      intervalSeconds: 60         # 每分鐘
      minGapSeconds: 55
      merchantId: M001
    - id: daily-stats
      action: DAILY_STATISTICS
      mode: cron
      cronExpression: "0 1 * * *"  # 每天凌晨 01:00
      timezone: Asia/Taipei
      merchantId: M001
    - id: manage-partitions
      action: MANAGE_PARTITIONS
      mode: cron
      cronExpression: "0 2 1 * *"  # 每月 1 號凌晨 02:00
```

### 5.3 dispatch 合約

```
FETCH_ALL_ORDERS:
  │  // 目前: 硬編碼 4 平台
  │  // 目標: 查 DB → SELECT DISTINCT platform_type FROM channel
  │  //        WHERE merchant_id = ? AND actived = true AND enable_sync = true
  │  for (每個平台) {
  │    taskProducer.send("{platform}.slow", null, FETCH_ORDERS msg)
  │  }

CHECK_ALL_HEALTH:
  │  for (每個平台) {
  │    taskProducer.send("{platform}.fast", "health-check", CHECK_HEALTH msg)
  │  }

DAILY_STATISTICS:
  │  taskProducer.send("task.backend", merchantId, DAILY_STATISTICS msg)

MANAGE_PARTITIONS:
  │  taskProducer.send("task.backend", "system", MANAGE_PARTITIONS msg)
```

### 5.4 抽象合約

**SchedulerJob 是時鐘，不是大腦。**

| 知道 | 不知道 |
|------|--------|
| 幾分鐘該觸發 | 平台有什麼狀態 |
| 發到哪個 topic | 拉單要拉多久 |
| 帶什麼 merchantId | 什麼物流類型 |
| | 窗口要多寬 |

策略全部在 `FetchOrdersActionService.doAction()` 內（見 §2.4 和 FETCH_STRATEGY.md）。

---

## 6. RetryDispatchJob — 失敗重打

### 6.1 架構

```
task.failed
  │
  ▼
RetryDispatchJob.handle(TaskMessage msg)
  │
  ├─ 鐵則: fast topic → 永不重打 → DLT
  │
  ├─ 超過 maxRetry → DLT
  │
  ├─ !isRetryable(action) → DLT
  │
  ├─ delay(retryCount)
  │
  └─ retryCount++ → 重送原 topic
```

**現有程式碼**: `simpleec-retry-job/.../RetryDispatchJob.java` (已實作完成，不需修改)

### 6.2 失敗路由規則

| 條件 | 動作 | 說明 |
|------|------|------|
| `originalTopic.endsWith(".fast")` | → DLT | 鐵則：即時操作過時就沒意義 |
| `retryCount >= maxRetry` | → DLT | 超過最大重試次數 |
| `!isRetryable(action)` | → DLT | 此 action 不允許重打 |
| else | → retryCount++ → 原 topic | 可重打 |

### 6.3 待實作

- `FailedTaskLogService.save()`: 持久化到 `failed_task_logs` DB 表（目前可能只 log）

---

## 7. FrontendJob — 前端通知

### 7.1 架構

```
task.frontend
  │
  ▼
FrontendJob.handle(TaskMessage msg)
  │
  ├─ 根據 taskAction 分發通知
  │
  ├─ NOTIFY_STATUS_CHANGE → WebSocket 推送
  ├─ NOTIFY_SYNC_COMPLETE → WebSocket 推送
  └─ NOTIFY_SYNC_FAILED   → WebSocket 推送
```

**現有程式碼**: `simpleec-frontend-job/.../FrontendJob.java` (skeleton，只 log + ack)

### 7.2 預計的 Action

| Action | 來源 | 通知內容 |
|--------|------|---------|
| `NOTIFY_STATUS_CHANGE` | BackendJob (ORDER_STATUS_CHANGED) | 「訂單 MOMO-ORD-12345 狀態變更: 待處理 → 已出貨」 |
| `NOTIFY_SYNC_COMPLETE` | ChannelJob (FETCH_PRODUCTS 結束) | 「Momo 冷凍館 商品同步完成，共 150 筆」 |
| `NOTIFY_SYNC_FAILED` | ChannelJob (任務失敗) | 「Shopee 同步失敗: Token 過期」 |

### 7.3 推送機制 — WebSocket

> **確認使用 WebSocket**（過去專案已實作過此模式）。
> FrontendJob 是專門的 JOB，職責：收 `task.frontend` topic → 轉 WebSocket 推送。

```
架構:
  FrontendJob (Spring Boot 應用)
    ├─ Kafka consumer: 監聽 task.frontend
    ├─ WebSocket server: 管理前端連線
    └─ 收到 Kafka msg → 根據 merchantId 找到對應 WebSocket session → 推送

前端:
  WebSocket client 連線到 FrontendJob
  → 收到推送 → UI 即時更新（Toast 通知、列表刷新等）

連線管理:
  ├─ 用 merchantId 做 session 分組
  ├─ 前端登入後建立 WebSocket 連線
  └─ 斷線自動重連（前端 heartbeat）
```

---

## 8. ChannelAdapter — HTTP 客戶端介面

### 8.1 現有介面

```java
// simpleec-channel/.../adapter/ChannelAdapter.java (已存在)
public interface ChannelAdapter {
    ChannelType getChannelType();
    boolean validateConnection(Map<String, String> credentials);

    // 商品操作
    String createListing(String channelId, SellPack sellPack, Map<String, Object> extraData);
    void updateListing(String channelId, SellPack sellPack, Map<String, Object> extraData);
    void updatePrice(String channelId, String channelProductId, BigDecimal price);
    void updateQuantity(String channelId, String channelProductId, int quantity);
    void startSelling(String channelId, String channelProductId);
    void stopSelling(String channelId, String channelProductId);

    // 訂單操作
    List<Order> fetchOrders(String channelId, LocalDateTime from, LocalDateTime to);
    void confirmShipment(String channelId, String channelOrderId, String trackingNumber, String logisticsCompany);
    void acceptCancellation(String channelId, String channelOrderId);
    String getShippingLabel(String channelId, String channelOrderId);
}
```

### 8.2 需要演進的部分

```java
// 演進後的 ChannelAdapter 介面

public interface ChannelAdapter {
    ChannelType getChannelType();
    boolean validateConnection(Map<String, String> credentials);

    // ==================== 商品（兩段式 API）====================
    /** Step 1: 取商品列表（編號 + 規格編號） */
    List<ChannelProductRef> fetchProductList(String channelId);        // ← 新增
    /** Step 2: 取單一商品明細（含完整資料） */
    ChannelProduct fetchProductDetail(String channelId,               // ← 新增
                                      String channelProductId);

    String createListing(String channelId, SellPack sp, Map<String, Object> extra);
    void updateListing(String channelId, SellPack sp, Map<String, Object> extra);
    void updatePrice(String channelId, String channelProductId, BigDecimal price);
    void updateQuantity(String channelId, String channelProductId, int quantity);
    void startSelling(String channelId, String channelProductId);
    void stopSelling(String channelId, String channelProductId);

    // ==================== 訂單 ====================
    List<ChannelOrder> fetchOrders(FetchOrdersRequest request);       // ← 改簽名
    List<ChannelRefundOrder> fetchRefundOrders(FetchOrdersRequest r); // ← 新增
    void confirmShipment(String channelId, String channelOrderId,
                         String trackingNumber, String logisticsCompany);
    void acceptCancellation(String channelId, String channelOrderId);
    String getShippingLabel(String channelId, String channelOrderId);
}
```

**為什麼是兩段式而非 `fetchProducts()` 一次拉完？**
1. 多數平台的 LIST API 只回傳商品編號，不含完整明細
2. 需要逐筆 GET DETAIL 才能取得 SKU、價格、庫存等完整資料
3. Yahoo 更特殊：LIST 走 webhook 回打 CSV，DETAIL 走正常 API

**Yahoo 特殊處理（在 `fetchProductList()` 內部）：**
- API 請求 + 附帶 callbackUrl（`/webhook/yahoo/{merchantId}`）
- Yahoo 異步處理後，回打 CSV 到 webhook
- 從 URI path 的 `{merchantId}` 識別商品歸屬
- 解析 CSV → `List<ChannelProductRef>`
- 對調用方而言，結果與其他平台一致

### 8.3 Adapter 的職責邊界

| Adapter 做 | Adapter 不做 |
|------------|-------------|
| HTTP 呼叫平台 API | 決定要拉哪些狀態/時間窗 |
| JSON → DTO 轉換 | 訂單 dedup (hash) |
| 分頁拉完 | DB 寫入 |
| 異常包裝 | 業務邏輯判斷 |

---

## 9. API 模組 — REST 端點合約

### 9.1 現有端點

| 方法 | 路徑 | Controller | 說明 |
|------|------|-----------|------|
| GET | `/api/v1/orders` | OrderController | 訂單列表（PII 遮罩） |
| GET | `/api/v1/orders/{id}` | OrderController | 訂單詳情（PII 明文） |
| GET | `/api/v1/orders/export` | OrderController | CSV 匯出（PII 明文） |
| POST | `/api/v1/orders/{id}/status` | OrderController | 更新狀態 |
| GET | `/api/v1/products` | ProductController | 商品列表 |
| POST | `/api/v1/channels/{id}/action` | ChannelActionController | 發送通路操作 |
| GET | `/health` | HealthController | 健康檢查 |

### 9.2 需要新增的端點

| 方法 | 路徑 | 說明 | 備註 |
|------|------|------|------|
| POST | `/api/v1/auth/login` | JWT 登入 | email + password → token |
| POST | `/api/v1/auth/refresh` | Token 刷新 | refreshToken → newToken |
| POST | `/api/v1/channels/{id}/sync-orders` | 手動拉單 | → {platform}.slow (FETCH_ORDERS) |
| POST | `/api/v1/channels/{id}/sync-products` | 手動同步商品（逐通路） | → {platform}.fast (FETCH_PRODUCTS，列表+diff 快速回應) |
| POST | `/api/v1/products/import` | CSV 匯入商品 | 解析 CSV → 逐筆發 task.backend (CREATE_PRODUCT，不帶 channelId) |
| GET | `/api/v1/statistics/summary` | 統計摘要 | ?view=sales/revenue/finance/rma |
| GET | `/api/v1/statistics/daily` | 每日趨勢 | 折線圖用 |
| GET | `/api/v1/statistics/by-channel` | 通路對比 | 對比表格用 |

### 9.3 手動操作端點合約

```
POST /api/v1/channels/{channelId}/sync-orders
  │
  │  1. 從 JWT 取 merchantId
  │  2. 查 channel 表確認歸屬 + actived=true
  │  3. 查 channel → platform 取 platformType
  │  4. 組裝 TaskMessage (taskAction=FETCH_ORDERS)
  │  5. send("{platformType}.slow", null, msg)
  │  6. 回傳 202 Accepted + messageId

POST /api/v1/channels/{channelId}/sync-products
  │  同上，taskAction=FETCH_PRODUCTS
  │  topic="{platformType}.fast"（列表+diff 快速回應，detail 背景處理）
  │  key=channelId（同通路排隊）

POST /api/v1/orders/{orderId}/ship
  │
  │  Body: { trackingNumber, logisticsCompany }
  │  1. 查 order 確認歸屬 + 可出貨狀態
  │  2. 查 channel → platform 取 platformType
  │  3. 組裝 TaskMessage (taskAction=SHIPPING_CONFIRMED)
  │  4. send("{platformType}.fast", null, msg)
  │  5. 回傳 202 Accepted

POST /api/v1/orders/{orderId}/cancel
  │  同上，taskAction=ORDER_CANCELED
```

### 9.4 統計端點合約

```
GET /api/v1/statistics/summary
  │  Query: merchantId, startDate, endDate, channelId?, view
  │  view = sales | revenue | finance | rma (預設 sales)
  │
  │  回傳: SUM(daily_statistics) for date range
  │
  │  各 view 回傳欄位:
  │    sales   → newOrderCount, newOrderAmount, grossOrderCount, grossAmount
  │    revenue → grossAmount, receivedAmount, refundAmount, netAmount, shippedCount, completedCount
  │    finance → receivedCount, receivedAmount, refundCount, refundAmount, netAmount
  │    rma     → refundCount, refundAmount, cancelledCount

GET /api/v1/statistics/daily
  │  Query: 同上
  │  回傳: Array of daily records (每天一筆，折線圖用)

GET /api/v1/statistics/by-channel
  │  Query: merchantId, startDate, endDate, view
  │  回傳: 各通路分組統計 (對比表格用)
```

---

## 10. Entity / Service 層合約

### 10.1 Entity 現況

| Entity | Mapper | Service | Controller | 狀態 |
|--------|--------|---------|------------|------|
| Order | ✅ | ✅ | ✅ | ✅ 完整 |
| Product | ✅ | ✅ | ✅ | ✅ 完整 |
| SellPack | ✅ | ❌ | ❌ | 需要 Service |
| OrderStatusLog | ✅ | ❌ | ❌ | 需要 Service |
| 其他 15 張表 | ❌ | ❌ | ❌ | Level 2 待建 |

> 15 張待建表見 `STATUS.md` Level 2 清單。

### 10.2 重點 Service 合約

#### OrderService (已存在，需擴充)

```java
public class OrderService {
    // 已有
    PageResult<Order> list(String merchantId, String status, int page, int size);
    Order getById(String merchantId, String orderId);
    List<Order> listForExport(String merchantId, String status, LocalDate start, LocalDate end);

    // 需要新增
    Order findByChannelOrderId(String channelId, String channelOrderId);
    void insert(Order order);
    void update(Order order);
    void updateStatus(String orderId, String newStatus);
    void addRefundAmount(String orderId, BigDecimal amount);
}
```

#### SellPackService (待建)

```java
public class SellPackService {
    SellPack findByChannelAndProductSpec(String channelId, String channelProductId, String channelSpecId);
    void insert(SellPack sp);
    void update(SellPack sp);
    List<SellPack> listByChannel(String channelId);
    List<SellPack> listByProduct(String productId);
}
```

#### ChannelService (待建)

```java
public class ChannelService {
    Channel getById(String channelId);
    Channel getWithPlatform(String channelId);  // JOIN platform
    List<Channel> listActiveByMerchant(String merchantId);
    void updateLastSyncTime(String channelId, Instant time);
}
```

#### RefundOrderService (待建)

```java
public class RefundOrderService {
    void create(RefundOrder refund);
    List<RefundOrder> listByOrder(String orderId);
    List<RefundOrder> listByMerchantAndDateRange(String merchantId, LocalDate start, LocalDate end);
}
```

#### DailyStatisticsService (待建)

```java
public class DailyStatisticsService {
    void upsert(DailyStatistics stats);
    DailyStatistics getSummary(String merchantId, LocalDate start, LocalDate end, String view);
    List<DailyStatistics> getDaily(String merchantId, LocalDate start, LocalDate end, String view);
    List<DailyStatistics> getByChannel(String merchantId, LocalDate start, LocalDate end, String view);
}
```

#### OrderStatusLogService (待建)

```java
public class OrderStatusLogService {
    void insert(String orderId, String fromStatus, String toStatus, String operator);
    List<OrderStatusLog> listByOrder(String orderId);
}
```

#### ChannelSyncLogService (已有 skeleton)

```java
public class ChannelSyncLogService {
    void log(TaskMessage msg, String status, String errorMessage);
    List<ChannelSyncLog> listByChannel(String channelId, String syncType, int limit);
}
```

#### FailedTaskLogService (已有 skeleton)

```java
public class FailedTaskLogService {
    void save(TaskMessage msg, String reason);  // 持久化到 DB
    List<FailedTaskLog> listRecent(int limit);
}
```

---

## 11. DTO 層合約

### 11.1 Adapter → ChannelJob 的 DTO

```java
// 平台訂單（Adapter 回傳）
@Data
public class ChannelOrder {
    String channelOrderId;
    String orderStatus;
    String buyerName, buyerPhone, buyerEmail, shippingAddress;
    String shippingMethod, paymentMethod;
    BigDecimal totalAmount, shippingFee, discountAmount;
    LocalDateTime channelCreatedAt, paidAt, shippedAt;
    List<ChannelOrderItem> items;
}

@Data
public class ChannelOrderItem {
    String channelProductId, channelSpecId;
    String channelProductName, channelSpecName;
    String sku;
    Integer quantity;
    BigDecimal unitPrice, subtotal;
}
```

```java
// 平台商品列表項（Step 1: fetchProductList 回傳）
@Data
public class ChannelProductRef {
    String channelProductId;
    List<String> channelSpecIds;  // 有些平台列表 API 也回傳規格編號
}

// 平台商品明細（Step 2: fetchProductDetail 回傳）
@Data
public class ChannelProduct {
    String channelProductId, channelProductName, channelProductUrl;
    BigDecimal sellingPrice;
    Integer quantity;
    String status, skuCode;
    List<ChannelProductSpec> specs;
}

@Data
public class ChannelProductSpec {
    String channelSpecId, channelSpecName;
    BigDecimal price;
    Integer quantity;
    String skuCode, barcode;
}
```

```java
// 平台退貨單（Adapter 回傳）
@Data
public class ChannelRefundOrder {
    String channelRefundId;
    String channelOrderId;    // 關聯到哪張訂單
    String refundStatus;
    BigDecimal refundAmount;
    String reason;
    List<ChannelOrderItem> items;  // 退貨明細
    LocalDateTime createdAt;
}
```

### 11.2 FetchOrdersActionService → Adapter 的 DTO

```java
// 拉取訂單的請求參數
@Data @Builder
public class FetchOrdersRequest {
    String channelId;
    LocalDateTime from;
    LocalDateTime to;
    String orderStatus;       // Shopee: "UNPAID", Momo: "pending", nullable
    String logisticsType;     // Momo: "Company"/"Stores"/"Third", nullable
    String queryMode;         // Shopify: "created"/"updated", nullable
    Map<String, Object> extra; // 各平台自訂擴展
}
```

### 11.3 API → 前端的 VO

```java
// 已存在: OrderVO (PII 遮罩/明文)
// 用途: API 回傳訂單資料
@Data
public class OrderVO {
    // 與 Order 欄位相同
    // fromMasked(Order) — PII 遮罩
    // fromPlain(Order)  — PII 明文
}
```

---

## 12. Redis 合約

### 12.1 訂單 Dedup Hash

```
Key:   order:hash:{merchantId}:{channelId}:{channelOrderId}
Value: SHA-256 hex string
TTL:   7 天

寫入方: OrderProcessJob（入庫成功後才寫）
讀取方: ChannelJob（FetchOrdersActionService.doAction()）

職責分離:
  ChannelJob = Producer → 只 READ hash → 判斷是否有變 → 有變才送 order.process
  OrderProcessJob = Consumer → DB 入庫成功 → 才 WRITE hash
  → 如果 OrderProcessJob 失敗（hash 沒更新）→ 下次拉單 ChannelJob 會再送一次 → 天然重試
```

---

## 13. 設計原則總結

| # | 原則 | 說明 |
|---|------|------|
| 1 | **被動同步方** | 平台給什麼就收什麼，不驗證狀態轉換，不假設資料完整性 |
| 2 | **三層分工** | SchedulerJob=時鐘, ActionService.doAction()=大腦, ChannelAdapter=手 |
| 3 | **Fast/Slow 分流** | 慢操作（拉單）不阻塞即時操作（出貨確認） |
| 4 | **Hash 職責分離** | Producer 只讀 hash, Consumer 才寫 hash |
| 5 | **容錯靠重複覆蓋** | 5 分鐘抓一次 × 1 小時窗口 = 12 次覆蓋，漏一次不影響 |
| 6 | **冪等設計** | UNIQUE 約束 + hash dedup + 重複處理無副作用 |
| 7 | **不回溯修改** | 退款記在退款日，不回溯訂單建立日的統計 |

---

## 14. 相關文件索引

| 文件 | 與本文的關係 |
|------|------------|
| `FETCH_STRATEGY.md` | §2.4 FetchOrders 的抓取策略細節 |
| `FETCH_ORDERS.md` | 拉單端到端事件流 + payload 完整定義 |
| `FETCH_PRODUCTS.md` | 同步商品端到端事件流 + upsert 邏輯 |
| `STATISTICS_DESIGN.md` | §4.5 統計聚合口徑 + 退款處理規則 |
| `SCHEMA.md` | 19 張 DB 表 DDL |
| `DB_ENTITY_GAPS.md` | Entity ↔ Schema 差異追蹤 |
| `STATUS.md` | 開發進度總覽 |
