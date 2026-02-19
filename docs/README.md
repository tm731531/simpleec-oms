# SimpleEC OMS 文檔指南

本目錄包含 SimpleEC OMS 系統的完整設計文檔。根據使用場景選擇對應的文檔。

---

## 📋 文檔導航

### 第一階段：理解系統架構

**這些文檔是系統設計的基礎，務必先讀。**

1. **[CORE_CONTRACTS.md](CORE_CONTRACTS.md)** ⭐
   - 核心事件流契約
   - 統一的 Header/Body 訊息結構
   - 所有 16 個 Topic 的定義
   - TaskType 分類（訂單、退貨、商品）
   - 冪等性保證原則
   - **先讀這個** — 了解系統的基本契約

2. **[EVENT_SAMPLES.md](EVENT_SAMPLES.md)** ⭐
   - 所有事件的具體 JSON 樣本
   - Channel Topics 的操作範例（10個 fast/slow）
   - Business Topics 的訊息範例（6個）
   - TaskType → Handler 完整路由表
   - **必讀** — 開發時的參考標準

3. **[QUEUE_CONSUMER_DESIGN.md](QUEUE_CONSUMER_DESIGN.md)** ⭐
   - Consumer Group 設計（10個 Channel + 6個 Business）
   - 每個 Consumer 的詳細行為邏輯
   - Scheduler 策略示例
   - Consumer 拓撲圖
   - 監控指標
   - **必讀** — 了解數據流向和処理邏輯

### 第二階段：實施與開發

**根據你的角色選擇對應的文檔。**

#### Channel Job 開發

4. **[CHANNEL_IMPLEMENTATION_GUIDE.md](CHANNEL_IMPLEMENTATION_GUIDE.md)**
   - Channel Job 的職責界定
   - 通路適配器 (Adapter) 實作模式
   - 分頁策略（Cursor-based vs Offset-based）
   - Rate Limiting 實作
   - 錯誤處理和重試策略
   - 訊息發送模式和批次優化
   - 健康檢查和監控
   - 測試策略（Mock vs 整合測試）
   - **Docker Compose 配置**：獨立的 fast/slow Consumer Group
   - **必讀** — 開發 Channel Job 時參考

#### Order/Return Process 開發

5. **[HANDLER_REGISTRY.md](HANDLER_REGISTRY.md)**
   - TaskType 到 Handler Class 的對應
   - 每個 Handler 的職責
   - Handler 實作框架
   - **必讀** — 開發 Process Handler 時參考

#### 去重和緩存

6. **[REDIS_DEDUPLICATION.md](REDIS_DEDUPLICATION.md)**
   - Redis Key 設計：`order:hash:{merchantId}:{channelId}:{orderId}`
   - **特殊字元完整保留**（不做任何轉換）
   - Channel Job：只讀 Redis（判斷是否需要詳情）
   - Process Job：讀寫 Redis + DB（確保同步）
   - Hash 計算策略
   - 資料一致性保證
   - **重要** — 訂單去重的關鍵設計

### 第三階段：部署與維運

7. **[DOCKER_GUIDE.md](DOCKER_GUIDE.md)**
   - 各服務的 Docker 容器配置
   - 網路設定
   - 數據卷掛載
   - **參考** — 部署時使用

8. **[OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md)**
   - 常見運維問題
   - 故障排查指南
   - 監控告警設定
   - **參考** — 生產環境維護

### 補充參考資料

9. **[DATA_FLOW_MAPPING.md](DATA_FLOW_MAPPING.md)**
   - 完整的數據流轉過程
   - 通路 API → Kafka Message → DB Entity 的對應
   - 狀態轉換映射表
   - **參考** — 理解數據如何流經系統

10. **[SCHEMA.md](SCHEMA.md)**
    - 資料庫表結構
    - 字段定義和約束
    - **參考** — 數據庫設計參考

11. **[STATISTICS_DESIGN.md](STATISTICS_DESIGN.md)**
    - 統計和報表設計
    - **參考** — 分析功能實作

---

## 🔗 文檔依賴關係

```
CORE_CONTRACTS.md (基礎)
    ↓
    ├─→ EVENT_SAMPLES.md (具體範例)
    │       ↓
    │       └─→ QUEUE_CONSUMER_DESIGN.md (處理邏輯)
    │              ├─→ CHANNEL_IMPLEMENTATION_GUIDE.md
    │              └─→ HANDLER_REGISTRY.md
    │
    ├─→ REDIS_DEDUPLICATION.md (去重策略)
    │
    ├─→ DATA_FLOW_MAPPING.md (數據流)
    │
    └─→ SCHEMA.md (資料庫設計)

CHANNEL_IMPLEMENTATION_GUIDE.md
    ↓
    └─→ DOCKER_GUIDE.md (部署)

OPERATIONS_RUNBOOK.md (維運)
```

---

## 📌 快速查找

### 我要開發 Channel Job
1. 先讀：CORE_CONTRACTS.md → EVENT_SAMPLES.md
2. 實施指南：CHANNEL_IMPLEMENTATION_GUIDE.md
3. 參考：DOCKER_GUIDE.md

### 我要開發 Order Process Handler
1. 先讀：CORE_CONTRACTS.md → QUEUE_CONSUMER_DESIGN.md
2. Handler 設計：HANDLER_REGISTRY.md
3. 去重策略：REDIS_DEDUPLICATION.md
4. 參考：SCHEMA.md

### 我要部署到生產
1. 檢查：CHANNEL_IMPLEMENTATION_GUIDE.md (Docker 配置)
2. 部署：DOCKER_GUIDE.md
3. 維運：OPERATIONS_RUNBOOK.md

### 我要排查問題
1. 基礎：CORE_CONTRACTS.md, QUEUE_CONSUMER_DESIGN.md
2. 數據流：DATA_FLOW_MAPPING.md
3. 故障排查：OPERATIONS_RUNBOOK.md

---

## ⭐ 核心概念速查

### 訊息結構（3 層）
```json
{
  "header": {
    "taskType": "路由關鍵",
    "merchantId": "商家隔離",
    "channelId": "通路實例",
    "requestId": "唯一追蹤",
    "timestamp": "時間戳"
  },
  "body": { /* TaskType 特定資料 */ }
}
```
— 詳見：CORE_CONTRACTS.md 第 3 章

### Consumer Groups（16 個）
- 10 個 Channel Consumer Groups（5個平台 × fast/slow）
- 6 個 Business Consumer Groups（訂單、退貨、商品、庫存、錯誤、死信）
— 詳見：QUEUE_CONSUMER_DESIGN.md

### Redis 去重（特殊字元保留）
```
Key: order:hash:{merchantId}:{channelId}:{orderId}
例：order:hash:M001:SHOPIFY_001:1002#100
```
— 詳見：REDIS_DEDUPLICATION.md

### Channel Job 職責（僅 API 通訊）
```
✅ 只做：API 呼叫、分頁、Rate Limit、資料轉換
❌ 不做：存資料、業務邏輯、狀態管理
```
— 詳見：CHANNEL_IMPLEMENTATION_GUIDE.md 第 1 章

---

## 📊 已棄用或需要重新審視的文檔

以下文檔可能已過時，建議在使用前與架構師確認：

- `ABSTRACT_DESIGN.md` - 早期抽象設計
- `IMPLEMENTATION_PLAN.md` - 早期實施計畫
- `STATUS.md` - 歷史狀態記錄

---

## 💡 文檔維護

### 修改文檔時的注意事項

1. **同步修改跨文檔引用**
   - 修改 EVENT_SAMPLES.md 時，檢查 QUEUE_CONSUMER_DESIGN.md 是否需要更新
   - 修改 CORE_CONTRACTS.md 的 TaskType 時，同時更新 EVENT_SAMPLES.md 和 HANDLER_REGISTRY.md

2. **保持文檔一致性**
   - JSON 範例必須符合 CORE_CONTRACTS.md 的 Header/Body 結構
   - Consumer 設計必須對應 QUEUE_CONSUMER_DESIGN.md

3. **文檔版本控制**
   - 文檔更新應提交到 `docs-only` 分支
   - 重大設計變更應標記版本號

---

## 版本記錄

| 版本 | 日期 | 重點 |
|------|------|------|
| 1.0 | 2026-02-19 | 初版：完整事件流設計，Redis 去重，16 個 Consumer Groups |
| | | 修正 FETCH_ORDERS 設計，獨立 fast/slow Consumer Group |
| | | 移除不必要的 Redis 全量同步 |