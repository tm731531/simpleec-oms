# 事件流完整架構 - SimpleEC OMS

## 系統架構概覽

```
┌─────────────────────────────────────────────────────────────────┐
│                    SimpleEC OMS 事件流架構                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Layer A (Foundation)                                            │
│  ├─ SchedulerJob (10.0.0.8:8083) → scheduler.heartbeat         │
│  ├─ RetryJob (10.0.0.10:8087) → task.failed + task.dlt         │
│                                                                   │
│  Layer B (Data Processing)                                       │
│  ├─ OrderJob (10.0.0.9:8085) → order.process + return.process  │
│  ├─ BackendJob (10.0.0.7:8084) → task.backend                  │
│  ├─ FrontendJob (10.0.0.11:8089) → task.frontend               │
│                                                                   │
│  Layer C (Channel Integration)                                   │
│  ├─ ChannelJob (10.0.0.6:8080) → [6 platform topics]           │
│     ├─ cyberbiz.{fast,slow}                                     │
│     ├─ momo.{fast,slow}                                         │
│     ├─ pchome.{fast,slow}                                       │
│     ├─ shopee.{fast,slow}                                       │
│     ├─ yahoo.{fast,slow}                                        │
│     └─ easystore.{fast,slow}                                    │
│                                                                   │
│  Kafka Broker (simpleec-kafka:9092)                             │
│  └─ 14 Topics + 11 Consumer Groups                              │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

## Consumer Groups 完整列表

| Consumer Group | Service | Topics | Partitions | Status |
|---|---|---|---|---|
| channel-job-group | ChannelJob | cyberbiz.slow, shopee.slow, ... | auto | ✅ Active |
| channel-job-pchome-fast | ChannelJob | pchome.fast | auto | ✅ Active |
| channel-job-pchome-slow | ChannelJob | pchome.slow | auto | ✅ Active |
| channel-job-shopee-fast | ChannelJob | shopee.fast | auto | ✅ Active |
| channel-job-shopee-slow | ChannelJob | shopee.slow | auto | ✅ Active |
| channel-job-yahoo-fast | ChannelJob | yahoo.fast | auto | ✅ Active |
| retry-job-group | RetryJob | task.failed | auto | ✅ Active |
| dlt-consumer-group | RetryJob | task.dlt | auto | ✅ Active |
| backend-consumer-group | BackendJob | task.backend | auto | ✅ Active |
| frontend-job-group | FrontendJob | task.frontend | auto | ✅ Active |
| scheduler-dispatcher-group-v3 | SchedulerJob | scheduler | auto | ✅ Active |
| order-job-group | OrderJob | order.process, return.process | auto | ✅ Active |

## Kafka Topics 完整映射

### Platform 主題 (12個)
```
cyberbiz.fast   ← Cyberbiz API 快速查詢
cyberbiz.slow   ← Cyberbiz API 慢速查詢（庫存、價格）

momo.fast       ← MOMO 快速查詢
momo.slow       ← MOMO 慢速查詢

pchome.fast     ← PChome 快速查詢
pchome.slow     ← PChome 慢速查詢

shopee.fast     ← Shopee 快速查詢
shopee.slow     ← Shopee 慢速查詢

yahoo.fast      ← Yahoo 購物中心 快速查詢
yahoo.slow      ← Yahoo 購物中心 慢速查詢

easystore.fast  ← EasyStore 快速查詢
easystore.slow  ← EasyStore 慢速查詢
```

### Order 處理主題 (2個)
```
order.process      ← OrderJob 消費: 訂單 UPSERT、狀態變更
return.process     ← BackendJob/OrderJob 消費: 退貨 UPSERT、狀態變更
```

### Task Types (非 Kafka Topic)
```
SYNC_PACK          ← BackendJob task type: 賣場同步、價格更新（在 task.backend 中傳遞）
UPDATE_PRICE       ← BackendJob task type: 價格更新（在 task.backend 中傳遞）
```

### Task 系統主題 (5個)
```
task.backend       ← BackendJob 消費: 後台任務（退貨、賣場同步）
task.frontend      ← FrontendJob 消費: 前端推送通知（WebSocket/SSE）
task.failed        ← RetryJob 消費: 失敗消息重試
task.dlt           ← RetryJob 消費: 死信隊列（最大重試達到）
task.retry         ← 重試任務隊列（預留）
```

### 系統主題 (1個)
```
scheduler          ← SchedulerJob 消費: 系統心跳、排程狀態
```

## 工作流程 (Data Flow)

### 1. 訂單擷取流程
```
┌─────────────────┐
│  SchedulerJob   │  (每 5 分鐘)
│  FETCH_ORDERS   │─────────────┐
└─────────────────┘             │
                                 ↓
            ┌──────────────────────────────────────┐
            │ cyberbiz.fast, momo.fast, ...        │
            │ (6 platform × 2 fast channels)       │
            └──────────────────────────────────────┘
                                 ↓
┌─────────────────┐
│  ChannelJob     │  (Layer C - Channel Integration)
│ 消費 & 適配器轉換 │  ├─ CyberbizAdapter
└─────────────────┘  ├─ MomoAdapter
                     ├─ PChomeAdapter
                     ├─ ShopeeAdapter
                     ├─ YahooAdapter
                     └─ EasystoreAdapter
       ↓
┌─────────────────┐
│ order.process   │  → ORDER_UPSERT
└─────────────────┘
       ↓
┌─────────────────┐
│  OrderJob       │  (Layer B - Data Processing)
│  UPSERT 邏輯    │  → INSERT/UPDATE orders table
└─────────────────┘
       ↓
   ✓ 訂單創建完成
```

### 2. 狀態變更流程
```
┌─────────────────┐
│  OrderJob       │
│ 監控訂單狀態     │  pending → confirmed → shipped → completed
└─────────────────┘
       ↓
┌─────────────────┐
│ order.process   │  → ORDER_STATUS_CHANGE
└─────────────────┘
       ↓
   ┌──────────────┴────────────────┐
   ↓                               ↓
┌──────────────┐         ┌─────────────────┐
│task.backend  │         │task.frontend    │
└──────────────┘         └─────────────────┘
   ↓                            ↓
┌──────────────┐         ┌─────────────────┐
│BackendJob    │         │FrontendJob      │
│(資料庫更新)  │         │(WebSocket推送)  │
└──────────────┘         └─────────────────┘
   ↓                            ↓
商業邏輯處理              客戶端實時通知
```

### 3. 退貨流程
```
┌─────────────────┐
│  SchedulerJob   │  (每 15 分鐘)
│ FETCH_RETURNS   │─────────────┐
└─────────────────┘             │
                                 ↓
        ┌──────────────────────────────────┐
        │ cyberbiz.slow, momo.slow, ...    │
        │ (6 platform × 2 slow channels)   │
        └──────────────────────────────────┘
                                 ↓
┌─────────────────┐
│  ChannelJob     │  RETURN_UPSERT 適配
└─────────────────┘
       ↓
┌─────────────────┐
│return.process   │  → RETURN_UPSERT
└─────────────────┘
       ↓
┌─────────────────┐
│  BackendJob     │  UPDATE return_orders table
└─────────────────┘
       ↓
   ✓ 退貨處理完成
```

### 4. 失敗重試流程
```
┌──────────────────────┐
│ 任何失敗的消息       │
│ (訂單、退貨、同步)   │
└──────────────────────┘
       ↓
┌──────────────────────┐
│ task.failed          │  (Max 3 retries)
│ 指數退避:1s→5s→30s  │
└──────────────────────┘
       ↓
┌──────────────────────┐
│  RetryJob           │  檢查 Redis retry count
├─────────────────────┤
│ 重試 < 3 ?          │
└──────────────────────┘
   ↙是       ↘否
   ↓         ↓
重新發送   task.dlt
原始topic  (死信隊列)
   ↓         ↓
處理      手動審核
        & 修復
```

## Job 服務詳細配置

### SchedulerJob (排程器)
- **啟動時間**: 應用啟動時自動啟動
- **主要職責**:
  - 發送 FETCH_ORDERS 到各通路（每 5 分鐘）
  - 發送 SYNC_INVENTORY（每 15 分鐘）
  - 發送 UPDATE_PRICE（每 1 小時）
  - 發送系統心跳（每 1 分鐘）
- **Kafka 角色**: Producer 和 Consumer
- **Consumer Group**: scheduler-dispatcher-group-v3

### ChannelJob (通路整合)
- **啟動時間**: 應用啟動時自動啟動
- **主要職責**:
  - 消費 12 個平台通路 topic
  - 調用平台 API（CyberbizAdapter、MomoAdapter 等）
  - 適配訂單/退貨到 OMS 格式
  - 發送 ORDER_UPSERT、RETURN_UPSERT 到 order.process 和 return.process
- **Kafka 角色**: Consumer 和 Producer
- **Consumer Groups**: channel-job-group + per-platform groups (6個)

### OrderJob (訂單處理)
- **啟動時間**: 應用啟動時自動啟動
- **主要職責**:
  - 消費 order.process 和 return.process
  - 執行訂單 UPSERT 邏輯（INSERT/UPDATE）
  - 發送狀態變更到 task.backend 和 task.frontend
  - 管理訂單生命週期
- **Kafka 角色**: Consumer 和 Producer
- **Consumer Group**: order-job-group

### BackendJob (後台處理)
- **啟動時間**: 應用啟動時自動啟動
- **主要職責**:
  - 消費 task.backend (from OrderJob)
  - 處理退貨 UPSERT、賣場同步、價格更新
  - 執行商業邏輯（資料庫更新）
  - 發送失敗消息到 task.failed（使用 error handler）
- **Kafka 角色**: Consumer
- **Consumer Group**: backend-consumer-group

### FrontendJob (前端推送)
- **啟動時間**: 應用啟動時自動啟動
- **主要職責**:
  - 消費 task.frontend (from OrderJob)
  - 通過 WebSocket/SSE 實時推送訂單狀態變更到客戶端
  - 按 merchantId 隔離消息（多租戶）
  - 格式化消息為前端友好的格式
- **Kafka 角色**: Consumer
- **Consumer Group**: frontend-job-group

### RetryJob (重試管理)
- **啟動時間**: 應用啟動時自動啟動
- **主要職責**:
  - 消費 task.failed (失敗消息)
  - 實現指數退避重試（1s, 5s, 30s）
  - 在 Redis 追蹤重試次數（24 小時過期）
  - 達到最大重試次數後發送到 task.dlt（死信隊列）
- **Kafka 角色**: Consumer 和 Producer
- **Consumer Groups**: retry-job-group + dlt-consumer-group (2個)

## 部署檢查清單

### 前置條件
- [ ] 6 個 Job 容器全部啟動
- [ ] Kafka broker 健康且可連接
- [ ] PostgreSQL 可連接
- [ ] Redis 可連接

### 驗證步驟
- [ ] `docker exec simpleec-kafka kafka-consumer-groups.sh --list` 顯示 11 個 groups
- [ ] 每個 Job 的日誌中顯示 "Started [ServiceName]Application"
- [ ] 每個 Job 的日誌中顯示 "Subscribed to topic"
- [ ] 無 "Connection refused" 或 "Timeout" 錯誤
- [ ] `__consumer_offsets` 主題存在

### 功能驗證
- [ ] 從 SchedulerJob 發送 FETCH_ORDERS 到平台 topic
- [ ] ChannelJob 消費平台消息並發送到 order.process
- [ ] OrderJob 消費 order.process 並寫入資料庫
- [ ] BackendJob 消費 task.backend 並執行邏輯
- [ ] FrontendJob 消費 task.frontend 並推送到客戶端
- [ ] RetryJob 捕獲失敗並重試

## 故障排查

### Consumer Group 無法註冊
1. 檢查 `__consumer_offsets` 主題存在
2. 檢查 Kafka broker 日誌：`docker logs simpleec-kafka`
3. 檢查 Job 應用日誌中的連接錯誤

### 消息無法消費
1. 檢查 Consumer Group 名稱是否正確
2. 檢查 Topic 是否存在：`kafka-topics.sh --list`
3. 檢查 Consumer lag：`kafka-consumer-groups.sh --describe --group [name]`
4. 檢查消息是否存在：`kafka-console-consumer.sh --from-beginning`

### Offset 問題
1. 重置 offset：`kafka-consumer-groups.sh --reset-offsets --group [name] --all-topics --to-earliest`
2. 檢查 offset commit 錯誤在應用日誌中
3. 驗證 `__consumer_offsets` 主題的 leader

## 性能指標

- **消息延遲**: < 100ms（平台 API 調用除外）
- **Throughput**: 每秒 1000+ 消息（單個 ChannelJob 實例）
- **消費者 lag**: 應小於 1000（表示實時處理）
- **Broker disk**: `__consumer_offsets` ~100MB（壓縮）

## 參考資源

- 詳細修復文件: `docs/KAFKA_CONSUMER_GROUPS_FIX_FEB23.md`
- Git Commit: `8fad1cd Fix Kafka consumer groups - restore event stream architecture`
- Docker Compose: `docker-compose.yml` (所有 6 個 Job 的容器定義)