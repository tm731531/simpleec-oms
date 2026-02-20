# SimpleEC OMS Kafka 拓撲設計

## 核心設計原則

### 多生產者 + TaskType 路由

**一個 Topic，多個 Producer，多個 TaskType**：

```
{platform}.slow ← 多個 Producer
├─ SchedulerConsumer (taskType: FETCH_ORDERS)
├─ Channel Job Slow (taskType: FETCH_ORDER_DETAIL, Mode B only)
├─ UI/Gateway (taskType: SYNC_PACK)
└─ ...

Consumer（Channel Job Slow）根據 taskType 路由：
├─ FETCH_ORDERS → [Mode A 直接 / Mode B + detail] → order.process
├─ SYNC_PACK → task.backend
└─ ...
```

**優勢**：
- 避免 Topic 爆炸（16 個而非幾十個）
- 多個模組可同時往同一 Topic 發不同操作
- Consumer 層透過 taskType switch 實現 ACID

---

## 完整 Topic & 流向圖

### 時間驅動層
```
HeartbeatJob (每秒)
   ↓ produce
 scheduler
   ↓ consume
SchedulerConsumer
   ├─ timestamp.minute % 5 == 0? → {platform}.slow (FETCH_ORDERS)
   └─ timestamp.minute % 15 == 0? → {platform}.fast (SYNC_PRODUCT)
```

### 通路同步層（Mode A vs Mode B）
```
{platform}.slow (來自 SchedulerConsumer)
   ↓ consume
Channel Job Slow
   ├─ 讀 Redis Layer1 (去重)
   ├─ Mode A: 直接 FETCH_ORDERS → order.process
   └─ Mode B: FETCH_ORDERS → FETCH_ORDER_DETAIL → order.process

{platform}.fast (來自 SchedulerConsumer)
   ↓ consume
Channel Job Fast
   ├─ SYNC_PRODUCT / SYNC_PACK
   └─ produce → task.backend
```

### 訂單處理核心層
```
order.process ← Channel Job Slow
   ↓ consume
OrderUpsertHandler
   ├─ Redis Layer2 (分鎖)
   ├─ INSERT/UPDATE orders
   └─ produce → task.backend

return.process ← Channel Job Slow
   ↓ consume
ReturnUpsertHandler
   ├─ Redis Layer2 (分鎖)
   ├─ INSERT/UPDATE returns
   └─ produce → task.backend
```

### 後端異步任務層
```
task.backend ← (多個 producer)
   ├─ SchedulerConsumer (報表、掃健檢)
   ├─ Channel Job Fast (商品同步後)
   ├─ OrderUpsertHandler (訂單後續)
   ├─ ReturnUpsertHandler (退貨後續)
   └─ [UI] (前台事件)
   ↓ consume
BackendTaskHandler
   ├─ GENERATE_SHIPMENT
   ├─ UPDATE_INVENTORY
   └─ SEND_TO_WAREHOUSE
```

### 重試 & 死信層
```
[任何 Consumer 失敗]
   ↓ exception
task.failed
   ↓ consume
ErrorHandler
   ├─ retryCount <= 3? → 發回原 topic
   └─ retryCount > 3? → task.dlt

task.dlt
   ↓ consume
DltHandler
   ├─ 存儲死信到 dlt_messages
   └─ 發警報
```

---

## 17 個 Kafka Topic 設定

### 通路 Topic（10 個）

| Topic | 分區 | Retention | 說明 |
|-------|------|-----------|------|
| momo.fast / momo.slow | 3 | 7d / 30d | Momo 快/慢同步 |
| shopee.fast / shopee.slow | 3 | 7d / 30d | Shopee 快/慢同步 |
| yahoo.fast / yahoo.slow | 3 | 7d / 30d | Yahoo 快/慢同步 |
| pchome.fast / pchome.slow | 3 | 7d / 30d | PChome 快/慢同步 |
| cyberbiz.fast / cyberbiz.slow | 3 | 7d / 30d | Cyberbiz 快/慢同步 |

### 業務 Topic（7 個）

| Topic | 分區 | Retention | 說明 |
|-------|------|-----------|------|
| scheduler | 1 | 1d | 脈衝 (Heartbeat) |
| order.process | 5 | 30d | 訂單待處理隊列（Compacted） |
| return.process | 5 | 30d | 退貨待處理隊列（Compacted） |
| task.backend | 3 | 7d | 後端異步任務 |
| task.frontend | 3 | 7d | 前端非同步任務（轉發至 task.backend） |
| task.failed | 3 | 30d | 失敗重試隊列 |
| task.dlt | 3 | 90d | 死信隊列（Compacted） |

---

## Producer/Consumer 責任矩陣

### 按 Topic 分類（多生產者視角）

#### {platform}.slow Topic
| Producer | TaskType | 目的 |
|----------|----------|------|
| SchedulerConsumer | FETCH_ORDERS | 定時抓訂單列表 |
| Channel Job Slow | FETCH_ORDER_DETAIL | Mode B 取訂單詳情 |
| UI/Gateway | SYNC_PACK | 手動打包同步 |

**Consumer**: Channel Job Slow
- 根據 taskType 決策路由
- Mode A: FETCH_ORDERS → 直接發 order.process
- Mode B: FETCH_ORDERS → FETCH_ORDER_DETAIL → 發 order.process

#### task.backend Topic
| Producer | TaskType | 目的 |
|----------|----------|------|
| SchedulerConsumer | HEALTH_CHECK, GENERATE_REPORT | 定時檢查、報表 |
| Channel Job Fast | SYNC_PRODUCT, UPDATE_INVENTORY | 商品同步後 |
| OrderUpsertHandler | GENERATE_SHIPMENT | 訂單入庫後 |
| ReturnUpsertHandler | PROCESS_RETURN | 退貨入庫後 |
| UI/Gateway | UI_EVENT | 前台事件 |

**Consumer**: BackendTaskHandler
- 根據 taskType 決策實際操作

---

### 完整責任矩陣

| Entity | 生產 Topic (+ TaskType) | 消費 Topic | 角色 |
|--------|-----------|-----------|------|
| **HeartbeatJob** | scheduler | - | 時間源 |
| **SchedulerConsumer** | {platform}.slow (FETCH_ORDERS, SYNC_PRODUCT), task.backend (HEALTH_CHECK) | scheduler | 決策中樞 |
| **Channel Job Fast** | task.backend (SYNC_PRODUCT, UPDATE_INVENTORY) | {platform}.fast | 商品同步 |
| **Channel Job Slow** | order.process, return.process, {platform}.slow (FETCH_ORDER_DETAIL) | {platform}.slow | 訂單抓取 (Mode A/B) |
| **OrderUpsertHandler** | task.backend (GENERATE_SHIPMENT), task.failed | order.process | 訂單入庫 |
| **ReturnUpsertHandler** | task.backend (PROCESS_RETURN), task.failed | return.process | 退貨入庫 |
| **BackendTaskHandler** | - | task.backend | 非同步任務 |
| **ErrorHandler** | {原topic}, task.dlt | task.failed | 重試管理 |
| **DltHandler** | - | task.dlt | 死信終結點 |
| **UI/Gateway** | {platform}.slow (SYNC_PACK), {platform}.fast | - | 同步入口 |

---

**上次更新**：2026-02-20
**版本**：1.0 - PLAN Phase I Release
