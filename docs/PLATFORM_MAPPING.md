# SimpleEC OMS Platform Mapping 標準

⚠️ **重要聲明 - 部分內容為示例**

本文件分為兩部分：

## 🟢 正式標準（已定義，不會改變）
- **OMS Schema 定義**（Order, Product, Pack, Return 結構）
- **OMS 狀態機定義**（訂單、商品、退貨的統一狀態）
- **TaskType 定義**（FETCH_ORDERS, SHIP_ORDER 等行為）

## 🔴 待實現部分（現在是 SAMPLE，逐步更新）
- **各平台 API 映射**（Shopee / Momo / Yahoo / PChome / easystore / Cyberbiz）
- **平台狀態 → OMS 狀態的對照表**
- **平台字段 → OMS 字段的映射規則**

未來當實現各平台時，會一個一個更新這些 SAMPLE 部分為真實 API 規範。

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
  "orderDate": "ISO-8601",                // 訂單建立時間
  "totalAmount": "number",                // 訂單總金額（含運費）
  "currency": "TWD|USD",                  // 幣別
  "shippingFee": "number",                // 運費
  "discountAmount": "number",             // 折扣
  "items": [                              // 訂單項目
    {
      "itemId": "string",
      "productId": "string",              // 指向 Product.productId
      "packId": "string",                 // 指向 Pack.packId（if applicable）
      "channelSku": "string",             // 通路 SKU
      "quantity": "number",
      "unitPrice": "number",
      "subtotal": "number"
    }
  ],
  "shipping": {
    "recipientName": "string",
    "phone": "string",
    "address": "string",
    "shippingStatus": "PENDING|SHIPPED|DELIVERED",
    "trackingNumber": "string"
  },
  "payment": {
    "paymentMethod": "CREDIT_CARD|BANK_TRANSFER|COD",
    "paymentStatus": "UNPAID|PAID|REFUNDED",
    "paidAt": "ISO-8601"
  },
  "isRollback": "boolean",                // 是否為回補訂單（遺漏的過往訂單）
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

### 1.2 Product Schema

```json
{
  "productId": "string",                  // OMS 系統產品 ID（unique key）
  "sku": "string",                        // OMS SKU（多通路聚合後的唯一識別）
  "productName": "string",
  "categoryId": "string",
  "attributes": [                         // 產品屬性（e.g., 顏色、尺寸）
    {
      "name": "string",
      "value": "string"
    }
  ],
  "basePrice": "number",                  // 基礎價格
  "cost": "number",                       // 成本
  "stock": "number",                      // 庫存數量
  "warehouseId": "string",                // 倉庫 ID
  "status": "ACTIVE|INACTIVE",
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

### 1.3 Pack Schema

```json
{
  "packId": "string",                     // OMS 系統套包 ID（unique key）
  "platformId": "string",                 // 通路識別（shopee, momo, yahoo 等）
  "specId": "string",                     // 通路上的規格 ID
  "packName": "string",                   // 套包名稱（e.g., "蘋果紅禮盒組"）
  "products": [                           // 套包包含的產品
    {
      "productId": "string",              // 指向 Product.productId
      "quantity": "number",
      "isMainProduct": "boolean"
    }
  ],
  "packPrice": "number",                  // 套包價格
  "discountAmount": "number",             // 套包折扣
  "status": "ACTIVE|INACTIVE",
  "channelUrl": "string",                 // 通路上的套包 URL
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

### 1.4 Return Schema

```json
{
  "returnId": "string",                   // OMS 系統退貨 ID（unique key）
  "orderId": "string",                    // 指向 Order.orderId
  "channelReturnId": "string",            // 通路的退貨 ID
  "returnStatus": "PENDING|APPROVED|REJECTED|COMPLETED|REFUNDED",
  "reason": "string",                     // 退貨原因
  "items": [                              // 退貨項目
    {
      "itemId": "string",                 // 指向 Order.items[x].itemId
      "quantity": "number"
    }
  ],
  "refundAmount": "number",               // 退款金額
  "requestedAt": "ISO-8601",
  "approvedAt": "ISO-8601",
  "completedAt": "ISO-8601",
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

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
