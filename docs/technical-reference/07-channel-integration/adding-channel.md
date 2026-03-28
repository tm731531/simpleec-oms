# 新增通路平台

本指南說明將新電商平台整合至 SimpleEC OMS 所需的每個步驟。整個過程涉及四個模組的程式碼修改以及 docker-compose 的更新。

---

## 事前準備

在開始之前，請先確認以下事項：
1. **Mode A 還是 Mode B？** — 該平台的訂單列表 API 是否回傳完整訂單（Mode A），或是只回傳需要另行呼叫 detail API 的訂單 ID（Mode B）？
2. **認證方式** — API key、HMAC 簽章、OAuth2 token 等。
3. **時間窗口策略** — 平台允許查詢多遠的歷史資料？有哪些時間欄位可用（created_at、updated_at 等）？
4. **速率限制** — 每分鐘/每小時的請求數限制。

---

## 步驟 1：新增平台 Enum

編輯 `simpleec-common/src/main/java/com/simpleec/common/enums/PlatformEnum.java`：

```java
public enum PlatformEnum {
    MOMO("momo", "Momo 購物", ModeEnum.B),
    SHOPEE("shopee", "Shopee", ModeEnum.B),
    YAHOO("yahoo", "Yahoo 購物", ModeEnum.B),
    PCHOME("pchome", "PChome", ModeEnum.B),
    CYBERBIZ("cyberbiz", "Cyberbiz", ModeEnum.B),
    EASYSTORE("easystore", "EasyStore", ModeEnum.A),
    NEWPLATFORM("newplatform", "New Platform Display Name", ModeEnum.A),  // ← 在此新增
    ;

    private final String code;
    private final String displayName;
    private final ModeEnum mode;
    // 建構子、getter ...
}
```

`code` 值（小寫）會用於：
- Kafka 主題前綴：`newplatform.fast`、`newplatform.slow`
- `ChannelAdapter.getPlatformCode()` 的回傳值
- docker-compose 中 `JOB_CHANNEL_GROUP_ID` 的後綴

---

## 步驟 2：建立 Kafka 主題

編輯 `docker/init-kafka/create-topics.sh`，為新平台新增兩個主題：

```bash
# New Platform 主題
kafka-topics.sh --create \
  --if-not-exists \
  --bootstrap-server localhost:9092 \
  --topic newplatform.fast \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=${FAST_RETENTION_MS:-86400000}

kafka-topics.sh --create \
  --if-not-exists \
  --bootstrap-server localhost:9092 \
  --topic newplatform.slow \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=${SLOW_RETENTION_MS:-86400000}
```

若 `TopicConstants.java` 中使用了平台特定常數，也請一併新增主題名稱；或確認 `TopicConstants.platformSlowTopic("newplatform")` 能正確回傳 `"newplatform.slow"`。

---

## 步驟 3：建立 API Client（如有需要）

對於需要複雜認證的平台（HMAC、OAuth 等），在 `simpleec-channel/src/main/java/com/simpleec/channel/api/` 中建立專用的 API client 類別：

```java
// NewPlatformApiClient.java
@Slf4j
@Component
public class NewPlatformApiClient {

    private final RestClient restClient;

    @Value("${NEWPLATFORM_API_URL:https://api.newplatform.com}")
    private String baseUrl;

    public NewPlatformApiClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    public List<Map<String, Object>> getOrders(String apiKey, long fromTs, long toTs) {
        // 建構帶簽名的請求、處理分頁、回傳原始回應
    }
}
```

較簡單的平台（只需在 Header 中帶入 API key）可以直接在 adapter 中內嵌 HTTP 呼叫，不需要獨立的 client 類別。

---

## 步驟 4：建立 Channel Adapter

建立 `simpleec-channel/src/main/java/com/simpleec/channel/adapter/NewPlatformAdapter.java`：

```java
@Slf4j
@Component  // ← 注冊為 Spring bean，讓 ChannelJobConsumer 能注入
@RequiredArgsConstructor
public class NewPlatformAdapter implements ChannelAdapter {

    private final NewPlatformApiClient apiClient;  // 或直接注入 RestClient

    @Override
    public String getPlatformCode() {
        return "newplatform";  // 必須與 PlatformEnum.code 一致
    }

    @Override
    public ModeEnum getMode() {
        return ModeEnum.A;  // 或 ModeEnum.B
    }

    // ── Mode A 實作 ─────────────────────────────────────────

    @Override
    public List<Map<String, Object>> fetchOrdersByTimestamp(String channelId, long baseTimestamp) throws Exception {
        log.info("Fetching NewPlatform orders for channel {} baseTimestamp={}", channelId, baseTimestamp);

        // 決定時間窗口（Channel Job 自主決策 — 不由 Scheduler 驅動）
        long fromTs = baseTimestamp - 7 * 86400;  // 新訂單 7 天窗口
        long toTs   = baseTimestamp;

        List<Map<String, Object>> newOrders  = apiClient.getOrders(channelId, fromTs, toTs);

        // 若平台也支援更新時間查詢，可抓取後合併：
        long updatedFromTs = baseTimestamp - 86400;  // 更新訂單 1 天窗口
        List<Map<String, Object>> updatedOrders = apiClient.getUpdatedOrders(channelId, updatedFromTs, toTs);

        // 依訂單 ID 合併並去重
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> o : newOrders)     merged.put(o.get("id").toString(), o);
        for (Map<String, Object> o : updatedOrders) merged.put(o.get("id").toString(), o);
        return new ArrayList<>(merged.values());
    }

    // ── Mode B 實作（若模式為 B）─────────────────────────

    @Override
    public List<String> fetchOrderListByTimestamp(String channelId, long baseTimestamp) throws Exception {
        long fromTs = baseTimestamp - 86400;  // 1 天窗口
        return apiClient.getOrderIds(channelId, fromTs, baseTimestamp);
    }

    @Override
    public Map<String, Object> fetchOrderDetail(String channelId, String orderId) throws Exception {
        return apiClient.getOrderDetail(channelId, orderId);
    }

    // ── 共用操作 ─────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> fetchReturns(String channelId, String timeRange) throws Exception {
        // 實作或回傳空列表（若該平台不支援）
        return List.of();
    }

    @Override
    public void shipOrder(String orderId, Map<String, Object> shippingInfo) throws Exception {
        apiClient.createShipment(orderId, shippingInfo);
    }

    @Override
    public void updateInventory(String productId, int quantity) throws Exception {
        apiClient.updateStock(productId, quantity);
    }

    @Override
    public boolean testConnection() throws Exception {
        return apiClient.ping();
    }

    // ── 舊版方法（介面要求保留，主流程不使用）─

    @Override
    public List<Map<String, Object>> fetchOrders(String channelId, String timeRange) throws Exception {
        throw new UnsupportedOperationException("Use fetchOrdersByTimestamp instead");
    }

    @Override
    public List<String> fetchOrderList(String channelId, String timeRange) throws Exception {
        throw new UnsupportedOperationException("Use fetchOrderListByTimestamp instead");
    }
}
```

---

## 步驟 5：在 ChannelJobConsumer 中注冊 Adapter

編輯 `simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelJobConsumer.java`：

```java
// 新增欄位注入
private final ChannelAdapter newPlatformAdapter;

// 在 getAdapter() 的 switch 中新增 case
private ChannelAdapter getAdapter(String platformCode) {
    switch (platformCode.toLowerCase()) {
        case "shopify":   return shopifyAdapter;
        case "easystore": return easystoreAdapter;
        case "shopee":    return shopeeAdapter;
        case "cyberbiz":  return cyberbizAdapter;
        case "newplatform": return newPlatformAdapter;  // ← 新增此行
        default:
            log.warn("Unknown platform: {}", platformCode);
            return null;
    }
}
```

---

## 步驟 6：新增訂單狀態映射

若新平台使用自定義的狀態字串，請在 `OrderStatusMapper.java`（位於 `simpleec-channel-job/src/main/java/com/simpleec/channeljob/util/`）中新增映射方法：

```java
// 在 OrderStatusMapper 內新增
private static String mapNewPlatformStatus(String raw) {
    if (raw == null) return "PENDING";
    return switch (raw.toLowerCase()) {
        case "new", "placed"     -> "PENDING";
        case "processing"        -> "CONFIRMED";
        case "ready"             -> "READY_TO_SHIP";
        case "dispatched"        -> "SHIPPING";
        case "delivered"         -> "COMPLETED";
        case "cancelled"         -> "CANCELLED";
        default -> {
            log.warn("Unknown NewPlatform status: {}", raw);
            yield "PENDING";
        }
    };
}

// 在分發方法中注冊
public static String mapToOmsStatus(String platformCode, String rawStatus) {
    return switch (platformCode.toLowerCase()) {
        case "shopee"      -> mapShopeeStatus(rawStatus);
        case "cyberbiz"    -> mapCyberbizStatus(rawStatus);
        case "newplatform" -> mapNewPlatformStatus(rawStatus);  // ← 新增
        default -> rawStatus != null ? rawStatus.toUpperCase() : "PENDING";
    };
}
```

---

## 步驟 7：新增 docker-compose 設定

在 `docker-compose.yml` 中新增兩個服務（fast 與 slow）：

```yaml
simpleec-channel-newplatform-fast:
  image: simpleec-channel-job
  build:
    context: .
    dockerfile: docker/Dockerfile.channel-job
  container_name: simpleec-channel-newplatform-fast
  environment:
    SPRING_PROFILES_ACTIVE: docker
    DB_HOST: postgres
    DB_PORT: '5432'
    DB_NAME: simpleec
    DB_USER: simpleec
    DB_PASSWORD: ${DB_PASSWORD:-simpleec123}
    REDIS_HOST: redis
    REDIS_PORT: '6379'
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    JOB_CHANNEL_TOPICS: newplatform.fast
    JOB_CHANNEL_GROUP_ID: channel-job-newplatform
    JOB_CHANNEL_CONCURRENCY: '3'
    NEWPLATFORM_API_URL: ${NEWPLATFORM_API_URL:-https://api.newplatform.com}
    NEWPLATFORM_API_KEY: ${NEWPLATFORM_API_KEY}
    JAVA_TOOL_OPTIONS: "-Xmx256m -XX:+ExitOnOutOfMemoryError"
  depends_on:
    postgres:
      condition: service_healthy
    redis:
      condition: service_healthy
    kafka-init:
      condition: service_completed_successfully
  restart: unless-stopped

simpleec-channel-newplatform-slow:
  image: simpleec-channel-job
  build:
    context: .
    dockerfile: docker/Dockerfile.channel-job
  container_name: simpleec-channel-newplatform-slow
  environment:
    SPRING_PROFILES_ACTIVE: docker
    DB_HOST: postgres
    DB_PORT: '5432'
    DB_NAME: simpleec
    DB_USER: simpleec
    DB_PASSWORD: ${DB_PASSWORD:-simpleec123}
    REDIS_HOST: redis
    REDIS_PORT: '6379'
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    JOB_CHANNEL_TOPICS: newplatform.slow
    JOB_CHANNEL_GROUP_ID: channel-job-newplatform
    JOB_CHANNEL_CONCURRENCY: '8'
    NEWPLATFORM_API_URL: ${NEWPLATFORM_API_URL:-https://api.newplatform.com}
    NEWPLATFORM_API_KEY: ${NEWPLATFORM_API_KEY}
    JAVA_TOOL_OPTIONS: "-Xmx256m -XX:+ExitOnOutOfMemoryError"
  depends_on:
    postgres:
      condition: service_healthy
    redis:
      condition: service_healthy
    kafka-init:
      condition: service_completed_successfully
  restart: unless-stopped
```

注意：fast 與 slow 容器共用同一個 `JOB_CHANNEL_GROUP_ID`，Kafka 會在同一 group 的 consumer 之間分配分區。

---

## 步驟 8：將憑證寫入 .env

```bash
# .env（請勿提交至 git）
NEWPLATFORM_API_URL=https://api.newplatform.com
NEWPLATFORM_API_KEY=your-secret-api-key-here
```

各商家店鋪專屬的憑證（例如不同商家有不同的 token）應儲存於 `channels` 資料表的 `token` 和 `token2` 欄位，並於執行時透過 `ChannelService.getChannel(channelId)` 載入。

整個平台共用的 API key（所有通路共享）可以如上所示使用環境變數設定。

---

## 步驟 9：建置並部署

```bash
# 編譯
./gradlew :simpleec-channel:build :simpleec-channel-job:build -x test

# 建立 Kafka 主題（若叢集已在運行中）
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic newplatform.fast --partitions 3 --replication-factor 1
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic newplatform.slow --partitions 3 --replication-factor 1

# 啟動新容器（quick-redeploy 會重新建置映像並僅重啟這兩個容器）
./quick-redeploy.sh simpleec-channel-newplatform-fast simpleec-channel-newplatform-slow
```

---

## 步驟 10：驗證

```bash
# 確認兩個容器正在運行
docker ps | grep newplatform

# 確認啟動日誌中有動態 listener 注冊記錄
docker logs simpleec-channel-newplatform-slow 2>&1 | grep -E "REGISTER_DYNAMIC|Dynamic listener"
# 預期輸出："Dynamic listener registered: groupId=channel-job-newplatform, topics=[newplatform.slow]"

# 透過 http://localhost:8088 的 Kafka UI 發送測試用的 FETCH_ORDERS 訊息
# 或使用 CLI：
docker exec kafka kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic newplatform.slow --property "key.serializer=..." << 'EOF'
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "channelId": "CHANNEL_NEWPLATFORM_001",
    "timestamp": "2026-03-28T10:00:00Z",
    "version": 1
  },
  "body": {}
}
EOF

# 追蹤訂單抓取的日誌輸出
docker logs -f simpleec-channel-newplatform-slow 2>&1 | grep -E "FETCH_ORDERS|Mode A|Mode B"
```

---

## 完成檢查清單

- [ ] `PlatformEnum` 已新增對應項目
- [ ] Kafka 主題已建立：`newplatform.fast`、`newplatform.slow`
- [ ] `ChannelAdapter` 實作類別已建立並標注 `@Component`
- [ ] API client 已建立（如有需要）
- [ ] 訂單狀態映射已新增至 `OrderStatusMapper`
- [ ] Adapter 已在 `ChannelJobConsumer.getAdapter()` 中注冊
- [ ] `docker-compose.yml` 已新增兩個服務項目（fast + slow）
- [ ] `.env` 已新增憑證
- [ ] 建置通過：`./gradlew :simpleec-channel:build :simpleec-channel-job:build -x test`
- [ ] 容器啟動並成功注冊動態 Kafka listener
- [ ] 測試訊息能觸發對應的 handler
- [ ] `ORDER_UPSERT` 訊息出現在 `order.process` 上
