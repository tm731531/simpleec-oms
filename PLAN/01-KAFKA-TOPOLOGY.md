# SimpleEC OMS Kafka 拓撲設計

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

## 16 個 Kafka Topic 設定

### 通路 Topic（10 個）

| Topic | 分區 | Retention | 說明 |
|-------|------|-----------|------|
| momo.fast / momo.slow | 3 | 7d / 30d | Momo 快/慢同步 |
| shopee.fast / shopee.slow | 3 | 7d / 30d | Shopee 快/慢同步 |
| yahoo.fast / yahoo.slow | 3 | 7d / 30d | Yahoo 快/慢同步 |
| pchome.fast / pchome.slow | 3 | 7d / 30d | PChome 快/慢同步 |
| cyberbiz.fast / cyberbiz.slow | 3 | 7d / 30d | Cyberbiz 快/慢同步 |

### 業務 Topic（6 個）

| Topic | 分區 | Retention | 說明 |
|-------|------|-----------|------|
| scheduler | 1 | 1d | 脈衝 (Heartbeat) |
| order.process | 5 | 30d | 訂單待處理隊列（Compacted） |
| return.process | 5 | 30d | 退貨待處理隊列（Compacted） |
| task.backend | 3 | 7d | 後端異步任務 |
| task.failed | 3 | 30d | 失敗重試隊列 |
| task.dlt | 3 | 90d | 死信隊列（Compacted） |

---

## Producer/Consumer 責任矩陣

| Entity | 生產 Topic | 消費 Topic | 角色 |
|--------|-----------|-----------|------|
| **HeartbeatJob** | scheduler | - | 時間源 |
| **SchedulerConsumer** | {platform}.slow, task.backend | scheduler | 決策中樞 |
| **Channel Job Fast** | task.backend | {platform}.fast | 商品同步 |
| **Channel Job Slow** | order.process, return.process | {platform}.slow | 訂單抓取 (Mode A/B) |
| **OrderUpsertHandler** | task.backend, task.failed | order.process | 訂單入庫 |
| **ReturnUpsertHandler** | task.backend, task.failed | return.process | 退貨入庫 |
| **BackendTaskHandler** | - | task.backend | 非同步任務 |
| **ErrorHandler** | {原topic}, task.dlt | task.failed | 重試管理 |
| **DltHandler** | - | task.dlt | 死信終結點 |

---

**上次更新**：2026-02-20
**版本**：1.0 - PLAN Phase I Release
