# SimpleEC OMS Queue Consumer 設計

根據事件流架構（CORE_CONTRACTS.md），定義所有 Consumer、Topic 和 Action 的對應關係。

**重點變更**：SYNC_PACK 現在是 UI (admin_ui) 驅動，在 {platform}.slow 執行雙層檢查 + 條件派發。SYNC_PRODUCT 和 SYNC_PACK 是獨立事件，各有各的 Handler。

## Consumer Groups 設計

### Channel Consumer Groups（10 組）

| Consumer | Topic | Action | Concurrency | Priority | Note |
|----------|-------|--------|-------------|----------|------|
| channel-job-momo-fast | momo.fast | FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 4 | HIGH | 1 小時內新訂單、出貨指令、價格/庫存快速更新 |
| channel-job-momo-slow | momo.slow | FETCH_ORDER_DETAIL, SYNC_PACK, FETCH_RETURNS, FETCH_RETURN_DETAIL | 2 | NORMAL | 訂單詳情、套包同步（雙層檢查 + 條件派發）、退貨詳情 |
| channel-job-shopee-fast | shopee.fast | FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 4 | HIGH | 蝦皮快速同步 |
| channel-job-shopee-slow | shopee.slow | FETCH_ORDER_DETAIL, SYNC_PACK, FETCH_RETURNS, FETCH_RETURN_DETAIL | 2 | NORMAL | 蝦皮詳情同步、套包同步 |
| channel-job-yahoo-fast | yahoo.fast | FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 4 | HIGH | Yahoo 快速同步 |
| channel-job-yahoo-slow | yahoo.slow | FETCH_ORDER_DETAIL, SYNC_PACK, FETCH_RETURNS, FETCH_RETURN_DETAIL | 2 | NORMAL | Yahoo CSV 處理、詳情同步、套包同步 |
| channel-job-pchome-fast | pchome.fast | FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 4 | HIGH | PChome 快速同步 |
| channel-job-pchome-slow | pchome.slow | FETCH_ORDER_DETAIL, SYNC_PACK, FETCH_RETURNS, FETCH_RETURN_DETAIL | 2 | NORMAL | PChome 詳情同步、套包同步 |
| channel-job-cyberbiz-fast | cyberbiz.fast | FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 4 | HIGH | Cyberbiz 快速同步 |
| channel-job-cyberbiz-slow | cyberbiz.slow | FETCH_ORDER_DETAIL, SYNC_PACK, FETCH_RETURNS, FETCH_RETURN_DETAIL | 2 | NORMAL | Cyberbiz 詳情同步、套包同步 |

### Business Consumer Groups（7 組）

| Consumer | Topic | Action | Concurrency | Priority | Note |
|----------|-------|--------|-------------|----------|------|
| order-process-handler | order.process | NEW_ORDER: 新訂單入庫 / UPDATE_ORDER: 訂單更新 | 8 | HIGH | 核心業務，需高吞吐 |
| return-process-handler | return.process | NEW_RETURN: 新退貨入庫 / PROCESS_RETURN: 退貨入庫 | 4 | NORMAL | 退貨處理 |
| sync-product-handler | task.backend | SYNC_PRODUCT: 商品同步（從 SKU 聚合建立 Product） | 4 | NORMAL | 獨立處理商品同步 |
| sync-pack-handler | task.backend | SYNC_PACK: 套包同步（建立 Pack 或更新 Pack→Product 映射） | 4 | NORMAL | 獨立處理套包同步 |
| backend-task-handler | task.backend | UPDATE_INVENTORY, UPDATE_PRICE, SHIP_ORDER 等 | 4 | NORMAL | 其他後端非同步任務 |
| error-handler | task.failed | FAILED_TASK: 失敗重試邏輯 | 2 | HIGH | 可重試的錯誤 |
| dlt-handler | task.dlt | DLT_MESSAGE: 死信記錄 & 告警 | 1 | CRITICAL | 無法恢復的訊息 |

### System Consumer Groups（可選）

| Consumer | Topic | Action | Concurrency | Priority | Note |
|----------|-------|--------|-------------|----------|------|
| scheduler-dispatcher | scheduler | DISPATCH_ORDER_FETCH: 分發訂單抓取任務 / HEALTH_CHECK: 系統健康檢查 | 1 | HIGH | 排程驅動，單一執行緒 |
| task-frontend-handler | task.frontend | 前端非同步任務（資料匯出、批次更新） | 4 | NORMAL | 用戶觸發任務 |

---

## Consumer 行為詳細設計

### Channel Job - Fast Consumer（example: shopee-channel-job-fast）

**消費邏輯**:
```
1. 從 scheduler 接收 FETCH_ORDERS 訊息
   - 指定 orderStatus（PENDING、PROCESSING、SHIPPED 等）
   - 指定要抓的時間段或狀態範圍

2. 呼叫 Shopee API
   - 根據 scheduler 指令決定查詢參數
   - 處理分頁/游標
   - 無法決定時間範圍的詳細細節（留給 DETAIL）

3. 判斷是否需要抓詳情
   - 金額 > 10000 → 需要
   - 有退貨 → 需要
   - 其他特殊狀態 → 需要

4. 發送到 order.process
   - 訊息格式：header.taskType = NEW_ORDER/UPDATE_ORDER
   - body 包含：orderId, orderData (list 資料), needsDetail, metadata
```

### Channel Job - Slow Consumer（example: shopee-channel-job-slow）

**兩種不同的消費邏輯**（根據 taskType）：

#### A. FETCH_ORDER_DETAIL

```
1. 從 {platform}.slow 接收 FETCH_ORDER_DETAIL 訊息
   - 帶著需要詳情的 orderId 列表

2. 逐筆呼叫 Shopee 訂單詳情 API
   - 取得完整的訂單資訊（items、payments、shipping 等）
   - 處理 rate limit

3. 發送到 order.process
   - 訊息格式：header.taskType = UPDATE_ORDER
   - body 包含完整的訂單資料
```

#### B. SYNC_PACK（新增）— 雙層檢查 + 條件派發

```
1. 從 {platform}.slow 接收 SYNC_PACK 訊息（source: admin_ui）
   - 帶著套包資料：platformId, specId, packName, packData 等

2. 執行「雙層對映檢查」（讀取 OMS 資料庫，NOT 呼叫平台 API）
   ① 查詢 PRODUCT 表：根據 SKU → Product 是否存在？
   ② 查詢 PACK 表：根據「platformId + specId」→ Pack 是否存在？

3. 根據「決策矩陣」決定派發事件到 task.backend

   | Product | Pack | 動作 |
   |---------|------|------|
   | ✅ 有   | ✅ 有 | 不做事（已完整） |
   | ✅ 有   | ❌ 無 | → 派發 SYNC_PACK |
   | ❌ 無   | ✅ 有 | → 派發 SYNC_PRODUCT → 派發 SYNC_PACK |
   | ❌ 無   | ❌ 無 | → 派發 SYNC_PRODUCT → 派發 SYNC_PACK |

4. 發送事件到 task.backend
   - 訊息格式：SYNC_PRODUCT（如需）+ SYNC_PACK（如需）
   - 注意：兩個事件都發送，由各自的獨立 Handler 處理
   - SYNC_PRODUCT 優先於 SYNC_PACK（Product 必須先存在）
```

### Order Process Handler

**消費邏輯**:
```
1. 從 order.process 接收 NEW_ORDER/UPDATE_ORDER
   - 取得訂單完整資料

2. 檢查 Redis Hash
   - 計算當前訂單的 hash 值
   - 比對 Redis 中是否存在相同 hash
   - 相同 → 跳過（已處理）
   - 不同或不存在 → 繼續處理

3. 入庫或更新資料庫
   - INSERT 新訂單
   - UPDATE 已存在的訂單

4. 更新 Redis Hash
   - 將新 hash 寫入 Redis（7天 TTL）

5. 發送下游事件
   - 可能發送到其他 topic（如 inventory.update、task.backend 等）
```

### Return Process Handler

**消費邏輯**:
```
1. 從 return.process 接收 NEW_RETURN 訊息
   - 包含完整的退貨資料

2. 入庫或更新資料庫
   - 新建或更新退貨單

3. 發送下游事件
   - 觸發退貨相關的後續流程
```

### Backend Task Handler（拆分為多個獨立 Handler）

**設計原則**：每個 TaskType 有各自的獨立 Handler（最小粒度設計），可被不同業務流程重用。

#### 1. SYNC_PRODUCT Handler（獨立）

```
1. 從 task.backend 接收 SYNC_PRODUCT 訊息
   - 帶著商品資料：SKU, name, price, attributes 等

2. 檢查資料庫
   - 根據 SKU 查詢 PRODUCT 表 → 是否已存在？

3. 執行業務邏輯
   - 不存在 → INSERT 新商品
   - 已存在 → UPDATE 商品信息（如名稱、價格等）

4. 記錄結果
   - 返回 Product ID（用於後續 SYNC_PACK）
   - 記錄成功或失敗
```

#### 2. SYNC_PACK Handler（獨立）

```
1. 從 task.backend 接收 SYNC_PACK 訊息
   - 帶著套包資料：platformId, specId, Product ID（可能來自上一步）等

2. 檢查資料庫
   - 根據「platformId + specId」查詢 PACK 表 → 是否已存在？

3. 執行業務邏輯
   - 不存在 → INSERT 新套包記錄
   - 已存在 → UPDATE 套包信息（關聯 Product ID、更新價格等）

4. 記錄結果
   - 返回 Pack ID
   - 記錄成功或失敗
```

#### 3. 其他 Handler（UPDATE_INVENTORY, UPDATE_PRICE, SHIP_ORDER 等）

```
根據 TaskType 路由到對應的業務邏輯
- UPDATE_INVENTORY: 庫存更新
- UPDATE_PRICE: 價格更新
- SHIP_ORDER: 出貨指令（自動或手動）
- 等其他後端任務

各 Handler 獨立執行、互不干擾。
```

### Error Handler (task.failed)

**消費邏輯**:
```
1. 從 task.failed 接收 FAILED_TASK 訊息
   - 包含原始訊息、錯誤資訊、重試次數

2. 判斷是否可重試
   - API timeout → 重試
   - Rate limit → 延遲重試（60秒後）
   - 認證失敗 → 不重試，轉移到 DLT
   - 資料格式錯誤 → 不重試，轉移到 DLT

3. 發送回原始 topic 或 DLT
   - retryCount < MAX_RETRIES → 重新發送原始 topic
   - retryCount >= MAX_RETRIES → 轉移到 task.dlt
```

### DLT Handler (task.dlt)

**消費邏輯**:
```
1. 從 task.dlt 接收 DLT_MESSAGE 訊息
   - 無法被任何 consumer 處理的訊息

2. 記錄詳細資訊
   - 原始訊息
   - 失敗原因
   - Kafka offset/partition

3. 發出告警
   - Email、Slack 通知 OPS
   - 保留 30 天供排查
```

---

## Scheduler 策略示例

### 核心原則
- ✅ **訂單/退貨抓取**：Scheduler **只發時間戳**，Channel Job 自行決策時間窗口邏輯
- ❌ **商品/套包同步**：不由 Scheduler 驅動（高機率被平台 DDOS 鎖機），完全手動 UI 驅動
- ✅ **報表生成**：每 5/30 分鐘或整點觸發，根據**客戶時區**判斷是否需要日報

### 訂單/退貨抓取策略

```
Scheduler 每 X 分鐘觸發一次（根據平台和訂單優先級）：

08:00 → FETCH_ORDERS { timestamp: "2026-02-13T08:00:00Z" }
08:15 → FETCH_ORDERS { timestamp: "2026-02-13T08:15:00Z" }
...
（每 15 分鐘一次，時間增量由 Scheduler 自動計算）

FETCH_RETURNS { timestamp: "..." }  # 類似邏輯

⚠️  Channel Job 內部根據 timestamp 自行決策：
  - 新訂單（1h 窗口）vs 待出貨（3d 窗口）vs 已完成（7d 窗口）
  - 是否需要打 detail API（平台能力、rate limit）
  - Scheduler 不關心這些細節，只提供時間戳
```

### 商品和套包同步策略

```
⚠️  重要：SYNC_PRODUCT 和 SYNC_PACK 不由 Scheduler 驅動！

原因：
  - 會被平台認為 DDOS，高機率被鎖機
  - 每個平台 rate limit 不同，定期同步不可控

策略：完全手動 UI 驅動
  1. 客戶在後臺按「同步套包」按鈕（source: admin_ui）
  2. 發送 SYNC_PACK 到 {platform}.slow
  3. {platform}.slow 執行雙層檢查 + 條件派發
  4. task.backend 中 SYNC_PRODUCT-handler 和 SYNC_PACK-handler 獨立處理

沒有定時排程，完全由用戶需求驅動。
```

### 報表生成策略（task.backend） — 時區感知 + 任務分散 + 增量快取

**兩層設計**：避免每筆訂單都重算（性能浪費）

#### 第一層：訂單狀態變更 → 寫快取標記

```
訂單狀態更新時（如出貨、完成、退貨等）：
  1. 不做任何報表計算
  2. 只寫一筆快取：
     cache["channel_recompute"][channel_id] = true

  例如：
    訂單 ORD-001 在 Shopee 出貨 → cache["channel_recompute"]["SHOPEE_001"] = true
    訂單 ORD-002 在 Momo 完成   → cache["channel_recompute"]["MOMO_001"] = true
    訂單 ORD-003 在 Shopee 退貨 → cache["channel_recompute"]["SHOPEE_001"] = true

  ✅ 優點：零計算成本，只是標記需要重算的通路
```

#### 第二層：Scheduler 定期檢查快取 → 批量派發

```
每 5 分鐘，Scheduler 執行：
  1. 檢查 cache["channel_recompute"]
  2. 收集所有 channel_id
  3. 根據 reportType 計算偏移，分散派發：

     if (current_minute % 5 == offset_for_orders) {
         // 訂單報表：發送需要重算的 channel 列表
         for (channelId in cache["channel_recompute"].keys()) {
             task.backend.send({
                 taskType: "RECOMPUTE_ORDER_REPORT",
                 channelId: channelId,
                 timestamp: current_timestamp
             })
         }
         // 清空快取
         cache["channel_recompute"].clear()
     }

  4. 不同報表類型在不同分鐘觸發：
     - 01, 06, 11... → 訂單報表
     - 02, 07, 12... → 庫存報表
     - 03, 08, 13... → 銷售額報表
     - 04, 09, 14... → 退貨報表
```

#### 第三層：Backend Handler 根據 Channel 重算報表

```
Handler 接收 RECOMPUTE_ORDER_REPORT：
  1. 根據 channelId 查詢該通路所有訂單
  2. 計算聚合數據（銷售額、數量、狀態分佈等）
  3. 更新（或新增）銷售統計表：

     UPDATE sales_stats
     SET
       total_sales = xxx,
       order_count = xxx,
       avg_price = xxx,
       updated_at = now()
     WHERE channel_id = ? AND report_date = ?

  4. 記錄處理時間，便於監控

⚠️  關鍵優勢：
  - 只在狀態變更時寫快取（成本低）
  - 每 5 分鐘批量重算一次（不是每筆訂單）
  - 多個訂單更新同一通路 → 一次重算搞定
```

---

**原理對比**：

| 方式 | 性能 | 準確性 | 複雜度 |
|------|------|--------|--------|
| 每筆訂單直接重算 | ❌ 浪費（大量重複計算） | ✅ 實時 | 低 |
| **快取 + 定期批量** | ✅ 高效 | ⚠️ 延遲 5 分鐘 | 中 |
| 每天重算一次 | ✅ 最高效 | ❌ 延遲太大 | 低 |

---

```
⚠️  關鍵：避免 Thundering Herd 問題 — 不同「事項（報表類型）」在不同分鐘觸發！

Scheduler 根據「報表類型/任務性質」計算偏移，分散觸發時間：

5 分鐘間隔（小時報表）：
  - 訂單報表   → 偏移 01 分 → 01, 06, 11, 16, 21, 26, 31, 36, 41, 46, 51, 56
  - 庫存報表   → 偏移 02 分 → 02, 07, 12, 17, 22, 27, 32, 37, 42, 47, 52, 57
  - 銷售額報表 → 偏移 03 分 → 03, 08, 13, 18, 23, 28, 33, 38, 43, 48, 53, 58
  - 退貨報表   → 偏移 04 分 → 04, 09, 14, 19, 24, 29, 34, 39, 44, 49, 54, 59
  （hash(reportType) % 5 決定偏移）

10 分鐘間隔（定期狀態同步）：
  - 訂單狀態更新   → 偏移 02 分 → 02, 12, 22, 32, 42, 52
  - 庫存檢查       → 偏移 05 分 → 05, 15, 25, 35, 45, 55
  - 價格更新       → 偏移 07 分 → 07, 17, 27, 37, 47, 57
  - 出貨確認       → 偏移 09 分 → 09, 19, 29, 39, 49, 59
  （hash(taskType) % 10 決定偏移）

30 分鐘和整點（日報）：
  - 所有日報統一：00 分和 30 分（無偏移，因為日報頻率低，且數量少）

報表/任務 Handler 邏輯：
  1. 接收 timestamp（UTC）
  2. 遍歷該商家的所有客戶，獲取每個客戶的時區（timezone）
  3. 將 timestamp 轉換為該客戶時區
  4. 判斷是否跨過 0 點（午夜）
     - YES: 觸發「日報更新」→ 彙整前一天的訂單、銷售額、退貨等數據
     - NO: 僅更新該報表類型的小時數據

範例：
  timestamp = 2026-02-13T16:06:00Z (UTC)

  訂單報表（偏移 01）：
    ✓ 現在是 06 分 → 符合「5 分鐘間隔 + 偏移」→ 觸發

  庫存報表（偏移 02）：
    ✗ 現在是 06 分 → 不符合 02 分偏移 → 不觸發

  銷售額報表（偏移 03）：
    ✗ 現在是 06 分 → 不符合 03 分偏移 → 不觸發

  customer A (UTC+8):
    → 本地時間 2026-02-14T00:06:00+08:00
    → 偵測到跨 0 點 ✓ → 同時觸發所有「日報」

  customer B (UTC-5):
    → 本地時間 2026-02-13T11:06:00-05:00
    → 未跨 0 點 ✗ → 不觸發日報，僅更新符合偏移時間的小時報表
```

---

## 拓撲圖

```
scheduler（排程驅動）
  ├─→ {platform}.fast/slow (Channel Job)
  │       ↓
  │    order.process (Order Handler: 判斷新建/更新)
  │    return.process (Return Handler)
  │       ↓
  │    [Redis Hash updated]
  │
  └─→ scheduler 還會分發「庫存、價格更新」等任務
       └─→ task.backend
           ├─ UPDATE_PRICE-handler
           ├─ UPDATE_INVENTORY-handler
           └─ ...其他排程任務

UI Trigger（用戶驅動）
  ├─→ SYNC_PACK (source: admin_ui)
  │   └─→ {platform}.slow (Channel Job: 雙層檢查)
  │       ↓ [條件派發]
  │    task.backend
  │       ├─ SYNC_PRODUCT-handler (獨立)
  │       └─ SYNC_PACK-handler (獨立)
  │
  └─→ SHIP_ORDER (用戶手動出貨)
      └─→ task.backend
          └─ SHIP_ORDER-handler

[Error handling]
Any failure → task.failed (Error Handler)
             ↓
          [retry] ──→ 原始 topic
          [max retries] ──→ task.dlt (DLT Handler)
```

---

## 監控指標

### 應監控的 Consumer 指標

| 指標 | 說明 |
|------|------|
| consumer_lag | Consumer lag - 應保持在 0~100 訊息內 |
| processing_time_p95 | 處理耗時 P95 - 快速應 < 500ms，慢速應 < 5s |
| error_rate | 錯誤率 - 應 < 1% |
| throughput | 吞吐量 - 每秒訊息數 |
| rebalance_count | 重新平衡次數 - 越少越好 |
| task_failed_count | task.failed 訊息數 - 監控異常 |
| task_dlt_count | task.dlt 訊息數 - 監控無法恢復的錯誤 |