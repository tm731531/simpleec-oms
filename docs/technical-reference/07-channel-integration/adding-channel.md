# Adding a New Channel Platform

This guide walks through every step required to integrate a new e-commerce platform into SimpleEC OMS. The process involves code changes in four modules and a docker-compose update.

---

## Prerequisites

Before starting, determine:
1. **Mode A or Mode B?** — Does the platform's order list API return complete orders (Mode A) or only order IDs that require a separate detail call (Mode B)?
2. **Authentication method** — API key, HMAC signature, OAuth2 token, etc.
3. **Time window strategy** — How far back does the platform allow querying? What time fields are available (created_at, updated_at, etc.)?
4. **Rate limits** — Requests per minute/hour constraints.

---

## Step 1: Add Platform Enum

Edit `simpleec-common/src/main/java/com/simpleec/common/enums/PlatformEnum.java`:

```java
public enum PlatformEnum {
    MOMO("momo", "Momo 購物", ModeEnum.B),
    SHOPEE("shopee", "Shopee", ModeEnum.B),
    YAHOO("yahoo", "Yahoo 購物", ModeEnum.B),
    PCHOME("pchome", "PChome", ModeEnum.B),
    CYBERBIZ("cyberbiz", "Cyberbiz", ModeEnum.B),
    EASYSTORE("easystore", "EasyStore", ModeEnum.A),
    NEWPLATFORM("newplatform", "New Platform Display Name", ModeEnum.A),  // ← add here
    ;

    private final String code;
    private final String displayName;
    private final ModeEnum mode;
    // constructor, getters ...
}
```

The `code` value (lowercase) is used as:
- Kafka topic prefix: `newplatform.fast`, `newplatform.slow`
- `ChannelAdapter.getPlatformCode()` return value
- `JOB_CHANNEL_GROUP_ID` suffix in docker-compose

---

## Step 2: Create Kafka Topics

Edit `docker/init-kafka/create-topics.sh` and add the two topics for the new platform:

```bash
# New Platform topics
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

Also add the topic names to `TopicConstants.java` if platform-specific constants are used there, or confirm that `TopicConstants.platformSlowTopic("newplatform")` returns `"newplatform.slow"` correctly.

---

## Step 3: Create an API Client (if needed)

For platforms requiring complex authentication (HMAC, OAuth, etc.), create a dedicated API client class in `simpleec-channel/src/main/java/com/simpleec/channel/api/`:

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
        // Build signed request, handle pagination, return raw response
    }
}
```

Simpler platforms (plain API key in header) can inline HTTP calls directly in the adapter without a separate client class.

---

## Step 4: Create the Channel Adapter

Create `simpleec-channel/src/main/java/com/simpleec/channel/adapter/NewPlatformAdapter.java`:

```java
@Slf4j
@Component  // ← register as Spring bean so ChannelJobConsumer can inject it
@RequiredArgsConstructor
public class NewPlatformAdapter implements ChannelAdapter {

    private final NewPlatformApiClient apiClient;  // or inject RestClient directly

    @Override
    public String getPlatformCode() {
        return "newplatform";  // must match PlatformEnum.code
    }

    @Override
    public ModeEnum getMode() {
        return ModeEnum.A;  // or ModeEnum.B
    }

    // ── Mode A implementation ─────────────────────────────────────────

    @Override
    public List<Map<String, Object>> fetchOrdersByTimestamp(String channelId, long baseTimestamp) throws Exception {
        log.info("Fetching NewPlatform orders for channel {} baseTimestamp={}", channelId, baseTimestamp);

        // Decide time window (Channel Job autonomy — not driven by Scheduler)
        long fromTs = baseTimestamp - 7 * 86400;  // 7-day window for new orders
        long toTs   = baseTimestamp;

        List<Map<String, Object>> newOrders  = apiClient.getOrders(channelId, fromTs, toTs);

        // If platform also has update-time query, fetch and merge:
        long updatedFromTs = baseTimestamp - 86400;  // 1-day window for updates
        List<Map<String, Object>> updatedOrders = apiClient.getUpdatedOrders(channelId, updatedFromTs, toTs);

        // Merge and deduplicate by order ID
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> o : newOrders)     merged.put(o.get("id").toString(), o);
        for (Map<String, Object> o : updatedOrders) merged.put(o.get("id").toString(), o);
        return new ArrayList<>(merged.values());
    }

    // ── Mode B implementation (if mode is B) ─────────────────────────

    @Override
    public List<String> fetchOrderListByTimestamp(String channelId, long baseTimestamp) throws Exception {
        long fromTs = baseTimestamp - 86400;  // 1-day window
        return apiClient.getOrderIds(channelId, fromTs, baseTimestamp);
    }

    @Override
    public Map<String, Object> fetchOrderDetail(String channelId, String orderId) throws Exception {
        return apiClient.getOrderDetail(channelId, orderId);
    }

    // ── Shared operations ─────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> fetchReturns(String channelId, String timeRange) throws Exception {
        // implement or return empty list if not supported
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

    // ── Legacy methods (required by interface, not used in main flow) ─

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

## Step 5: Register the Adapter in ChannelJobConsumer

Edit `simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelJobConsumer.java`:

```java
// Add field injection
private final ChannelAdapter newPlatformAdapter;

// Add case in getAdapter() switch
private ChannelAdapter getAdapter(String platformCode) {
    switch (platformCode.toLowerCase()) {
        case "shopify":   return shopifyAdapter;
        case "easystore": return easystoreAdapter;
        case "shopee":    return shopeeAdapter;
        case "cyberbiz":  return cyberbizAdapter;
        case "newplatform": return newPlatformAdapter;  // ← add this
        default:
            log.warn("Unknown platform: {}", platformCode);
            return null;
    }
}
```

---

## Step 6: Add Order Status Mapping

If the new platform uses its own status strings, add a mapping method to `OrderStatusMapper.java` (in `simpleec-channel-job/src/main/java/com/simpleec/channeljob/util/`):

```java
// Add inside OrderStatusMapper
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

// Register in the dispatch method
public static String mapToOmsStatus(String platformCode, String rawStatus) {
    return switch (platformCode.toLowerCase()) {
        case "shopee"      -> mapShopeeStatus(rawStatus);
        case "cyberbiz"    -> mapCyberbizStatus(rawStatus);
        case "newplatform" -> mapNewPlatformStatus(rawStatus);  // ← add
        default -> rawStatus != null ? rawStatus.toUpperCase() : "PENDING";
    };
}
```

---

## Step 7: Add docker-compose Entries

Add two services to `docker-compose.yml` (fast and slow):

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

Note: fast and slow containers share the same `JOB_CHANNEL_GROUP_ID`. Kafka distributes partitions across consumers in the same group.

---

## Step 8: Add Credentials to .env

```bash
# .env (never commit to git)
NEWPLATFORM_API_URL=https://api.newplatform.com
NEWPLATFORM_API_KEY=your-secret-api-key-here
```

Credentials that are per-channel (different for each merchant's shop) should be stored in the `channels` table columns `token` and `token2`, and loaded at runtime via `ChannelService.getChannel(channelId)`.

Platform-wide API keys (shared across all channels) can be environment variables as shown above.

---

## Step 9: Build and Deploy

```bash
# Compile
./gradlew :simpleec-channel:build :simpleec-channel-job:build -x test

# Create the Kafka topics (if the cluster is already running)
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic newplatform.fast --partitions 3 --replication-factor 1
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic newplatform.slow --partitions 3 --replication-factor 1

# Start the new containers (quick-redeploy rebuilds the image and restarts only these two)
./quick-redeploy.sh simpleec-channel-newplatform-fast simpleec-channel-newplatform-slow
```

---

## Step 10: Verify

```bash
# Confirm both containers are running
docker ps | grep newplatform

# Check startup logs for dynamic listener registration
docker logs simpleec-channel-newplatform-slow 2>&1 | grep -E "REGISTER_DYNAMIC|Dynamic listener"
# Expected: "Dynamic listener registered: groupId=channel-job-newplatform, topics=[newplatform.slow]"

# Send a test FETCH_ORDERS message via Kafka UI at http://localhost:8088
# Or use the CLI:
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

# Watch for the order fetch log line
docker logs -f simpleec-channel-newplatform-slow 2>&1 | grep -E "FETCH_ORDERS|Mode A|Mode B"
```

---

## Checklist

- [ ] `PlatformEnum` updated with new entry
- [ ] Kafka topics created: `newplatform.fast`, `newplatform.slow`
- [ ] `ChannelAdapter` implementation created and annotated `@Component`
- [ ] API client created (if needed)
- [ ] Order status mapping added to `OrderStatusMapper`
- [ ] Adapter registered in `ChannelJobConsumer.getAdapter()`
- [ ] `docker-compose.yml` — two new service entries (fast + slow)
- [ ] `.env` — credentials added
- [ ] Build passes: `./gradlew :simpleec-channel:build :simpleec-channel-job:build -x test`
- [ ] Containers start and register dynamic Kafka listener
- [ ] Test message triggers correct handler
- [ ] `ORDER_UPSERT` message appears on `order.process`
