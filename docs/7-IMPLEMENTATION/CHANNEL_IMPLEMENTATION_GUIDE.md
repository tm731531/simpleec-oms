# Channel 實作指南

⚠️ **重要警告 - API 相關內容是示例模板**

本文件中關於各通路 API 的具體例子（如 Shopee GET /api/orders、Momo API 參數等）**目前是示例/模板**，**不是生產環境準確的 API 規範**。

**待確認項目**：
- Shopee, Momo, Yahoo, PChome, easystore, Cyberbiz 的實際 API 文件
- 真實的 request/response 結構
- 分頁方式（特別是 Yahoo 的假 cursor 機制）
- Rate limit 策略
- Error handling 細節

**使用建議**：
1. 當前代碼結構（PaginationStrategy 抽象、Channel Job 架構）**是正確的**
2. 具體 API 呼叫邏輯需要根據實際 API 文件確認後再更新
3. 看到真實 API 文件後，會更新本文件為準確內容

---

## 1. Channel Job 職責界定

### 1.1 核心原則
- **數據適配層**：將各通路 API 的五花八門格式統一轉換為 OMS 標準結構
- **不存資料**：所有資料透過 Kafka 傳遞
- **不做業務邏輯**：業務邏輯在 Process Job

### 1.2 Channel Job 該做什麼
```java
✅ 正確的職責：
- 呼叫通路 API（FETCH_ORDERS, FETCH_ORDER_DETAIL 等）
- 處理分頁/游標
- 處理 rate limit
- 重試邏輯
- 資料結構轉換（Shopee/Momo/Yahoo 訂單 → OMS 標準 orderData）
  * 例：Shopee shop_order_id → channelOrderId
  * 例：Momo 商品結構 → items[] (統一格式)
  * 例：多通路運費計算 → shipping.fee (統一字段)
- 發送轉換後的訊息到對應 topic

❌ 不該做的事：
- 存取資料庫（資料庫查詢由 Handler 負責）
- 決定新訂單或更新訂單（由 Handler 查 DB 決定）
- 計算去重 hash（由 Handler 計算）
- 業務規則驗證（訂單狀態管理、金額計算、庫存扣減等）
```

## 2. Mode A/B 架構模式

### 2.0 平台分類：API 數據完整性決定處理流程

不同平台的 API 設計差異很大，主要區別在「訂單列表 API 是否包含完整訊息」。這決定了是否需要額外的詳情 API 呼叫。SimpleEC OMS 將平台分為 **Mode A** 和 **Mode B** 兩種：

#### Mode A：列表 API 已完整（直接轉 PROCESS_ORDER）

| 平台 | 列表 API 包含 | Handler 需做 | 優勢 |
|------|----------|----------|------|
| **Shopify** | ✅ items、payment、shipping | 直接入庫 | API 呼叫最少 |
| **easystore** | ✅ 完整訂單資訊，50 張/次 | 直接入庫 | 批量效率高 |

**流程**：
```
Scheduler (Heartbeat) → FETCH_ORDERS (Shopify API list) → 訊息已包含 orderData 完整 → PROCESS_ORDER → DB INSERT/UPDATE
```

#### Mode B：列表 API 不完整（需要 FETCH_ORDER_DETAIL）

| 平台 | 列表 API 缺少 | 補充方式 | 額外成本 |
|------|----------|----------|---------|
| **Shopee** | items 詳情、payment、shipping | 逐筆呼叫 detail API | 高 |
| **Momo** | 非訂單層級（item-level）| 按訂單號聚合 | 高（需自己整合） |

**流程**：
```
Scheduler (Heartbeat)
  → FETCH_ORDERS (Shopee API list, 不完整)
  → [決定需要 FETCH_ORDER_DETAIL]
  → FETCH_ORDER_DETAIL (Shopee detail API, 逐筆)
  → 訊息現在包含完整 orderData
  → PROCESS_ORDER → DB INSERT/UPDATE
```

#### 實作差異對照表

| 面向 | Mode A | Mode B |
|------|--------|--------|
| **Handler 數量** | 1 個（FetchOrders 直接發 PROCESS_ORDER） | 2 個（FetchOrders + DetailHandler） |
| **データ完全性檢查** | 列表 API 後即可 | 詳情 API 後才完整 |
| **Rate Limit 考量** | 1 次呼叫/訂單 | 2 次呼叫/訂單（list + detail） |
| **Kafka 流量** | 少（單一 PROCESS_ORDER） | 多（FETCH_ORDER_DETAIL + PROCESS_ORDER） |
| **Code Complexity** | 低 | 中（需判斷何時 fetch detail） |

#### 決策：如何判定平台是 Mode A 還是 Mode B？

新增平台時，檢查以下清單：

```
1️⃣ 訂單列表 API 的 response 是否包含以下所有欄位？
   ✅ items[]（商品清單，含 SKU、名稱、數量、價格）
   ✅ payment（付款方式、金額、狀態）
   ✅ shipping（運費、配送方式、地址）
   ✅ 其他必要欄位（買家名稱、Email、Phone 等）

   → YES: Mode A（直接用 list API）
   → NO: Mode B（需要詳情 API）

2️⃣ 是否有專門的「詳情 API」可以補充缺失的欄位？
   → YES: Mode B（使用詳情 API）
   → NO: Mode B（需在 Channel Job 層自己整合，如 Momo）

3️⃣ 文件或實測確認後，更新 PLATFORM_MAPPING.md
```

---

## 2.1 Channel Adapter 實作模式

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

### 3.0 通路分頁方式差異對照表

**重要**：各通路分頁方式天差地遠，必須為每個通路實作各自的 PaginationStrategy。

| 通路 | 分頁方式 | 主要識別方式 | 複雜性 | 備註 |
|------|--------|-----------|-------|------|
| **Shopee** | 真正 Cursor | 回傳 `nextCursor` 和 `hasMore` | 低 | 標準 cursor 實作，可直接使用 |
| **Momo** | Offset + Limit | 回傳 `totalCount`，計算 `offset += pageSize` | 低 | 傳統 offset-based |
| **Yahoo** | 假 Cursor（其實是 Offset） | 回傳字串 cursor，但實際是 base64 encoded offset | 中 | 需要解碼 cursor 為 offset，再計算下一頁 |
| **PChome** | Header-based Pagination | 回傳 `X-Page-Count`, `X-Page-No` headers | 中 | 不是 body，要從 HTTP headers 讀 pagination 資訊 |
| **easystore** | Limit + 時間範圍 | 無 cursor，改用 `from_date`, `to_date` | 低 | 按時間範圍分批，不按 offset/cursor |
| **Cyberbiz** | 待確認 | - | 待評估 | 新通路，需實測 API 行為 |

### 3.1 策略介面

```java
/**
 * 通路無關的分頁策略介面
 * 每個通路實作各自的邏輯（cursor、offset、header、時間範圍等）
 */
public interface PaginationStrategy {
    /**
     * 判斷是否還有下一頁
     * - Cursor 類：檢查 cursor 是否非空且 hasMore flag
     * - Offset 類：檢查 offset < totalCount
     * - Header 類：檢查 HTTP headers 的頁碼資訊
     * - 時間範圍類：檢查是否還有時間窗口
     */
    boolean hasMore();

    /**
     * 根據 API response 更新分頁狀態
     * - CursorPagination: 從 response 提取 nextCursor、hasMore flag
     * - OffsetPagination: 從 response 提取 totalCount，計算新 offset
     * - HeaderPagination: 從 response headers 提取 page info
     * - TimestampPagination: 推進時間窗口
     */
    void updateState(Object response);

    /**
     * 取得下一頁的請求參數
     * 返回值格式各通路不同：
     * - Cursor: { "cursor": "abc123" }
     * - Offset: { "offset": 100, "limit": 100 }
     * - Header: {} (資訊存在 headers)
     * - Time: { "from_date": "...", "to_date": "..." }
     */
    Map<String, Object> getNextParams();

    /**
     * 取得額外 HTTP headers（某些通路需要）
     * 預設實作返回空 map，Header-based 通路（如 PChome）可覆寫
     */
    default Map<String, String> getHeaders() {
        return Map.of();
    }
}

// ========== Cursor-based (Shopee) ==========
/**
 * 真正的 cursor pagination
 * - API 回傳 nextCursor 和 hasMore flag
 * - 每次請求帶著 cursor，直到 hasMore=false
 */
public class ShopeeeCursorPagination implements PaginationStrategy {
    private String cursor = "";
    private boolean more = true;

    @Override
    public boolean hasMore() {
        return more && cursor != null;
    }

    @Override
    public void updateState(Object response) {
        ShopeeResponse r = (ShopeeResponse) response;
        this.cursor = r.getNextCursor();  // e.g., "abc123def456"
        this.more = r.hasMore();           // true/false
    }

    @Override
    public Map<String, Object> getNextParams() {
        return Map.of("cursor", cursor);
    }
}

// ========== Offset-based (Momo) ==========
/**
 * 傳統 offset + limit 分頁
 * - API 回傳 totalCount
 * - 每次計算新 offset = offset + pageSize
 * - 直到 offset >= totalCount
 */
public class MomoOffsetPagination implements PaginationStrategy {
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

    @Override
    public Map<String, Object> getNextParams() {
        return Map.of("offset", offset, "limit", pageSize);
    }
}

// ========== Fake Cursor (Yahoo) ==========
/**
 * 假 Cursor：API 回傳字串 cursor，但實際是 base64 encoded offset
 * 需要：
 * 1. 解碼 cursor 為整數 offset
 * 2. 計算下一頁 offset
 * 3. 編碼為新 cursor
 */
public class YahoFakeCursorPagination implements PaginationStrategy {
    private String cursor = "";
    private int currentOffset = 0;
    private boolean hasMore = true;
    private final int pageSize = 50;

    @Override
    public boolean hasMore() {
        return hasMore;
    }

    @Override
    public void updateState(Object response) {
        YahooResponse r = (YahooResponse) response;
        // Yahoo 回傳 nextCursor（是 base64 encoded offset）
        String nextCursor = r.getNextCursor();
        if (nextCursor != null && !nextCursor.isEmpty()) {
            // 解碼 cursor: Base64.decode(nextCursor) → 整數 offset
            this.currentOffset = decodeOffsetFromCursor(nextCursor);
            this.cursor = nextCursor;
            this.hasMore = r.hasMore();
        } else {
            this.hasMore = false;
        }
    }

    @Override
    public Map<String, Object> getNextParams() {
        return Map.of("cursor", cursor);
    }

    private int decodeOffsetFromCursor(String cursor) {
        // 實作：Base64 解碼，提取 offset
        byte[] decoded = Base64.getDecoder().decode(cursor);
        return Integer.parseInt(new String(decoded));
    }
}

// ========== Header-based Pagination (PChome) ==========
/**
 * PChome 特殊：分頁資訊在 HTTP response headers 中
 * - X-Page-No: 當前頁碼（1-indexed）
 * - X-Page-Count: 總頁數
 * 需要追蹤當前頁碼，逐頁遞進
 */
public class PChomeHeaderPagination implements PaginationStrategy {
    private int currentPage = 1;
    private int totalPages = 1;
    private final int pageSize = 100;

    @Override
    public boolean hasMore() {
        return currentPage < totalPages;
    }

    @Override
    public void updateState(Object response) {
        if (response instanceof HttpResponse) {
            HttpResponse httpResponse = (HttpResponse) response;
            // 從 headers 提取分頁資訊
            String pageNoHeader = httpResponse.getHeader("X-Page-No");
            String pageCountHeader = httpResponse.getHeader("X-Page-Count");

            if (pageCountHeader != null) {
                this.totalPages = Integer.parseInt(pageCountHeader);
            }
            // 更新當前頁為下一頁
            this.currentPage++;
        }
    }

    @Override
    public Map<String, Object> getNextParams() {
        return Map.of("page", currentPage, "limit", pageSize);
    }

    @Override
    public Map<String, String> getHeaders() {
        // PChome 某些 API 需要特定 headers
        return Map.of("Accept", "application/json");
    }
}

// ========== Timestamp-based Pagination (easystore) ==========
/**
 * easystore 無 cursor/offset，改用時間範圍分批
 * - from_date, to_date 定義查詢時間窗口
 * - 無法從 response 獲知是否還有更多數據，需要自行計算時間進度
 */
public class EasystoreTimestampPagination implements PaginationStrategy {
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private LocalDateTime currentCheckpoint;
    private final Duration batchWindow = Duration.ofHours(6);  // 每批 6 小時

    public EasystoreTimestampPagination(LocalDateTime startDate, LocalDateTime endDate) {
        this.fromDate = startDate;
        this.toDate = endDate;
        this.currentCheckpoint = startDate;
    }

    @Override
    public boolean hasMore() {
        return currentCheckpoint.isBefore(toDate);
    }

    @Override
    public void updateState(Object response) {
        // easystore API 無分頁 metadata，直接推進時間窗口
        currentCheckpoint = currentCheckpoint.plus(batchWindow);
        if (currentCheckpoint.isAfter(toDate)) {
            currentCheckpoint = toDate;
        }
    }

    @Override
    public Map<String, Object> getNextParams() {
        LocalDateTime nextCheckpoint = currentCheckpoint.plus(batchWindow);
        if (nextCheckpoint.isAfter(toDate)) {
            nextCheckpoint = toDate;
        }
        return Map.of(
            "from_date", currentCheckpoint.toString(),
            "to_date", nextCheckpoint.toString(),
            "limit", 50
        );
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