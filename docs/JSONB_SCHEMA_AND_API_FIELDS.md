# JSONB 結構定義與 API 欄位補充

> 文件日期：2026-02-20
> 目標：補充低優先級問題 4 & 9 的文檔定義

---

## 問題 4️⃣: order.items JSONB 模式定義

### 概述

`orders.items` 是 JSONB 類型，存儲訂單的所有商品行項目。每個 item 包含通路信息（審計追蹤）和業務數據（計費、庫存）的混合。

### 完整結構定義

```json
[
  {
    "itemId": "OI-20260213-001",              // 訂單行項目 ID（唯一）
    "sku": "IPHONE-15-PRO-MAX",               // 我們的 SKU（內部庫存識別）
    "productId": "pd_xyz789",                 // FK → Product.productId（內部 ID）
    "channelProductId": "MOMO-SKU-001",       // 通路的商品編號（賣編）
    "channelSpecId": "MOMO-SPEC-001",         // 通路的規格編號（if applicable）
    "channelItemId": "MOMO-ITEM-20260213001", // 通路訂單行 ID（審計用）
    "channelProductName": "iPhone 15 Pro Max",// 通路顯示的商品名稱
    "channelSpecName": "太空黑/256GB",        // 通路顯示的規格名稱
    "productName": "iPhone 15 Pro Max",       // 我們系統的商品名稱
    "quantity": 1,                            // 訂購數量（正整數）
    "unitPrice": 44900.00,                    // 單價（DECIMAL，小數點 2 位）
    "subtotal": 44900.00,                     // 小計 = quantity × unitPrice（已計算）
    "sellPackId": "sp_abc123"                 // FK → sell_pack.id（審計用）
  }
]
```

### 欄位說明

| 欄位 | 類型 | 必填 | 說明 |
|------|------|------|------|
| `itemId` | string | Y | 訂單行唯一識別碼，通常由 Channel Job 生成 |
| `sku` | string | Y | 我們系統的 SKU，用於內部庫存追蹤 |
| `productId` | string | Y | FK 到 product 表，但可能為 null（如通路有、OMS 還沒建） |
| `channelProductId` | string | Y | 通路給的商品編號（必須有，用於取消訂單/退貨時匹配） |
| `channelSpecId` | string | N | 通路給的規格 ID（某些通路無規格概念） |
| `channelItemId` | string | Y | 通路訂單行 ID，用於審計和對帳 |
| `channelProductName` | string | Y | 通路顯示的商品名稱（審計追蹤） |
| `channelSpecName` | string | N | 通路顯示的規格（如「紅色/M」） |
| `productName` | string | Y | 我們系統的商品名稱（可能與通路不同） |
| `quantity` | integer | Y | 訂購數量，必須 ≥ 1 |
| `unitPrice` | decimal | Y | 訂購時的單價，必須 > 0，保留 2 位小數 |
| `subtotal` | decimal | Y | 已計算的小計（通常 = quantity × unitPrice） |
| `sellPackId` | string | N | 對應的 sell_pack ID，用於追蹤平台配置 |

### 驗證規則

Handler 在接收 PROCESS_ORDER 消息時應驗證：

```java
// 示例驗證邏輯（Java）
for (JsonNode item : items) {
    // 必填欄位檢查
    assert item.has("itemId") && item.get("itemId").isTextual();
    assert item.has("channelProductId") && item.get("channelProductId").isTextual();
    assert item.has("quantity") && item.get("quantity").isIntegralNumber();
    assert item.has("unitPrice") && item.get("unitPrice").isNumber();

    // 數值驗證
    int qty = item.get("quantity").asInt();
    assert qty >= 1 : "quantity must be >= 1";

    BigDecimal price = item.get("unitPrice").decimalValue();
    assert price.compareTo(BigDecimal.ZERO) > 0 : "unitPrice must > 0";

    // 小計驗證（允許舍入誤差 ≤ 0.01）
    BigDecimal expected = new BigDecimal(qty).multiply(price);
    BigDecimal actual = item.get("subtotal").decimalValue();
    BigDecimal diff = expected.subtract(actual).abs();
    assert diff.compareTo(new BigDecimal("0.01")) <= 0 : "subtotal mismatch";
}
```

### SQL 查詢示例

#### 查詢訂單及其商品

```sql
-- 取得訂單及展開所有商品
SELECT
    o.id as order_id,
    o.channel_id,
    jsonb_array_elements(o.items)->>'itemId' as item_id,
    jsonb_array_elements(o.items)->>'channelProductId' as channel_product_id,
    jsonb_array_elements(o.items)->>'quantity' as quantity,
    jsonb_array_elements(o.items)->>'unitPrice' as unit_price,
    jsonb_array_elements(o.items)->>'subtotal' as subtotal
FROM orders o
WHERE o.id = 'ord_abc123def456';
```

#### 統計訂單中商品數量

```sql
SELECT
    id,
    jsonb_array_length(items) as item_count,
    (jsonb_array_elements(items)->>'subtotal')::decimal as total_per_item
FROM orders
WHERE created_at > now() - interval '7 days'
ORDER BY id;
```

#### 查詢特定通路的商品

```sql
SELECT
    o.id,
    jsonb_array_elements(o.items)->>'channelProductId' as channel_product_id,
    jsonb_array_elements(o.items)->>'quantity' as qty
FROM orders o
WHERE o.channel_id = 'ch_momo'
  AND jsonb_array_elements(o.items)->>'channelProductId' = 'MOMO-SKU-001';
```

#### 統計銷售額（按日期）

```sql
SELECT
    DATE(o.created_at) as sale_date,
    SUM((jsonb_array_elements(o.items)->>'subtotal')::decimal) as total_sales,
    COUNT(DISTINCT o.id) as order_count
FROM orders o
GROUP BY DATE(o.created_at)
ORDER BY sale_date DESC;
```

### 特殊情況

#### 什麼時候 productId 為 null？

1. **客戶不在 OMS 維護該產品** — 某些通路的商品不重要，客戶決定只在通路管理
2. **OMS 還未建立產品記錄** — 商品先上架到通路，OMS 稍後才建立對應 Product

**處理方式**：
- Handler 記錄此情況但不中斷訂單建立
- 可後續補充或忽略（取決於業務規則）
- 使用 `channelProductId` + `sku` 可以後續對帳

#### channelSpecId 為 null？

某些通路（如 PChome）無規格概念，所有商品都是「一規格」，此時 `channelSpecId` 可為 null。

#### 什麼時候出現同一 productId 多筆 items？

同一訂單可能同一產品買多個規格：
```json
[
  {"productId": "pd_xyz", "channelSpecId": "spec-red-m", "quantity": 1, ...},
  {"productId": "pd_xyz", "channelSpecId": "spec-red-l", "quantity": 1, ...}
]
```

---

## 問題 9️⃣: sell_pack API 欄位補充

### 概述

`sell_pack` 表存儲產品在每個通路上的上架信息。兩個字段（`channel_product_url` 和 `product_title`）在某些通路需要從外部 API 獲取。

### channel_product_url 欄位

#### 定義

商品在該通路上的顯示頁面 URL。

#### 值的來源

| 通路 | API 字段 | 範例 | 備註 |
|------|---------|------|------|
| **Momo** | itemUrl | `https://www.momoshop.com.tw/goods/...` | 直接來自 API |
| **Shopee** | 需組裝 | `https://shopee.tw/{shop_id}/p/{product_id}` | API 無直接 URL，需自行拼接 |
| **Yahoo** | itemUrl | `https://tw.mall.yahoo.com/...` | 直接來自 API |
| **PChome** | 需組裝 | `https://24h.pchome.com.tw/prod/{product_id}` | 需自行拼接 |
| **Cyberbiz** | productUrl | `https://store.cyberbiz.io/products/...` | 直接來自 API |

#### 處理策略

**在 Sync Handler 中**：
```java
// 優先使用 API 提供的 URL
String url = apiResponse.getItemUrl();

// 若 API 無提供，嘗試組裝
if (url == null || url.isEmpty()) {
    url = constructUrlFromParts(channelConfig, productId, sku);
}

// 最後都找不到，允許 null
sellPack.setChannelProductUrl(url);  // null is acceptable
```

### product_title 欄位

#### 定義

產品在該通路上的展示標題。可能與 `channelProductName` + `channelSpecName` 不同（某些通路添加促銷文字或特殊符號）。

#### 值的來源

| 通路 | 通常來自 | 範例 |
|------|---------|------|
| **Momo** | itemName | `iPhone 15 Pro Max【64G新色上市中】` |
| **Shopee** | name | `iPhone 15 Pro Max (太空黑/256GB)` |
| **Yahoo** | title | `iPhone 15 Pro Max - 官方認證翻新機 🔥` |
| **PChome** | productName | `iPhone 15 Pro Max 256G` |
| **Cyberbiz** | productTitle | `iPhone 15 Pro Max（太空黑）` |

#### 處理策略

**預設行為**：
- 若通路 API 提供 `productTitle` 或等效字段，直接使用
- 若無，可自動組裝：`channelProductName + " (" + channelSpecName + ")"`
- 若都無，允許 `null` 或使用 `channelProductName` 作為備選

```java
String title = apiResponse.getProductTitle();

if (title == null || title.isEmpty()) {
    // 組裝備選值
    String specPart = channelSpecName != null ?
        " (" + channelSpecName + ")" : "";
    title = channelProductName + specPart;
}

sellPack.setProductTitle(title);
```

### 在 SYNC_PACK 消息中的應用

#### 目前 EVENT_SAMPLES.md 示例

```json
{
  "body": {
    "channelProductId": "PC-2026021300001",
    "channelProductName": "iPhone 15 Pro Max - Space Black 256GB",
    "channelSpecName": "太空黑 / 256GB",
    // channel_product_url 來自 API（若有）
    // product_title 來自 API 或自動組裝
  }
}
```

#### Handler 實現建議

```java
// 在 SyncPackHandler 中
String channelProductUrl = apiResponse.getItemUrl();  // 可能為 null
String productTitle = apiResponse.getProductTitle();  // 可能為 null

if (productTitle == null) {
    productTitle = constructTitle(apiResponse.getProductName(),
                                 apiResponse.getSpecName());
}

sellPack.setChannelProductUrl(channelProductUrl);
sellPack.setProductTitle(productTitle);
sellPack.save();
```

### 運維建議

1. **監控 null 值**：建議在 Handler 中記錄 URL/Title 為 null 的情況，便於診斷
2. **後續補充**：若某通路 API 更新新增 URL/Title 字段，可後期在 SYNC_PACK 中補充
3. **前端展示**：UI 在展示產品時，優先用 `channel_product_url`（若有），作為跳轉到通路的鏈接

---

## 總結

### 問題 4（JSONB Schema）

✅ **已完整定義**：
- 完整 JSON 結構
- 欄位說明表
- 驗證規則
- SQL 查詢示例
- 特殊情況處理

### 問題 9（API Fields）

✅ **已補充說明**：
- 各通路 URL 和 Title 的來源
- 處理策略（優先級、備選方案）
- Handler 實現建議
- 運維指南

---

**文件日期**：2026-02-20
**狀態**：完成低優先級文檔補充
