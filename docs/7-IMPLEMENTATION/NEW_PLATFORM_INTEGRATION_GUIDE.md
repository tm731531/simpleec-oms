# 新平台集成完整指南

**最後更新**: 2026-02-25
**基於**: Cyberbiz 集成經驗
**預計耗時**: 3-5 天（包括測試）

---

## 🚀 快速概覽

新增平台需要完成的工作：

```
準備階段 (1 天)
  ├─ API 文檔分析
  ├─ 認證方式確認
  └─ 時間窗口策略設計
        ↓
開發階段 (2-3 天)
  ├─ 建立 ChannelAdapter 實現
  ├─ 建立 OrderStatusMapper
  ├─ 編寫單元測試
  └─ 整合 Kafka 消費者
        ↓
測試階段 (1-2 天)
  ├─ 本地測試
  ├─ 沙箱環境驗證
  └─ 生產灰度發佈
```

---

## 第一步：API 分析和設計

### 1. API 特性評估表

在開始開發前，填寫下表：

| 特性 | 評估 | 備註 |
|------|------|------|
| **認證方式** | □ API Key □ OAuth □ HMAC □ 其他 | 獲取並測試 |
| **訂單列表 API** | □ 支持 □ 不支持 | 是否有分頁？時間範圍？ |
| **訂單詳情 API** | □ 支持 □ 不支持 | 是否必須？Rate limit？ |
| **狀態分類** | □ 明確 □ 模糊 □ 無 | 有無預定義狀態？ |
| **時間欄位** | □ 創建時間 □ 更新時間 □ 支付時間 | 哪個可用於查詢？ |
| **Rate Limit** | 💯 請求/分鐘 | 評估時間窗口大小 |
| **IP 限制** | □ 有 □ 無 | 是否需要 IP 白名單？ |
| **分頁方式** | □ Offset □ Cursor □ 無 | 如何處理多頁面？ |
| **返回欄位** | □ 完整 □ 需詳情 API | 列表 API 是否有完整資訊？ |

### 2. 時間窗口策略設計

根據 API 特性決定時間窗口：

**情景 A：有明確狀態分類 + 支持狀態過濾 (如 Shopee)**
```
Scheduler 發送: 2026-02-25T10:00:00Z
    ↓
Channel Job 決策:
  • 新訂單 (PENDING): 基準時間 - 1 小時
  • 待出貨 (CONFIRMED): 基準時間 - 3 天
  • 已出貨 (SHIPPED): 基準時間 - 5 天
  • 完成 (COMPLETED): 基準時間 - 7 天
    ↓
優勢: 精確定位，避免重複抓取老訂單
缺點: 需要多次 API 呼叫
```

**情景 B：支持時間範圍查詢 (如 Cyberbiz)**
```
Scheduler 發送: 2026-02-25T10:00:00Z
    ↓
Channel Job 決策:
  • 創建時間範圍: 基準時間 - 7 天 ~ 基準時間
  • 更新時間範圍: 基準時間 - 1 天 ~ 基準時間
    ↓
優勢: 少量 API 呼叫，一次性取得所有狀態的訂單
缺點: 可能取到不需要的舊訂單
```

**情景 C：只有更新時間，無狀態分類 (如 Yahoo)**
```
Scheduler 發送: 2026-02-25T10:00:00Z
    ↓
Channel Job 決策:
  • 更新時間: 基準時間 - 1 天 ~ 基準時間
  • 評估狀態: 根據出貨/支付時間等欄位推測
    ↓
優勢: 簡單邏輯
缺點: 状態推測可能不準確
```

---

## 第二步：實現 ChannelAdapter

### 1. 目錄結構

```
simpleec-channel/src/main/java/com/simpleec/channel/
├── adapter/
│   ├── ChannelAdapter.java                  (介面)
│   ├── CyberbizAdapter.java                 (已實現)
│   ├── ShopeeAdapter.java                   (已實現)
│   ├── NewPlatformAdapter.java              (新增 ← 你要做)
│   └── ...
├── api/
│   ├── CyberbizApiClient.java               (API 客戶端)
│   ├── NewPlatformApiClient.java            (新增 ← 你要做)
│   └── ...
└── exception/
    └── ChannelException.java
```

### 2. ChannelAdapter 介面

```java
public interface ChannelAdapter {
    // 基本資訊
    ModeEnum getMode();                      // Mode A 或 Mode B
    String getPlatformCode();                // "newplatform"

    // 列表取得 (Mode A: 完整列表)
    List<Map<String, Object>> fetchOrdersByTimestamp(
        String channelId,
        long baseTimestamp                   // Unix 秒
    );

    // 詳情取得 (Mode B: 需要詳情 API)
    Map<String, Object> fetchOrderDetail(
        String channelId,
        String channelOrderId
    );

    // 認證檢查
    void authenticate(String channelId) throws ChannelException;
}
```

### 3. 實現模板

```java
@Slf4j
public class NewPlatformAdapter implements ChannelAdapter {
    private final NewPlatformApiClient apiClient;

    @Override
    public ModeEnum getMode() {
        // Mode A: 列表 API 已完整 (如 Cyberbiz, Easystore)
        // Mode B: 列表不完整，需詳情 API (如 Shopee, Momo)
        return ModeEnum.A;
    }

    @Override
    public String getPlatformCode() {
        return "newplatform";
    }

    @Override
    public List<Map<String, Object>> fetchOrdersByTimestamp(
        String channelId,
        long baseTimestamp
    ) {
        try {
            // 1️⃣ 計算時間窗口
            long createdFrom = calculateCreatedFrom(baseTimestamp);
            long createdTo = baseTimestamp;

            log.info("Fetching orders from {} to {} for channel {}",
                createdFrom, createdTo, channelId);

            // 2️⃣ 呼叫 API (含重試邏輯)
            List<Map<String, Object>> orders = apiClient.getOrders(
                channelId,
                createdFrom,
                createdTo,
                0,      // 分頁 offset
                100     // 分頁 limit
            );

            // 3️⃣ 處理分頁
            int totalFetched = orders.size();
            int offset = 100;
            while (orders.size() == totalFetched) {  // 可能有更多
                List<Map<String, Object>> nextPage = apiClient.getOrders(
                    channelId,
                    createdFrom,
                    createdTo,
                    offset,
                    100
                );
                if (nextPage.isEmpty()) break;
                orders.addAll(nextPage);
                offset += 100;
            }

            log.info("Fetched {} total orders from NewPlatform", orders.size());
            return orders;

        } catch (Exception e) {
            log.error("Failed to fetch orders from NewPlatform", e);
            throw new ChannelException("NewPlatform fetch failed", e);
        }
    }

    @Override
    public void authenticate(String channelId) throws ChannelException {
        // 測試認證是否有效
        try {
            apiClient.testConnection(channelId);
        } catch (Exception e) {
            throw new ChannelException("Authentication failed", e);
        }
    }

    // 私有方法：計算時間窗口 (由平台決定)
    private long calculateCreatedFrom(long baseTimestamp) {
        // 例如：7 天前
        return baseTimestamp - (7 * 86400);
    }
}
```

### 4. API 客戶端實現

```java
@Slf4j
public class NewPlatformApiClient {
    private final String apiBaseUrl;
    private final RestTemplate restTemplate;
    private final Channel channelConfig;  // 存儲認證資訊

    public List<Map<String, Object>> getOrders(
        String channelId,
        long createdFrom,
        long createdTo,
        int offset,
        int limit
    ) {
        try {
            // 1️⃣ 構建請求 URL
            String url = buildOrderUrl(channelId, createdFrom, createdTo, offset, limit);

            // 2️⃣ 添加認證 header (HMAC / API Key 等)
            HttpHeaders headers = buildAuthHeaders(channelId);

            // 3️⃣ 發送 GET 請求
            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            // 4️⃣ 解析 JSON
            JsonNode data = objectMapper.readTree(response.getBody());
            List<Map<String, Object>> orders = new ArrayList<>();

            data.get("orders").forEach(item -> {
                orders.add(objectMapper.convertValue(item, Map.class));
            });

            return orders;

        } catch (Exception e) {
            log.error("API call failed", e);
            throw new ChannelException("API error", e);
        }
    }

    private HttpHeaders buildAuthHeaders(String channelId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 認證方式取決於平台
        // 例如: HMAC 簽名
        String hmacSignature = generateHmacSignature(channelId);
        headers.set("Authorization", "Bearer " + hmacSignature);

        return headers;
    }

    private String generateHmacSignature(String channelId) {
        // 實現平台特定的簽名邏輯
        // 例如: Cyberbiz 使用特定格式的 HMAC
        return "...";
    }

    private String buildOrderUrl(String channelId, long createdFrom,
                                 long createdTo, int offset, int limit) {
        return String.format(
            "%s/orders?created_from=%d&created_to=%d&offset=%d&limit=%d",
            apiBaseUrl, createdFrom, createdTo, offset, limit
        );
    }
}
```

---

## 第三步：實現 OrderStatusMapper

### 1. 添加平台映射

```java
public class OrderStatusMapper {

    public static String mapToOmsStatus(String platform, String platformStatus) {
        return switch (platform.toLowerCase()) {
            case "cyberbiz" -> mapCyberbizStatus(platformStatus);
            case "newplatform" -> mapNewPlatformStatus(platformStatus);  // ← 新增
            default -> "PENDING";
        };
    }

    // 新平台的狀態映射
    private static String mapNewPlatformStatus(String status) {
        return switch (status.toLowerCase()) {
            case "unpaid" -> "PENDING";
            case "paid" -> "CONFIRMED";
            case "ready_for_shipment" -> "READY_TO_SHIP";
            case "shipped" -> "SHIPPED";
            case "delivered" -> "COMPLETED";
            case "cancelled" -> "CANCELLED";
            default -> {
                log.warn("Unknown NewPlatform status: {}", status);
                yield "PENDING";
            }
        };
    }
}
```

---

## 第四步：集成到 Channel Job

### 1. 消費者註冊

```java
@Slf4j
@Component
public class ChannelJobConsumer {
    private final ChannelService channelService;
    private final ModeAOrderListHandler modeAHandler;

    @KafkaListener(topics = "newplatform.slow")  // ← 新增
    public void consumeNewPlatformSlowTask(String message) {
        try {
            KafkaMessage msg = objectMapper.readValue(message, KafkaMessage.class);
            ChannelAdapter adapter = channelService.getAdapter("newplatform");

            long baseTimestamp = parseTimestamp(msg.getHeader().getTimestamp());

            modeAHandler.handleModeAOrders(
                adapter,
                baseTimestamp,
                msg.getHeader().getMerchantId(),
                msg.getHeader().getChannelId()
            );

        } catch (Exception e) {
            log.error("Failed to consume NewPlatform task", e);
        }
    }
}
```

### 2. Kafka Topic 建立

```bash
# 建立 newplatform 相關的 topics
docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh \
  --create \
  --topic newplatform.fast \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 1

docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh \
  --create \
  --topic newplatform.slow \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 1
```

---

## 第五步：測試

### 1. 單元測試

```java
@ExtendWith(MockitoExtension.class)
public class NewPlatformAdapterTest {

    @Mock
    private NewPlatformApiClient apiClient;

    private NewPlatformAdapter adapter;

    @BeforeEach
    public void setup() {
        adapter = new NewPlatformAdapter(apiClient);
    }

    @Test
    public void testFetchOrdersByTimestamp() {
        // 準備
        long baseTimestamp = 1771997600L;  // 2026-02-25T10:00:00Z
        List<Map<String, Object>> mockOrders = createMockOrders();

        when(apiClient.getOrders(any(), anyLong(), anyLong(), anyInt(), anyInt()))
            .thenReturn(mockOrders);

        // 執行
        List<Map<String, Object>> result = adapter.fetchOrdersByTimestamp("ch_123", baseTimestamp);

        // 驗證
        assertEquals(2, result.size());
        verify(apiClient, times(1)).getOrders(
            "ch_123",
            baseTimestamp - (7 * 86400),
            baseTimestamp,
            0,
            100
        );
    }

    @Test
    public void testStatusMapping() {
        assertEquals("PENDING", OrderStatusMapper.mapToOmsStatus("newplatform", "unpaid"));
        assertEquals("CONFIRMED", OrderStatusMapper.mapToOmsStatus("newplatform", "paid"));
        assertEquals("SHIPPED", OrderStatusMapper.mapToOmsStatus("newplatform", "shipped"));
    }

    private List<Map<String, Object>> createMockOrders() {
        // 構造測試資料
        return List.of(
            Map.ofEntries(
                Map.entry("order_id", "ord_np_001"),
                Map.entry("status", "paid"),
                Map.entry("total_price", 1000),
                Map.entry("customer_name", "Test User")
            )
        );
    }
}
```

### 2. 集成測試

```java
@SpringBootTest
public class NewPlatformIntegrationTest {

    @Autowired
    private ModeAOrderListHandler handler;

    @Autowired
    private ChannelService channelService;

    @Test
    public void testEndToEndOrderFetch() throws Exception {
        // 1️⃣ 取得適配器
        ChannelAdapter adapter = channelService.getAdapter("newplatform");

        // 2️⃣ 模擬時間戳
        long baseTimestamp = Instant.now().getEpochSecond();

        // 3️⃣ 執行處理 (會發送 Kafka 消息)
        handler.handleModeAOrders(
            adapter,
            baseTimestamp,
            "m_test",
            "ch_test"
        );

        // 4️⃣ 驗證 Kafka 消息是否發送
        // (使用 Embedded Kafka 或 testcontainers)
    }
}
```

### 3. 沙箱環境測試清單

- [ ] 取得沙箱 API 認證資訊
- [ ] 測試認證連接
- [ ] 執行一次訂單抓取，檢查日誌
- [ ] 驗證 Kafka 消息格式
- [ ] 檢查 Order Job 是否正確儲存訂單
- [ ] 測試狀態轉換 (多個 fetch 循環)
- [ ] 測試去重邏輯 (重複抓取相同訂單)

---

## 第六步：生產發佈

### 1. 灰度發佈計劃

```
Day 1-2: 監控模式 (日誌記錄，不寫入 DB)
    ↓
Day 3-5: 沙箱環境 (完整流程，包括 DB 寫入)
    ↓
Week 2: 生產灰度 (5% 流量)
    ↓
Week 3: 生產全量 (100% 流量)
```

### 2. 監控指標

```
- 每分鐘訂單抓取數
- API 呼叫成功率
- Kafka 發送成功率
- 訂單去重率
- 平均處理時間
```

### 3. 回滾計劃

如果發現問題：
1. 停止 Kafka 消費 (暫停 consumer group)
2. 關閉 Channel Job 容器
3. 檢查日誌，確認問題根因
4. 修復並重新發佈

---

## 檢查清單

### 開發完成前
- [ ] ChannelAdapter 實現完成
- [ ] OrderStatusMapper 映射完成
- [ ] API 客戶端實現完成
- [ ] 單元測試覆蓋 > 80%
- [ ] 代碼審查通過
- [ ] Kafka Topics 已建立

### 測試完成前
- [ ] 沙箱環境端到端測試通過
- [ ] 負載測試通過 (rate limit 內)
- [ ] 回滾測試驗證
- [ ] 監控告警配置完成
- [ ] 文檔更新完成

### 發佈前
- [ ] 生產環境配置驗證
- [ ] 灰度發佈計劃確認
- [ ] 值班人員通知
- [ ] 回滾方案確認

---

## 參考

- [訂單處理完整架構](./ORDER_PROCESS_ARCHITECTURE.md)
- [Cyberbiz 集成經驗](./CYBERBIZ_INTEGRATION_EXPERIENCE.md)
- [退貨流程設計](./RETURN_FLOW_DESIGN.md)

---

**預計完成時間**: 3-5 天
**難度等級**: ⭐⭐⭐ (中等)
**經驗要求**: 了解 Spring Boot, Kafka, API 集成
