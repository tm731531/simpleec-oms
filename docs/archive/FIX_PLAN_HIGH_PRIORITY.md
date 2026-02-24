# 修復高優先級 6 個問題 - 執行計劃

> 修復時間：2026-02-20

## 問題 1️⃣: platformId 無數據庫對應 (PROCESS_ORDER)

**現象**：Kafka 消息有 `header.platformId`，但 orders 表無此字段

**解決方案**：
- ✅ **建議方案**：Handler 從 channel FK 查詢 platform_id，無需修改 schema
- orders 表已有 channel_id FK，可通過 JOIN 獲取 platform_id
- 代碼層面處理，不涉及 schema 修改

**狀態**：🟢 無需修改

---

## 問題 3️⃣: channelItemId 無法存儲 (PROCESS_ORDER.items)

**現象**：EVENT_SAMPLES 中有 `channelItemId`，但無專用數據庫字段

**解決方案**：
- ✅ **建議方案**：保留在 items JSONB 中
- `orders.items` 已是 JSONB，channelItemId 作為 items 中的一個字段
- 無需修改 schema

**狀態**：🟢 無需修改

---

## 問題 5️⃣: requestDate 無數據庫對應 (PROCESS_RETURN)

**現象**：EVENT_SAMPLES 中 `returnData.requestDate`，但 refund_orders 表無此字段

**解決方案**：
- ❌ **需要修改 schema**：為 refund_orders 表添加 `requested_at TIMESTAMPTZ` 字段
- 位置：refund_orders 表，在 reason 之後

**修改內容**：
```sql
ALTER TABLE public.refund_orders
ADD COLUMN requested_at TIMESTAMPTZ;
```

**狀態**：🔴 需要修改 schema

---

## 問題 6️⃣: order_id FK 映射不清晰 (PROCESS_RETURN)

**現象**：PROCESS_RETURN 消息無法直接關聯到 orders 表

**解決方案**：
- ✅ **建議方案**：消息應包含原訂單的 channelOrderId
- Handler 通過 `channel_id + channelOrderId` 查詢 orders 表
- 或直接在消息中帶入 orderId（推薦）

**消息修改**：
```json
{
  "body": {
    "orderId": "ord_abc123",           // ← 添加此字段
    "channelReturnId": "YH-RET-...",
    "returnData": {...}
  }
}
```

**狀態**：🟡 需要修改 EVENT_SAMPLES

---

## 問題 8️⃣: attributes 無處存儲 (SYNC_PRODUCT)

**現象**：EVENT_SAMPLES 中 SYNC_PRODUCT 有 `attributes`，但 product 表無此字段

**分析**：
- attributes 是**通路特定的規格**（如「紅色/M」）
- Product 表是我們的倉庫 SKU，不應包含通路特定的 attributes
- attributes 應該存在 **sell_pack 表**，而非 SYNC_PRODUCT

**解決方案**：
- ✅ **建議方案**：SYNC_PRODUCT 消息不應包含 attributes
- attributes 應該由 SYNC_PACK 消息提供（通路特定）
- SYNC_PRODUCT 只負責：sku, name, price, cost_price 等通用信息

**修改內容**：
- 移除 SYNC_PRODUCT 消息中的 attributes
- 在 SYNC_PACK 消息中保留 attributes（已有）
- 修改 sell_pack 表添加 attributes JSONB 字段

**sell_pack 表修改**：
```sql
ALTER TABLE public.sell_pack
ADD COLUMN channel_spec_attrs JSONB;  -- 通路特定的規格屬性
```

**狀態**：🟡 需要修改 schema + EVENT_SAMPLES

---

## 問題 11️⃣: channel_product_id 缺失 (SYNC_PACK)

**現象**：SYNC_PACK 消息缺少 `channelProductId`，但這是 sell_pack.channel_product_id 的 Unique Key 一部分

**解決方案**：
- ❌ **需要修改 EVENT_SAMPLES**：添加 channelProductId 字段

**消息修改**：
```json
{
  "body": {
    "platformId": "pchome",
    "channelProductId": "PC-2026021300001",  // ← 添加此字段
    "specId": "SPEC-001",
    "packName": "iPhone 15 Pro Max - Space Black 256GB",
    ...
  }
}
```

**狀態**：🟡 需要修改 EVENT_SAMPLES

---

## 修改清單

### 需要修改的文件

| 文件 | 修改內容 | 優先級 |
|------|--------|--------|
| docker/init-db/01-schema.sql | 為 refund_orders 添加 requested_at；為 sell_pack 添加 channel_spec_attrs | 🔴 必須 |
| docs/EVENT_SAMPLES.md | PROCESS_RETURN 添加 orderId；SYNC_PRODUCT 移除 attributes；SYNC_PACK 添加 channelProductId | 🟡 應該 |
| docs/PLATFORM_MAPPING.md | 澄清 SYNC_PRODUCT vs SYNC_PACK 的職責分工 | 🟡 應該 |
| docs/KAFKA_SCHEMA_VALIDATION.md | 更新驗證結果，標記已解決的問題 | 🟡 應該 |

### 不需要修改的文件（已支持）

- ✅ orders 表（已支持 JSONB items，channelItemId 存儲於其中）
- ✅ Handler 代碼（可直接查詢 platform_id）

---

## 修改順序

1. **第 1 步**：修改 schema.sql（2 個 ALTER TABLE）
2. **第 2 步**：修改 EVENT_SAMPLES.md（3 個 Kafka 消息修改）
3. **第 3 步**：修改 PLATFORM_MAPPING.md（職責澄清）
4. **第 4 步**：修改 KAFKA_SCHEMA_VALIDATION.md（更新驗證狀態）

---

**狀態**：準備就緒，開始執行修改
