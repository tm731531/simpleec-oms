# 全量文檔對齐報告

> 檢查所有文檔定義是否一致：PLATFORM_MAPPING.md、EVENT_SAMPLES.md、SCHEMA.md、docker schema.sql、PLAN files

---

## 🔴 Critical Issues（必須立即修復）

### Issue 1: Pack vs sell_pack 定義矛盾

#### PLATFORM_MAPPING.md（§1.3）定義
```json
{
  "packId": "string",
  "platformId": "string",
  "specId": "string",
  "packName": "string",
  "products": [              // ← 多個 products！
    {
      "productId": "string",
      "quantity": "number",
      "isMainProduct": "boolean"
    }
  ],
  "packPrice": "number",
  "status": "ACTIVE|INACTIVE",
  "channelUrl": "string"
}
```

#### 實際 docker schema.sql（sell_pack 表）
```sql
CREATE TABLE public.sell_pack (
    id                   VARCHAR(20),
    product_id           VARCHAR(20),      -- ← 單一 product FK！
    channel_id           VARCHAR(20),      -- ← 綁定到 channel
    sku                  VARCHAR(100),
    channel_product_id   VARCHAR(256),
    channel_spec_id      VARCHAR(256),
    channel_product_name VARCHAR(512),
    selling_price        DECIMAL(12,2),
    ...
);
```

#### 問題分析
| 方面 | PLATFORM_MAPPING.md | 實際 Schema | 差異 |
|------|----------------|-----------|------|
| 定義 | 套包（多個商品組合） | 上架映射（product × channel） | ❌ 完全不同 |
| products | 陣列（多個） | 單一 FK | ❌ 不同 |
| platformId | 有（通路識別） | 無（通過 channel.platform_id） | ⚠️ 間接關聯 |
| packPrice | 有（套包價格） | 無（selling_price） | ⚠️ 不同命名 |
| channelUrl | 有 | channel_product_url | ⚠️ 字段名不同 |

#### 解決方案 ⚠️
**選項 A：修改 PLATFORM_MAPPING.md 以匹配實際 schema**
- 放棄"套包"的概念，改為"上架映射"
- 說明 sell_pack 是 product 在特定 channel 上的上架配置

**選項 B：修改 schema.sql 以支持套包**
- 分離 sell_pack（單產品上架）和 packs（組合套包）
- 在 packs 表中存儲多個 product IDs

**建議**：選項 A — 因為目前實現已經是 sell_pack 模型，修改代碼成本太高

---

### Issue 2: Product 字段定義差異

#### PLATFORM_MAPPING.md（§1.2）定義
```json
{
  "productId": "string",
  "sku": "string",
  "productName": "string",
  "categoryId": "string",             // ← 實際無此字段
  "attributes": [                     // ← 實際無此字段
    {"name": "string", "value": "string"}
  ],
  "basePrice": "number",              // ← 實際叫 suggest_price
  "cost": "number",                   // ← 實際叫 cost_price
  "stock": "number",                  // ← 實際叫 quantity
  "warehouseId": "string",            // ← 實際無此字段
  "status": "ACTIVE|INACTIVE",
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

#### 實際 docker schema.sql（product 表）
```sql
CREATE TABLE public.product (
    id               VARCHAR(20),      -- productId ✓
    merchant_id      VARCHAR(20),      -- 無對應字段
    product_group_id VARCHAR(20),      -- 無對應字段
    sku              VARCHAR(100),     -- sku ✓
    name             VARCHAR(512),     -- productName ✓
    spec_summary     VARCHAR(256),     -- 規格摘要（無對應）
    cost_price       DECIMAL(12,2),    -- cost ✓
    suggest_price    DECIMAL(12,2),    -- basePrice ✓
    quantity         INTEGER,          -- stock ✓
    safety_quantity  INTEGER,          -- 安全庫存（無對應）
    status           VARCHAR(20),      -- status ✓
    created_at       TIMESTAMPTZ,      -- createdAt ✓
    updated_at       TIMESTAMPTZ       -- updatedAt ✓
);
```

#### 字段對應表
| PLATFORM_MAPPING | schema.sql | 狀態 | 備註 |
|-----------------|-----------|------|------|
| productId | id | ✅ 對應 |  |
| sku | sku | ✅ 對應 |  |
| productName | name | ✅ 對應 |  |
| categoryId | ❌ 無 | ❌ 缺失 | 改用 product_group |
| attributes | ❌ 無 | ❌ 缺失 | 無處存儲商品屬性 |
| basePrice | suggest_price | ⚠️ 命名不同 |  |
| cost | cost_price | ⚠️ 命名不同 |  |
| stock | quantity | ⚠️ 命名不同 |  |
| warehouseId | ❌ 無 | ❌ 缺失 | OMS 無倉庫概念 |
| status | status | ✅ 對應 |  |
| createdAt | created_at | ✅ 對應 |  |
| updatedAt | updated_at | ✅ 對應 |  |

#### 解決方案 ⚠️
**修改 PLATFORM_MAPPING.md Product Schema**
- 移除 categoryId（改用 product_group_id）
- 移除 attributes（應存在 sell_pack，非 product）
- 移除 warehouseId（OMS 無倉庫分層）
- 修正字段名：basePrice → suggest_price, cost → cost_price, stock → quantity
- 新增 merchant_id, product_group_id, spec_summary, safety_quantity

---

### Issue 3: Order.items 結構差異

#### EVENT_SAMPLES.md（PROCESS_ORDER 消息）
```json
"items": [
  {
    "sku": "IPHONE-15-PRO-MAX",
    "productId": "pd_xyz789",
    "channelProductId": "MOMO-SKU-001",
    "channelSpecId": "MOMO-SPEC-001",
    "channelItemId": "MOMO-ITEM-2026021300001",  // ← Kafka 消息特有
    "channelProductName": "iPhone 15 Pro Max",
    "channelSpecName": "太空黑/256GB",
    "productName": "iPhone 15 Pro Max",
    "quantity": 1,
    "unitPrice": 44900.00,
    "subtotal": 44900.00,
    "sellPackId": "sp_abc123"                    // ← Kafka 消息特有
  }
]
```

#### PLATFORM_MAPPING.md（Order Schema, §1.1）
```json
"items": [
  {
    "itemId": "string",
    "productId": "string",
    "packId": "string",                    // ← 無對應（schema 無 packId）
    "channelSku": "string",
    "quantity": "number",
    "unitPrice": "number",
    "subtotal": "number"
  }
]
```

#### 差異分析
| EVENT_SAMPLES | PLATFORM_MAPPING | schema.sql | 狀態 |
|---------------|-----------------|-----------|------|
| sku | ❌ 無 | — | ❌ 缺失 |
| productId | productId | — | ✅ 對應 |
| channelProductId | ❌ 無 | — | ❌ 缺失 |
| channelSpecId | ❌ 無 | — | ❌ 缺失 |
| channelItemId | ❌ 無 | — | ❌ 缺失（Kafka 特有） |
| channelProductName | ❌ 無 | — | ❌ 缺失 |
| channelSpecName | ❌ 無 | — | ❌ 缺失 |
| productName | ❌ 無 | — | ❌ 缺失 |
| quantity | quantity | — | ✅ 對應 |
| unitPrice | unitPrice | — | ✅ 對應 |
| subtotal | subtotal | — | ✅ 對應 |
| packId/sellPackId | packId | — | ⚠️ 不同命名 |

#### 解決方案 ⚠️
**修改 PLATFORM_MAPPING.md Order.items Schema**
- 添加所有 Kafka 消息中的字段：sku, channelProductId, channelSpecId, channelProductName, channelSpecName, productName, channelItemId
- 將 packId 改為 sellPackId（與 schema 一致）
- 明確說明：items 中的通路信息用於審計追蹤，不作為數據庫主鍵依據

---

## ⚠️ Important Issues（應該修復）

### Issue 4: shipping vs shippingInfo 命名混亂

#### PLATFORM_MAPPING.md（Order Schema, §1.1）
```json
"shipping": {
  "recipientName": "string",
  "phone": "string",
  "address": "string",
  "shippingStatus": "PENDING|SHIPPED|DELIVERED",
  "trackingNumber": "string"
}
```

#### EVENT_SAMPLES.md（PROCESS_ORDER 消息）
```json
"buyerName": "王小明",
"buyerPhone": "0912345678",
"buyerEmail": "wang@example.com",
"shippingAddress": "台北市中山區南京東路三段100號",
"shippingMethod": "HOME_DELIVERY",
```

#### 實際 schema.sql（orders 表）
```sql
buyer_name         VARCHAR(512),
buyer_phone        VARCHAR(256),
buyer_email        VARCHAR(512),
shipping_address   TEXT,
shipping_method    VARCHAR(50),
```

#### 差異分析
- PLATFORM_MAPPING.md 中有 `shipping.recipientName` 對應 `buyer_name`
- EVENT_SAMPLES 和 schema 直接使用平面結構（無 shipping 嵌套）
- shippingStatus 不在 orders 表中（在 order_shipments.shipping_status）

#### 解決方案 ⚠️
**修改 PLATFORM_MAPPING.md Order Schema**
- 移除 shipping 嵌套結構，改為平面字段
- 將 shippingStatus 移到 order_shipments 表定義中

---

### Issue 5: Return / RefundOrder 定義缺失

#### PLATFORM_MAPPING.md
- 無 Return / RefundOrder Schema 定義（§1 只有 Order, Product, Pack）

#### EVENT_SAMPLES.md（PROCESS_RETURN 消息）
- 有詳細的 returnData 結構（lines 451-465）

#### 實際 schema.sql
- refund_orders 表定義明確（lines 340-354）

#### 解決方案 ⚠️
**添加 PLATFORM_MAPPING.md §1.4 Return / RefundOrder Schema**
```json
{
  "refundOrderId": "string",
  "channelReturnId": "string",
  "orderId": "string",
  "merchantId": "string",
  "refundStatus": "PENDING_APPROVAL|APPROVED|REJECTED|SHIPPED|COMPLETED",
  "refundAmount": "number",
  "reason": "string",
  "requestedAt": "ISO-8601",
  "items": [
    {
      "productId": "string",
      "channelProductId": "string",
      "channelSpecId": "string",
      "quantity": "number",
      "unitPrice": "number"
    }
  ],
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

---

## 💡 Documentation Issues（優化建議）

### Issue 6: sell_pack 角色定義模糊

**現象**：多個文檔用不同的詞彙
- PLATFORM_MAPPING.md: "Pack"（套包）
- SCHEMA.md: "sell_pack"（賣場檔）
- EVENT_SAMPLES.md: "pack" / "packInfo"
- Kafka 消息: "SYNC_PACK" task type

**建議統一命名**：
- OMS 內部文檔：統一用 **"sell_pack"** 或 **"上架映射"**
- 區分 sell_pack（product × channel 映射）和 product（SKU）
- 在 PLATFORM_MAPPING.md 中明確說明：sell_pack 不是套包，是上架配置

---

### Issue 7: 時間戳字段命名不一致

#### PLATFORM_MAPPING.md
- createdAt, updatedAt, paidAt, requestedAt（駝峰）

#### schema.sql
- created_at, updated_at, paid_at, channel_created_at（蛇形）

#### 建議
- 文檔定義用駝峰（JSON 標準）
- 數據庫實現用蛇形（SQL 標準）
- 在對應表中明確標註映射

---

## 📋 對齊檢查清單

### PLATFORM_MAPPING.md（需修改）
- [ ] 修改 §1.2 Product Schema（移除 categoryId, attributes, warehouseId；修正字段命名）
- [ ] 修改 §1.3 Pack Schema（改為 sell_pack 的正確定義）
- [ ] 修改 §1.1 Order Schema（扁平化 shipping，添加所有 Kafka 消息字段）
- [ ] 添加 §1.4 RefundOrder Schema（參考 EVENT_SAMPLES 和 schema.sql）
- [ ] 添加字段映射說明（駝峰 ↔ 蛇形）

### EVENT_SAMPLES.md（檢查，可能需要小調整）
- [ ] 驗證 PROCESS_ORDER 中的所有字段都能存入 orders 表
- [ ] 驗證 PROCESS_RETURN 中的所有字段都能存入 refund_orders 表
- [ ] 驗證 SYNC_PACK 消息包含所有必需字段（特別是 channel_product_id）

### KAFKA_SCHEMA_VALIDATION.md（需更新）
- [ ] 基於新的 PLATFORM_MAPPING.md 重新驗證字段映射
- [ ] 重新計算 critical issues 數量

### PLAN 文檔（需同步）
- [ ] 更新 PLAN/00-OVERVIEW.md 中 sell_pack 的角色描述
- [ ] 更新 PLAN/02-MODULE-DESIGN.md 中 Product Schema 注釋

---

## 優先修復順序

### 第 1 優先級（影響代碼理解，立即修復）
1. **PLATFORM_MAPPING.md** —— 這是最權威的業務定義，其他文檔都應對齐它
   - 修正 Product, Pack, Order.items, RefundOrder schema
   - 添加字段對應映射表

2. **KAFKA_SCHEMA_VALIDATION.md** —— 重新驗證，基於新的 PLATFORM_MAPPING

### 第 2 優先級（改進文檔清晰度）
3. **EVENT_SAMPLES.md** —— 檢查消息樣本是否完整
4. **SCHEMA.md** —— 確保表註釋與 PLATFORM_MAPPING 一致

### 第 3 優先級（同步 PLAN）
5. **PLAN 文檔** —— 同步最新的架構理解

---

**檢查日期**：2026-02-20
**狀態**：等待用戶確認修復方向

