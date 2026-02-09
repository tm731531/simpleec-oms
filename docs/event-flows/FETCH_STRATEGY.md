# FETCH_STRATEGY — 各平台抓取策略設計

> **依據**: `oneec-consuming-action-job` 參考架構 + 業務討論（2026-02-09）
>
> **本文定義**：FetchOrdersActionService / FetchRefundOrdersActionService 的 `doAction()` 內部如何決定
> 「要抓哪些狀態 × 多長時間 × 什麼物流類型 × 多久跑一次」。

---

## 0. 核心原則

| # | 原則 | 說明 |
|---|------|------|
| 1 | **減少抓取量，但正確** | 不要每次拉全部，要按狀態/時間/物流分段，減少平台 DB 壓力 |
| 2 | **貼合平台 API 模式** | 每個平台的查詢維度不同（Shopee=時間+狀態, Momo=時間+狀態+物流, Shopify=時間+類型），照著走 |
| 3 | **避免被鎖/限流** | 分段 + 錯開 + 降頻；不要對同一個 endpoint 狂打 |
| 4 | **兼顧即時性和完整性** | 新訂單窗口小（1hr）保即時，完成訂單窗口大（14天）保完整 |
| 5 | **容錯靠重複覆蓋** | 5分鐘抓一次、每次1小時窗口 = 12次重複覆蓋，漏一次不要緊 |
| 6 | **fast/slow 分流** | 慢操作（拉單）不能阻塞即時操作（出貨確認、改價） |

---

## 0.5 為什麼要分 fast / slow Topic

每個平台有兩個 Kafka topic：`{platform}.fast` 和 `{platform}.slow`，各自有獨立的 consumer group。

| Topic | 用途 | 特性 | 範例 action |
|-------|------|------|------------|
| `{platform}.slow` | 重操作（拉單、同步商品） | 一次可能打幾百個 API call，耗時長 | FETCH_ORDERS, FETCH_PRODUCTS, FETCH_REFUND_ORDERS |
| `{platform}.fast` | 輕操作（即時回應） | 單筆操作，需要立即執行 | SHIPPING_CONFIRMED, ORDER_CANCELED, MODIFY_PRICE, MODIFY_QUANTITY |

**為什麼不能共用一個 topic？**

```
假設只有一個 momo topic，consumer concurrency=4：

10:00:00  SchedulerJob 發 FETCH_ORDERS → 拉單開始
10:00:01  拉單佔住 4 個 consumer thread，正在打 200 個 API call...
10:00:05  使用者按「確認出貨」→ SHIPPING_CONFIRMED 進入同一個 topic
10:00:06  使用者在等...（consumer 被拉單占滿）
10:02:30  拉單終於跑完，出貨確認才開始處理
          → 使用者等了 2 分半，體驗極差
```

分成 fast + slow 後：
- slow consumer 慢慢跑拉單，不影響任何人
- fast consumer 隨時待命，出貨確認秒回

**Retry 差異：**
- slow topic 失敗 → 可重打（拉單過期了沒關係，重拉就好）
- fast topic 失敗 → **永不重打，直接進 DLT**（即時操作過時就沒意義了，2分鐘前的出貨確認重打可能已經不正確）

---

## 1. 架構分層：誰負責什麼

```
┌─────────────────────────────────────────────────────────────────┐
│  SchedulerJob                                                    │
│  ─ 平台無關                                                      │
│  ─ 只負責「幾分鐘觸發一次」                                        │
│  ─ 發 FETCH_ORDERS 到 {platform}.slow                            │
│  ─ 不知道平台有什麼狀態、什麼物流、要拉多長時間                       │
│  ─ payload 只帶 channelId + platformType                          │
└──────────────────────────┬──────────────────────────────────────┘
                           │ Kafka: {platform}.slow
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  ChannelJob → ActionFactory → FetchOrdersActionService          │
│  ─ 每個平台各自實作 FetchOrdersActionService                       │
│  ─ doAction() 內部決定：                                          │
│      • 要查哪些狀態（Shopee: UNPAID/READY_TO_SHIP/SHIPPED/...）   │
│      • 每種狀態拉多長時間（1hr / 3天 / 14天）                       │
│      • 要分幾種物流類型（Momo: 公司/門市/第三方）                    │
│      • 哪些段快速刷新（每10分鐘）、哪些段慢速刷新（每小時）           │
│  ─ 對每個 (狀態, 時間窗, 物流) 組合，呼叫 adapter 一次               │
└──────────────────────────┬──────────────────────────────────────┘
                           │ 呼叫
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  ChannelAdapter (HTTP 層)                                        │
│  ─ 純粹的 HTTP 客戶端                                             │
│  ─ 接收參數（channelId, status, from, to, logisticsType...）      │
│  ─ 呼叫平台 API，回傳 List<ChannelOrder>                          │
│  ─ 不決定策略，只執行                                              │
└─────────────────────────────────────────────────────────────────┘
```

**關鍵理解**：
- **SchedulerJob** = 時鐘（只管幾分鐘敲一次）
- **FetchOrdersActionService.doAction()** = 大腦（決定要抓什麼）
- **ChannelAdapter** = 手（照指令去打 API）

---

## 2. 通用機制：分鐘條件 + 滑動窗口

### 2.1 分鐘條件執行（Minute-Based Conditional）

參考 `oneec-consuming-action-job` 的做法：

```java
// doAction() 內部
int minute = requestTime.getMinute();

// 快速刷新：每 10 分鐘（minute % 10 >= 5 時執行）
if (minute % 10 >= 5) {
    fetchNewOrders();       // 新訂單，1hr 窗口
    fetchPendingOrders();   // 待出貨，3天窗口
}

// 慢速刷新：每小時一次（minute > 53 時執行）
if (minute > 53) {
    fetchShippedOrders();   // 出貨中，7天窗口
    fetchCompletedOrders(); // 已完成，分段 2+7+14 天
}
```

**為什麼用 `minute % 10 >= 5` 而不是 `minute % 10 == 0`？**
- 給 5 分鐘的容忍窗口，避免因 Kafka 消費延遲剛好錯過整點
- SchedulerJob 每 5 分鐘觸發，分鐘條件判斷確保同一個批次只執行一次

### 2.2 滑動窗口分段（Sliding Window Segmentation）

目的：**讓剛完成的訂單快速出現，同時保證舊訂單不遺漏**

```
完成訂單的分段抓取（以 Shopee 為例）：

 ├── 段 1: now - 2天 ~ now         ← 每小時刷新（最新完成的）
 ├── 段 2: now - 9天 ~ now - 7天   ← 每小時刷新（中間段）
 └── 段 3: now - 15天 ~ now - 14天 ← 每小時刷新（尾巴段）

每段只拉 1~2 天寬度，但三段合起來覆蓋 0~15 天
段與段之間有間隔（滑動），隨時間推移會自然掃到所有訂單
比拉 0~15 天全量少了 80% 的資料量
```

---

## 3. 各平台策略

### 3.1 Shopee — 時間 + 狀態

**查詢維度**: 時間區間 + 訂單狀態
**API 特性**: 必須指定狀態，每個狀態分開查

| 狀態 | 刷新頻率 | 時間窗口 | 說明 |
|------|---------|---------|------|
| UNPAID | 每 10 分鐘 | 1 小時 | 新訂單，要即時 |
| READY_TO_SHIP | 每 10 分鐘 | 3 天 | 待出貨，隔日出貨常見 |
| IN_CANCEL | 每 10 分鐘 | 5 天 | 取消中 |
| PROCESSED | 每 10 分鐘 | 4 天 | 處理中 |
| CANCELLED | 每 10 分鐘 | 5 天（快速段） | 已取消 |
| SHIPPED | 每小時 | 7 天 | 出貨中，較穩定 |
| COMPLETED | 每小時 | 分段 2 + 7-9 + 14-15 天 | 已完成，滑動窗口 |
| CANCELLED | 每小時 | 5-20 天（補撈段） | 舊的取消訂單 |

```
Shopee doAction() 偽碼:

if (minute % 10 >= 5) {
    fetch("UNPAID",        now - 1hr,  now)
    fetch("READY_TO_SHIP", now - 3d,   now)
    fetch("CANCELLED",     now - 5d,   now)
    fetch("PROCESSED",     now - 4d,   now)
    fetch("IN_CANCEL",     now - 5d,   now)
}

if (minute > 53) {
    fetch("SHIPPED",     now - 7d,   now)
    fetch("COMPLETED",   now - 2d,   now)          // 段1: 最新
    fetch("COMPLETED",   now - 9d,   now - 7d)     // 段2: 中間
    fetch("COMPLETED",   now - 15d,  now - 14d)    // 段3: 尾巴
    fetch("CANCELLED",   now - 20d,  now - 5d)     // 補撈
}
```

### 3.2 Shopify — 時間 + 查詢類型

**查詢維度**: 時間區間 + 查詢模式（created / updated）
**API 特性**: 不需指定狀態，只有「新建」和「更新」兩種查詢

| 查詢 | 刷新頻率 | 時間窗口 | 說明 |
|------|---------|---------|------|
| created_at | 每 10 分鐘 | 1 小時 | 新訂單 |
| updated_at | 每 10 分鐘 | 1 小時 | 狀態變更的訂單 |

```
Shopify doAction() 偽碼:

// Shopify 很簡單，不需要分段
if (minute % 10 >= 5) {
    fetchByCreated(now - 1hr, now)    // 新建
    fetchByUpdated(now - 1hr, now)    // 更新
}
```

**為什麼 Shopify 這麼簡單？**
- API 直接回傳「最近新建/更新」的全部訂單（含各種狀態）
- 1 小時窗口 × 每 5 分鐘拉一次 = 12 倍覆蓋，足夠

### 3.3 Momo — 時間 + 狀態 + 物流類型

**查詢維度**: 時間區間 + 狀態 + 物流類型（Company/Stores/Third）
**API 特性**: 物流類型是獨立維度，超取訂單資料量大需要更短窗口

| 物流 | 狀態 | 刷新頻率 | 時間窗口 | 說明 |
|------|------|---------|---------|------|
| 宅配(Company) | 待出貨 | 每 10 分鐘 | 1 小時 | 一般物流 |
| 超取(Stores) | 待出貨 | 每 10 分鐘 | 1 小時 | 超商取貨 |
| 第三方(Third) | 待出貨 | 每 10 分鐘 | 1 小時 | 第三方物流 |
| 宅配 | 已出貨 | 每小時 | 0-5 天 | |
| 宅配 | 已完成 | 每小時 | 0-5, 6-10 天 | |
| 超取 | 已出貨 | 每小時 | 0-2 天 | **較短**（資料重） |
| 超取 | 已完成 | 每小時 | 0-2, 2-4 天 | **較短**（資料重） |

```
Momo doAction() 偽碼:

if (minute % 10 >= 5) {
    fetchCompany("pending", now - 1hr, now)
    fetchStores("pending",  now - 1hr, now)
    fetchThird("pending",   now - 1hr, now)
}

if (minute > 53) {
    // 宅配：窗口較長
    fetchCompany("shipped",    now - 5d,  now)
    fetchCompany("completed",  now - 5d,  now)
    fetchCompany("completed",  now - 10d, now - 6d)

    // 超取：窗口較短（資料重）
    fetchStores("shipped",     now - 2d,  now)
    fetchStores("completed",   now - 2d,  now)
    fetchStores("completed",   now - 4d,  now - 2d)

    // 第三方：類似宅配
    fetchThird("shipped",      now - 5d,  now)
    fetchThird("completed",    now - 5d,  now)
}
```

### 3.4 Yahoo — 時間 + 狀態 + 物流類型

**查詢維度**: 時間區間 + 訂單狀態 + 物流類型
**API 特性**: 類似 Momo，超取資料量大

與 Momo 策略類似，超取窗口更短。
（具體參數待串接 API 時確認）

### 3.5 PChome — 待確認

PChome API 文件尚未取得，策略待定。

---

## 4. ChannelAdapter 介面調整

目前的 `fetchOrders` 簽名太簡單：

```java
// 目前（不夠用）
List<Order> fetchOrders(String channelId, LocalDateTime from, LocalDateTime to);
```

需要擴展為支持平台特定查詢維度的方式。

### 方案：每次查詢帶 FetchOrdersRequest

```java
/**
 * 拉取訂單的請求參數
 * 每個平台的 Adapter 實作取自己需要的欄位
 */
@Data
@Builder
public class FetchOrdersRequest {
    private String channelId;
    private LocalDateTime from;
    private LocalDateTime to;
    private String orderStatus;       // Shopee: "UNPAID", Momo: "pending", 可 null
    private String logisticsType;     // Momo: "Company"/"Stores"/"Third", 可 null
    private String queryMode;         // Shopify: "created"/"updated", 可 null
    private Map<String, Object> extra; // 各平台自訂擴展
}
```

ChannelAdapter 改為：

```java
List<ChannelOrder> fetchOrders(FetchOrdersRequest request);
```

**各平台 Adapter 實作時只取自己需要的欄位**：
- Shopee: 取 `from`, `to`, `orderStatus`
- Shopify: 取 `from`, `to`, `queryMode`
- Momo: 取 `from`, `to`, `orderStatus`, `logisticsType`

---

## 5. FetchOrdersActionService 如何拿到 requestTime

### SchedulerJob 發送的 TaskMessage:

```json
{
  "taskAction": "FETCH_ORDERS",
  "createdAt": "2026-02-09T10:35:00Z",   ← 觸發時間
  "payload": {
    "channelId": "CH-MOMO-001",
    "platformType": "momo"
  }
}
```

### FetchOrdersActionService.setting():

```java
void setting(Resource resource) {
    this.msg = resource.getMsg();
    this.requestTime = msg.getCreatedAt();  // ← 用這個判斷 minute
    this.channelId = (String) msg.getPayload().get("channelId");
}
```

### FetchOrdersActionService.doAction():

```java
void doAction() {
    int minute = requestTime.atZone(ZoneId.of("Asia/Taipei")).getMinute();

    // 根據 minute 決定跑哪些段...
    if (minute % 10 >= 5) { ... }
    if (minute > 53) { ... }
}
```

---

## 6. 退貨/退款單抓取策略

退貨單（`FETCH_REFUND_ORDERS`）和訂單抓取獨立：

| 平台 | 策略 | 說明 |
|------|------|------|
| Shopee | 7-15 天窗口 + 回溯待出貨訂單 | getReturns(15天前, 7天前) + 對待出貨3+天的訂單回查 |
| Momo | 類似訂單，按物流分段 | 與 FETCH_ORDERS 結構相同 |
| Shopify | updated_at 涵蓋 | refund 狀態包含在 updated 查詢中 |

---

## 7. 參考架構來源

本設計參考 `oneec-consuming-action-job-ga`（路徑: `/home/tom/ONEEC/ONEEC/oneec-consuming-action-job-ga/`）：

- 19 個平台 × ~25 個 Action = ~500 個 ServiceImpl
- `ActionFactory`: switch-case 路由 (topic, action) → ServiceImpl
- `ActionService` 介面: `setting()`, `getPlatformTokens()`, `verifyNeedData()`, `doAction()`
- 時間窗口邏輯嵌入在每個平台的 `GetOrderServiceImpl.doAction()` 中
- 分鐘條件: `getMinute() % 10 >= 5` (快速刷新) / `getMinute() > 53` (慢速刷新)

SimpleEC OMS 已經有相同的 4-step 生命週期（ActionService.java），架構一致。

---

## 8. 與現有設計文件的關係

| 文件 | 與本文的關係 |
|------|------------|
| `FETCH_ORDERS.md` | 定義 3-JOB 端到端事件流；本文深入 §2（ChannelJob 內部策略） |
| `FETCH_PRODUCTS.md` | 商品同步事件流；商品不需要分段策略（全量拉取） |
| `STATISTICS_DESIGN.md` | 統計設計；依賴正確的訂單抓取才能算出正確統計 |
| `DB_ENTITY_GAPS.md` | ChannelAdapter 介面調整追蹤（fetchOrders 回傳類型 + 參數） |
