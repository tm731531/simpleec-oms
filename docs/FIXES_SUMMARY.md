# Kafka Schema 映射修復完成報告

> 修復日期：2026-02-20
> 狀態：✅ 所有高優先級問題已解決

---

## 修復摘要

### 🔴 高優先級 6 個問題 → ✅ 全部解決

| # | 問題 | 位置 | 解決方案 | 文件 |
|----|------|------|---------|------|
| 1 | platformId 無 DB 對應 | PROCESS_ORDER | Handler 查詢 channel.platform_id | 無需改 schema |
| 3 | channelItemId 無法存儲 | PROCESS_ORDER.items | 保留在 items JSONB | 無需改 schema |
| 5 | requestDate 無對應 | PROCESS_RETURN | ✅ refund_orders.requested_at | schema.sql |
| 6 | order_id FK 不清 | PROCESS_RETURN | ✅ 消息添加 orderId | EVENT_SAMPLES.md |
| 8 | attributes 無處存 | SYNC_PRODUCT | ✅ 移到 SYNC_PACK（sell_pack.channel_spec_attrs） | schema.sql + EVENT_SAMPLES.md |
| 11 | channel_product_id 缺失 | SYNC_PACK | ✅ 消息添加 channelProductId | EVENT_SAMPLES.md |

---

## 詳細修復清單

### 1️⃣ refund_orders 表添加 requested_at

**修改**：docker/init-db/01-schema.sql
```sql
ALTER TABLE public.refund_orders
ADD COLUMN requested_at TIMESTAMPTZ;  -- 退貨申請時間
```

**對應**：PROCESS_RETURN.returnData.requestedAt

---

### 2️⃣ sell_pack 表添加 2 個欄位

**修改**：docker/init-db/01-schema.sql
```sql
ALTER TABLE public.sell_pack
ADD COLUMN channel_spec_attrs JSONB;  -- 通路特定規格（{color: 紅色, size: M}）
ADD COLUMN visibility VARCHAR(20);     -- 通路可見性（VISIBLE, HIDDEN）
```

**對應**：
- SYNC_PACK.channelSpecAttrs → sell_pack.channel_spec_attrs
- SYNC_PACK.packInfo.visibility → sell_pack.visibility

---

### 3️⃣ PROCESS_RETURN 消息添加 orderId

**修改**：docs/EVENT_SAMPLES.md
```json
{
  "body": {
    "orderId": "ord_xyz789",           // ← 添加此字段
    "channelReturnId": "YH-RET-...",
    "returnData": {
      "requestedAt": "2026-02-13T11:00:00Z"  // ← 改名 requestDate
    }
  }
}
```

**效果**：Handler 可直接使用 orderId FK，無需查詢

---

### 4️⃣ SYNC_PRODUCT 消息移除 attributes

**修改**：docs/EVENT_SAMPLES.md

**原始**（❌ 錯誤）：
```json
{
  "body": {
    "sku": "SH-SKU-001",
    "name": "iPhone 15 Pro Max",
    "price": 44900,
    "attributes": {              // ← 這是通路特定的！
      "color": "Space Black",
      "capacity": "256GB"
    }
  }
}
```

**修正後**（✅）：
```json
{
  "body": {
    "sku": "SH-SKU-001",
    "name": "iPhone 15 Pro Max",
    "costPrice": 35000,
    "suggestPrice": 44900
    // 無 attributes（那是 SYNC_PACK 的職責）
  }
}
```

**職責澄清**：
- SYNC_PRODUCT = 倉庫 SKU（我們有什麼）
- SYNC_PACK = 通路上架（平台怎麼賣它）

---

### 5️⃣ SYNC_PACK 消息添加 channelProductId

**修改**：docs/EVENT_SAMPLES.md

**新增字段**：
```json
{
  "body": {
    "channelProductId": "PC-2026021300001",    // ✅ 關鍵字段
    "channelSpecId": "SPEC-001",
    "channelProductName": "iPhone 15 Pro Max - Space Black 256GB",
    "channelSpecName": "太空黑 / 256GB",
    "sku": "PCHOME-SKU-001",
    "channelSpecAttrs": {                      // ✅ 通路特定規格
      "color": "Space Black",
      "capacity": "256GB"
    },
    "sellingPrice": 44900,
    "packInfo": {
      "packStatus": "ACTIVE",
      "visibility": "VISIBLE"
    }
  }
}
```

**對應關係**：
- channelProductId → sell_pack.channel_product_id
- channelSpecAttrs → sell_pack.channel_spec_attrs
- visibility → sell_pack.visibility

---

### 6️⃣ PLATFORM_MAPPING.md 澄清職責

**新增**：TaskType 職責澄清表

| TaskType | 職責 | 存儲位置 |
|----------|------|--------|
| SYNC_PRODUCT | 商品通用元數據 | product 表 |
| SYNC_PACK | 通路特定配置 | sell_pack 表 |

---

## 數據庫 Schema 變更總結

### 新增欄位

| 表 | 欄位 | 類型 | 說明 |
|----|------|------|------|
| refund_orders | requested_at | TIMESTAMPTZ | 退貨申請時間（來自平台） |
| sell_pack | channel_spec_attrs | JSONB | 通路特定的規格屬性 |
| sell_pack | visibility | VARCHAR(20) | 通路上的可見性 |

### 無需修改 Schema

- ✅ orders.items (JSONB) - 已支持 channelItemId 存儲
- ✅ orders 表 - Handler 可通過 channel FK 查詢 platform_id

---

## Kafka 消息格式更新

### PROCESS_RETURN（已修正）
```diff
{
  "body": {
+   "orderId": "ord_abc123",
    "channelReturnId": "YH-RET-...",
    "returnData": {
-     "requestDate": "...",
+     "requestedAt": "...",
      "items": [...]
    }
  }
}
```

### SYNC_PRODUCT（已修正）
```diff
{
  "body": {
    "sku": "...",
    "name": "...",
-   "price": 44900,
+   "costPrice": 35000,
+   "suggestPrice": 44900,
-   "attributes": {...}  // ← 移除！
  }
}
```

### SYNC_PACK（已完善）
```diff
{
  "body": {
+   "channelProductId": "PC-2026021300001",
    "channelSpecId": "SPEC-001",
-   "packName": "...",
+   "channelProductName": "...",
+   "channelSpecName": "...",
    "sku": "...",
-   "attributes": {...},
+   "channelSpecAttrs": {...},
-   "price": 44900,
+   "sellingPrice": 44900,
    "packInfo": {
      "packStatus": "ACTIVE",
+     "visibility": "VISIBLE"
    }
  }
}
```

---

## 中優先級問題狀態

| # | 問題 | 狀態 |
|----|------|------|
| 2 | shipped_at 在消息中缺失 | ✅ 正常（由 SHIP_ORDER 更新） |
| 7 | returnData.items 信息不完整 | ⏳ 待改進（應包含 channelProductId） |
| 12 | visibility 無處存 | ✅ 已解決（sell_pack.visibility） |
| 13 | logistics_company 需推導 | ⏳ 待映射表（shippingMethod → company） |
| 14 | shipping_status 未設置 | ⏳ 待改進（消息應包含此字段） |

---

## 低優先級問題狀態

| # | 問題 | 狀態 |
|----|------|------|
| 4 | items JSONB 模式未定義 | ⏳ 待文檔補充 |
| 9 | URL/title 缺失 | ⏳ 待 API 補充 |

---

## 文件修改清單

### 已修改（高優先級修復）
- ✅ docker/init-db/01-schema.sql（3 個欄位添加）
  - refund_orders.requested_at（TIMESTAMPTZ）
  - sell_pack.channel_spec_attrs（JSONB）
  - sell_pack.visibility（VARCHAR）
- ✅ docs/EVENT_SAMPLES.md（3 個 Kafka 消息更新）
  - PROCESS_RETURN: 添加 orderId，重命名 requestDate → requestedAt
  - SYNC_PRODUCT: 移除 attributes，改用 costPrice/suggestPrice
  - SYNC_PACK: 添加 channelProductId，新增 channelSpecAttrs
- ✅ docs/PLATFORM_MAPPING.md（職責澄清 + 架構更新）
  - Product vs SellPack 職責邊界明確化
  - SellPack 支持孤立上架（productId nullable）
  - SKU 作為可選的匹配橋樑
- ✅ docs/FIX_PLAN_HIGH_PRIORITY.md（執行計劃）
- ✅ docs/FIX_PLAN_MEDIUM_LOW_PRIORITY.md（7 個剩餘問題詳細分析）

### 待處理（低優先級）
- ⏳ KAFKA_SCHEMA_VALIDATION.md（待更新驗證狀態，可選）
- ⏳ DOCUMENTATION_ALIGNMENT.md（待標記已解決，可選）

---

## 驗證結論

### ✅ 高優先級完成度：100% (6/6)
- 所有關鍵字段映射問題已解決
- Schema 和消息定義現在一致
- 所有修改已提交到 git

### ⏳ 中優先級分析完成：5 個
- 詳細分析文檔已生成（FIX_PLAN_MEDIUM_LOW_PRIORITY.md）
- Issue 2 驗證為正確設計 ✅
- Issue 7,14 建議修改消息樣本（容易）
- Issue 13 建議創建新映射表（中等難度）
- **狀態**：待用戶決定是否實施

### 💡 低優先級分析完成：2 個
- Issue 4: JSONB 結構文檔補充（純文檔）
- Issue 9: API 字段說明（純文檔）
- **狀態**：可選，不影響系統功能

---

**修復完成日期**：2026-02-20
**驗證人**：Claude Code
**當前狀態**：高優先級修復完成 + 中低優先級計劃文檔完成

## 後續行動建議

### 選項 A：進入實現階段（推薦）
- ✅ 基礎架構已驗證和修正
- ✅ Kafka 消息 Schema 已對齊 Database
- ✓ 開始 Handler/Adapter 代碼開發
- 可將 Cyberbiz API 集成作為第一個適配器測試

### 選項 B：先修復中優先級問題
- 實施 Issue 7, 14（消息增強）— 30 分鐘
- 實施 Issue 13（物流映射表）— 2 小時
- 再進入實現階段

### 選項 C：補充低優先級文檔
- 添加 JSONB 結構定義和 SQL 查詢示例
- 補充 API 字段說明
- 無代碼改動，純文檔增強

