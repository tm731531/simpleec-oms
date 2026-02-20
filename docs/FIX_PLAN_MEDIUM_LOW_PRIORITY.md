# 修復中優先級 5 個 + 低優先級 2 個問題 - 詳細計劃

> 文件日期：2026-02-20
> 狀態：待用戶優先級確認

---

## 概述

在 6 個高優先級問題完全解決後，目前有 7 個剩餘問題需要處理：
- **中優先級 5 個**：非阻塞但影響數據完整性或運營效率
- **低優先級 2 個**：文檔補充和 API 增強

本文檔分析每個問題的根本原因、影響範圍、和建議的解決方案。

---

## 中優先級問題 (5 個)

### 問題 2️⃣: shipped_at 在 PROCESS_ORDER 消息中缺失 — ✅ 已驗證無需修改

**現象**：PROCESS_ORDER Kafka 消息無 `shipped_at` 字段，但 order_shipments 表有此字段

**分析**：
- orders 表本身不存儲 shipped_at（訂單只記錄下單時間 created_at）
- shipped_at 屬於 order_shipments 表，由出貨流程（SHIP_ORDER taskType）設置
- 訂單建立時無法預知出貨時間
- PROCESS_ORDER 的責任是建立訂單，出貨後更新由 SHIP_ORDER 消息處理

**結論**：✅ 正常設計，無需修改

**對應關係**：
- PROCESS_ORDER (created_at) → orders.created_at（下單時間）
- SHIP_ORDER (shippedAt) → order_shipments.shipped_at（出貨時間）

---

### 問題 7️⃣: returnData.items 信息不完整 — ⏳ 需增強

**現象**：EVENT_SAMPLES.md 中 PROCESS_RETURN 的 returnData.items 缺少通路信息

**當前消息結構**：
```json
{
  "body": {
    "orderId": "ord_xyz789",
    "channelReturnId": "YH-RET-...",
    "returnData": {
      "requestedAt": "2026-02-13T11:00:00Z",
      "items": [
        {
          "productId": "pd_xyz789",
          "quantity": 1,
          "unitPrice": 44900.00,
          "reason": "商品瑕疵"
        }
      ]
    }
  }
}
```

**缺失字段分析**：
| 字段 | 用途 | 重要性 | 建議 |
|------|------|--------|------|
| `channelProductId` | 通路產品編碼 | 高 | ✅ 應添加（審計追蹤） |
| `channelSpecId` | 通路規格編碼 | 中 | ⏳ 可選（某些通路無規格） |
| `sku` | 我們的 SKU | 中 | ⏳ 可選（為了檢查產品匹配） |
| `quantity` | 退貨數量 | 必須 | ✅ 已有 |

**根本原因**：
- Handler 需要驗證退貨商品確實屬於該訂單
- 如無 channelProductId，無法直接查詢 order_items 中的商品記錄
- 需要依賴 productId + sku 二級查詢，容易出錯

**建議解決方案**：
在 PROCESS_RETURN 消息的 items 中添加 channelProductId（最小改動）：
```json
{
  "returnData": {
    "items": [
      {
        "productId": "pd_xyz789",
        "channelProductId": "YH-PROD-001",  // ← 添加此字段
        "quantity": 1,
        "unitPrice": 44900.00,
        "reason": "商品瑕疵"
      }
    ]
  }
}
```

**實現步驟**：
1. 修改 EVENT_SAMPLES.md PROCESS_RETURN 樣本（添加 channelProductId）
2. Handler 代碼在驗證時參考此字段（增強錯誤檢查）
3. 測試：模擬平台退貨消息，驗證 items 映射正確

**優先級**：中等 — 不修改不會系統崩潰，但修改能提升數據品質和可維護性

---

### 問題 13️⃣: logistics_company 無法推導 — ⏳ 需映射表

**現象**：order_shipments.logistics_company 在消息中無法直接獲取

**當前狀態**：
- PROCESS_ORDER 消息有 `shippingMethod`（如 "HOME_DELIVERY"、"STORE_PICKUP"）
- 但無對應的物流公司信息（如 "黑貓宅急便"、"新竹物流"）
- order_shipments 表要求 logistics_company 字段

**根本原因**：
- 每個通路使用不同的物流服務商名稱和編碼
- 需要通路特定的映射表：`shippingMethod → logistics_company_id / name`
- 對應關係不是全局統一，而是通路/商家級別

**建議解決方案**：

**方案 A：創建映射表（推薦）**
在 docker/init-db/01-schema.sql 添加：
```sql
-- 通路物流公司映射
CREATE TABLE public.channel_shipping_mapping (
    id VARCHAR(20) PRIMARY KEY,
    channel_id VARCHAR(20) NOT NULL,
    platform_shipping_method VARCHAR(100),  -- 通路提供的 shippingMethod
    logistics_company VARCHAR(100),         -- OMS 內部物流公司名稱
    logistics_company_code VARCHAR(50),     -- 物流公司編碼
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (channel_id) REFERENCES public.channel(id),
    UNIQUE (channel_id, platform_shipping_method)
);

-- 示例數據
INSERT INTO public.channel_shipping_mapping VALUES
('csp_001', 'ch_momo', 'HOME_DELIVERY', '黑貓宅急便', 'BLACKCAT'),
('csp_002', 'ch_momo', 'STORE_PICKUP', 'MOMO 超商取貨', 'MOMO_STORE'),
('csp_003', 'ch_pchome', 'HOME_DELIVERY', '新竹物流', 'XINDE'),
('csp_004', 'ch_pchome', 'STORE_PICKUP', 'PChome 門市', 'PCHOME_STORE');
```

**方案 B：Handler 代碼中硬編碼映射（簡易，不可擴展）**
```java
// 不建議 — 改通路映射需要重新部署代碼
Map<String, String> shippingMap = Map.of(
    "HOME_DELIVERY", "黑貓宅急便",
    "STORE_PICKUP", "超商取貨"
);
```

**實現步驟**（方案 A）**：
1. 在 docker/init-db/01-schema.sql 添加 channel_shipping_mapping 表
2. 修改 OrderUpsertHandler：JOIN channel_shipping_mapping 查詢 logistics_company
3. 若查詢失敗（未配置映射），設置為通路預設值或 null（不中斷流程）
4. 在運維文檔中記錄如何添加新通路的物流映射

**優先級**：中等 — 影響出貨單的物流公司記錄，重要但非致命

---

### 問題 14️⃣: shipping_status 未在 PROCESS_ORDER 消息中設置 — ⏳ 需增強

**現象**：PROCESS_ORDER 消息無 shipping_status，但 order_shipments 表需要此字段

**當前狀態**：
```json
{
  "body": {
    "shippingAddress": "台北市...",
    "shippingMethod": "HOME_DELIVERY",
    // 無 shippingStatus
  }
}
```

**order_shipments 表需要**：
```sql
shipping_status VARCHAR(50) NOT NULL  -- PENDING, SHIPPED, DELIVERED, CANCELLED
```

**根本原因**：
- PROCESS_ORDER 只記錄下單時的配送信息
- 此時訂單剛建立，還未出貨，shipping_status 應為 "PENDING"
- 出貨後由 SHIP_ORDER 消息更新為 "SHIPPED"

**建議解決方案**：

**方案 A：在 EVENT_SAMPLES.md 中添加 shippingStatus（推薦）**
```json
{
  "body": {
    "shippingAddress": "台北市...",
    "shippingMethod": "HOME_DELIVERY",
    "shippingStatus": "PENDING"  // ← 添加此字段
  }
}
```

**方案 B：Handler 代碼硬編碼初始值**
```java
// OrderUpsertHandler.java
order_shipments.shipping_status = "PENDING";  // 訂單建立時預設值
```

**方案 C 合併推薦**（最健壯）：
1. 修改 EVENT_SAMPLES.md，消息可選帶 shippingStatus
2. Handler 代碼優先使用消息值；若無則預設 "PENDING"
3. 預留空間讓通路提供初始 status（如某些通路直接提供預付出貨狀態）

**實現步驟**：
1. 修改 EVENT_SAMPLES.md PROCESS_ORDER 樣本（新增 shippingStatus 字段，標記為可選）
2. OrderUpsertHandler 修改：讀取 shippingStatus，無則預設 "PENDING"
3. 修改 SHIP_ORDER 消息樣本，確保其中 shippingStatus = "SHIPPED"
4. 單元測試：驗證 shippingStatus 正確流向和更新

**優先級**：中等 — 影響出貨追蹤，運營會關心，但可以用預設值兜底

---

### 問題 12️⃣: visibility 無處存儲 — ✅ 已完全解決

不涉及本次修復，visibility 已在 sell_pack.visibility 中解決。

---

## 低優先級問題 (2 個)

### 問題 4️⃣: order.items JSONB 模式未定義 — ⏳ 待文檔補充

**現象**：orders.items 是 JSONB 類型，但 EVENT_SAMPLES.md 未詳細定義其內部結構

**當前狀態**：
```sql
items JSONB NOT NULL  -- 什麼時候用？怎麼查詢？
```

```json
"items": [
  {
    "sku": "IPHONE-15-PRO-MAX",
    "productId": "pd_xyz789",
    "channelProductId": "MOMO-SKU-001",
    // ... 更多字段
  }
]
```

**缺失的文檔**：
- items 中每個字段的用途和類型
- 是否有必填字段
- 如何查詢 JSONB（SQL 示例）
- Handler 如何驗證 items 結構的完整性

**建議解決方案**：

在 EVENT_SAMPLES.md 或新增 docs/JSONB_SCHEMA.md，補充：
```markdown
## Order.items JSONB 結構定義

### 數據類型
```json
{
  "itemId": "string",              // 訂單行項目 ID（唯一）
  "sku": "string",                 // 我們的 SKU
  "productId": "string",           // 我們的產品 ID（可能為 null）
  "channelProductId": "string",    // 通路的產品ID（賣編）
  "channelSpecId": "string",       // 通路的規格ID
  "channelItemId": "string",       // 通路的訂單行ID（審計用）
  "channelProductName": "string",  // 通路展示的產品名稱
  "channelSpecName": "string",     // 通路展示的規格名稱
  "productName": "string",         // 我們系統的產品名稱
  "quantity": 1,                   // 數量（整數）
  "unitPrice": 44900.00,           // 單價（小數）
  "subtotal": 44900.00,            // 小計
  "sellPackId": "sp_abc123"        // 對應的 sell_pack ID（審計用）
}
```

### SQL 查詢示例
```sql
-- 查詢所有訂單及其商品
SELECT o.id, o.channel_id,
       jsonb_array_elements(o.items)->>'productId' as product_id,
       jsonb_array_elements(o.items)->>'quantity' as quantity
FROM orders o
WHERE o.id = 'ord_123';

-- 統計訂單中商品數量
SELECT id, jsonb_array_length(items) as item_count
FROM orders;
```

### 驗證規則
1. items 不能為空數組 `[]`
2. 每個 item 必須有 quantity ≥ 1
3. 每個 item 必須有 unitPrice > 0
4. subtotal ≈ quantity × unitPrice（允許舍入誤差）
5. channelProductId 和 channelItemId 必須與 Kafka 消息對應
```

**優先級**：低 — 不修改不會影響系統運行，但補充文檔能幫助未來的維護和擴展

---

### 問題 9️⃣: channel_product_url 和 product_title 缺失 — ⏳ 待 API 補充

**現象**：sell_pack 表有 channel_product_url 和 product_title 字段，但 EVENT_SAMPLES.md 中無對應數據

**當前狀態**：
```sql
channel_product_url VARCHAR(2048),  -- 通路上的商品頁面 URL
product_title VARCHAR(512),         -- 通路顯示的商品標題
```

```json
// SYNC_PACK 消息缺少這兩個字段
{
  "body": {
    "channelProductId": "PC-2026021300001",
    "channelProductName": "iPhone 15 Pro Max - Space Black 256GB",
    // 無 channelProductUrl
    // 無 productTitle
  }
}
```

**根本原因**：
- 不是所有 API 都提供商品頁面 URL
- 有些通路可能需要單獨查詢商品詳情取得 URL
- product_title 的定義模糊（是 channelProductName 還是別的？）

**建議解決方案**：

**短期（無需修改代碼）**：
在 SCHEMA.md 中記錄這兩個字段的用途和可選性：
```markdown
### sell_pack 表字段說明

| 字段 | 類型 | 可選 | 說明 |
|------|------|------|------|
| channel_product_url | VARCHAR(2048) | Y | 通路商品頁面 URL（可能來自 API，也可能需組裝） |
| product_title | VARCHAR(512) | Y | 額外標題字段（保留給未來使用，目前同 channelProductName） |
```

**中期（改進消息樣本）**：
在 EVENT_SAMPLES.md 補充說明：
```json
{
  "body": {
    "channelProductId": "PC-2026021300001",
    "channelProductUrl": "https://www.pchome.com.tw/prod/PCGA0000001",  // 可選
    "productTitle": "iPhone 15 Pro Max - Space Black 256GB 官方版",      // 可選
    // 其他字段...
  }
}
```

**長期（與各通路 API 對接時）**：
1. Cyberbiz API 查詢結果中提取 URL
2. 如通路 API 無此字段，由 Handler 組裝（如某些平台需 product_id + sku 拼接 URL）
3. 更新 docs/PLATFORM_MAPPING.md，記錄各通路 URL 構造方式

**優先級**：低 — 不提供 URL 也不影響核心功能（訂單、退貨、庫存），主要用於運營後台展示

---

## 修復實施建議

### 修復順序（按優先級和難度）

1. **第 1 優先**（中優先級 + 容易）：
   - 問題 7：PROCESS_RETURN items 添加 channelProductId
   - 問題 14：PROCESS_ORDER 添加 shippingStatus
   - **工作量**：修改 2 個 EVENT_SAMPLES 樣本，≈30 分鐘

2. **第 2 優先**（中優先級 + 中等）：
   - 問題 13：創建 channel_shipping_mapping 表
   - **工作量**：新表 + 示例數據 + Handler 邏輯，≈2 小時

3. **第 3 優先**（低優先級 + 文檔）：
   - 問題 4：JSONB 結構文檔
   - 問題 9：URL/Title 字段說明
   - **工作量**：補充文檔，≈1 小時

### 不建議修復

- **問題 2**：已驗證無需修改（設計正確）
- **問題 12**：已在高優先級中完全解決

---

## 風險評估

| 問題 | 修復風險 | 跳過風險 |
|------|---------|---------|
| 問題 7 | 低 — 只是消息補充字段 | 中 — 退貨驗證邏輯不完整 |
| 問題 13 | 中 — 需新表 + 迁移數據 | 低 — 可用預設值或手動配置 |
| 問題 14 | 低 — 字段初始化 | 低 — 可硬編碼預設值 |
| 問題 4 | 無 — 純文檔 | 低 — 維護難度增加 |
| 問題 9 | 無 — 純文檔 | 低 — URL 可後續補充 |

---

## 下一步建議

### 立即行動
1. 確認用戶對 5 個中優先級問題的修復優先級
2. 確認是否進行低優先級文檔補充

### 後續規劃
- 若修復完成中優先級：進入 **實現階段**（Adapter/Handler 代碼開發）
- 若不修復某些中優先級：在實現階段記錄為已知限制（Known Limitation）
- 可考慮將 Cyberbiz API 集成作為實現階段的第一個適配器（作為測試用例驗證架構）

---

**文檔日期**：2026-02-20
**作者**：Claude Code
**狀態**：待用戶審核和優先級指示
