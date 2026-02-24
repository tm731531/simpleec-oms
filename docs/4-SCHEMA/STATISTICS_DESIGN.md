# 統計設計 — 多角色 × 正逆物流

> 最後更新：2026-02-09
>
> 本文件定義統計的業務口徑、資料來源、Schema 異動、API 設計。

---

## 0. 核心前提：我們是被動同步方

```
★ 本系統是多平台訂單「同步」系統，不是訂單「產生」系統。
★ 我們是被動方 — 平台給什麼就收什麼，不能假設資料完整性。

資料不完整的原因：
  1. 拉單有時間差/快取 — 區間沒覆蓋到的訂單會漏掉
  2. Webhook 漏接 — 平台推送但我們沒收到
  3. 平台根本不給某些狀態 — 有些平台不提供某些中間狀態的訂單
  4. 平台跳過中間狀態 — 訂單可能從 confirmed 直接跳到 refunding
  5. 同步延遲 — 同一張訂單的不同狀態可能在不同批次拉到

設計原則：
  - 不做狀態轉換驗證 — 接受平台給的任何狀態，不檢查 from→to 是否符合「理想流程」
  - 所有訂單處理邏輯都必須容錯 — 訂單可能從任何狀態跳到任何狀態
  - 統計/業務邏輯只看「當前快照」— 不假設歷史狀態經過了哪些步驟
```

---

## 1. 業務背景

### 1.1 正物流 vs 逆物流

```
正物流（Forward Logistics）:
  pending → confirmed → processing → shipped → delivered → completed
                                                              │
                                                              └─ cancelled（正物流結束信號，非逆物流）

逆物流（Reverse Logistics）:
  ★ 任何正物流狀態都可能直接跳到退貨（平台資料有 gap）
  pending ─────────┐
  confirmed ───────┤
  processing ──────┤
  shipped ─────────┤──→ refunding → refunded
  delivered ───────┤    （可部分退貨）
  completed ───────┘
```

**關鍵認知：**
- `cancelled` 是正物流的結束信號，不是逆物流
- 逆物流只有 `refunding` / `refunded`
- **部分退貨**：一張 $1000 的訂單可能只退 $300，訂單狀態不變
- **退貨可從任何狀態觸發**：因為我們從平台拉單有 gap（時間區間沒覆蓋、webhook 漏接、平台跳過中間狀態），訂單可能從 confirmed 直接跳到 refunding，不能假設一定經過 completed

### 1.2 四種角色的統計視角

| 角色 | 關注 | 口徑定義 | 看什麼數字 |
|------|------|---------|-----------|
| **業務** | 新增訂單 | 當天 `created_at` 新進來的訂單，不管狀態 | order_count, order_amount |
| **老闆** | 營業額 | 所有非取消訂單的 total_amount（含未付款、含 pending） | gross_amount（= total_amount WHERE status != cancelled） |
| **財務** | 實收金額 | 可出貨狀態以上的訂單金額 - 退款金額 | received_amount - refund_amount |
| **RMA** | 退貨退款 | refunding + refunded 筆數 + 金額 | refund_count, refund_amount |

### 1.3 財務實收定義

```
可出貨 = status IN (confirmed, processing, shipped, delivered, completed)
         ─ 不管 COD 或預付，能出貨就算實收
         ─ 排除 pending（訂單未確認）、cancelled（已取消）

net_received = received_amount - refund_amount
               ─ received_amount: SUM(total_amount) WHERE 可出貨狀態
               ─ refund_amount: SUM(refund_orders.refund_amount) WHERE 該日退款

注意：一張已完成的訂單如果後續部分退貨 $300：
  ─ received_amount 不變（因為訂單仍是 completed）
  ─ refund_amount 增加 $300
  ─ net_received 減少 $300
```

### 1.4 部分退貨的處理

```
情境：訂單 ORD-001，total_amount = $1,000，status = shipped（任何狀態皆可能發生退貨）

退貨 #1：退 $300（商品 A 瑕疵）
  → refund_orders 新增一筆：refund_amount = 300
  → orders.refund_amount 累加：0 → 300
  → orders.has_refund = true
  → orders.order_status 不變（仍是 shipped）

退貨 #2：再退 $200（商品 B 不滿意）
  → refund_orders 再新增一筆：refund_amount = 200
  → orders.refund_amount 累加：300 → 500
  → orders.order_status 不變（仍是 shipped）

全額退貨：退剩餘 $500
  → refund_orders 新增一筆：refund_amount = 500
  → orders.refund_amount 累加：500 → 1000
  → orders.order_status 改為 refunded（全退才改狀態）

規則：
  refund_amount < total_amount → 部分退貨 → status 不變（維持當前狀態）
  refund_amount >= total_amount → 全額退貨 → status = refunded

★ 退貨可從任何 order_status 發生（pending, confirmed, processing, shipped, delivered, completed）
  因為平台資料有 gap：區間沒覆蓋、webhook 漏接、平台跳過中間狀態等
```

---

## 2. orders 表異動

### 2.1 新增欄位

```sql
ALTER TABLE public.orders
  ADD COLUMN refund_amount DECIMAL(12,2) NOT NULL DEFAULT 0,  -- 累計退款金額
  ADD COLUMN has_refund    BOOLEAN       NOT NULL DEFAULT false; -- 是否有退貨（快速判斷）
```

**用途：**
- `refund_amount`：每次退貨時累加，不需 JOIN refund_orders 就能算 net
- `has_refund`：訂單列表快速篩選「有退貨的訂單」，避免 EXISTS 子查詢

**同步時機：**
- 建立 refund_orders 時 → 同時 UPDATE orders SET refund_amount = refund_amount + X, has_refund = true
- `ORDER_STATUS_CHANGED` 事件中由 BackendJob 執行兩邊同步

### 2.2 狀態轉換規則（含逆物流）

```
正物流狀態轉換（理想單向，但平台資料有 gap，可能跳過中間狀態）:
  pending → confirmed → processing → shipped → delivered → completed
                                                  │
                                                  └→ cancelled（任何正物流階段都可取消）

逆物流狀態轉換（★ 可從任何正物流狀態觸發，不限 completed）:
  任何狀態 + 部分退貨 → status 不變，refund_amount > 0, has_refund = true
  任何狀態 + 全額退貨 → status = refunded

  ★ 為什麼不限 completed？
    - 平台拉單有時間 gap（區間沒覆蓋到）
    - Webhook 可能漏接
    - 平台可能跳過中間狀態（confirmed 直接跳 refunding）
    - 我們的系統必須容錯，接受任何合法的狀態轉換

判斷邏輯:
  IF orders.refund_amount >= orders.total_amount:
      status = refunded（全退）
  ELSE IF orders.refund_amount > 0:
      status 不變（部分退，維持當前正物流狀態）
      has_refund = true
```

---

## 3. daily_statistics 表重設計

### 3.1 問題

SCHEMA.md v4 的 daily_statistics 只有 6 個 metric（order_count, total_amount, shipped_count, completed_count, cancelled_count, refund_count），缺少：
- 各狀態的**金額**（只有 count 沒有 amount）
- **財務實收**相關欄位
- **退款金額**
- 商品相關統計

### 3.2 新版 daily_statistics

```sql
CREATE TABLE public.daily_statistics (
    id              VARCHAR(20),                 -- NanoID
    merchant_id     VARCHAR(20)   NOT NULL,
    platform_id     VARCHAR(20)   NOT NULL,      -- 平台（全通路加總時填 '_ALL_'）
    channel_id      VARCHAR(20)   NOT NULL,      -- 通路（全平台加總時填 '_ALL_'）
    stat_date       DATE          NOT NULL,

    -- ★ 業務視角：當日新增訂單（不分狀態，只看 created_at 在當天的）
    new_order_count     INTEGER       DEFAULT 0,     -- 新增訂單數
    new_order_amount    NUMERIC(15,2) DEFAULT 0,     -- 新增訂單金額

    -- ★ 老闆視角：營業額（排除 cancelled 的全部訂單）
    gross_order_count   INTEGER       DEFAULT 0,     -- 有效訂單數（非取消）
    gross_amount        NUMERIC(15,2) DEFAULT 0,     -- 營業額（排除 cancelled）

    -- ★ 財務視角：實收（可出貨狀態以上）
    received_count      INTEGER       DEFAULT 0,     -- 可出貨訂單數
    received_amount     NUMERIC(15,2) DEFAULT 0,     -- 可出貨訂單金額
    refund_count        INTEGER       DEFAULT 0,     -- 退款筆數（refund_orders 當日新增）
    refund_amount       NUMERIC(15,2) DEFAULT 0,     -- 退款金額
    net_amount          NUMERIC(15,2) DEFAULT 0,     -- 淨收 = received_amount - refund_amount

    -- ★ 物流視角：各狀態計數
    shipped_count       INTEGER       DEFAULT 0,     -- 已出貨
    completed_count     INTEGER       DEFAULT 0,     -- 已完成
    cancelled_count     INTEGER       DEFAULT 0,     -- 已取消

    -- ★ 商品統計
    item_sold_count     INTEGER       DEFAULT 0,     -- 售出件數（items[].quantity 加總）

    created_at      TIMESTAMPTZ   DEFAULT now(),
    updated_at      TIMESTAMPTZ   DEFAULT now(),
    PRIMARY KEY (id, stat_date)
) PARTITION BY RANGE (stat_date);

CREATE UNIQUE INDEX idx_daily_stats_unique
    ON public.daily_statistics (merchant_id, platform_id, channel_id, stat_date);
```

### 3.3 欄位聚合規則

```
DailyStatisticsActionService 聚合邏輯（以某 merchant 的某 channel 某天為例）：

FROM orders WHERE merchant_id = ? AND channel_id = ? AND channel_created_at 在 stat_date 當天（merchant 時區）：

  new_order_count   = COUNT(*)
  new_order_amount  = SUM(total_amount)

  gross_order_count = COUNT(*) WHERE order_status != 'cancelled'
  gross_amount      = SUM(total_amount) WHERE order_status != 'cancelled'

  received_count    = COUNT(*) WHERE order_status IN ('confirmed','processing','shipped','delivered','completed')
  received_amount   = SUM(total_amount) WHERE order_status IN ('confirmed','processing','shipped','delivered','completed')

  shipped_count     = COUNT(*) WHERE order_status IN ('shipped','delivered','completed')
  completed_count   = COUNT(*) WHERE order_status = 'completed'
  cancelled_count   = COUNT(*) WHERE order_status = 'cancelled'

  item_sold_count   = SUM(jsonb_array_length(items)) WHERE order_status != 'cancelled'
                      -- 簡化：不解析每個 item 的 quantity，用 JSONB items 陣列長度近似
                      -- 精確版：SUM over items[].quantity（需 jsonb_array_elements）

FROM refund_orders WHERE merchant_id = ? AND created_at 在 stat_date 當天：

  refund_count      = COUNT(*)
  refund_amount     = SUM(refund_amount)

計算：
  net_amount        = received_amount - refund_amount
```

### 3.4 退款造成的統計修正

```
情境：
  ─ 2/5: 訂單 ORD-001 建立，total_amount = $1,000，status = pending
  ─ 2/5: 確認出貨，status = shipped
  ─ 2/7: 送達完成，status = completed
  ─ 2/10: 客戶退貨 $300

2/5 的 daily_statistics:
  new_order_count = 1, new_order_amount = 1000
  gross_amount = 1000（非取消）
  received_amount = 1000（可出貨）
  refund_amount = 0
  net_amount = 1000

2/10 的 daily_statistics:
  new_order_count = 0（2/10 沒有新訂單）
  refund_count = 1, refund_amount = 300
  net_amount = received_amount(2/10) - 300

★ 退款記在退款發生的那一天，不回溯修改訂單建立日的統計。
  這是會計慣例：收入記在收入日，退款記在退款日。
  要看特定訂單的「最終淨收」→ 查 orders.total_amount - orders.refund_amount。
```

---

## 4. API 設計

### 4.1 統計端點

```
GET /api/v1/statistics/summary
  Query: merchantId, startDate, endDate, channelId?, view
  view = sales | revenue | finance | rma（預設 sales）

  回傳：SUM(daily_statistics) for date range
```

**各 view 回傳欄位：**

| view | 回傳欄位 | 說明 |
|------|---------|------|
| `sales` (業務) | newOrderCount, newOrderAmount, grossOrderCount, grossAmount | 新增訂單 + 營業額 |
| `revenue` (老闆) | grossAmount, receivedAmount, refundAmount, netAmount, shippedCount, completedCount | 全局營收 |
| `finance` (財務) | receivedCount, receivedAmount, refundCount, refundAmount, netAmount | 實收 + 退款 |
| `rma` | refundCount, refundAmount, cancelledCount | 逆物流 |

### 4.2 趨勢端點

```
GET /api/v1/statistics/daily
  Query: merchantId, startDate, endDate, channelId?, view

  回傳：Array of daily records（每天一筆）
  用途：折線圖（趨勢）
```

### 4.3 通路對比端點

```
GET /api/v1/statistics/by-channel
  Query: merchantId, startDate, endDate, view

  回傳：各通路分組統計
  用途：通路對比表格
```

### 4.4 Dashboard 統計卡片映射

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ 營業額    │  │ 新增訂單  │  │ 已出貨    │  │ 退貨/取消 │
│ grossAmt │  │ newOrder │  │ shipped  │  │ refund / │
│ ↑12%     │  │ Count    │  │ Count    │  │ cancelled│
└──────────┘  └──────────┘  └──────────┘  └──────────┘

  營業額     = SUM(gross_amount)           ← 老闆視角
  新增訂單   = SUM(new_order_count)         ← 業務視角
  已出貨     = SUM(shipped_count)           ← 物流視角
  退貨/取消  = SUM(refund_count) / SUM(cancelled_count) ← RMA 視角

  ↑12% = 與前一等長日期區間相比的變化率
```

---

## 5. ORDER_STATUS_CHANGED 事件處理

### 5.1 觸發場景

```
OrderStatusChange 事件來源：
  1. ChannelJob 拉單 → 訂單狀態從平台更新（pending→confirmed, shipped→completed, etc）
  2. API 手動操作 → 出貨確認、取消
  3. ChannelJob 拉取退款 → refunding / refunded
  4. Webhook 推送 → 平台主動推狀態變更

所有狀態變更 → OrderProcessJob 寫 order_status_logs + 發 ORDER_STATUS_CHANGED 到 task.backend
```

### 5.2 BackendJob 處理 ORDER_STATUS_CHANGED

```java
// BackendJob 收到 ORDER_STATUS_CHANGED 時的處理：

payload = {
    orderId: "ORD-001",
    channelOrderId: "MOMO-ORD-12345",
    fromStatus: "shipped",       // ★ 可以是任何狀態（平台資料有 gap）
    toStatus: "refunding",       // 或 "refunded"
    refundOrderId: "RFN-001",    // 如果是退貨觸發的，帶退貨單 ID
    refundAmount: 300             // 如果是退貨觸發的，帶退款金額
}

處理邏輯：
  1. 更新 orders.order_status
     ★ 不驗證 fromStatus → toStatus 是否符合「理想」流程
       因為平台資料有 gap，任何狀態轉換都可能發生
  2. 寫 order_status_logs

  // ★ 退款相關同步
  3. IF refundOrderId != null:
       UPDATE orders
         SET refund_amount = refund_amount + refundAmount,
             has_refund = true
         WHERE id = orderId

  // ★ 全退判斷（不管當前 status 是什麼，只看金額）
  4. IF orders.refund_amount >= orders.total_amount:
       UPDATE orders SET order_status = 'refunded'

  // ★ 統計不在這裡即時更新
  //   daily_statistics 由 DailyStatisticsActionService 每日預彙整
  //   退款金額記在 refund_orders.created_at 的那一天
```

### 5.3 訂單狀態完整狀態機

```
                    ┌──────────── cancelled ◄────────────┐
                    │                                     │
                    │                                     │
  pending ──→ confirmed ──→ processing ──→ shipped ──→ delivered ──→ completed
     │            │              │            │            │              │
     │            │              │            │            │              │
     └────────────┴──────────────┴────────────┴────────────┴──────────────┘
                                      │
                                      │ (退貨：任何狀態都可能)
                                      ▼
                               refunding ──→ refunded

  ★ 退貨可從任何正物流狀態觸發（平台資料有 gap，不能假設線性流程）
  ★ 部分退貨不改 order_status（維持當前狀態）
  ★ 全額退貨（refund_amount >= total_amount）→ status = refunded
  ★ cancelled 可從任何正物流階段觸發
  ★ 不做狀態轉換驗證 — 接受平台給的任何合法狀態
```

---

## 6. 前端呈現建議

### 6.1 訂單列表增加標記

```
訂單列表的狀態 badge 映射（更新）：
  pending      → 待處理（黃色）
  confirmed    → 已確認（藍色）
  processing   → 處理中（藍色）
  shipped      → 已出貨（紫色）
  delivered    → 已送達（綠色）
  completed    → 已完成（綠色）
  任何狀態 + has_refund → 原狀態 ⚠️ 部分退貨（原色 + 橙色小標）
  cancelled    → 已取消（灰色）
  refunding    → 退款中（橙色）
  refunded     → 已退款（紅色）
```

### 6.2 訂單詳情頁

```
訂單 ORD-001                     狀態: 已出貨 ⚠️ 部分退貨
──────────────────────────────────
訂單金額:    $1,000.00
退款金額:    -$300.00            ← 醒目紅字
淨收金額:    $700.00

退貨記錄:                        ← 新區塊
  ├── RFN-001  2026/02/10  $300  原因: 商品A瑕疵
  └── (若有更多退貨記錄)
```

### 6.3 Dashboard 統計頁

```
日期: [2026/02/01] ~ [2026/02/08]    視角: [業務 ▼]

切換視角:
  ├── 業務 → 新增訂單數 + 新增金額 + 營業額 + 有效訂單
  ├── 營收 → 營業額 + 實收 + 退款 + 淨收 + 出貨 + 完成
  ├── 財務 → 實收 + 退款 + 淨收
  └── RMA  → 退款筆數 + 退款金額 + 取消筆數
```

---

## 7. 異動清單（Schema 變更）

### 7.1 orders 表

```diff
  CREATE TABLE public.orders (
      ...
      discount_amount   DECIMAL(12,2) NOT NULL DEFAULT 0,
+     refund_amount     DECIMAL(12,2) NOT NULL DEFAULT 0,  -- 累計退款金額
+     has_refund        BOOLEAN       NOT NULL DEFAULT false, -- 是否有退貨
      items             JSONB         NOT NULL DEFAULT '[]',
      ...
  );

+ CREATE INDEX idx_order_has_refund ON public.orders (merchant_id, has_refund) WHERE has_refund = true;
```

### 7.2 daily_statistics 表

```diff
  CREATE TABLE public.daily_statistics (
      id              VARCHAR(20),
      merchant_id     VARCHAR(20)   NOT NULL,
      platform_id     VARCHAR(20)   NOT NULL,
      channel_id      VARCHAR(20)   NOT NULL,
      stat_date       DATE          NOT NULL,

-     order_count     INTEGER       DEFAULT 0,
-     total_amount    NUMERIC(15,2) DEFAULT 0,
-     shipped_count   INTEGER       DEFAULT 0,
-     completed_count INTEGER       DEFAULT 0,
-     cancelled_count INTEGER       DEFAULT 0,
-     refund_count    INTEGER       DEFAULT 0,
+     -- 業務視角
+     new_order_count     INTEGER       DEFAULT 0,
+     new_order_amount    NUMERIC(15,2) DEFAULT 0,
+     -- 老闆視角
+     gross_order_count   INTEGER       DEFAULT 0,
+     gross_amount        NUMERIC(15,2) DEFAULT 0,
+     -- 財務視角
+     received_count      INTEGER       DEFAULT 0,
+     received_amount     NUMERIC(15,2) DEFAULT 0,
+     refund_count        INTEGER       DEFAULT 0,
+     refund_amount       NUMERIC(15,2) DEFAULT 0,
+     net_amount          NUMERIC(15,2) DEFAULT 0,
+     -- 物流視角
+     shipped_count       INTEGER       DEFAULT 0,
+     completed_count     INTEGER       DEFAULT 0,
+     cancelled_count     INTEGER       DEFAULT 0,
+     -- 商品統計
+     item_sold_count     INTEGER       DEFAULT 0,

      created_at      TIMESTAMPTZ   DEFAULT now(),
      updated_at      TIMESTAMPTZ   DEFAULT now(),
      PRIMARY KEY (id, stat_date)
  ) PARTITION BY RANGE (stat_date);
```

### 7.3 OrderStatus enum

```diff
  public enum OrderStatus {
      PENDING("pending", "待處理"),
      CONFIRMED("confirmed", "已確認"),
      PROCESSING("processing", "處理中"),
      SHIPPED("shipped", "已出貨"),
      DELIVERED("delivered", "已送達"),
      COMPLETED("completed", "已完成"),
      CANCELLED("cancelled", "已取消"),
      REFUNDING("refunding", "退款中"),
      REFUNDED("refunded", "已退款");
+     // ★ 不新增 partial_refund 狀態
+     // 部分退貨靠 orders.has_refund + orders.refund_amount 判斷
  }
```

### 7.4 Order Entity

```diff
  // Order.java 需新增：
+ private BigDecimal refundAmount;  // 累計退款金額
+ private Boolean hasRefund;        // 是否有退貨
```

### 7.5 OrderVO

```diff
  // OrderVO.java 需新增：
+ private BigDecimal refundAmount;
+ private Boolean hasRefund;
```

---

## 8. 相關文件交叉引用

| 文件 | 需更新的部分 |
|------|------------|
| `docs/SCHEMA.md` | orders 加 refund_amount + has_refund；daily_statistics 改多視角欄位 |
| `DESIGN_v2.md` §1 | 統一 daily_statistics DDL |
| `DESIGN_v2.md` §2 | 新增 /statistics API 的 view 參數 |
| `DESIGN_v2.md` §8 | Dashboard mockup 加視角切換 |
| `docs/event-flows/FETCH_ORDERS.md` | §3 DB 對應表加 refund_amount + has_refund |
| `docs/event-flows/DB_ENTITY_GAPS.md` | Order.java 新增欄位標記 |
| `docs/STATUS.md` | 統計設計完成度 |

---

## 9. 實作優先順序

```
1. Schema 異動（本次只更新文件，下週實作）
   ─ orders 加 refund_amount + has_refund
   ─ daily_statistics 改多視角欄位
   ─ Order.java / OrderVO.java 加欄位

2. OrderStatusChanged 處理（Level 3）
   ─ BackendJob 處理 ORDER_STATUS_CHANGED
   ─ 退款同步: refund_orders + orders.refund_amount 雙寫

3. DailyStatisticsActionService（Level 3）
   ─ 多視角聚合邏輯
   ─ 退款記在退款日（不回溯）

4. Statistics API（Level 3）
   ─ /summary, /daily, /by-channel
   ─ view 參數切換角色視角

5. Dashboard 前端（Level 4+）
   ─ 視角切換 selector
   ─ 統計卡片 + 趨勢圖
```
