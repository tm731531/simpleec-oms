# SimpleEC OMS 部署指南

## 快速啟動 (一行命令)

```bash
cd /home/tom/ONEEC/simpleec-oms
docker-compose -f docker-compose.full.yml up -d
```

## 系統架構

### 11 個 OMS 模塊

| 模塊 | 端口 | 職責 |
|------|------|------|
| **scheduler-job** | 8083 | 時間驅動的任務調度引擎 |
| **channel-job** | 8084 | 通路訂單同步 (Shopify/Shopee/Easystore) |
| **order-job** | 8085 | 訂單入庫 + 雙層去重 |
| **backend-job** | 8086 | 產品同步、庫存同步、報表生成 |
| **retry-job** | 8087 | 失敗重試 + 死信隊列處理 |
| **gateway** | 8088 | Webhook 入口（接收平台推送） |
| **frontend-job** | 8089 | UI 事件處理（出貨、退貨等） |
| **api** | 8082 | REST API（訂單/產品/退貨查詢） |

### 基礎設施

| 服務 | 端口 | 用途 |
|------|------|------|
| PostgreSQL | 5432 | 數據持久化 |
| Redis | 6379 | 訂單去重快取 |
| Kafka | 9092 | 消息隊列 |
| Zookeeper | 2181 | Kafka 協調器 |
| Kafka UI | 8080 | Kafka 管理界面 |
| PgAdmin | 5050 | 數據庫管理界面 |

## 消息流轉 (完整鏈路)

```
1. HeartbeatJob (每秒)
   ↓ scheduler topic
2. SchedulerConsumer (分鐘路由)
   ↓ {platform}.slow / {platform}.fast topics
3. ChannelJobConsumer
   ├─ Mode A: ModeAOrderListHandler
   └─ Mode B: ModeBOrderListHandler + ModeBOrderDetailHandler
   ↓ order.process topic
4. OrderUpsertConsumer (雙層去重)
   ↓ 數據庫存儲
```

## API 端點

### 訂單 API
```bash
# 查詢訂單列表
GET /api/orders?merchantId=M001&page=0&size=10&status=PENDING

# 查詢單筆訂單
GET /api/orders/{orderId}

# 建立訂單
POST /api/orders
Content-Type: application/json

# 更新訂單狀態
PATCH /api/orders/{orderId}
```

### 產品 API
```bash
# 查詢產品列表
GET /api/products?page=0&size=10

# 按 SKU 查詢
GET /api/products/sku/{sku}?merchantId=M001

# 低庫存警告
GET /api/products/low-stock
```

### 退貨 API
```bash
# 查詢退貨列表
GET /api/returns?merchantId=M001&status=PENDING

# 批准退貨
POST /api/returns/{returnId}/approve

# 拒絕退貨
POST /api/returns/{returnId}/reject
```

## Webhook 端點

```bash
# Shopify webhook
POST /webhook/shopify/orders/create
POST /webhook/shopify/orders/update

# Shopee webhook
POST /webhook/shopee/orders/create

# Easystore webhook
POST /webhook/easystore/orders/update
```

## 監控 & 管理

### Kafka UI
http://localhost:8080
- 查看所有 topic
- 監控 consumer group
- 檢查消息內容

### PgAdmin
http://localhost:5050
- 數據庫管理
- SQL 查詢
- 使用者: admin@simpleec.local
- 密碼: simpleec123

## 關鍵 Kafka Topics

```
# 核心業務 topics
scheduler              # HeartbeatJob 心跳
order.process         # ORDER_UPSERT 消息
return.process        # RETURN_UPSERT 消息
task.backend          # 後端任務 (同步、報表)
task.failed           # 失敗重試隊列
task.dlt              # 死信隊列 (人工檢查)

# 平台通道 topics (Mode A)
shopify.slow          # Shopify 訂單同步
easystore.slow        # Easystore 訂單同步

# 平台通道 topics (Mode B)
shopee.slow           # Shopee 訂單列表
shopee.detail         # Shopee 訂單詳情
```

## 常見操作

### 查看日誌
```bash
docker logs -f simpleec_scheduler_job
docker logs -f simpleec_channel_job
docker logs -f simpleec_order_job
```

### 檢查數據庫
```bash
# 進入 PostgreSQL
docker exec -it simpleec_postgres psql -U simpleec -d simpleec

# 查詢訂單
SELECT * FROM orders ORDER BY created_at DESC LIMIT 10;

# 查詢產品
SELECT * FROM products;
```

### 停止所有服務
```bash
docker-compose -f docker-compose.full.yml down
```

### 清空數據並重新啟動
```bash
docker-compose -f docker-compose.full.yml down -v
docker-compose -f docker-compose.full.yml up -d
```

## 連接字符串

| 服務 | 連接字符串 |
|------|----------|
| PostgreSQL | `postgresql://simpleec:simpleec123@localhost:5432/simpleec` |
| Redis | `redis://localhost:6379` |
| Kafka | `localhost:9092` |

## 性能目標

- **訂單吞吐**: 50+ 訂單/分鐘
- **API 延遲**: < 500ms
- **去重精確度**: 100% (雙層去重)
- **重試成功率**: 95%+ (4 次重試)

## 故障排查

### Kafka Consumer 落後
```bash
# 進入 Kafka UI (http://localhost:8080)
# 檢查 Consumer Group 的 lag
# 增加 consumer 並發數: concurrency=8
```

### 訂單未入庫
```bash
# 檢查 order.process topic
# 查看 OrderUpsertConsumer 日誌
# 驗證 Redis 連接
```

### 高 DLT (Dead Letter Topic) 率
```bash
# 檢查 task.dlt topic 內容
# 進入 PgAdmin 查看錯誤日誌
# 需要人工介入審查
```

## 性能調優

### 增加消費者並發
在 consumer 添加: `@KafkaListener(concurrency = "8")`

### Kafka Topic 分區數
默認 8 個分區，支援 8 個並發 consumer

### 數據庫連接池
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
```

### Redis 優化
- 使用 Redis 集群提高可用性
- 定期監控記憶體使用
- 設置合適的 TTL (默認 7 天)

## 擴展性

### 增加新的通路平台
1. 實現 ChannelAdapter 接口
2. 創建對應的 Adapter (Mode A 或 Mode B)
3. 在 ChannelAdapterConfig 註冊 Bean
4. 在 ChannelJobConsumer 添加 @KafkaListener

### 增加新的報表類型
1. 創建 ReportHandler (如 OrderReportHandler)
2. 注入到 BackendJobConsumer
3. 添加 switch case 處理新的 taskType

---

**部署日期**: 2024-02-20
**版本**: 1.0.0
**作者**: SimpleEC Team
