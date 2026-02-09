# Level 1 實作計畫 — Entity ↔ Schema 對齊

> **狀態：已完成** (2026-02-09)
>
> 目標：讓 `docker compose up -d` 後所有 26 個容器啟動且**無 DB 相關 runtime error**。
> 不含 Phase 4 業務邏輯（TODO 保留），只修正結構性的型別與欄位不匹配。
>
> **注意**：實作時發現 MyBatis-Plus `IdType.ASSIGN_UUID` 的 `nextId()` 回傳 `Number`，
> 不支援 String。改用 `IdType.ASSIGN_UUID` + 自訂 `nextUUID()` 回傳 NanoID。

---

## 問題摘要

| # | 問題 | 影響 |
|---|------|------|
| 1 | **所有 Entity PK 是 `Long id` + `IdType.AUTO`** | Schema 用 `VARCHAR(20)` NanoID，insert 必 crash |
| 2 | **OrderItem.java** 映射到不存在的 `order_items` 表 | 任何涉及 OrderItem 的查詢 crash |
| 3 | **ProductSpec.java** 映射到不存在的 `product_spec` 表 | 同上 |
| 4 | **Product.java 欄位不匹配** | `itemNumber` vs `sku`、多餘欄位、缺少欄位 |
| 5 | **SellPack.java 缺少 4 個欄位** | `sku`, `channel_spec_id`, `channel_product_name`, `channel_spec_name` |
| 6 | **Order.java 缺少 `items` JSONB 欄位** | Schema v4 訂單明細存 JSONB |
| 7 | **所有 FK 欄位用 Long** | Schema FK 全是 `VARCHAR(20)` |
| 8 | **ChannelAdapter 介面用 `Long channelId`** | 與 Schema 不匹配 |
| 9 | **Controller / Service 參數用 Long** | 與 Schema 不匹配 |

---

## 修改清單（依執行順序）

### Step 1：加入 NanoID 依賴

**檔案：`simpleec-common/build.gradle`**

```gradle
// 現在
dependencies {
    implementation 'com.fasterxml.jackson.core:jackson-annotations'
}

// 改為
dependencies {
    implementation 'com.fasterxml.jackson.core:jackson-annotations'
    api 'com.aventrix.jnanoid:jnanoid:2.0.0'
}
```

> NanoID 放 `simpleec-common` 是因為所有模組都能用到。

---

### Step 2：建立 NanoID 工具類

**新增檔案：`simpleec-common/src/main/java/com/simpleec/common/util/IdGenerator.java`**

```java
package com.simpleec.common.util;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;

/**
 * 統一 ID 產生器 — VARCHAR(20) NanoID
 * 與 01-schema.sql PK 規格一致
 */
public final class IdGenerator {

    private static final int ID_LENGTH = 20;

    private IdGenerator() {}

    public static String next() {
        return NanoIdUtils.randomNanoId(
            NanoIdUtils.DEFAULT_NUMBER_GENERATOR,
            NanoIdUtils.DEFAULT_ALPHABET,
            ID_LENGTH
        );
    }
}
```

---

### Step 3：建立 MyBatis-Plus ID 自動填充

**新增檔案：`simpleec-core/src/main/java/com/simpleec/core/config/MybatisPlusConfig.java`**

```java
package com.simpleec.core.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.simpleec.common.util.IdGenerator;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
```

> 注意：ID 由 `IdType.ASSIGN_UUID` + 自訂 `IdentifierGenerator` 處理，或在 Service 層手動呼叫 `IdGenerator.next()`。
> 建議用 `IdType.ASSIGN_UUID` 搭配自訂 generator（見下方 Entity 修改）。

**在 `MybatisPlusConfig.java` 增加自訂 ID 生成器：**

```java
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;

@Bean
public IdentifierGenerator identifierGenerator() {
    return entity -> IdGenerator.next();
}
```

這樣所有標記 `@TableId(type = IdType.ASSIGN_UUID)` 的 Entity insert 時會自動產生 NanoID。

---

### Step 4：修改 Entity — Order.java

**檔案：`simpleec-core/src/main/java/com/simpleec/core/entity/Order.java`**

| 欄位 | 現在 | 改為 | 原因 |
|------|------|------|------|
| `id` | `Long` + `IdType.AUTO` | `String` + `IdType.ASSIGN_UUID` | Schema `VARCHAR(20)` |
| `merchantId` | `Long` | `String` | Schema `VARCHAR(20)` |
| `channelId` | `Long` | `String` | Schema `VARCHAR(20)` |
| `items` | 不存在 | 新增 `String` | Schema `JSONB NOT NULL DEFAULT '[]'` |

**完整修改後：**

```java
package com.simpleec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Order {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String merchantId;
    private String channelId;
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

    /** JSONB — 訂單明細，JSON array string */
    private String items;

    private LocalDateTime channelCreatedAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

---

### Step 5：修改 Entity — Product.java

**檔案：`simpleec-core/src/main/java/com/simpleec/core/entity/Product.java`**

| 欄位 | 現在 | 改為 | 原因 |
|------|------|------|------|
| `id` | `Long` + `IdType.AUTO` | `String` + `IdType.ASSIGN_UUID` | Schema `VARCHAR(20)` |
| `merchantId` | `Long` | `String` | Schema `VARCHAR(20)` |
| `itemNumber` | `String` | **刪除**，改為 `sku` | Schema 欄位名 `sku` |
| `description` | `String` | **刪除** | Schema v4 無此欄位（移到 product_group） |
| `brand` | `String` | **刪除** | Schema v4 無此欄位（移到 product_group） |
| `mainImageUrl` | `String` | **刪除** | Schema v4 無此欄位（移到 product_group） |
| `totalQuantity` | `Integer` | 改名 `quantity` | Schema 欄位名 `quantity` |
| `productGroupId` | 不存在 | 新增 `String` | Schema `VARCHAR(20)` FK |
| `specSummary` | 不存在 | 新增 `String` | Schema `VARCHAR(256)` |

**完整修改後：**

```java
package com.simpleec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String merchantId;
    private String productGroupId;
    private String sku;
    private String name;
    private String specSummary;
    private BigDecimal costPrice;
    private BigDecimal suggestPrice;
    private Integer quantity;
    private Integer safetyQuantity;
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

---

### Step 6：修改 Entity — SellPack.java

**檔案：`simpleec-core/src/main/java/com/simpleec/core/entity/SellPack.java`**

| 欄位 | 現在 | 改為 | 原因 |
|------|------|------|------|
| `id` | `Long` + `IdType.AUTO` | `String` + `IdType.ASSIGN_UUID` | Schema `VARCHAR(20)` |
| `merchantId` | `Long` | `String` | Schema `VARCHAR(20)` |
| `productId` | `Long` | `String` | Schema `VARCHAR(20)` |
| `channelId` | `Long` | `String` | Schema `VARCHAR(20)` |
| `sku` | 不存在 | 新增 `String` | Schema `VARCHAR(100) NOT NULL` |
| `channelSpecId` | 不存在 | 新增 `String` | Schema `VARCHAR(256)` |
| `channelProductName` | 不存在 | 新增 `String` | Schema `VARCHAR(512)` |
| `channelSpecName` | 不存在 | 新增 `String` | Schema `VARCHAR(256)` |

**完整修改後：**

```java
package com.simpleec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sell_pack")
public class SellPack {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String merchantId;
    private String productId;
    private String channelId;
    private String sku;
    private String channelProductId;
    private String channelSpecId;
    private String channelProductName;
    private String channelSpecName;
    private String channelProductUrl;
    private String title;
    private BigDecimal sellingPrice;
    private Integer quantity;
    private String status;

    private LocalDateTime lastSyncAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

---

### Step 7：修改 Entity — OrderStatusLog.java

**檔案：`simpleec-core/src/main/java/com/simpleec/core/entity/OrderStatusLog.java`**

| 欄位 | 現在 | 改為 |
|------|------|------|
| `id` | `Long` + `IdType.AUTO` | `String` + `IdType.ASSIGN_UUID` |
| `orderId` | `Long` | `String` |

**完整修改後：**

```java
package com.simpleec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("order_status_logs")
public class OrderStatusLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String orderId;
    private String fromStatus;
    private String toStatus;
    private String operator;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
```

---

### Step 8：刪除不存在的 Entity 和 Mapper

**刪除以下 4 個檔案**（對應的 DB 表在 Schema v4 已不存在）：

| 檔案 | 原因 |
|------|------|
| `simpleec-core/.../entity/OrderItem.java` | 無 `order_items` 表，明細存 `orders.items` JSONB |
| `simpleec-core/.../entity/ProductSpec.java` | 無 `product_spec` 表，規格合併到 Product |
| `simpleec-core/.../mapper/OrderItemMapper.java` | Entity 刪除，Mapper 跟著刪 |
| `simpleec-core/.../mapper/ProductSpecMapper.java` | Entity 刪除，Mapper 跟著刪 |

---

### Step 9：修改 OrderService.java

**檔案：`simpleec-core/src/main/java/com/simpleec/core/service/OrderService.java`**

**變更要點：**

1. 移除 `OrderItemMapper` 依賴
2. 移除 `OrderItem` 相關 import
3. `saveOrder(Order, List<OrderItem>)` → `saveOrder(Order)`（items 已是 Order 的 JSONB 欄位）
4. 移除 `getOrderItems(Long)` 方法
5. 所有 `Long` 參數 → `String`

**完整修改後：**

```java
package com.simpleec.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simpleec.common.model.PageResult;
import com.simpleec.core.entity.Order;
import com.simpleec.core.entity.OrderStatusLog;
import com.simpleec.core.mapper.OrderMapper;
import com.simpleec.core.mapper.OrderStatusLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;

    public PageResult<Order> list(String merchantId, int page, int size) {
        Page<Order> result = orderMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getMerchantId, merchantId)
                        .orderByDesc(Order::getCreatedAt)
        );
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    public Order getByChannelOrderId(String channelId, String channelOrderId) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getChannelId, channelId)
                        .eq(Order::getChannelOrderId, channelOrderId)
        );
    }

    @Transactional
    public void saveOrder(Order order) {
        if (order.getId() == null) {
            orderMapper.insert(order);
        } else {
            orderMapper.updateById(order);
        }
    }

    @Transactional
    public void updateStatus(String orderId, String fromStatus, String toStatus,
                             String operator, String remark) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("Order not found: " + orderId);
        }
        order.setOrderStatus(toStatus);
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);

        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(orderId);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperator(operator);
        log.setRemark(remark);
        orderStatusLogMapper.insert(log);
    }
}
```

---

### Step 10：修改 ProductService.java

**檔案：`simpleec-core/src/main/java/com/simpleec/core/service/ProductService.java`**

**變更要點：**

1. `Long merchantId` → `String merchantId`
2. `Long id` → `String id`
3. `getByItemNumber()` → `getBySku()`，`Product::getItemNumber` → `Product::getSku`

**完整修改後：**

```java
package com.simpleec.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simpleec.common.model.PageResult;
import com.simpleec.core.entity.Product;
import com.simpleec.core.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;

    public PageResult<Product> list(String merchantId, int page, int size) {
        Page<Product> result = productMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Product>()
                        .eq(Product::getMerchantId, merchantId)
                        .ne(Product::getStatus, "deleted")
                        .orderByDesc(Product::getCreatedAt)
        );
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    public Product getById(String id) {
        return productMapper.selectById(id);
    }

    public void save(Product product) {
        if (product.getId() == null) {
            productMapper.insert(product);
        } else {
            productMapper.updateById(product);
        }
    }

    public Product getBySku(String merchantId, String sku) {
        return productMapper.selectOne(
                new LambdaQueryWrapper<Product>()
                        .eq(Product::getMerchantId, merchantId)
                        .eq(Product::getSku, sku)
        );
    }
}
```

---

### Step 11：修改 OrderController.java

**檔案：`simpleec-api/src/main/java/com/simpleec/api/controller/OrderController.java`**

**變更要點：**

- `@RequestParam Long merchantId` → `@RequestParam String merchantId`
- `@PathVariable Long id` → `@PathVariable String id`

**現在的程式碼：**

```java
@GetMapping
public ApiResponse<PageResult<Order>> list(
        @RequestParam Long merchantId,
        ...
```

**改為：**

```java
@GetMapping
public ApiResponse<PageResult<Order>> list(
        @RequestParam String merchantId,
        ...
```

同理所有 `@PathVariable Long id` → `@PathVariable String id`。

---

### Step 12：修改 ProductController.java

**檔案：`simpleec-api/src/main/java/com/simpleec/api/controller/ProductController.java`**

同 Step 11：
- `@RequestParam Long merchantId` → `@RequestParam String merchantId`
- `@PathVariable Long id` → `@PathVariable String id`
- `productService.getById(Long)` → `productService.getById(String)`

---

### Step 13：修改 ChannelActionController.java

**檔案：`simpleec-api/src/main/java/com/simpleec/api/controller/ChannelActionController.java`**

**現在：**

```java
@RequestParam Long merchantId,
@RequestParam Long channelId,
...
String.valueOf(merchantId)    // 轉 String 傳給 TaskMessage
String.valueOf(channelId)
```

**改為：**

```java
@RequestParam String merchantId,
@RequestParam String channelId,
...
merchantId    // 已經是 String，不需轉換
channelId
```

---

### Step 14：修改 ChannelAdapter.java 介面

**檔案：`simpleec-channel/src/main/java/com/simpleec/channel/adapter/ChannelAdapter.java`**

**所有 `Long channelId` 參數 → `String channelId`**

```java
// 現在
String createListing(Long channelId, SellPack sellPack, Map<String, Object> extraData);
void updateListing(Long channelId, SellPack sellPack, Map<String, Object> extraData);
void updatePrice(Long channelId, String channelProductId, BigDecimal price);
void updateQuantity(Long channelId, String channelProductId, int quantity);
void startSelling(Long channelId, String channelProductId);
void stopSelling(Long channelId, String channelProductId);
List<Order> fetchOrders(Long channelId, LocalDateTime from, LocalDateTime to);
void confirmShipment(Long channelId, String channelOrderId, String trackingNumber, String logisticsCompany);
void acceptCancellation(Long channelId, String channelOrderId);
String getShippingLabel(Long channelId, String channelOrderId);
boolean validateConnection(Map<String, String> credentials);

// 改為
String createListing(String channelId, SellPack sellPack, Map<String, Object> extraData);
void updateListing(String channelId, SellPack sellPack, Map<String, Object> extraData);
void updatePrice(String channelId, String channelProductId, BigDecimal price);
void updateQuantity(String channelId, String channelProductId, int quantity);
void startSelling(String channelId, String channelProductId);
void stopSelling(String channelId, String channelProductId);
List<Order> fetchOrders(String channelId, LocalDateTime from, LocalDateTime to);
void confirmShipment(String channelId, String channelOrderId, String trackingNumber, String logisticsCompany);
void acceptCancellation(String channelId, String channelOrderId);
String getShippingLabel(String channelId, String channelOrderId);
boolean validateConnection(Map<String, String> credentials);
```

---

### Step 15：掃描其他引用並修正

可能受影響的檔案（需檢查 `Long channelId` / `Long merchantId` 等引用）：

| 檔案 | 預期修改 |
|------|---------|
| `ChannelAdapterFactory.java` | 可能不需要修改（只建 adapter 物件） |
| `ChannelJob.java` 及 ActionService 子類 | `Long` → `String` 如有使用 channelId/merchantId |
| `OrderProcessJob.java` | 已用 `String`（從 TaskMessage 取），不需改 |
| `BackendJob.java` | 同上 |
| `RetryDispatchJob.java` | 同上 |
| `SchedulerJob.java` | 如有 hardcoded `Long` 需改 |

> **執行方式：** 全專案搜尋 `Long merchantId`、`Long channelId`、`Long orderId`、`Long productId`，逐一改為 `String`。

---

## 驗證步驟

```bash
# 1. 編譯是否通過
./gradlew clean build -x test

# 2. Docker 啟動
docker compose down
rm -rf data/          # 重建 DB
docker compose up -d --build

# 3. 確認容器狀態
docker compose ps     # 全部 healthy

# 4. 確認無 DB error
docker compose logs simpleec-api 2>&1 | grep -i error
docker compose logs simpleec-order-job 2>&1 | grep -i error

# 5. 快速 API 測試
curl http://localhost:8082/api/v1/products?merchantId=SEED_MERCHANT_001
curl http://localhost:8082/api/v1/orders?merchantId=SEED_MERCHANT_001
```

---

## 完整異動檔案清單

| # | 檔案 | 動作 | 步驟 |
|---|------|------|------|
| 1 | `simpleec-common/build.gradle` | 修改 — 加 jnanoid | Step 1 |
| 2 | `simpleec-common/.../util/IdGenerator.java` | **新增** | Step 2 |
| 3 | `simpleec-core/.../config/MybatisPlusConfig.java` | **新增** | Step 3 |
| 4 | `simpleec-core/.../entity/Order.java` | 修改 | Step 4 |
| 5 | `simpleec-core/.../entity/Product.java` | 修改 | Step 5 |
| 6 | `simpleec-core/.../entity/SellPack.java` | 修改 | Step 6 |
| 7 | `simpleec-core/.../entity/OrderStatusLog.java` | 修改 | Step 7 |
| 8 | `simpleec-core/.../entity/OrderItem.java` | **刪除** | Step 8 |
| 9 | `simpleec-core/.../entity/ProductSpec.java` | **刪除** | Step 8 |
| 10 | `simpleec-core/.../mapper/OrderItemMapper.java` | **刪除** | Step 8 |
| 11 | `simpleec-core/.../mapper/ProductSpecMapper.java` | **刪除** | Step 8 |
| 12 | `simpleec-core/.../service/OrderService.java` | 修改 | Step 9 |
| 13 | `simpleec-core/.../service/ProductService.java` | 修改 | Step 10 |
| 14 | `simpleec-api/.../controller/OrderController.java` | 修改 | Step 11 |
| 15 | `simpleec-api/.../controller/ProductController.java` | 修改 | Step 12 |
| 16 | `simpleec-api/.../controller/ChannelActionController.java` | 修改 | Step 13 |
| 17 | `simpleec-channel/.../adapter/ChannelAdapter.java` | 修改 | Step 14 |
| 18 | 其他引用 `Long channelId/merchantId` 的檔案 | 修改 | Step 15 |

**總計：2 新增 + 4 刪除 + 12+ 修改**

---

---

## Level 1.5 — PII 加密 + API 遮罩（已完成 2026-02-09）

### Step 16：PII 欄位加密（AES-256-GCM）

**已完成。** DB 層透明加解密。

| 新增檔案 | 說明 |
|---------|------|
| `core/crypto/EncryptionContext.java` | ThreadLocal merchantId |
| `core/crypto/MasterKeyProvider.java` | JdbcTemplate 讀三次 Base64 master key |
| `core/crypto/AesGcmEncryptor.java` | AES-256-GCM + PBKDF2 per-merchant key |
| `core/crypto/EncryptedFieldTypeHandler.java` | MyBatis TypeHandler 透明加解密 |
| `core/crypto/EncryptionConfig.java` | Spring Config 注入 encryptor |

**修改：** Order.java（autoResultMap + 4 個 TypeHandler 註解）、OrderService.java（EncryptionContext 包裝）、Schema（加寬欄位 + master key seed）

### Step 17：API PII 遮罩 + 解鎖 + CSV 匯出

**已完成。**

| 新增檔案 | 說明 |
|---------|------|
| `common/util/PiiMasker.java` | 遮罩工具（maskName/Phone/Email/Address） |
| `api/vo/OrderVO.java` | 回應 DTO（fromMasked / fromPlain） |

**修改：** OrderService.java（+getById +listForExport）、OrderController.java（列表遮罩 + 詳情明文 + CSV 匯出）

---

## 未來（不在本次範圍）

- **Level 2**：補齊缺少的 14 張表的 Entity / Mapper（Merchant, Channel, Platform, Account 等）
- **Level 3**：實作 Phase 4 TODO — OrderProcessJob DB upsert、DailyStatistics 聚合、FailedTaskLog 持久化、SchedulerJob 從 DB 讀 channel 列表
- **Level 4**：實作 ChannelAdapter 各通路子類（MomoAdapter, ShopeeAdapter 等）
