# Channel 實作指南

## 1. Channel Job 職責界定

### 1.1 核心原則
- **單一職責**：只負責與通路 API 溝通
- **不存資料**：所有資料透過 Kafka 傳遞
- **不做業務邏輯**：業務邏輯在 Process Job

### 1.2 Channel Job 該做什麼
```java
✅ 正確的職責：
- 呼叫通路 API
- 處理分頁/游標
- 處理 rate limit
- 重試邏輯
- 資料格式轉換（API response → Kafka message）
- 發送訊息到對應 topic

❌ 不該做的事：
- 存取資料庫
- 業務規則驗證
- 訂單狀態管理
- 金額計算
- 庫存扣減
```

## 2. Channel Adapter 實作模式

### 2.1 基礎架構
```java
// 通用介面
public interface ChannelAdapter {
    String getPlatform();
    void initialize(ChannelConfig config);
    boolean isHealthy();
}

// 訂單相關
public interface OrderChannelAdapter extends ChannelAdapter {
    OrderListResponse fetchOrders(TimeRange range, PaginationParams params);
    OrderDetail fetchOrderDetail(String orderId);
    ShipmentResult shipOrder(String orderId, ShipmentParams params);
}

// 商品相關
public interface ProductChannelAdapter extends ChannelAdapter {
    ProductListResponse fetchProducts(ProductFilter filter);
    UpdateResult updateInventory(String productId, int quantity);
    UpdateResult updatePrice(String productId, BigDecimal price);
}
```

### 2.2 Shopee 實作範例
```java
@Component
@Slf4j
public class ShopeeAdapter implements OrderChannelAdapter, ProductChannelAdapter {

    private final ShopeeApiClient apiClient;
    private final ShopeeAuthManager authManager;
    private final ShopeeRateLimiter rateLimiter;

    @Override
    public String getPlatform() {
        return "shopee";
    }

    @Override
    public OrderListResponse fetchOrders(TimeRange range, PaginationParams params) {
        // Shopee 特定實作
        String timeRangeField = determineTimeRangeField(params.getStatus());
        String cursor = params.getCursor();

        rateLimiter.acquire(); // Rate limiting

        ShopeeOrderListRequest request = ShopeeOrderListRequest.builder()
            .timeRangeField(timeRangeField)
            .timeFrom(toTimestamp(range.getStart()))
            .timeTo(toTimestamp(range.getEnd()))
            .cursor(cursor)
            .pageSize(50) // Shopee 限制
            .build();

        ShopeeApiResponse<ShopeeOrderList> response =
            apiClient.getOrderList(authManager.getToken(), request);

        return OrderListResponse.builder()
            .orders(mapToOrders(response.getData().getOrderList()))
            .hasMore(response.getData().hasMore())
            .nextCursor(response.getData().getNextCursor())
            .build();
    }

    private String determineTimeRangeField(String status) {
        // Shopee 特定邏輯
        switch (status) {
            case "UNPAID": return "create_time";
            case "READY_TO_SHIP": return "update_time";
            default: return "create_time";
        }
    }
}
```

## 3. 分頁策略實作

### 3.1 策略介面
```java
public interface PaginationStrategy {
    boolean hasMore();
    void updateState(Object response);
    Map<String, Object> getNextParams();
}

// Cursor-based (Shopee)
public class CursorPagination implements PaginationStrategy {
    private String cursor = "";
    private boolean more = true;

    @Override
    public boolean hasMore() {
        return more && cursor != null;
    }

    @Override
    public void updateState(Object response) {
        ShopeeResponse r = (ShopeeResponse) response;
        this.cursor = r.getNextCursor();
        this.more = r.hasMore();
    }

    @Override
    public Map<String, Object> getNextParams() {
        return Map.of("cursor", cursor);
    }
}

// Offset-based (Momo)
public class OffsetPagination implements PaginationStrategy {
    private int offset = 0;
    private int total = Integer.MAX_VALUE;
    private final int pageSize = 100;

    @Override
    public boolean hasMore() {
        return offset < total;
    }

    @Override
    public void updateState(Object response) {
        MomoResponse r = (MomoResponse) response;
        this.total = r.getTotalCount();
        this.offset += pageSize;
    }
}
```

### 3.2 統一分頁處理
```java
public abstract class BaseChannelJob {

    protected <T> List<T> fetchAllPages(
        Supplier<PaginationStrategy> strategySupplier,
        Function<Map<String, Object>, PagedResponse<T>> fetcher) {

        List<T> allItems = new ArrayList<>();
        PaginationStrategy strategy = strategySupplier.get();

        while (strategy.hasMore()) {
            PagedResponse<T> response = fetcher.apply(strategy.getNextParams());
            allItems.addAll(response.getItems());
            strategy.updateState(response);

            if (allItems.size() > MAX_ITEMS) {
                log.warn("Reached max items limit: {}", MAX_ITEMS);
                break;
            }
        }

        return allItems;
    }
}
```

## 4. Rate Limiting 實作

### 4.1 Rate Limiter 設計
```java
@Component
public class ShopeeRateLimiter {

    private final RateLimiter orderApiLimiter = RateLimiter.create(5.0); // 5 req/s
    private final RateLimiter productApiLimiter = RateLimiter.create(10.0); // 10 req/s

    public void acquireForOrder() {
        double waitTime = orderApiLimiter.acquire();
        if (waitTime > 0) {
            log.debug("Rate limited, waited: {}s", waitTime);
        }
    }

    @Retryable(
        value = RateLimitException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public <T> T executeWithRetry(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException e) {
            if (e.getCode() == 429) { // Too Many Requests
                throw new RateLimitException("Rate limited by Shopee", e);
            }
            throw e;
        }
    }
}
```

## 5. 錯誤處理模式

### 5.1 錯誤分類
```java
public class ChannelErrorHandler {

    public void handleError(Exception e, TaskMessage originalMessage) {
        if (e instanceof ApiAuthException) {
            // 認證錯誤 - 不重試
            sendToDlt(originalMessage, e);

        } else if (e instanceof ApiRateLimitException) {
            // Rate limit - 延遲重試
            sendToRetryWithDelay(originalMessage, e, 60000); // 1分鐘後

        } else if (e instanceof ApiServerException) {
            // 伺服器錯誤 - 指數退避重試
            sendToRetryWithBackoff(originalMessage, e);

        } else if (e instanceof DataFormatException) {
            // 資料格式錯誤 - 不重試
            sendToDlt(originalMessage, e);

        } else {
            // 未知錯誤 - 預設重試
            sendToRetry(originalMessage, e);
        }
    }
}
```

### 5.2 重試策略
```java
@Component
public class RetryManager {

    private final KafkaProducer producer;

    public void sendToRetry(TaskMessage original, Exception error) {
        int retryCount = original.getHeader().getRetryCount();

        if (retryCount >= MAX_RETRIES) {
            sendToDlt(original, error);
            return;
        }

        TaskMessage retryMessage = original.toBuilder()
            .header(original.getHeader().toBuilder()
                .retryCount(retryCount + 1)
                .build())
            .build();

        producer.send("task.failed", retryMessage);
    }
}
```

## 6. 訊息發送模式

### 6.1 訊息建構
```java
public class MessageBuilder {

    public TaskMessage buildNewOrderMessage(
        ShopeeOrder order,
        boolean needsDetail,
        String correlationId) {

        return TaskMessage.builder()
            .header(MessageHeader.builder()
                .taskType("NEW_ORDER")
                .merchantId(getMerchantId())
                .channelId(getChannelId())
                .requestId(UUID.randomUUID().toString())
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .source("channel_job")
                .version(1)
                .build())
            .body(NewOrderBody.builder()
                .orderId(order.getOrderSn())
                .orderData(order) // 原始資料
                .needsDetail(needsDetail)
                .metadata(Map.of(
                    "status", order.getOrderStatus(),
                    "hasReturn", order.getReturnStatus() > 0
                ))
                .build())
            .build();
    }
}
```

### 6.2 批次發送優化
```java
public class BatchMessageSender {

    private final KafkaProducer producer;
    private final int BATCH_SIZE = 100;

    public void sendBatch(String topic, List<TaskMessage> messages) {
        Lists.partition(messages, BATCH_SIZE).forEach(batch -> {
            List<Future<SendResult>> futures = batch.stream()
                .map(msg -> producer.sendAsync(topic, msg))
                .collect(Collectors.toList());

            // 等待批次完成
            futures.forEach(future -> {
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("Failed to send message", e);
                }
            });
        });
    }
}
```

## 7. 健康檢查

### 7.1 Channel 健康狀態
```java
@Component
@Endpoint(id = "channel-health")
public class ChannelHealthIndicator {

    private final Map<String, ChannelAdapter> adapters;

    @ReadOperation
    public Map<String, Health> health() {
        return adapters.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> checkHealth(e.getValue())
            ));
    }

    private Health checkHealth(ChannelAdapter adapter) {
        try {
            if (adapter.isHealthy()) {
                return Health.up()
                    .withDetail("platform", adapter.getPlatform())
                    .build();
            }
        } catch (Exception e) {
            return Health.down()
                .withException(e)
                .build();
        }
        return Health.down().build();
    }
}
```

## 8. 測試策略

### 8.1 Mock API 測試
```java
@TestConfiguration
public class MockChannelConfig {

    @Bean
    @Primary
    public ShopeeApiClient mockShopeeClient() {
        ShopeeApiClient mock = Mockito.mock(ShopeeApiClient.class);

        // 設定 Mock 行為
        when(mock.getOrderList(any())).thenReturn(
            ShopeeApiResponse.success(mockOrderList())
        );

        return mock;
    }

    private ShopeeOrderList mockOrderList() {
        return ShopeeOrderList.builder()
            .orderList(Arrays.asList(
                mockOrder("TEST001", "READY_TO_SHIP"),
                mockOrder("TEST002", "UNPAID")
            ))
            .more(false)
            .build();
    }
}
```

### 8.2 整合測試
```java
@SpringBootTest
@EmbeddedKafka
public class ChannelJobIntegrationTest {

    @Test
    public void testOrderFetchFlow() {
        // Given
        TaskMessage fetchMessage = buildFetchOrdersMessage();

        // When
        kafkaTemplate.send("shopee.slow", fetchMessage);

        // Then
        ConsumerRecord<String, String> record =
            KafkaTestUtils.getSingleRecord(consumer, "order.process", 10000);

        assertThat(record).isNotNull();
        TaskMessage result = parseMessage(record.value());
        assertThat(result.getHeader().getTaskType()).isEqualTo("NEW_ORDER");
    }
}
```

## 9. 監控指標

### 9.1 關鍵指標
```java
@Component
public class ChannelMetrics {

    private final MeterRegistry registry;

    public void recordApiCall(String platform, String api, boolean success) {
        registry.counter("channel.api.calls",
            "platform", platform,
            "api", api,
            "status", success ? "success" : "failure"
        ).increment();
    }

    public void recordProcessingTime(String platform, String operation, long ms) {
        registry.timer("channel.processing.time",
            "platform", platform,
            "operation", operation
        ).record(ms, TimeUnit.MILLISECONDS);
    }
}
```

## 10. 部署配置

### 10.1 環境變數
```yaml
# Channel Job 配置
JOB_CHANNEL_TOPICS: shopee.fast,shopee.slow
JOB_CHANNEL_GROUP_ID: channel-job-shopee
JOB_CHANNEL_CONCURRENCY: 4

# API 配置
SHOPEE_API_URL: https://partner.shopeemobile.com
SHOPEE_PARTNER_ID: ${SHOPEE_PARTNER_ID}
SHOPEE_PARTNER_KEY: ${SHOPEE_PARTNER_KEY}

# Rate Limiting
SHOPEE_RATE_LIMIT_ORDER: 5
SHOPEE_RATE_LIMIT_PRODUCT: 10
```

### 10.2 Docker Compose（獨立 Consumer Groups）
```yaml
# 快速通道：1小時內新訂單、出貨指令等
shopee-channel-job-fast:
  image: simpleec-oms/channel-job:latest
  environment:
    JOB_CHANNEL_TOPICS: shopee.fast
    JOB_CHANNEL_GROUP_ID: channel-job-shopee-fast
    SPRING_PROFILES_ACTIVE: shopee
    JOB_CONCURRENCY: 4
  depends_on:
    - kafka
    - redis

# 慢速通道：商品詳情、訂單詳情等
shopee-channel-job-slow:
  image: simpleec-oms/channel-job:latest
  environment:
    JOB_CHANNEL_TOPICS: shopee.slow
    JOB_CHANNEL_GROUP_ID: channel-job-shopee-slow
    SPRING_PROFILES_ACTIVE: shopee
    JOB_CONCURRENCY: 2
  depends_on:
    - kafka
    - redis

# 其他通路類似配置...
momo-channel-job-fast:
  image: simpleec-oms/channel-job:latest
  environment:
    JOB_CHANNEL_TOPICS: momo.fast
    JOB_CHANNEL_GROUP_ID: channel-job-momo-fast
    SPRING_PROFILES_ACTIVE: momo
    JOB_CONCURRENCY: 4
  depends_on:
    - kafka
    - redis

momo-channel-job-slow:
  image: simpleec-oms/channel-job:latest
  environment:
    JOB_CHANNEL_TOPICS: momo.slow
    JOB_CHANNEL_GROUP_ID: channel-job-momo-slow
    SPRING_PROFILES_ACTIVE: momo
    JOB_CONCURRENCY: 2
  depends_on:
    - kafka
    - redis
```

**說明**：
- 每個 `{platform}-{speed}` 組合有獨立的 Consumer Group
- 快速通道（.fast）通常並發數高（4），處理小訊息
- 慢速通道（.slow）並發數低（2），處理複雜邏輯
- 獨立 GROUP 避免一個失敗拖累整個通路