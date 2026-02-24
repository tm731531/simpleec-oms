# Kafka 消息样本字段验证矩阵

> 对比 EVENT_SAMPLES.md 中的 Kafka 消息字段与 docker/init-db/01-schema.sql 数据库定义

## 验证概述

### 检查清单
- [ ] PROCESS_ORDER 消息 → orders 表字段映射
- [ ] PROCESS_ORDER.items → orders.items JSONB 结构
- [ ] PROCESS_RETURN 消息 → refund_orders 表字段映射
- [ ] SYNC_PRODUCT 消息 → product 表字段映射
- [ ] SYNC_PACK 消息 → sell_pack 表字段映射
- [ ] SHIP_ORDER 消息 → order_shipments 表字段映射
- [ ] UPDATE_INVENTORY 消息 → product 表库存字段

---

## 1️⃣ PROCESS_ORDER 消息验证

### 消息定义（EVENT_SAMPLES.md:370-418）
```json
{
  "header": {
    "taskType": "PROCESS_ORDER",
    "merchantId": "M001",
    "platformId": "momo",
    "channelId": "MOMO_001",
    "requestId": "process_req_001",
    "timestamp": "2026-02-13T10:35:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "fetch_req_001",
    "isRollback": false
  },
  "body": {
    "orderData": {
      "orderId": "ord_abc123def456",
      "channelOrderId": "MOMO-2026021300001",
      "orderStatus": "PENDING",
      "buyerName": "王小明",
      "buyerPhone": "0912345678",
      "buyerEmail": "wang@example.com",
      "shippingAddress": "台北市中山區南京東路三段100號",
      "shippingMethod": "HOME_DELIVERY",
      "paymentMethod": "CREDIT_CARD",
      "totalAmount": 43900.00,
      "shippingFee": 0.00,
      "discountAmount": 1000.00,
      "channelCreatedAt": "2026-02-13T09:30:00Z",
      "paidAt": "2026-02-13T09:31:00Z",
      "items": [...]
    }
  }
}
```

### orders 表字段（schema.sql:268-292）
```sql
CREATE TABLE public.orders (
    id                 VARCHAR(20),       -- ← orderId
    merchant_id        VARCHAR(20),       -- ← header.merchantId
    channel_id         VARCHAR(20),       -- ← header.channelId
    channel_order_id   VARCHAR(100),      -- ← orderData.channelOrderId
    order_status       VARCHAR(20),       -- ← orderData.orderStatus
    buyer_name         VARCHAR(512),      -- ← orderData.buyerName (PII/AES)
    buyer_phone        VARCHAR(256),      -- ← orderData.buyerPhone (PII/AES)
    buyer_email        VARCHAR(512),      -- ← orderData.buyerEmail (PII/AES)
    shipping_address   TEXT,              -- ← orderData.shippingAddress (PII/AES)
    shipping_method    VARCHAR(50),       -- ← orderData.shippingMethod
    payment_method     VARCHAR(50),       -- ← orderData.paymentMethod
    total_amount       DECIMAL(12,2),     -- ← orderData.totalAmount
    shipping_fee       DECIMAL(12,2),     -- ← orderData.shippingFee
    discount_amount    DECIMAL(12,2),     -- ← orderData.discountAmount
    items              JSONB,             -- ← orderData.items (JSONB array)
    channel_created_at TIMESTAMPTZ,       -- ← orderData.channelCreatedAt
    paid_at            TIMESTAMPTZ,       -- ← orderData.paidAt
    shipped_at         TIMESTAMPTZ,       -- ← (NOT IN MESSAGE, updated later by SHIP_ORDER)
    created_at         TIMESTAMPTZ,       -- AUTO
    updated_at         TIMESTAMPTZ        -- AUTO
);
```

### 字段映射检查
| Kafka 字段 | 数据库字段 | 数据类型 | 状态 | 备注 |
|-----------|----------|--------|------|------|
| header.merchantId | merchant_id | VARCHAR(20) | ✅ | 必填 |
| header.channelId | channel_id | VARCHAR(20) | ✅ | 必填 |
| orderData.orderId | id | VARCHAR(20) | ✅ | 消息中生成或带入 |
| orderData.channelOrderId | channel_order_id | VARCHAR(100) | ✅ | Unique Key |
| orderData.orderStatus | order_status | VARCHAR(20) | ✅ | PENDING/CONFIRMED/... |
| orderData.buyerName | buyer_name | VARCHAR(512) | ✅ | AES-256-GCM 加密存储 |
| orderData.buyerPhone | buyer_phone | VARCHAR(256) | ✅ | AES-256-GCM 加密存储 |
| orderData.buyerEmail | buyer_email | VARCHAR(512) | ✅ | AES-256-GCM 加密存储 |
| orderData.shippingAddress | shipping_address | TEXT | ✅ | AES-256-GCM 加密存储 |
| orderData.shippingMethod | shipping_method | VARCHAR(50) | ✅ | 配送方式 |
| orderData.paymentMethod | payment_method | VARCHAR(50) | ✅ | 支付方式 |
| orderData.totalAmount | total_amount | DECIMAL(12,2) | ✅ | 订单总金额 |
| orderData.shippingFee | shipping_fee | DECIMAL(12,2) | ✅ | 运费 |
| orderData.discountAmount | discount_amount | DECIMAL(12,2) | ✅ | 折扣 |
| orderData.channelCreatedAt | channel_created_at | TIMESTAMPTZ | ✅ | 平台订单创建时间 |
| orderData.paidAt | paid_at | TIMESTAMPTZ | ✅ | 支付时间 |
| orderData.items | items | JSONB | ✅ | 订单项目数组 |

### ⚠️ 发现的问题

**问题 1：header 中缺少 platformId 到数据库的映射**
- Kafka 消息有 `header.platformId`（值为 "momo"）
- 但 orders 表没有 `platform_id` 字段
- Platform 信息可通过 channel_id 的 FK 关系获取
- **解决方案**：Handler 需要从 channel 表查询 platform_id，或在消息中添加 platform_id

**问题 2：shipped_at 不在消息中**
- orders.shipped_at 应该由 SHIP_ORDER 消息更新
- 当前 PROCESS_ORDER 中没有此字段
- **这是正常的** ✅ 因为订单创建时还未出货

---

## 2️⃣ PROCESS_ORDER.items 结构验证

### 消息中的 items 结构（EVENT_SAMPLES.md:400-415）
```json
"items": [
  {
    "sku": "IPHONE-15-PRO-MAX",
    "productId": "pd_xyz789",
    "channelProductId": "MOMO-SKU-001",
    "channelSpecId": "MOMO-SPEC-001",
    "channelItemId": "MOMO-ITEM-2026021300001",
    "channelProductName": "iPhone 15 Pro Max",
    "channelSpecName": "太空黑/256GB",
    "productName": "iPhone 15 Pro Max",
    "quantity": 1,
    "unitPrice": 44900.00,
    "subtotal": 44900.00,
    "sellPackId": "sp_abc123"
  }
]
```

### 项目字段映射
| Kafka 字段 | 数据库对应 | 描述 |
|-----------|----------|------|
| sku | product.sku | 我们的 SKU |
| productId | product.id | 指向 product 表 |
| channelProductId | sell_pack.channel_product_id | 通路的商品 ID |
| channelSpecId | sell_pack.channel_spec_id | 通路的规格 ID |
| channelItemId | — | 通路的行项目 ID（无法存储） |
| channelProductName | sell_pack.channel_product_name | 通路上的商品名 |
| channelSpecName | sell_pack.channel_spec_name | 通路上的规格名 |
| productName | product.name | 我们的商品名 |
| quantity | — | 订购数量（在 items JSONB 中） |
| unitPrice | — | 单价（在 items JSONB 中） |
| subtotal | — | 小计（在 items JSONB 中） |
| sellPackId | sell_pack.id | 指向 sell_pack 表 |

### ⚠️ 发现的问题

**问题 3：channelItemId 无处存储**
- Kafka 消息包含 `channelItemId`（通路的行项目 ID）
- 但 orders 表的 items JSONB 中没有定义模式
- **解决方案**：
  - 保留在 items JSONB 中作为临时数据
  - 或在 Handler 中记录到 channel_sync_logs 的 request_payload

**问题 4：orders.items 的 JSONB 模式未定义**
- 数据库只定义了 `items JSONB NOT NULL DEFAULT '[]'`
- 没有 JSON Schema 定义项目结构
- **建议**：在 docs/SCHEMA.md 中补充 JSON Schema 定义

---

## 3️⃣ PROCESS_RETURN 消息验证

### 消息定义（EVENT_SAMPLES.md:439-467）
```json
{
  "header": {
    "taskType": "PROCESS_RETURN",
    "merchantId": "M001",
    "channelId": "YAHOO_001",
    "requestId": "req-20260213-310000",
    "timestamp": "2026-02-13T11:30:00Z",
    "source": "channel_job",
    "version": 1,
    "correlationId": "req-20260213-200002"
  },
  "body": {
    "channelReturnId": "YH-RET-2026021300001",
    "returnData": {
      "returnStatus": "PENDING_APPROVAL",
      "reason": "SIZE_MISMATCH",
      "requestedAmount": 5000,
      "requestDate": "2026-02-13T11:00:00Z",
      "items": [
        {
          "productId": "SKU002",
          "quantity": 1,
          "unitPrice": 5000
        }
      ]
    }
  }
}
```

### refund_orders 表字段（schema.sql:340-354）
```sql
CREATE TABLE public.refund_orders (
    id                VARCHAR(20),       -- 生成的退款单 ID
    order_id          VARCHAR(20),       -- ← (需要查询 orders 表)
    merchant_id       VARCHAR(20),       -- ← header.merchantId
    channel_refund_id VARCHAR(100),      -- ← body.channelReturnId
    refund_status     VARCHAR(20),       -- ← returnData.returnStatus
    refund_amount     DECIMAL(12,2),     -- ← returnData.requestedAmount
    reason            TEXT,              -- ← returnData.reason
    items             JSONB,             -- ← returnData.items
    created_at        TIMESTAMPTZ,       -- AUTO
    updated_at        TIMESTAMPTZ        -- AUTO
);
```

### 字段映射检查
| Kafka 字段 | 数据库字段 | 数据类型 | 状态 | 备注 |
|-----------|----------|--------|------|------|
| header.merchantId | merchant_id | VARCHAR(20) | ✅ | 必填 |
| body.channelReturnId | channel_refund_id | VARCHAR(100) | ✅ | 通路的退货 ID |
| returnData.returnStatus | refund_status | VARCHAR(20) | ✅ | PENDING_APPROVAL/... |
| returnData.requestedAmount | refund_amount | DECIMAL(12,2) | ✅ | 退款金额 |
| returnData.reason | reason | TEXT | ✅ | 退货原因 |
| returnData.requestDate | — | — | ❌ | 消息有但数据库没有！ |
| returnData.items | items | JSONB | ✅ | 退货项目 |

### ⚠️ 发现的问题

**问题 5：requestDate 字段没有数据库对应**
- 消息包含 `returnData.requestDate: "2026-02-13T11:00:00Z"`
- refund_orders 表没有此字段
- 只有 created_at（Handler 创建时间）
- **解决方案**：
  - 将 requestDate 存储在 items JSONB 中，或
  - 在 refund_orders 表添加 requested_at 字段

**问题 6：缺少 order_id FK 映射**
- 消息没有直接提供 order_id
- Handler 需要通过 channel_id + 某个映射字段查询 orders 表
- 但消息中没有 channel_order_id，无法直接关联
- **解决方案**：
  - 消息应包含原订单的 channelOrderId，或
  - Handler 从退货单关联信息中推导

**问题 7：returnData.items 缺少 channelProductId/channelSpecId**
- PROCESS_ORDER.items 包含完整的通路信息（channelProductId, channelSpecId 等）
- PROCESS_RETURN.items 只有 productId, quantity, unitPrice
- **建议**：为一致性，PROCESS_RETURN.items 应包含相同的字段

---

## 4️⃣ SYNC_PRODUCT 消息验证

### 消息定义（EVENT_SAMPLES.md:477-498）
```json
{
  "header": {
    "taskType": "SYNC_PRODUCT",
    "merchantId": "M001",
    "platformId": "shopee",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-320000",
    "timestamp": "2026-02-13T11:00:00Z",
    "source": "channel_job",
    "version": 1
  },
  "body": {
    "sku": "SH-SKU-001",
    "name": "iPhone 15 Pro Max",
    "price": 44900,
    "attributes": {
      "color": "Space Black",
      "capacity": "256GB"
    }
  }
}
```

### product 表字段（schema.sql:250-275）
```sql
CREATE TABLE public.product (
    id               VARCHAR(20),       -- NanoID
    merchant_id      VARCHAR(20),       -- ← header.merchantId
    product_group_id VARCHAR(20),       -- (可选，消息中没有)
    sku              VARCHAR(100),      -- ← body.sku
    name             VARCHAR(512),      -- ← body.name
    spec_summary     VARCHAR(256),      -- ← (无对应)
    cost_price       DECIMAL(12,2),     -- (消息中没有)
    suggest_price    DECIMAL(12,2),     -- ← body.price
    quantity         INTEGER,           -- (应从库存更新获取)
    safety_quantity  INTEGER,           -- (消息中没有)
    status           VARCHAR(20),       -- (消息中没有)
    created_at       TIMESTAMPTZ,       -- AUTO
    updated_at       TIMESTAMPTZ        -- AUTO
);
```

### 字段映射检查
| Kafka 字段 | 数据库字段 | 数据类型 | 状态 | 备注 |
|-----------|----------|--------|------|------|
| header.merchantId | merchant_id | VARCHAR(20) | ✅ | 必填 |
| body.sku | sku | VARCHAR(100) | ✅ | 商家内唯一 |
| body.name | name | VARCHAR(512) | ✅ | 商品名 |
| body.price | suggest_price | DECIMAL(12,2) | ✅ | 建议价格 |
| body.attributes | — | — | ⚠️ | 无直接映射 |

### ⚠️ 发现的问题

**问题 8：attributes 无处存储**
- 消息包含 `attributes: {color, capacity}`
- product 表没有 attributes/spec_summary 字段来存储这些详情
- **解决方案**：
  - 在 sell_pack 表添加 attributes JSON 字段（更合适，因为规格是通路特定的）
  - 如 sell_pack.channel_spec_attrs

**问题 10（已移除）：库存管理不用管**
- product.quantity = 我们的库存，sell_pack.quantity = 平台库存，各自独立管理
- UPDATE_INVENTORY 和 SYNC_PRODUCT 不涉及库存，只处理通路同步的元数据

---

## 5️⃣ SYNC_PACK 消息验证

### 消息定义（EVENT_SAMPLES.md:550-577）
```json
{
  "header": {
    "taskType": "SYNC_PACK",
    "merchantId": "M001",
    "platformId": "pchome",
    "channelId": "PCHOME_001",
    "requestId": "req-20260213-350000",
    "timestamp": "2026-02-13T06:30:00Z",
    "source": "admin_ui",
    "version": 1
  },
  "body": {
    "platformId": "pchome",
    "specId": "SPEC-001",
    "packName": "iPhone 15 Pro Max - Space Black 256GB",
    "sku": "PCHOME-SKU-001",
    "attributes": {
      "color": "Space Black",
      "capacity": "256GB"
    },
    "price": 44900,
    "packInfo": {
      "packStatus": "ACTIVE",
      "visibility": "VISIBLE"
    }
  }
}
```

### sell_pack 表字段（schema.sql:233-256）
```sql
CREATE TABLE public.sell_pack (
    id                   VARCHAR(20),   -- NanoID
    merchant_id          VARCHAR(20),   -- ← header.merchantId
    product_id           VARCHAR(20),   -- (需要查询关联)
    channel_id           VARCHAR(20),   -- ← header.channelId
    sku                  VARCHAR(100),  -- ← body.sku
    channel_product_id   VARCHAR(256),  -- (消息中没有)
    channel_spec_id      VARCHAR(256),  -- ← body.specId
    channel_product_name VARCHAR(512),  -- ← body.packName
    channel_spec_name    VARCHAR(256),  -- (可从 attributes 生成)
    channel_product_url  VARCHAR(1024), -- (消息中没有)
    title                VARCHAR(512),  -- (消息中没有)
    selling_price        DECIMAL(12,2), -- ← body.price
    quantity             INTEGER,       -- (消息中没有)
    status               VARCHAR(20),   -- ← body.packInfo.packStatus
    last_sync_at         TIMESTAMPTZ,   -- AUTO
    created_at           TIMESTAMPTZ,   -- AUTO
    updated_at           TIMESTAMPTZ    -- AUTO
);
```

### 字段映射检查
| Kafka 字段 | 数据库字段 | 数据类型 | 状态 | 备注 |
|-----------|----------|--------|------|------|
| header.merchantId | merchant_id | VARCHAR(20) | ✅ | 必填 |
| header.channelId | channel_id | VARCHAR(20) | ✅ | 必填 |
| body.sku | sku | VARCHAR(100) | ✅ | 通路 SKU |
| body.specId | channel_spec_id | VARCHAR(256) | ✅ | 规格 ID |
| body.packName | channel_product_name | VARCHAR(512) | ✅ | 上架名称 |
| body.attributes | — | — | ⚠️ | 可推导 channel_spec_name |
| body.price | selling_price | DECIMAL(12,2) | ✅ | 销售价 |
| body.packInfo.packStatus | status | VARCHAR(20) | ✅ | ACTIVE/INACTIVE |
| body.packInfo.visibility | — | — | ❌ | 无处存储 |

### ⚠️ 发现的问题

**问题 11：channel_product_id 缺失**
- sell_pack.channel_product_id 是重要字段（Unique Key 的一部分）
- SYNC_PACK 消息中没有提供
- **解决方案**：消息应包含 channelProductId

**问题 9（已降级为低优先级）：channel_product_url 和 title 缺失**
- 消息中没有产品 URL 或标题
- **建议**：从通路 API 补充获取（非关键）

**问题 12：visibility 字段无处存储**
- 消息包含 `packInfo.visibility: "VISIBLE"`
- sell_pack 表没有此字段
- **解决方案**：在 sell_pack 表添加 visibility 字段

**问题 13（已移除）：quantity 字段**
- sell_pack.quantity = 平台库存，product.quantity = 我们的库存，独立管理
- 通路库存由平台各自维护，不在 OMS 中同步

---

## 6️⃣ SHIP_ORDER 消息验证

### 消息定义（EVENT_SAMPLES.md:539-545）
```json
{
  "header": {
    "taskType": "SHIP_ORDER",
    "merchantId": "M001",
    "channelId": "SHOPEE_001",
    "requestId": "req-20260213-340000",
    "timestamp": "2026-02-13T12:00:00Z",
    "source": "scheduler",
    "version": 1
  },
  "body": {
    "orderId": "ORD-SYS-20260213-001",
    "channelOrderId": "SH202602130456",
    "shippingMethod": "SHOPEE_PICKUP",
    "trackingNumber": "SE123456789"
  }
}
```

### order_shipments 表字段（schema.sql:320-332）
```sql
CREATE TABLE public.order_shipments (
    id                VARCHAR(20),  -- NanoID
    order_id          VARCHAR(20),  -- ← body.orderId (需要验证/查询)
    tracking_number   VARCHAR(100), -- ← body.trackingNumber
    logistics_company VARCHAR(100), -- (消息中没有，需从 shippingMethod 推导)
    shipping_status   VARCHAR(20),  -- (消息中没有)
    shipped_at        TIMESTAMPTZ,  -- ← header.timestamp
    delivered_at      TIMESTAMPTZ,  -- (后续更新)
    created_at        TIMESTAMPTZ,  -- AUTO
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_id)
);
```

### orders 表更新（从 PROCESS_ORDER 的 shipped_at）
- SHIP_ORDER 消息应更新 orders.shipped_at = header.timestamp
- SHIP_ORDER 消息应更新 orders.order_status → "SHIPPED"

### 字段映射检查
| Kafka 字段 | 数据库字段 | 数据类型 | 状态 | 备注 |
|-----------|----------|--------|------|------|
| body.orderId | order_shipments.order_id | VARCHAR(20) | ✅ | FK 到 orders |
| body.trackingNumber | tracking_number | VARCHAR(100) | ✅ | 追踪号 |
| body.shippingMethod | logistics_company | VARCHAR(100) | ⚠️ | 需推导物流公司名 |
| header.timestamp | shipped_at | TIMESTAMPTZ | ✅ | 出货时间 |

### ⚠️ 发现的问题

**问题 15：logistics_company 需要从 shippingMethod 推导**
- 消息提供 `shippingMethod: "SHOPEE_PICKUP"`
- 数据库需要 logistics_company（如 "7-ELEVEN", "BLACK_CAT"）
- **解决方案**：Handler 需要维护 shippingMethod → logistics_company 映射表

**问题 16：shipping_status 未设置**
- order_shipments.shipping_status 默认为 "pending"
- SHIP_ORDER 消息应将其更新为 "shipped"
- **建议**：消息体应包含 shippingStatus 字段

---

## 7️⃣ UPDATE_INVENTORY 消息验证（库存各自独立管理）

### 原则
- **product.quantity** = 我们的仓库库存（由我们维护）
- **sell_pack.quantity** = 各平台的库存（由平台各自维护）
- UPDATE_INVENTORY 消息在OMS中**不涉及库存更新**，仅作为同步元数据的一部分

### 结论 ✅
- UPDATE_INVENTORY 消息处理：可记录到 channel_sync_logs，但不更新库存字段
- 倉庫就是倉庫，平台就是平台，两边库存各自管理

---

## 总结：发现的问题清单

> 📌 **库存分离原则**：product.quantity = 我们的库存，sell_pack.quantity = 平台库存，各自独立管理，UPDATE_INVENTORY 不纠结

### 高优先级 🔴（必须修复）

| # | 问题 | 位置 | 解决方案 |
|----|------|------|--------|
| 1 | platformId 无数据库对应 | PROCESS_ORDER | 消息添加 platformId FK 或 Handler 查询 |
| 3 | channelItemId 无法存储 | PROCESS_ORDER.items | 在 items JSONB 中保留或记录到 sync_logs |
| 5 | requestDate 无数据库对应 | PROCESS_RETURN | refund_orders 表添加 requested_at 字段 |
| 6 | order_id FK 映射不清晰 | PROCESS_RETURN | 消息应包含原订单信息或关联字段 |
| 8 | attributes 无处存储 | SYNC_PRODUCT | sell_pack 表添加 attributes JSON（通路规格） |
| 11 | channel_product_id 缺失 | SYNC_PACK | 消息应包含 channelProductId |

### 中优先级 ⚠️（应该改进）

| # | 问题 | 位置 | 解决方案 |
|----|------|------|--------|
| 2 | shipped_at 在消息中缺失 | PROCESS_ORDER | 正常（出货时由 SHIP_ORDER 更新） |
| 7 | returnData.items 信息不完整 | PROCESS_RETURN | items 应包含完整的通路信息（channelProductId 等） |
| 12 | visibility 字段无处存储 | SYNC_PACK | sell_pack 表添加 visibility 字段 |
| 13 | logistics_company 需要推导 | SHIP_ORDER | 维护 shippingMethod → logistics_company 映射表 |
| 14 | shipping_status 未设置 | SHIP_ORDER | 消息应包含 shippingStatus，或 Handler 推导 |

### 低优先级 💡（建议）

| # | 问题 | 位置 | 解决方案 |
|----|------|------|--------|
| 4 | items JSONB 模式未定义 | orders.items | 在 SCHEMA.md 中补充 JSON Schema 定义 |
| 9 | 通路 URL 和标题缺失 | SYNC_PACK | 从通路 API 补充获取 |

---

## 建议修复优先顺序

1. **第 1 阶段（关键）**：修复 #1, #3, #5, #6, #8, #11
   - 这些影响消息到数据库的正确映射
   - 需要修改消息模式或数据库 schema

2. **第 2 阶段（重要）**：修复 #2, #7, #12, #13, #14
   - 提高数据完整性和一致性
   - 可能需要修改消息模式或 Handler 逻辑

3. **第 3 阶段（优化）**：修复 #4, #9
   - 文档完善和设计优化
   - 不影响当前功能，但提高可维护性

---

**验证完成日期**：2026-02-20
**验证人**：Claude Code
**状态**：需要修复高优先级问题后重新验证
