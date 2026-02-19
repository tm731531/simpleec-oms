# SimpleEC OMS Redis 去重設計

## 1. 核心原則

### 1.1 特殊字元保留原則
- **訂單號碼中的特殊字元具有業務意義，必須完整保留**
- 例如：`1002#100` 和 `1002100` 是兩張完全不同的訂單
- 特殊字元（如 `#`、`-`、`_`、`@` 等）可能作為：
  - 訂單階段分隔符（如 `1002#100` 中 `#` 分隔主單號與子單號）
  - 通路特定標記（如 `ORD@2024` 中 `@` 表示年份標記）
  - 批次識別符（如 `BATCH-001-A` 中 `-` 分隔批次資訊）

### 1.2 複合鍵設計原則
- 訂單號碼在**通路內唯一**，但**跨通路可能重複**
- 必須使用 `channelId + orderId` 組成複合鍵
- 例如：Shopify `#1001` 和 Cyberbiz `#1001` 是不同訂單
- 客戶可能會在訂單號中使用任意符號（如 `/t`、`/n`、`@`、`#` 等），這些都是有意義的

## 2. Redis Key 設計

### 2.1 Key 命名規範

#### 訂單 Hash Key
```
order:hash:{merchantId}:{channelId}:{orderId}
```

範例：
- `order:hash:M001:SHOPIFY_001:1002#100`
- `order:hash:M001:CYBERBIZ_002:ORD@2024-001`
- `order:hash:M002:MOMO_001:MM-20240101-#5678`

#### 其他資源 Hash Key
```
return:hash:{merchantId}:{channelId}:{returnId}
product:hash:{merchantId}:{channelId}:{productId}
inventory:hash:{merchantId}:{channelId}:{skuId}
```

### 2.2 特殊字元處理策略

#### 直接保留所有字元（推薦）
- **直接保留所有特殊字元在 Redis Key 中，不做任何轉換**
- Redis 原生支援所有 ASCII 和 UTF-8 字元
- 客戶的訂單號（包括 `/t`, `/n`, `@`, `#`, `-` 等）都是有業務意義的，必須完整保留
- Key 分割使用冒號 `:` 作為分隔符，但訂單號中的冒號也是保留的

```java
public class RedisKeyBuilder {

    public static String buildOrderHashKey(String merchantId, String channelId, String orderId) {
        // 驗證參數
        validateNotNull(merchantId, "merchantId");
        validateNotNull(channelId, "channelId");
        validateNotNull(orderId, "orderId");

        // 直接組合，不做任何字元轉換
        return String.format("order:hash:%s:%s:%s",
            merchantId, channelId, orderId);
    }

    public static OrderKey parseOrderHashKey(String key) {
        // 限制分割數量為 5，避免訂單號中的冒號被錯誤分割
        String[] parts = key.split(":", 5);
        if (parts.length != 5) {
            throw new IllegalArgumentException("Invalid key format: " + key);
        }

        // 直接使用，不做任何解碼
        return new OrderKey(parts[2], parts[3], parts[4]);
    }
}
```

**範例**：
```
訂單號: ORD/t2024/n001  →  Key: order:hash:M001:SHOPIFY_001:ORD/t2024/n001
訂單號: 1002#100       →  Key: order:hash:M001:CYBERBIZ_001:1002#100
訂單號: SH@2024:ORD:01 →  Key: order:hash:M002:SHOPEE_001:SH@2024:ORD:01
```

## 3. Hash 計算與存儲

### 3.1 Order Hash 計算
```java
public class OrderHashService {

    /**
     * 計算訂單資料的 Hash 值
     * 使用 SHA-256，只包含會變動的欄位
     */
    public String calculateOrderHash(OrderData orderData) {
        // 排序欄位確保一致性
        TreeMap<String, Object> sortedData = new TreeMap<>();

        // 只包含業務欄位，排除系統欄位
        sortedData.put("orderStatus", orderData.getOrderStatus());
        sortedData.put("totalAmount", orderData.getTotalAmount());
        sortedData.put("shippingStatus", orderData.getShippingStatus());
        sortedData.put("paymentStatus", orderData.getPaymentStatus());
        sortedData.put("items", normalizeItems(orderData.getItems()));
        sortedData.put("buyerInfo", orderData.getBuyerInfo());
        sortedData.put("shippingInfo", orderData.getShippingInfo());

        String json = objectMapper.writeValueAsString(sortedData);
        return DigestUtils.sha256Hex(json);
    }
}
```

### 3.2 去重流程

#### Channel Job（只讀 Redis）
```java
@Component
public class ShopeeOrderListHandler {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public void handle(TaskMessage message) {
        String merchantId = message.getHeader().getMerchantId();
        String channelId = message.getHeader().getChannelId();

        // 抓取訂單列表
        List<ShopeeOrder> orders = fetchOrders(message);

        for (ShopeeOrder order : orders) {
            String orderHashKey = RedisKeyBuilder.buildOrderHashKey(
                merchantId, channelId, order.getOrderSn()
            );

            // 只讀取 Redis，不寫入
            String existingHash = redisTemplate.opsForValue().get(orderHashKey);
            String currentHash = calculateOrderHash(order);

            // 判斷是否需要處理
            boolean isNewOrChanged = (existingHash == null ||
                                     !existingHash.equals(currentHash));

            if (isNewOrChanged) {
                // 發送到 order.process
                sendToOrderProcess(order, currentHash);
            } else {
                log.debug("Order unchanged, skip: {}", order.getOrderSn());
            }
        }
    }
}
```

#### Order Process Job（讀寫 Redis + DB）
```java
@Component
public class OrderProcessHandler {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Transactional
    public void handle(OrderMessage message) {
        String merchantId = message.getHeader().getMerchantId();
        String channelId = message.getHeader().getChannelId();
        String orderId = message.getOrderId();
        String orderHash = message.getOrderHash();

        String orderHashKey = RedisKeyBuilder.buildOrderHashKey(
            merchantId, channelId, orderId
        );

        // 1. 再次檢查 Redis（避免並發重複）
        String existingHash = redisTemplate.opsForValue().get(orderHashKey);
        if (orderHash.equals(existingHash)) {
            log.info("Order already processed (Redis check): {}", orderId);
            return;
        }

        // 2. 檢查資料庫
        Order existingOrder = orderRepository.findByChannelIdAndChannelOrderId(
            channelId, orderId
        );

        if (existingOrder == null) {
            // 新訂單
            Order newOrder = createOrder(message);
            orderRepository.save(newOrder);
            log.info("New order created: {}", orderId);
        } else {
            // 更新訂單
            updateOrder(existingOrder, message);
            orderRepository.save(existingOrder);
            log.info("Order updated: {}", orderId);
        }

        // 3. 更新 Redis Hash（確保與 DB 同步）
        redisTemplate.opsForValue().set(
            orderHashKey,
            orderHash,
            Duration.ofDays(7)  // TTL 7 天
        );
    }
}
```

## 4. 資料一致性保證

### 4.1 Transaction + Redis Pipeline
```java
@Transactional
public void processOrderWithConsistency(OrderMessage message) {
    String merchantId = message.getHeader().getMerchantId();
    String channelId = message.getHeader().getChannelId();
    String orderId = message.getOrderId();
    String orderHash = message.getOrderHash();

    // 直接組合 Key（不做任何轉換）
    String orderHashKey = String.format("order:hash:%s:%s:%s",
        merchantId, channelId, orderId);

    // 使用 Redis Pipeline 減少往返
    redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
        // 檢查並設置（原子操作）
        byte[] key = orderHashKey.getBytes(StandardCharsets.UTF_8);
        byte[] value = orderHash.getBytes(StandardCharsets.UTF_8);

        // SET NX EX（如果不存在則設置，並設定過期時間）
        connection.set(key, value,
            Expiration.seconds(86400 * 7),  // 7 天
            SetOption.ifAbsent());

        return null;
    });

    // DB 操作
    saveOrUpdateOrder(message);
}
```

### 4.2 失敗恢復機制
```java
@Component
public class RedisRecoveryService {

    /**
     * 定期同步 DB 到 Redis
     * 處理 Redis 失效或不一致的情況
     */
    @Scheduled(cron = "0 0 3 * * *")  // 每天凌晨 3 點
    public void syncDbToRedis() {
        // 查詢最近 7 天的訂單
        List<Order> recentOrders = orderRepository.findRecentOrders(7);

        for (Order order : recentOrders) {
            String orderHashKey = RedisKeyBuilder.buildOrderHashKey(
                order.getMerchantId(),
                order.getChannelId(),
                order.getChannelOrderId()
            );

            String orderHash = calculateOrderHash(order);
            redisTemplate.opsForValue().set(
                orderHashKey,
                orderHash,
                Duration.ofDays(7)
            );
        }

        log.info("Synced {} orders to Redis", recentOrders.size());
    }
}
```

## 5. 監控與告警

### 5.1 Key 指標
```java
@Component
public class DeduplicationMetrics {

    private final MeterRegistry meterRegistry;

    // 去重命中率
    public void recordDeduplicationHit(String channelId, boolean hit) {
        meterRegistry.counter("order.deduplication",
            "channel", channelId,
            "result", hit ? "hit" : "miss"
        ).increment();
    }

    // Hash 計算耗時
    public void recordHashCalculation(long duration) {
        meterRegistry.timer("order.hash.calculation")
            .record(duration, TimeUnit.MILLISECONDS);
    }

    // Redis 操作耗時
    public void recordRedisOperation(String operation, long duration) {
        meterRegistry.timer("redis.operation",
            "type", operation
        ).record(duration, TimeUnit.MILLISECONDS);
    }
}
```

### 5.2 告警規則
- 去重命中率 < 80%：可能有大量新訂單或 Hash 計算問題
- Redis 操作延遲 > 100ms：Redis 效能問題
- Hash 不一致：DB 和 Redis 資料不同步

## 6. 測試案例

### 6.1 特殊字元測試
```java
@Test
public void testSpecialCharactersInOrderId() {
    String[] testOrderIds = {
        "1002#100",           // # 符號
        "ORD@2024",          // @ 符號
        "BATCH-001-A",       // 連字號
        "ORDER_2024_001",    // 底線
        "ORD.2024.001",      // 點號
        "2024/01/001",       // 斜線
        "ORDER:2024:001",    // 訂單號中包含冒號
        "ORD/t2024/n001",    // 斜線符號
        "訂單-2024-001"      // 中文
    };

    for (String orderId : testOrderIds) {
        // 直接組合 Key（不做任何轉換）
        String key = String.format("order:hash:%s:%s:%s",
            "M001", "SHOPIFY_001", orderId
        );

        // 驗證 Redis 可以存取
        redisTemplate.opsForValue().set(key, "test-hash");
        String retrieved = redisTemplate.opsForValue().get(key);
        assertEquals("test-hash", retrieved);

        // 驗證可以正確解析
        OrderKey parsed = RedisKeyBuilder.parseOrderHashKey(key);
        assertEquals(orderId, parsed.getOrderId());
    }
}
```

### 6.2 重複訂單號測試
```java
@Test
public void testDuplicateOrderIdAcrossChannels() {
    String orderId = "#1001";  // 相同訂單號

    // Shopify 訂單
    String shopifyKey = RedisKeyBuilder.buildOrderHashKey(
        "M001", "SHOPIFY_001", orderId
    );

    // Cyberbiz 訂單
    String cyberbizKey = RedisKeyBuilder.buildOrderHashKey(
        "M001", "CYBERBIZ_001", orderId
    );

    // 確認是不同的 Key
    assertNotEquals(shopifyKey, cyberbizKey);

    // 可以分別存取
    redisTemplate.opsForValue().set(shopifyKey, "shopify-hash");
    redisTemplate.opsForValue().set(cyberbizKey, "cyberbiz-hash");

    assertEquals("shopify-hash", redisTemplate.opsForValue().get(shopifyKey));
    assertEquals("cyberbiz-hash", redisTemplate.opsForValue().get(cyberbizKey));
}
```

## 7. 實施建議

1. **完全保留訂單號中的所有字元**
   - Redis 原生支援所有 UTF-8 字元
   - 不做任何轉換、編碼、規範化
   - 客戶的訂單號格式就是有業務意義的

2. **Key 分割時使用冒號限制分割數量**
   - 使用 `split(":", 5)` 確保訂單號中的冒號不會被分割
   - 保證能正確解析包含分隔符的訂單號

3. **定期資料一致性檢查**
   - 每日比對 DB 和 Redis 資料
   - 發現不一致時自動修復
   - 確保特殊字元的完整性

4. **效能優化**
   - 使用 Redis Pipeline 批次操作
   - 考慮使用 Redis Cluster 分散負載
   - UTF-8 編碼可能會增加 Key 大小，監控內存使用