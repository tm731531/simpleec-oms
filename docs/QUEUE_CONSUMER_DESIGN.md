# SimpleEC OMS Queue Consumer 設計

根據事件流架構，定義所有 Consumer、Topic 和 Action 的對應關係。

## Consumer Groups 設計

### Channel Consumer Groups（10組）

| Consumer | Topic | Action | Concurrency | Priority | Note |
|----------|-------|--------|-------------|----------|------|
| channel-job-momo-fast | momo.fast | FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 4 | HIGH | 1小時內新訂單、出貨指令、價格/庫存快速更新 |
| channel-job-momo-slow | momo.slow | FETCH_ORDER_DETAIL, SYNC_PRODUCT, FETCH_RETURNS, FETCH_RETURN_DETAIL | 2 | NORMAL | 訂單詳情、商品詳情、退貨詳情 |
| channel-job-shopee-fast | shopee.fast | FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 4 | HIGH | 蝦皮快速同步 |
| channel-job-shopee-slow | shopee.slow | FETCH_ORDER_DETAIL, SYNC_PRODUCT, FETCH_RETURNS, FETCH_RETURN_DETAIL | 2 | NORMAL | 蝦皮詳情同步 |
| channel-job-yahoo-fast | yahoo.fast | FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 4 | HIGH | Yahoo 快速同步 |
| channel-job-yahoo-slow | yahoo.slow | FETCH_ORDER_DETAIL, SYNC_PRODUCT, FETCH_RETURNS, FETCH_RETURN_DETAIL | 2 | NORMAL | Yahoo CSV 處理、詳情同步 |
| channel-job-pchome-fast | pchome.fast | FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 4 | HIGH | PChome 快速同步 |
| channel-job-pchome-slow | pchome.slow | FETCH_ORDER_DETAIL, SYNC_PRODUCT, FETCH_RETURNS, FETCH_RETURN_DETAIL | 2 | NORMAL | PChome 詳情同步 |
| channel-job-cyberbiz-fast | cyberbiz.fast | FETCH_ORDERS, SHIP_ORDER, UPDATE_PRICE, UPDATE_INVENTORY, APPROVE_RETURN | 4 | HIGH | Cyberbiz 快速同步 |
| channel-job-cyberbiz-slow | cyberbiz.slow | FETCH_ORDER_DETAIL, SYNC_PRODUCT, FETCH_RETURNS, FETCH_RETURN_DETAIL | 2 | NORMAL | Cyberbiz 詳情同步 |

### Business Consumer Groups（6組）

| Consumer | Topic | Action | Concurrency | Priority | Note |
|----------|-------|--------|-------------|----------|------|
| order-process-handler | order.process | NEW_ORDER: 新訂單入庫 | 8 | HIGH | 訂單是核心業務，需要高吞吐 |
| | | UPDATE_ORDER: 訂單狀態更新、出貨記錄 | | | |
| return-process-handler | return.process | NEW_RETURN: 新退貨入庫 | 4 | NORMAL | 退貨流程 |
| | | APPROVE_RETURN: 同意退貨 | | | |
| product-sync-handler | product.sync | PRODUCT_SYNCED: 商品同步結果記錄 | 2 | LOW | 異步記錄，可低優先級 |
| inventory-update-handler | inventory.update | INVENTORY_UPDATED: 庫存更新記錄 | 4 | HIGH | 庫存影響銷售，需重視 |
| error-handler | task.failed | FAILED_TASK: 失敗重試邏輯 | 2 | HIGH | 處理可重試的錯誤 |
| dlt-handler | task.dlt | DLT_MESSAGE: 死信記錄 & 告警 | 1 | CRITICAL | 無法處理的訊息，需立即告警 |

### System Consumer Groups（可選）

| Consumer | Topic | Action | Concurrency | Priority | Note |
|----------|-------|--------|-------------|----------|------|
| scheduler-dispatcher | scheduler | DISPATCH_ORDER_FETCH: 分發訂單抓取任務 | 1 | HIGH | 排程驅動，單一執行緒 |
| | | HEALTH_CHECK: 系統健康檢查 | | | |
| task-backend-handler | task.backend | 後端非同步任務（報表、資料同步等） | 2 | NORMAL | 內部系統任務 |
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

**消費邏輯**:
```
1. 從 order.process 接收 FETCH_ORDER_DETAIL 訊息
   - 帶著需要詳情的 orderId 列表

2. 逐筆呼叫 Shopee 訂單詳情 API
   - 取得完整的訂單資訊（items、payments、shipping 等）
   - 處理 rate limit

3. 發送到 order.process
   - 訊息格式：header.taskType = UPDATE_ORDER
   - body 包含完整的訂單資料
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

### Inventory Update Handler

**消費邏輯**:
```
1. 從 inventory.update 接收 UPDATE_INVENTORY 訊息
   - 通常來自 Channel Job（快速庫存更新）

2. 更新資料庫庫存
   - 支援絕對值和相對值更新

3. 記錄 INVENTORY_UPDATED 結果
   - 供後續追蹤
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

Scheduler 應該按以下邏輯分發任務：

### 訂單抓取策略（Shopee 例）

```
08:00 → FETCH_ORDERS(PENDING)      # 1小時內新訂單（08:00-09:00）
09:00 → FETCH_ORDERS(PENDING)      # 09:00-10:00
...
12:00 → FETCH_ORDERS(PROCESSING)   # 3天內出貨中訂單
12:30 → FETCH_ORDERS(SHIPPED)      # 7天內已出貨訂單
13:00 → FETCH_ORDERS(COMPLETED)    # 7~15天內已完成訂單
```

### 商品同步策略

```
06:00 → SYNC_PRODUCT(BATCH)        # 全量同步（每天一次）
每分鐘 → UPDATE_PRICE/INVENTORY    # 快速同步（價格、庫存）
```

---

## 拓撲圖

```
scheduler
  ↓
  ├─→ momo.fast/slow (Channel Job)
  ├─→ shopee.fast/slow (Channel Job)
  ├─→ yahoo.fast/slow (Channel Job)
  ├─→ pchome.fast/slow (Channel Job)
  └─→ cyberbiz.fast/slow (Channel Job)
        ↓
        order.process (Order Process Handler)
             ↓
        ├─→ product.sync (Product Sync Handler)
        ├─→ inventory.update (Inventory Handler)
        ├─→ return.process (Return Handler)
        └─→ task.backend (可選後續流程)

        error during processing
             ↓
        task.failed (Error Handler)
             ↓
        [retry] ──→ 原始 topic
        [max retry exceeded] ──→ task.dlt (DLT Handler)
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