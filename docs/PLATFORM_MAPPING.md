# SimpleEC OMS Platform Mapping 標準

⚠️ **重要聲明 - 部分內容為示例**

本文件分為三部分：

## 🟢 正式標準（已定義，不會改變）
- **OMS Schema 定義**（Order, Product, Pack, Return 結構）
- **OMS 狀態機定義**（訂單、商品、退貨的統一狀態）
- **TaskType 定義**（FETCH_ORDERS, SHIP_ORDER 等行為）
- **平台處理模式**（Mode A vs Mode B — 根據列表 API 的完整度）

## 🟡 條件式標準（根據平台特性變化）
- **各平台所屬模式**（Mode A 直接模式 或 Mode B 列表+詳情模式）

## 🔴 待實現部分（現在是 SAMPLE，逐步更新）
- **各平台 API 映射**（Shopee / Momo / Yahoo / PChome / easystore / Cyberbiz）
- **平台狀態 → OMS 狀態的對照表**
- **平台字段 → OMS 字段的映射規則**

未來當實現各平台時，會一個一個更新這些 SAMPLE 部分為真實 API 規範。

---

## 0. 平台處理模式定義

參考 DATA_FLOW_MAPPING.md §0，每個平台根據其**列表 API 的數據完整度**決定處理模式：

### Mode A：直接模式（列表 API 已含完整資訊）
- ✓ 單一 Handler：FETCH_ORDERS → 直接組織 OMS → 計算 hash → ORDER_UPSERT
- ✓ 適合：列表 API 已包含完整商品、客戶、地址等資訊
- ✓ 優點：快速、低 API 配額消耗
- ✗ 缺點：依賴列表 API 設計完整度

**示例：Shopify, Stripe, 自有平台**

### Mode B：列表+詳情模式（列表 API 缺少關鍵資訊）
- ✓ 兩個 Handler：FETCH_ORDERS → 產生 FETCH_ORDER_DETAIL 訊息 → 詳情 API → ORDER_UPSERT
- ✓ 適合：列表 API 缺少商品清單、物流詳情等關鍵資訊
- ✓ 優點：數據完整、準確
- ✗ 缺點：詳情 API 呼叫多（=訂單數），消耗更多配額

**示例：Shopee（列表無 items/shippingInfo），Momo（類似限制）**

### 平台模式對應（待補充）

| 通路 | 模式 | 理由 | 數據完整度檢查點 |
|------|------|------|-----------------|
| Shopee | Mode B | 列表無商品、物流詳情 | 需檢查：items, shippingInfo, buyerInfo |
| Shopify | Mode A | 列表已包含完整數據 | 列表 API 含：line_items, shipping_address, customer |
| Momo | ? | 待確認 | ? |
| Yahoo | ? | 待確認 | ? |
| PChome | ? | 待確認 | ? |
| easystore | ? | 待確認 | ? |
| Cyberbiz | ? | 待確認 | ? |

---

## 1. OMS Schema 定義（統一標準）

### 1.1 Order Schema

```json
{
  "orderId": "string",                    // OMS 系統訂單 ID（unique key）
  "channelOrderId": "string",             // 通路的訂單 ID（e.g., shop_order_id for Shopee）
  "channelId": "string",                  // 通路實例（e.g., SHOPEE_001, MOMO_002）
  "merchantId": "string",                 // 商家 ID
  "orderStatus": "PENDING|CONFIRMED|SHIPPED|COMPLETED|CANCELLED",
  "channelCreatedAt": "ISO-8601",         // 通路上的訂單建立時間
  "buyerName": "string",                  // 買家名稱（PII，AES-256-GCM 加密存儲）
  "buyerPhone": "string",                 // 買家電話（PII，AES-256-GCM 加密存儲）
  "buyerEmail": "string",                 // 買家郵箱（PII，AES-256-GCM 加密存儲）
  "shippingAddress": "string",            // 收貨地址（PII，AES-256-GCM 加密存儲）
  "totalAmount": "number",                // 訂單總金額（含運費）
  "shippingFee": "number",                // 運費
  "discountAmount": "number",             // 折扣
  "items": [                              // 訂單項目（JSONB 陣列）
    {
      "sku": "string",                    // 我們的 SKU
      "productId": "string",              // FK → Product.productId
      "channelProductId": "string",       // 通路的商品 ID（e.g., Shopee item_id）
      "channelSpecId": "string",          // 通路的規格 ID（e.g., Shopee variation_id）
      "channelItemId": "string",          // 通路的行項目 ID（用於追蹤）
      "channelProductName": "string",     // 通路上的商品名稱
      "channelSpecName": "string",        // 通路上的規格名稱（「紅色/M」）
      "productName": "string",            // 我們的商品名稱
      "quantity": "number",               // 數量
      "unitPrice": "number",              // 單位售價
      "subtotal": "number",               // 小計
      "sellPackId": "string"              // FK → SellPack.id
    }
  ],
  "shippingMethod": "string",             // 配送方式（HOME_DELIVERY, STORE_PICKUP 等）
  "paymentMethod": "string",              // 支付方式（CREDIT_CARD, BANK_TRANSFER, COD 等）
  "paidAt": "ISO-8601",                   // 支付時間
  "isRollback": "boolean",                // 是否為回補訂單（遺漏的過往訂單）
  "createdAt": "ISO-8601",                // OMS 建立時間
  "updatedAt": "ISO-8601"
}
```

### 1.2 Product Schema（我們的倉庫 SKU）

```json
{
  "productId": "string",                  // OMS 系統產品 ID（unique key）
  "merchantId": "string",                 // 商家 ID
  "sku": "string",                        // OMS SKU（商家內唯一）
  "productName": "string",                // 我們的商品名稱
  "specSummary": "string",                // 規格摘要（如「紅色/M」）
  "productGroupId": "string",             // 所屬群組（可選，可 null）
  "costPrice": "number",                  // 成本價
  "suggestPrice": "number",               // 建議售價
  "quantity": "integer",                  // 當前庫存量
  "safetyQuantity": "integer",            // 安全庫存量（低於此值警告）
  "status": "active|inactive",            // 產品狀態
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

**重點說明**：
- Product 是我們倉庫的 SKU 級別庫存單位
- quantity = 我們實際有多少
- 各平台各自決定要不要上架、要上架多少（通過 SellPack）
- 無 categoryId, attributes, warehouseId（不在 OMS 層級管理）

### 1.3 SellPack Schema（通路上架映射）

> **架構原則**：一個 Product（我們的 SKU）可以在多個 Channel 上架，
> 每次上架是一個 SellPack 記錄。**平台決定要不要上架這個 Product**。
>
> Product → 倉庫視角（我們有什麼）
> SellPack → 平台視角（平台怎麼賣它）

```json
{
  "sellPackId": "string",                 // OMS 上架記錄 ID（unique key）
  "merchantId": "string",                 // 商家 ID
  "productId": "string",                  // FK → Product.productId（我們的 SKU）
  "channelId": "string",                  // FK → Channel.id（通路實例）
  "sku": "string",                        // 通路上的 SKU（可能與 product.sku 不同）
  "channelProductId": "string",           // 通路方給的商品 ID（e.g., Shopee item_id）
  "channelSpecId": "string",              // 通路上的規格 ID（e.g., Shopee variation_id）
  "channelProductName": "string",         // 通路上展示的商品名稱
  "channelSpecName": "string",            // 通路上的規格名稱（「紅色/M」）
  "channelProductUrl": "string",          // 通路上的商品頁面 URL
  "title": "string",                      // 上架標題（可能與 channelProductName 不同）
  "attributes": {                         // 通路特定的屬性（JSONB）
    "color": "string",
    "size": "string",
    "other": "string"
  },
  "sellingPrice": "number",               // 通路上的售價
  "quantity": "integer",                  // 通路顯示的庫存（平台各自管理）
  "status": "draft|active|inactive",      // 上架狀態
  "visibility": "VISIBLE|HIDDEN",         // 通路上的可見性
  "lastSyncAt": "ISO-8601",               // 最後同步時間
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

**重點說明**：
- SellPack **不是套包**，是「Product 在 Channel 上的配置」
- 1 個 Product × 1 個 Channel = 1 個 SellPack
- Product.sku = 我們的內部 SKU，SellPack.sku = 平台上的 SKU（可能不同）
- SellPack.quantity = 平台顯示的庫存，與 Product.quantity 獨立
- 平台決定「我的 channel 要上架哪些 product」

### 1.4 RefundOrder Schema（退貨單）

```json
{
  "refundOrderId": "string",              // OMS 系統退款單 ID（unique key）
  "orderId": "string",                    // FK → Order.orderId
  "merchantId": "string",                 // 商家 ID
  "channelRefundId": "string",            // 通路的退貨/退款 ID
  "refundStatus": "pending|approved|rejected|completed|refunded",
  "reason": "string",                     // 退貨原因
  "items": [                              // 退款項目（JSONB）
    {
      "productId": "string",              // FK → Product.productId
      "channelProductId": "string",       // 通路的商品 ID
      "channelSpecId": "string",          // 通路的規格 ID
      "quantity": "number",               // 退貨數量
      "unitPrice": "number"               // 單位退款價
    }
  ],
  "refundAmount": "number",               // 總退款金額
  "requestedAt": "ISO-8601",              // 退貨申請時間
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

**重點說明**：
- RefundOrder 是獨立的退款記錄，FK 到原 Order
- items 中包含該筆退貨的所有項目（JSONB 陣列）
- 通路特定信息（channelProductId, channelSpecId）用於追蹤

---

## 2. OMS 狀態機定義（統一標準）

### 2.1 Order Status Flow

```
PENDING → CONFIRMED → SHIPPED → COMPLETED
          ↓
       CANCELLED (可在任何時刻發生)

PENDING: 訂單已建立，待確認
CONFIRMED: 訂單已確認，準備出貨
SHIPPED: 已出貨，物流進行中
COMPLETED: 訂單完成
CANCELLED: 訂單已取消
```

### 2.2 Product Status

```
ACTIVE: 產品正常，可販售
INACTIVE: 產品停用，不可販售
```

### 2.3 Return Status Flow

```
PENDING → APPROVED → COMPLETED → REFUNDED
       ↓
    REJECTED (退貨被拒絕)

PENDING: 退貨申請中
APPROVED: 退貨已核准
REJECTED: 退貨被拒絕
COMPLETED: 退貨已完成（物品收回）
REFUNDED: 已退款
```

---

## 3. Platform Mapping（🔴 SAMPLE - 待實現）

### ⚠️ 注意
以下各平台的映射規則現在都是 **SAMPLE 版本**，基於通常的電商平台模式。

**必須在看到實際 API 文件後，一個一個更新為真實規範**。

---

## 3.1 Shopee Platform Mapping

### Order Mapping（SAMPLE）

| OMS 字段 | Shopee API 字段 | 備註 |
|---------|-----------------|------|
| orderId | 由 OMS 生成 | - |
| channelOrderId | `order_id` | Shopee 的 order_id |
| channelId | `shop_id` | Shopee 商店 ID |
| orderStatus | `order_status` | 見下表 |
| orderDate | `create_time` | Unix timestamp → ISO-8601 |
| totalAmount | `total_amount` | 單位：分（需除以 100） |
| items[].channelSku | `item_sku` | Shopee SKU |
| shipping.trackingNumber | `tracking_number` | 物流追蹤號 |

### Shopee Order Status → OMS Order Status（SAMPLE）

| Shopee Status | OMS Status | 備註 |
|---------------|-----------|------|
| UNPAID | PENDING | 待付款 |
| READY_TO_SHIP | CONFIRMED | 待出貨 |
| SHIPPED | SHIPPED | 已出貨 |
| COMPLETED | COMPLETED | 已完成 |
| CANCELLED | CANCELLED | 已取消 |
| RETURN_REFUND | 特殊處理 | 觸發 RETURN 流程 |

---

## 3.2 Momo Platform Mapping（SAMPLE）

### Order Mapping（SAMPLE）

| OMS 字段 | Momo API 字段 | 備註 |
|---------|---------------|------|
| orderId | 由 OMS 生成 | - |
| channelOrderId | `order_no` | Momo 訂單編號 |
| channelId | `store_id` | Momo 店家 ID |
| orderStatus | `order_status` | 見下表 |
| totalAmount | `total_price` | 單位：元 |
| items[].channelSku | `product_id` | Momo 商品 ID |

### Momo Order Status → OMS Status（SAMPLE）

| Momo Status | OMS Status | 備註 |
|------------|-----------|------|
| WAITING_PAYMENT | PENDING | 待付款 |
| AWAITING_SHIPMENT | CONFIRMED | 待出貨 |
| SHIPPED | SHIPPED | 已出貨 |
| DELIVERED | COMPLETED | 已送達 |
| CANCELLED | CANCELLED | 已取消 |

---

## 3.3 Yahoo Platform Mapping（SAMPLE）

### Order Mapping（SAMPLE）

| OMS 字段 | Yahoo API 字段 | 備註 |
|---------|-----------------|------|
| orderId | 由 OMS 生成 | - |
| channelOrderId | `order_id` | Yahoo 訂單 ID |
| channelId | `seller_id` | Yahoo 賣家 ID |
| orderStatus | 由更新時間推導 | Yahoo 無明確狀態字段 |
| totalAmount | `total_amount` | 單位：元 |

---

## 3.4 PChome Platform Mapping（SAMPLE）

### Order Mapping（SAMPLE）

| OMS 字段 | PChome API 字段 | 備註 |
|---------|-----------------|------|
| orderId | 由 OMS 生成 | - |
| channelOrderId | `order_id` | PChome 訂單 ID |
| channelId | `shop_id` | PChome 店家 ID |
| orderStatus | `status` | 見下表 |
| totalAmount | `total` | 單位：元 |

### PChome Order Status → OMS Status（SAMPLE）

| PChome Status | OMS Status | 備註 |
|--------------|-----------|------|
| 1 | PENDING | 待確認 |
| 2 | CONFIRMED | 已確認 |
| 3 | SHIPPED | 已出貨 |
| 4 | COMPLETED | 已完成 |
| 0 | CANCELLED | 已取消 |

---

## 3.5 easystore Platform Mapping（SAMPLE）

### Order Mapping（SAMPLE）

| OMS 字段 | easystore API 字段 | 備註 |
|---------|-------------------|------|
| orderId | 由 OMS 生成 | - |
| channelOrderId | `order_id` | easystore 訂單 ID |
| orderStatus | `status` | 見下表 |
| totalAmount | `total_amount` | 單位：元 |

### easystore Order Status → OMS Status（SAMPLE）

| easystore Status | OMS Status | 備註 |
|-----------------|-----------|------|
| new | PENDING | 新訂單 |
| confirmed | CONFIRMED | 已確認 |
| shipped | SHIPPED | 已出貨 |
| completed | COMPLETED | 已完成 |
| cancelled | CANCELLED | 已取消 |

---

## 3.6 Cyberbiz Platform Mapping（SAMPLE - 待實現）

🔴 **Cyberbiz API 映射規則待確認**

需要在看到 Cyberbiz 實際 API 文件後補充。

---

## 4. 未來更新計畫

| 通路 | Schema 確認 | Status 映射確認 | API 驗證完成 |
|------|-----------|---------------|-----------|
| Shopee | ✅ | ✅ SAMPLE | ⏳ TODO |
| Momo | ✅ | ✅ SAMPLE | ⏳ TODO |
| Yahoo | ✅ | ✅ SAMPLE | ⏳ TODO |
| PChome | ✅ | ✅ SAMPLE | ⏳ TODO |
| easystore | ✅ | ✅ SAMPLE | ⏳ TODO |
| Cyberbiz | ✅ | ⏳ TODO | ⏳ TODO |

---

## 5. Job 實現時的使用方式

當實現 Channel Job 時，**必須使用此映射表**：

```java
// 偽代碼
ShopeeOrder shopeeOrder = apiClient.getOrder(orderId);

// 使用 PLATFORM_MAPPING.md 中的對照表轉換
OmsOrder omsOrder = new OmsOrder();
omsOrder.setOrderId(generateOmsOrderId());
omsOrder.setChannelOrderId(shopeeOrder.getOrderId());
omsOrder.setOrderStatus(mapShopeeStatusToOmsStatus(shopeeOrder.getStatus()));
omsOrder.setTotalAmount(shopeeOrder.getTotalAmount());
// ... 其他字段映射
```

**關鍵點**：
- ✅ 使用映射表確保一致性
- ✅ 處理單位轉換（Shopee 用分，Momo 用元）
- ✅ 處理狀態映射（各平台狀態 → OMS 狀態）
- ✅ 處理日期格式（各平台時間格式 → ISO-8601）
