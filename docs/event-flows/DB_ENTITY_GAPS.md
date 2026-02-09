# DB ↔ Entity ↔ Payload 差異彙整

> **依據: SCHEMA.md v4（2026-02-09）**
>
> 盤點來源: `SCHEMA.md v4`, `simpleec-core/entity/*.java`, `ChannelAdapter.java`, DESIGN_v2.md §15 payload

## 差異總覽

| 類別 | 項目 | 嚴重度 | 狀態 |
|------|------|--------|------|
| PK 型別 | 所有 Entity 的 id 還是 Long → 應改 String (NanoID) | 🔴 高 | 待修 |
| Entity 缺欄位 | SellPack 缺 4 個欄位 (sku + 3 個平台欄位) | 🔴 高 | 待修 |
| Entity 缺欄位 | Order 缺 items JSONB 欄位 | 🔴 高 | 待修 |
| Entity 型別不一致 | Order.merchantId/channelId Long vs String | 🔴 高 | 待修 |
| Entity 應刪除 | OrderItem.java 應刪除（無 order_items 表） | 🔴 高 | 待修 |
| Adapter 缺方法 | ChannelAdapter 缺 fetchProducts() | 🟡 中 | 待修 |
| Adapter 回傳型 | fetchOrders 回傳 Order → 應改 ChannelOrder | 🟡 中 | 待修 |
| Adapter 參數型 | channelId 是 Long → 應改 String | 🟡 中 | 待修 |
| DTO 缺少 | ChannelProduct, ChannelOrder 等 DTO | 🟡 中 | 待建 |

---

## 1. 全局：所有 Entity 的 PK 型別

**SCHEMA v4 規則：所有表的 PK 都是 VARCHAR(20) NanoID，程式端產生。**

目前所有 Entity 的 `id` 都是 `Long`，需要統一改成 `String`。
所有 FK 引用（如 `merchantId`, `channelId`, `productId`, `orderId`）也都要改成 `String`。

| Entity | 需改的 ID 欄位 | 目前 | 應改為 |
|--------|--------------|------|--------|
| Order | id, merchantId, channelId | Long | String |
| SellPack | id, merchantId, productId, channelId | Long | String |
| Product | id, merchantId, productGroupId | Long | String |
| Channel | id, platformId, merchantId | Long | String |
| Platform | id | Long | String |
| Merchant | id | Long | String |
| Account | id, merchantId | Long | String |

---

## 2. SellPack.java — 缺 4 個欄位

**DB 有，Entity 沒有：**

```diff
  // SellPack.java 需要新增:
+ private String sku;                    // sku — 我方 SKU（= product.sku，冗餘方便 match）
+ private String channelSpecId;          // channel_spec_id — 平台規格編號
+ private String channelProductName;     // channel_product_name — 平台商品名
+ private String channelSpecName;        // channel_spec_name — 平台規格名
```

**影響範圍:**
- FETCH_PRODUCTS 同步時無法寫入 sku / 平台規格 / 名稱
- 改價/改量/上下架 payload 組裝時無法從 DB 讀取這些欄位
- §15 payload 的 `channelProductName`, `channelSpecName` 無法從 sell_pack 帶出
- 無 `sku` 欄位 → 無法從 sell_pack 直接 match product

---

## 3. Order.java — 型別不一致 + 缺 items

### 型別不一致

| 欄位 | Entity 目前 | DB 型別 (v4) | 應改為 |
|------|-----------|-------------|--------|
| `id` | `Long` | `VARCHAR(20)` | `String` |
| `merchantId` | `Long` | `VARCHAR(20)` | `String` |
| `channelId` | `Long` | `VARCHAR(20)` | `String` |

### 缺 items JSONB

Schema v4 訂單明細存在 `orders.items` JSONB 欄位（無 order_items 獨立表）。
Order.java 需要新增 items 欄位：

```diff
  // Order.java 需要新增:
+ private String items;  // JSONB，使用 @Column(columnDefinition = "jsonb")
```

**連鎖影響:**
- OrderMapper 的查詢/寫入邏輯
- OrderProcessJob 裡建立 Order 時需組裝 items JSONB
- OrderService / OrderController 的參數型別

---

## 4. OrderItem.java — 應刪除

Schema v4 移除了 `order_items` 獨立表。
訂單明細改存在 `orders.items` JSONB。

**`OrderItem.java` 整個 Entity 類別應該刪除。**

關聯影響：
- OrderItemMapper（如有）應刪除
- OrderItemRepository（如有）應刪除
- OrderProcessJob 裡寫 order_items 的邏輯 → 改為組裝 items JSONB

---

## 5. ChannelAdapter — 缺方法 + 型別問題

### 缺 fetchProducts

```diff
  public interface ChannelAdapter {
      // ... 現有方法 ...

+     /** 從通路拉取所有商品（含規格） */
+     List<ChannelProduct> fetchProducts(String channelId);
  }
```

### fetchOrders 回傳型別 + 參數型別

```diff
- List<Order> fetchOrders(Long channelId, LocalDateTime from, LocalDateTime to);
+ List<ChannelOrder> fetchOrders(String channelId, LocalDateTime from, LocalDateTime to);
```

**2 個問題：**
1. 回傳 `Order` Entity → 應改 `ChannelOrder` DTO（平台原始資料）
2. `channelId` 是 `Long` → 應改 `String`（NanoID）

**原因:** Adapter 層回傳的是平台原始資料（含 channelProductId, channelSpecId），不是我方 Entity。
需要 DataMapper 在 Adapter 內部做轉換：`平台 JSON → ChannelOrder DTO`

---

## 6. 需要新建的 DTO

### ChannelProduct（平台商品）

```java
package com.simpleec.channel.dto;

@Data
public class ChannelProduct {
    private String channelProductId;
    private String channelProductName;
    private String channelProductUrl;
    private BigDecimal sellingPrice;
    private Integer quantity;
    private String status;
    private String skuCode;               // 平台的 SKU → 對應我方 product.sku
    private List<ChannelProductSpec> specs;
}

@Data
public class ChannelProductSpec {
    private String channelSpecId;
    private String channelSpecName;
    private BigDecimal price;
    private Integer quantity;
    private String skuCode;               // 規格的 SKU → 對應我方 product.sku
    private String barcode;
}
```

### ChannelOrder（平台訂單）

```java
package com.simpleec.channel.dto;

@Data
public class ChannelOrder {
    private String channelOrderId;
    private String orderStatus;
    private String buyerName;
    private String buyerPhone;
    private String buyerEmail;
    private String shippingAddress;
    private String shippingMethod;
    private String paymentMethod;
    private BigDecimal totalAmount;
    private BigDecimal shippingFee;
    private BigDecimal discountAmount;
    private LocalDateTime channelCreatedAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;
    private List<ChannelOrderItem> items;
}

@Data
public class ChannelOrderItem {
    private String channelProductId;
    private String channelSpecId;
    private String channelProductName;
    private String channelSpecName;
    private String sku;                   // 我方 SKU
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
}
```

---

## 7. 修正優先順序

```
Phase 1 — 必須先修（影響所有事件流）:
  1. 全局 PK/FK 型別: 所有 Entity 的 id/merchantId/channelId/productId 改 String
  2. Order.java 加 items (JSONB) 欄位
  3. 刪除 OrderItem.java + 相關 Mapper/Repository
  4. SellPack.java 加 4 個欄位 (sku, channelSpecId, channelProductName, channelSpecName)

Phase 2 — Adapter 層重構:
  5. 新建 ChannelProduct, ChannelOrder 等 DTO
  6. ChannelAdapter 加 fetchProducts(String channelId)
  7. ChannelAdapter.fetchOrders() 改回傳 ChannelOrder, 參數改 String
  8. 各平台 Adapter 實作 fetchProducts + 修改 fetchOrders

Phase 3 — 驗證:
  9. 驗證所有 §15 payload 的欄位都能從 DB 組裝出來
  10. 驗證 FETCH_PRODUCTS / FETCH_ORDERS 事件流的完整性
```

---

## 8. 資料流完整性驗證矩陣

### FETCH_PRODUCTS 流向

```
平台 API → ChannelProduct DTO → sell_pack 表 → SellPack Entity

平台欄位              DTO 欄位                DB 欄位                  Entity 欄位
─────────           ──────────            ──────────               ────────────
product_id    →     channelProductId  →   channel_product_id   →  channelProductId    ✅ OK
spec_id       →     channelSpecId     →   channel_spec_id      →  channelSpecId       ❌ Entity 缺
product_name  →     channelProductName→   channel_product_name →  channelProductName  ❌ Entity 缺
spec_name     →     channelSpecName   →   channel_spec_name    →  channelSpecName     ❌ Entity 缺
url           →     channelProductUrl →   channel_product_url  →  channelProductUrl   ✅ OK
sku           →     skuCode           →   sku                  →  sku                 ❌ Entity 缺
price         →     sellingPrice      →   selling_price        →  sellingPrice        ✅ OK
qty           →     quantity          →   quantity             →  quantity            ✅ OK
status        →     status            →   status               →  status              ✅ OK
```

### FETCH_ORDERS 流向

```
平台 API → ChannelOrder DTO → Kafka payload → orders 表 (items JSONB) → Order Entity

                     DTO                 payload               DB (orders)            Entity (Order)
                    ─────               ─────────             ──────────             ─────────────
channelOrderId  →   channelOrderId  →   channelOrderId    →  channel_order_id    →  channelOrderId     ✅ OK
orderStatus     →   orderStatus     →   orderStatus       →  order_status        →  orderStatus        ✅ OK
buyerName       →   buyerName       →   buyerName         →  buyer_name          →  buyerName          ✅ OK
totalAmount     →   totalAmount     →   totalAmount       →  total_amount        →  totalAmount        ✅ OK
merchantId      →   (from msg)      →   msg.merchantId    →  merchant_id (varchar) → merchantId        ❌ Entity 是 Long
channelId       →   (from msg)      →   msg.ownerId       →  channel_id (varchar)  → channelId         ❌ Entity 是 Long
items           →   items           →   items[]           →  items (JSONB)       →  items              ❌ Entity 缺

                     DTO (item)           payload (item)        orders.items[] JSONB
                    ─────────            ──────────            ──────────────────
channelProductId →  channelProductId →   channelProductId  →  channelProductId        ✅ OK (JSONB 可存任意結構)
channelSpecId    →  channelSpecId    →   channelSpecId     →  channelSpecId           ✅ OK
channelProductName→ channelProductName→  channelProductName→  channelProductName      ✅ OK
channelSpecName  →  channelSpecName  →   channelSpecName   →  channelSpecName         ✅ OK
sku              →  sku              →   sku               →  sku                     ✅ OK
quantity         →  quantity         →   quantity          →  quantity                ✅ OK
unitPrice        →  unitPrice        →   unitPrice         →  unitPrice               ✅ OK
subtotal         →  subtotal         →   subtotal          →  subtotal                ✅ OK
(match sell_pack)→  ---              →   ---               →  sellPackId               ✅ OrderProcessJob 填入
(match sell_pack)→  ---              →   ---               →  productId                ✅ OrderProcessJob 填入
```

---

## 9. 與舊版差異摘要（v3 → v4）

| 項目 | v3 (舊) | v4 (新) |
|------|---------|---------|
| PK | 混用 Long/varchar | **全部 VARCHAR(20) NanoID** |
| 訂單明細 | order_items 獨立表 + OrderItem Entity | **orders.items JSONB，刪除 OrderItem** |
| 退款明細 | refund_order_items 獨立表 | **refund_orders.items JSONB** |
| 商品規格 | product_spec 獨立表 | **移除（product = SKU 級別）** |
| sell_pack.sku | 無 | **新增（match product 必須）** |
| SKU 欄位名 | item_number / sku_code | **統一為 sku** |
| 統計表 channel_id | null = 全通路 | **'_ALL_' sentinel（走 index）** |
| category | 有 | **移除** |
