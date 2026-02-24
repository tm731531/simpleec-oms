# SimpleEC OMS 實施順序與里程碑

## 5 個開發階段

### Phase I：基礎架構（4-5 週）
目標：完成 Layer 1 + 初步 Layer 2

**實施內容**：
```
✅ 建立 Gradle 多模組結構
✅ 設計資料庫 schema（19 張表）
✅ 實現 simpleec-common（Enum、DTO、工具）
✅ 實現 simpleec-core（Entity、Mapper、Service、Kafka config）
✅ 實現基本 Kafka 設定（16 個 topic bean）
✅ 實現 Redis 配置（Layer 1 + Layer 2 去重邏輯）
✅ 實現 simpleec-channel 介面 + 1 個適配器（Shopify）
```

**交付物**：
- 完整的 MySQL schema
- 5 個 JUnit 測試（Entity、Service、Kafka config）
- Shopify Adapter 單元測試

**關鍵里程碑**：
- 所有 entity 可序列化/反序列化
- Kafka producer/consumer 連線通過
- Redis 鎖機制可靠（分散式鎖測試）

---

### Phase II：Kafka 消費者基礎（3-4 週）
目標：完成所有 9 個 Consumer 的骨架 + 決策邏輯

**實施內容**：
```
✅ 實現 simpleec-scheduler-job
  - HeartbeatJob (每秒)
  - SchedulerConsumer (決策邏輯：minute % 5, 15, 30)
  - 發送到 {platform}.slow / task.backend

✅ 實現 simpleec-channel-job
  - ChannelJobFastConsumer (商品、庫存同步骨架)
  - ChannelJobSlowConsumer (訂單、退貨骨架)
  - Mode A/B 邏輯判定

✅ 實現 simpleec-order-job
  - OrderUpsertConsumer (order.process)
  - ReturnUpsertConsumer (return.process)
  - Redis Layer 2 分鎖機制

✅ 實現 simpleec-backend-job
  - BackendTaskConsumer (task.backend)
  - Handler 骨架（GENERATE_SHIPMENT 等）

✅ 實現 simpleec-retry-job
  - ErrorConsumer (task.failed)
  - DltConsumer (task.dlt)
  - 指數退避重試邏輯
```

**交付物**：
- 9 個 Consumer 能消費訊息（日誌輸出驗證）
- 決策邏輯覆蓋率 > 80%
- 集成測試 × 5（每個 job）

**關鍵里程碑**：
- 可在 Kafka 發訊息→ Consumer 成功消費→日誌確認
- 時間驅動決策邏輯驗證（模擬時間快進）
- 重試邏輯單元測試通過

---

### Phase III：通路適配（5-6 週）
目標：完成 5-6 個平台的 Adapter + 実装測試

**實施內容**：
```
✅ 補齊 simpleec-channel 適配器
  - ShopeeAdapter (Mode B)
  - MomoAdapter (Mode B)
  - YahooAdapter (Mode B)
  - PChomeAdapter (Mode B)
  - CyberbizAdapter (Mode B)
  - ShopifyAdapter → 補齊細節（Mode A）

✅ 各適配器實現
  - fetchOrders() / fetchOrderDetail()
  - fetchProducts() / fetchInventory()
  - shipOrder() / cancelOrder()

✅ 欄位映射
  - PlatformFieldMapper (規則執行)
  - 貨幣單位轉換
  - 日期格式轉換

✅ Webhook 入口
  - simpleec-gateway 實現
  - HmacVerifier（簽名驗證）
  - 各平台 webhook controller
```

**交付物**：
- 6 個 Adapter 單元測試（Mock API）
- Webhook 集成測試 × 6
- 欄位映射規則文檔

**關鍵里程碑**：
- 能模擬各平台 API 響應
- Webhook 簽名驗證無誤
- Mode A/B 邏輯在 Adapter 層驗證

---

### Phase IV：業務邏輯完善（4-5 週）
目標：實現訂單、退貨、庫存、報表等核心業務

**實施內容**：
```
✅ 訂單流程完善
  - OrderUpsertHandler 完整實現
  - 庫存扣減邏輯
  - 出貨單生成（simpleec-backend-job）

✅ 退貨流程
  - ReturnUpsertHandler 完整實現
  - 退貨單狀態機

✅ 商品同步
  - Channel Job Fast 實現
  - 商品表、SKU 表維護
  - 庫存快照機制

✅ 報表 & 統計
  - daily_statistics 表自動填入
  - 報表生成 Service
  - 排程固定時間生成

✅ 監控 & 告警
  - OTEL 埋點
  - Grafana 儀表板
  - 異常告警規則
```

**交付物**：
- 訂單流程端到端測試
- 退貨流程測試
- 報表生成測試
- 監控儀表板

**關鍵里程碑**：
- 訂單從 Kafka → DB 完整流程驗證
- 庫存正確扣減
- 日報表能正確生成

---

### Phase V：REST API + 優化（3-4 週）
目標：完成 simpleec-api + 性能優化 + 上線準備

**實施內容**：
```
✅ REST API 實現
  - 訂單查詢、詳情、新增
  - 退貨管理
  - 商品查詢
  - 庫存查詢
  - 統計報表
  - 手動同步端點

✅ 性能優化
  - Kafka batch 消費參數調優
  - Redis 快取策略優化
  - 資料庫索引優化
  - 連線池參數調優

✅ 容錯 & 高可用
  - Consumer group rebalance 測試
  - Broker 故障恢復測試
  - 手動死信隊列觸發機制

✅ 安全性
  - API 認證 (JWT)
  - 加密 PII 欄位驗證
  - SQL injection / XSS 防護
  - CORS 配置

✅ 上線準備
  - Docker image 建構
  - Kubernetes deployment 配置
  - 操作手冊
  - 上線檢查表
```

**交付物**：
- 完整 REST API 文檔（OpenAPI）
- 性能基準測試報告
- 上線檢查表（30+ 項）
- Docker/K8S 配置

**關鍵里程碑**：
- 壓力測試：1K msg/sec 無誤
- API 響應時間 < 200ms (p99)
- 零 manual intervention 自動化

---

## 各階段消耗資源估算

| Phase | 週數 | 人員 | 重點 |
|-------|-----|-----|------|
| I | 4-5 | 2-3 | 基礎穩固 |
| II | 3-4 | 3 | 訊息流驗證 |
| III | 5-6 | 3-4 | 適配器正確性 |
| IV | 4-5 | 2-3 | 業務邏輯完善 |
| V | 3-4 | 2 | 上線準備 |
| **合計** | **19-24 週** | **2-4 人月** | 4-5 個月交付 |

---

## 各階段測試計劃

### Phase I 測試
```
✅ 單元測試
  - Entity JPA 映射
  - Service 業務邏輯
  - Kafka serialization

✅ 集成測試
  - Kafka topic 建立驗證
  - Redis 連線驗證
  - 資料庫 schema 驗證

覆蓋率目標：> 70%
```

### Phase II 測試
```
✅ 單元測試
  - 決策邏輯（minute % 5）
  - 重試邏輯

✅ 集成測試
  - Consumer 消費訊息
  - Producer 發送訊息
  - Offset 正確提交

✅ 壓力測試
  - SchedulerConsumer 1000 msg/sec

覆蓋率目標：> 75%
```

### Phase III 測試
```
✅ 單元測試
  - 各 Adapter API 調用（Mock）

✅ 集成測試
  - Webhook 簽名驗證
  - 欄位映射正確性
  - Mode A/B 決策邏輯

✅ 端到端測試
  - Webhook → Channel Job → order.process 完整流

覆蓋率目標：> 80%
```

### Phase IV 測試
```
✅ 單元測試
  - 業務服務邏輯

✅ 集成測試
  - 訂單完整流程
  - 庫存快照機制
  - 報表生成

✅ 功能性測試
  - 5 平台訂單流程逐一驗證

覆蓋率目標：> 85%
```

### Phase V 測試
```
✅ 性能測試
  - 1K msg/sec
  - API 響應時間 < 200ms (p99)

✅ 負載測試
  - 突發流量：5K msg/sec

✅ 長運行測試
  - 24 小時持續運行
  - 無記憶體洩漏

✅ 災難恢復測試
  - Kafka broker 故障
  - Redis 故障
  - DB 連線逾時恢復

✅ 安全性測試
  - SQL injection 掃描
  - API 認證驗證
  - PII 加密驗證

覆蓋率目標：> 90%
```

---

## 關鍵風險與緩解措施

| 風險 | 影響 | 緩解 |
|------|------|------|
| 通路 API 變動 | 適配器失效 | 建立 API 監控；版本管理 |
| Kafka 訊息順序性 | 訂單重複/遺漏 | 使用 partition key (orderId)；測試順序性 |
| Redis 故障 | 去重失效 | 主從配置 + sentinel；定期快照 |
| 時區轉換錯誤 | 報表時間偏差 | 統一使用 UTC；轉換層測試 |
| 加密 PII 欄位 performance | 查詢變慢 | 只在展示層解密；緩存解密結果 |

---

## 上線清單（Phase V 完成後）

```
基礎設施
  ☐ Kubernetes cluster ready
  ☐ PostgreSQL 16 replication
  ☐ Kafka 3.7.1 KRaft mode
  ☐ Redis 7 sentinel

應用程式
  ☐ 所有 11 個模組编译通过
  ☐ Docker image 掃描無安全漏洞
  ☐ 單元測試覆蓋率 > 85%
  ☐ 集成測試全部通過

效能基準
  ☐ 吞吐量 > 1K msg/sec
  ☐ API 延遲 < 200ms (p99)
  ☐ 記憶體穩定 (< 2GB)

監控告警
  ☐ Grafana 儀表板完成
  ☐ 關鍵指標告警規則配置
  ☐ 日誌收集 (ELK) 就緒

文檔
  ☐ API 文檔 (OpenAPI/Swagger)
  ☐ 操作手冊
  ☐ 故障排查指南
  ☐ Schema 變更指南

上線前檢查
  ☐ 資料遷移計劃確認
  ☐ 回滾計劃確認
  ☐ 備份策略確認
  ☐ 灾難恢復訓練完成
```

---

**上次更新**：2026-02-20
**版本**：1.0 - PLAN Phase I Release
