# DB ↔ Entity ↔ Payload 差異彙整

> 盤點日期: 2026-02-09
> 依據: `02-new-tables.sql`, `simpleec-core/entity/*.java`, `ChannelAdapter.java`, DESIGN_v2.md §15 payload

## 差異總覽

| 類別 | 項目 | 嚴重度 | 狀態 |
|------|------|--------|------|
| Entity 缺欄位 | SellPack 缺 3 個平台欄位 | 🔴 高 | 待修 |
| Entity 缺欄位 | OrderItem 缺 2 個平台 ID | 🔴 高 | 待修 |
| 類型不一致 | Order.merchantId Long vs varchar(20) | 🔴 高 | 待修 |
| 類型不一致 | Order.channelId Long vs varchar(20) | 🔴 高 | 待修 |
| DB 缺欄位 | order_items 缺 channel_product_id, channel_spec_id | 🔴 高 | 待修 |
| DB 缺約束 | sell_pack 缺 UNIQUE 約束 | 🟡 中 | 待修 |
| Adapter 缺方法 | ChannelAdapter 缺 fetchProducts() | 🟡 中 | 待修 |
| Adapter 回傳型 | fetchOrders 回傳 Order 應改 ChannelOrder | 🟡 中 | 待修 |
| DTO 缺少 | ChannelProduct, ChannelOrder 等 DTO | 🟡 中 | 待建 |

---

## 1. SellPack.java — 缺 3 個欄位

**DB 有，Entity 沒有：**

```diff
  // SellPack.java 需要新增:
+ private String channelSpecId;        // channel_spec_id — 平台規格編號
+ private String channelProductName;   // channel_product_name — 平台商品名
+ private String channelSpecName;      // channel_spec_name — 平台規格名
```

**影響範圍:**
- FETCH_PRODUCTS 同步時無法寫入平台規格/名稱
- 改價/改量/上下架 payload 組裝時無法從 DB 讀取這些欄位
- §15 payload 的 `channelProductName`, `channelSpecName` 無法從 sell_pack 帶出

---

## 2. OrderItem.java — 缺 2 個平台 ID

**payload 有帶，但 DB + Entity 都沒有：**

```diff
  // order_items 表需要加:
+ channel_product_id character varying(256),   -- 平台商品編號
+ channel_spec_id character varying(256),      -- 平台規格編號

  // OrderItem.java 需要加:
+ private String channelProductId;
+ private String channelSpecId;
```

**為什麼必須存？**
1. 訂單入庫時 sell_pack 可能不存在（尚未同步商品）→ sell_pack_id = null
2. 後續同步商品後，需要用 channelProductId + channelSpecId 回填 sell_pack_id
3. 訂單明細應保留平台原始資訊，即使 sell_pack 被重建（ID 變了）也能重新關聯

---

## 3. Order.java — 類型不一致

| 欄位 | Entity 類型 | DB 類型 | 正確應該是 |
|------|-----------|---------|-----------|
| `merchantId` | `Long` | `character varying(20)` | `String` |
| `channelId` | `Long` | `character varying(20)` | `String` |

**原因:** DB schema 沿用舊系統的 varchar ID 格式（如 "M001", "CH-MOMO-001"），但 Entity 寫成了 Long。

**修正方案:**

```diff
  // Order.java
- private Long merchantId;
+ private String merchantId;
- private Long channelId;
+ private String channelId;
```

**連鎖影響:**
- OrderMapper 的查詢條件型別
- OrderService / OrderController 的參數型別
- OrderProcessJob 裡 `msg.getMerchantId()` 和 `msg.getOwnerId()` 是 String → 對了
- 其他可能引用 Order.merchantId / channelId 的地方

---

## 4. sell_pack 表 — 缺 UNIQUE 約束

**現有 index（不是 UNIQUE）：**
```sql
CREATE INDEX idx_sellpack_channel_product ON public.sell_pack (channel_id, channel_product_id);
CREATE INDEX idx_sellpack_channel_spec ON public.sell_pack (channel_id, channel_spec_id);
```

**需要加:**
```sql
CREATE UNIQUE INDEX idx_sellpack_channel_product_spec
    ON public.sell_pack (channel_id, channel_product_id, COALESCE(channel_spec_id, ''));
```

**為什麼用 COALESCE?**
- 單規商品 channel_spec_id = null
- PostgreSQL 裡 `null != null`，所以 UNIQUE 約束對 null 不生效
- COALESCE 將 null 轉為空字串，確保 `(channel_id, channelProductId, null)` 也是唯一的

---

## 5. order_items 表 — 缺平台 ID 欄位

```sql
-- 需要加:
ALTER TABLE public.order_items ADD COLUMN channel_product_id character varying(256);
ALTER TABLE public.order_items ADD COLUMN channel_spec_id character varying(256);

COMMENT ON COLUMN public.order_items.channel_product_id IS '平台商品編號（賣編）';
COMMENT ON COLUMN public.order_items.channel_spec_id IS '平台規格編號';

CREATE INDEX idx_order_items_channel_product ON public.order_items (channel_product_id);
```

---

## 6. ChannelAdapter — 缺方法 + 回傳型別問題

### 缺 fetchProducts

```diff
  public interface ChannelAdapter {
      // ... 現有方法 ...

+     /** 從通路拉取所有商品（含規格） */
+     List<ChannelProduct> fetchProducts(Long channelId);
  }
```

### fetchOrders 回傳型別

```diff
- List<Order> fetchOrders(Long channelId, LocalDateTime from, LocalDateTime to);
+ List<ChannelOrder> fetchOrders(Long channelId, LocalDateTime from, LocalDateTime to);
```

**原因:** Adapter 層回傳的是平台原始資料（含 channelProductId, channelSpecId），不是我方 Entity。
需要 DataMapper 在 Adapter 內部做轉換：`平台 JSON → ChannelOrder DTO`

---

## 7. 需要新建的 DTO

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
    private String skuCode;
    private List<ChannelProductSpec> specs;
}

@Data
public class ChannelProductSpec {
    private String channelSpecId;
    private String channelSpecName;
    private BigDecimal price;
    private Integer quantity;
    private String skuCode;
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
    private String skuCode;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
}
```

---

## 8. 修正優先順序

```
Phase 1 — 必須先修（影響所有事件流）:
  1. Order.java merchantId/channelId 類型改 String
  2. SellPack.java 加 3 個平台欄位
  3. OrderItem.java 加 2 個平台 ID
  4. order_items 表加 channel_product_id, channel_spec_id

Phase 2 — Adapter 層重構:
  5. 新建 ChannelProduct, ChannelOrder 等 DTO
  6. ChannelAdapter 加 fetchProducts()
  7. ChannelAdapter.fetchOrders() 改回傳 ChannelOrder
  8. 各平台 Adapter 實作 fetchProducts + 修改 fetchOrders

Phase 3 — 完整性:
  9. sell_pack 加 UNIQUE 約束
  10. 驗證所有 §15 payload 的欄位都能從 DB 組裝出來
```

---

## 9. 資料流完整性驗證矩陣

### FETCH_PRODUCTS 流向

```
平台 API → ChannelProduct DTO → sell_pack 表 → SellPack Entity

平台欄位              DTO 欄位                DB 欄位                 Entity 欄位
─────────           ──────────            ──────────              ────────────
product_id    →     channelProductId  →   channel_product_id  →  channelProductId    ❌ Entity 缺
spec_id       →     channelSpecId     →   channel_spec_id     →  channelSpecId       ❌ Entity 缺
product_name  →     channelProductName→   channel_product_name→  channelProductName  ❌ Entity 缺
spec_name     →     channelSpecName   →   channel_spec_name   →  channelSpecName     ❌ Entity 缺
url           →     channelProductUrl →   channel_product_url →  channelProductUrl   ✅ OK
price         →     sellingPrice      →   selling_price       →  sellingPrice        ✅ OK
qty           →     quantity          →   quantity            →  quantity            ✅ OK
status        →     status            →   status              →  status              ✅ OK
```

### FETCH_ORDERS 流向

```
平台 API → ChannelOrder DTO → Kafka payload → orders/order_items 表 → Order/OrderItem Entity

                     DTO                 payload               DB (orders)         Entity (Order)
                    ─────               ─────────             ──────────          ─────────────
channelOrderId  →   channelOrderId  →   channelOrderId    →  channel_order_id →  channelOrderId     ✅ OK
orderStatus     →   orderStatus     →   orderStatus       →  order_status     →  orderStatus        ✅ OK
buyerName       →   buyerName       →   buyerName         →  buyer_name       →  buyerName          ✅ OK
totalAmount     →   totalAmount     →   totalAmount       →  total_amount     →  totalAmount        ✅ OK
merchantId      →   (from msg)      →   msg.merchantId    →  merchant_id (varchar) → merchantId (Long) ❌ 類型錯
channelId       →   (from msg)      →   msg.ownerId       →  channel_id (varchar)  → channelId (Long)  ❌ 類型錯

                     DTO (item)           payload (item)        DB (order_items)    Entity (OrderItem)
                    ─────────            ──────────            ──────────          ─────────────
channelProductId →  channelProductId →   channelProductId  →  ❌ DB 缺           →  ❌ Entity 缺
channelSpecId    →  channelSpecId    →   channelSpecId     →  ❌ DB 缺           →  ❌ Entity 缺
productName      →  channelProductName→  productName       →  product_name      →  productName         ✅ OK
quantity         →  quantity          →  quantity           →  quantity           →  quantity            ✅ OK
unitPrice        →  unitPrice         →  unitPrice          →  unit_price        →  unitPrice           ✅ OK
```
