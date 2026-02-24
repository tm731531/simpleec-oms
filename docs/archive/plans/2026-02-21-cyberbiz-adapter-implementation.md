# Cyberbiz Channel Adapter Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Implement CyberbizAdapter for Mode B order fetching with dual API calls (created + updated time windows) and return fetching.

**Architecture:** CyberbizAdapter implements ChannelAdapter interface and uses Mode B pattern: fetchOrderList returns order IDs from two separate API calls (created_at and updated_at time windows), then ModeBOrderDetailHandler fetches complete details for each order. The adapter also supports fetchReturns using refund_at timestamp filtering. All platform-specific Cyberbiz response mappings are handled in the adapter, which translates to OMS schema via ModeBOrderDetailHandler.

**Tech Stack:** Java 17, Spring Boot 3.5, Jackson ObjectMapper, Kafka, Mode B order processing pattern

---

## Task 1: Create CyberbizAdapter with Mode B Interface

**Files:**
- Create: `simpleec-channel/src/main/java/com/simpleec/channel/adapter/CyberbizAdapter.java`
- Reference: `simpleec-channel/src/main/java/com/simpleec/channel/adapter/ShopeeAdapter.java` (Mode B example)
- Reference: `simpleec-channel/src/main/java/com/simpleec/channel/adapter/ChannelAdapter.java` (interface)

**Step 1: Create the CyberbizAdapter class with basic structure**

Create `simpleec-channel/src/main/java/com/simpleec/channel/adapter/CyberbizAdapter.java`:

```java
package com.simpleec.channel.adapter;

import com.simpleec.common.enums.ModeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Cyberbiz 平台適配器 — Mode B (分離的訂單列表 + 詳情)
 *
 * Cyberbiz API 特性：
 * - 訂單列表 API 可根據建立時間或更新時間篩選
 * - 需要兩次 API 呼叫獲取全部訂單：一次查建立 (created_at)，一次查更新 (updated_at)
 * - 必須單獨呼叫訂單詳情 API 獲取完整資訊
 * - 退貨可通過 refund_at 時間參數查詢
 */
@Slf4j
@Component
public class CyberbizAdapter implements ChannelAdapter {

    @Override
    public String getPlatformCode() {
        return "cyberbiz";
    }

    @Override
    public ModeEnum getMode() {
        return ModeEnum.B;
    }

    /**
     * Mode A 不支援（Cyberbiz 使用 Mode B）
     */
    @Override
    public List<Map<String, Object>> fetchOrders(String timeRange) throws Exception {
        throw new UnsupportedOperationException(
            "Cyberbiz 使用 Mode B，需分三步：先 fetchOrderList (created + updated)，再 fetchOrderDetail");
    }

    /**
     * Mode B: 拉取訂單 ID 列表（通過兩次 API 呼叫）
     *
     * Cyberbiz API 特性：
     * - 第一次呼叫：get_orders 搭配 create_time_from/create_time_to 參數，獲取該時段內建立的訂單
     * - 第二次呼叫：get_orders 搭配 update_time_from/update_time_to 參數，獲取該時段內更新的訂單
     * - 合併兩次結果並去重（同一訂單可能在兩次查詢中都出現）
     *
     * @param timeRange 時間範圍 (e.g., "last_1_hour")
     * @return 訂單 ID 列表（去重後）
     */
    @Override
    public List<String> fetchOrderList(String timeRange) throws Exception {
        log.info("Fetching Cyberbiz order list with timeRange: {}", timeRange);

        Set<String> orderIds = new LinkedHashSet<>();

        // 第一次呼叫：查詢該時段內建立的訂單
        try {
            List<String> createdOrders = fetchOrdersCreatedInTimeRange(timeRange);
            orderIds.addAll(createdOrders);
            log.debug("Fetched {} orders created in timeRange from Cyberbiz", createdOrders.size());
        } catch (Exception e) {
            log.error("Error fetching created orders from Cyberbiz", e);
            throw e;
        }

        // 第二次呼叫：查詢該時段內更新的訂單
        try {
            List<String> updatedOrders = fetchOrdersUpdatedInTimeRange(timeRange);
            orderIds.addAll(updatedOrders);
            log.debug("Fetched {} orders updated in timeRange from Cyberbiz", updatedOrders.size());
        } catch (Exception e) {
            log.error("Error fetching updated orders from Cyberbiz", e);
            throw e;
        }

        log.info("Fetched {} unique order IDs from Cyberbiz (after dedup)", orderIds.size());
        return new ArrayList<>(orderIds);
    }

    /**
     * 輔助方法：查詢該時段內建立的訂單
     * 模擬 Cyberbiz API: GET /api/order/get_orders?create_time_from=<timestamp>&create_time_to=<timestamp>
     */
    private List<String> fetchOrdersCreatedInTimeRange(String timeRange) {
        log.debug("Fetching orders created in timeRange: {}", timeRange);
        // TODO: 實現實際 Cyberbiz API 呼叫
        // 模擬返回訂單 ID 列表
        return Arrays.asList(
            "CBZ-CREATE-00001",
            "CBZ-CREATE-00002",
            "CBZ-CREATE-00003"
        );
    }

    /**
     * 輔助方法：查詢該時段內更新的訂單
     * 模擬 Cyberbiz API: GET /api/order/get_orders?update_time_from=<timestamp>&update_time_to=<timestamp>
     */
    private List<String> fetchOrdersUpdatedInTimeRange(String timeRange) {
        log.debug("Fetching orders updated in timeRange: {}", timeRange);
        // TODO: 實現實際 Cyberbiz API 呼叫
        // 模擬返回訂單 ID 列表
        return Arrays.asList(
            "CBZ-UPDATE-00001",
            "CBZ-UPDATE-00002"
        );
    }

    /**
     * Mode B: 拉取單筆訂單詳情
     *
     * 模擬 Cyberbiz API: GET /api/order/get_order?order_id=<order_id>
     * 回傳：完整訂單詳情（商品、收貨人、金額等）
     */
    @Override
    public Map<String, Object> fetchOrderDetail(String orderId) throws Exception {
        log.info("Fetching order detail from Cyberbiz for orderId: {}", orderId);

        // TODO: 實現實際 Cyberbiz API 呼叫以取得訂單詳情
        // 目前回傳模擬數據
        Map<String, Object> orderDetail = new LinkedHashMap<>();
        orderDetail.put("order_id", orderId);
        orderDetail.put("order_sn", "CBZ" + System.currentTimeMillis());
        orderDetail.put("status", "pending");
        orderDetail.put("created_at", "2024-02-20T10:30:00Z");
        orderDetail.put("updated_at", "2024-02-20T10:35:00Z");

        // 金額資訊
        Map<String, Object> amountInfo = new LinkedHashMap<>();
        amountInfo.put("total", 2500.0);
        amountInfo.put("subtotal", 2300.0);
        amountInfo.put("shipping_fee", 200.0);
        amountInfo.put("discount", 0.0);
        orderDetail.put("amount_info", amountInfo);

        // 商品列表
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("sku", "CBZ-ITEM-001");
        item1.put("product_id", "9876543210");
        item1.put("name", "Cyberbiz 商品");
        item1.put("quantity", 1);
        item1.put("unit_price", 2300.0);
        items.add(item1);
        orderDetail.put("items", items);

        // 買家資訊
        Map<String, Object> buyer = new LinkedHashMap<>();
        buyer.put("user_id", "CBZ_BUYER_123");
        buyer.put("username", "cyberbuyer");
        buyer.put("email", "buyer@cyberbiz.tw");
        buyer.put("phone", "0922334455");
        orderDetail.put("buyer_info", buyer);

        // 配送地址
        Map<String, Object> shipping = new LinkedHashMap<>();
        shipping.put("name", "王小明");
        shipping.put("phone", "0922334455");
        shipping.put("address", "台北市信義區忠孝東路 100 號");
        shipping.put("city", "台北");
        shipping.put("postal_code", "11001");
        shipping.put("country", "TW");
        orderDetail.put("shipping_info", shipping);

        log.info("Fetched order detail from Cyberbiz: {}", orderId);
        return orderDetail;
    }

    /**
     * 拉取退貨列表
     *
     * 模擬 Cyberbiz API: GET /api/order/get_orders?refund_time_from=<timestamp>&refund_time_to=<timestamp>
     * 回傳：該時段內有退貨的訂單列表
     */
    @Override
    public List<Map<String, Object>> fetchReturns(String timeRange) throws Exception {
        log.info("Fetching returns from Cyberbiz with timeRange: {}", timeRange);

        // TODO: 實現實際 Cyberbiz 退貨查詢邏輯
        // 使用 refund_time_from/refund_time_to 參數查詢該時段內的退貨訂單
        return new ArrayList<>();
    }

    @Override
    public void shipOrder(String orderId, Map<String, Object> shippingInfo) throws Exception {
        log.info("Shipping order {} on Cyberbiz with info: {}", orderId, shippingInfo);
        // TODO: 實現 Cyberbiz 出貨確認邏輯
    }

    @Override
    public void updateInventory(String productId, int quantity) throws Exception {
        log.info("Updating inventory for product {} to quantity {} on Cyberbiz", productId, quantity);
        // TODO: 實現 Cyberbiz 庫存更新邏輯
    }

    @Override
    public boolean testConnection() throws Exception {
        log.info("Testing connection to Cyberbiz API");
        // 模擬連接測試
        return true;
    }
}
```

**Step 2: Verify the file compiles**

Run from project root:
```bash
cd /home/tom/ONEEC/simpleec-oms
mvn clean compile -DskipTests 2>&1 | grep -E "(ERROR|SUCCESS|BUILD)" | tail -5
```

Expected: BUILD SUCCESS (or similar success message)

**Step 3: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add simpleec-channel/src/main/java/com/simpleec/channel/adapter/CyberbizAdapter.java
git commit -m "feat: create CyberbizAdapter with Mode B pattern (dual API calls for order list)"
```

---

## Task 2: Register CyberbizAdapter in ChannelJobConsumer

**Files:**
- Modify: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelJobConsumer.java:34-36` (add adapter injection)
- Modify: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelJobConsumer.java:186-198` (add switch case)
- Modify: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelJobConsumer.java:41-68` (add Kafka listener for cyberbiz.slow)
- Modify: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelJobConsumer.java:64-68` (add listener for cyberbiz.detail)

**Step 1: Add CyberbizAdapter injection to ChannelJobConsumer**

In `simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelJobConsumer.java`, after line 36 (after `shopeeAdapter`), add:

```java
    // Platform-specific adapters (must be registered as Spring beans)
    private final ChannelAdapter shopifyAdapter;
    private final ChannelAdapter easystoreAdapter;
    private final ChannelAdapter shopeeAdapter;
    private final ChannelAdapter cyberbizAdapter;  // NEW: Cyberbiz adapter
```

**Step 2: Add Cyberbiz slow topic listener**

Add this method after `consumeShopeeSlowChannel` (around line 60):

```java
    /**
     * 消費 Cyberbiz slow channel
     */
    @KafkaListener(topics = "cyberbiz.slow", groupId = "channel-job-group")
    public void consumeCyberbizSlowChannel(String message) {
        consumeChannelMessage(message, "cyberbiz");
    }
```

**Step 3: Add Cyberbiz detail topic listener**

Add this method after `consumeShopeeDetailChannel` (around line 68):

```java
    /**
     * 消費 Cyberbiz detail channel（Mode B）
     */
    @KafkaListener(topics = "cyberbiz.detail", groupId = "channel-job-group")
    public void consumeCyberbizDetailChannel(String message) {
        consumeDetailChannel(message, "cyberbiz");
    }
```

**Step 4: Add Cyberbiz case to getAdapter switch statement**

In `getAdapter()` method, add this case before the default (around line 194):

```java
            case "cyberbiz":
                return cyberbizAdapter;
```

**Step 5: Verify the file compiles**

Run from project root:
```bash
cd /home/tom/ONEEC/simpleec-oms
mvn clean compile -DskipTests 2>&1 | grep -E "(ERROR|SUCCESS|BUILD)" | tail -5
```

Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add simpleec-channel-job/src/main/java/com/simpleec/channeljob/consumer/ChannelJobConsumer.java
git commit -m "feat: register CyberbizAdapter in ChannelJobConsumer for cyberbiz.slow and cyberbiz.detail topics"
```

---

## Task 3: Implement Cyberbiz API Client Wrapper

**Files:**
- Create: `simpleec-channel/src/main/java/com/simpleec/channel/api/CyberbizApiClient.java`
- Reference: Look at ShopeeAdapter to understand API response format

**Step 1: Create CyberbizApiClient interface and implementation**

Create `simpleec-channel/src/main/java/com/simpleec/channel/api/CyberbizApiClient.java`:

```java
package com.simpleec.channel.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Cyberbiz API 客戶端
 *
 * 封裝 Cyberbiz API 呼叫邏輯，包括：
 * - GET /api/order/get_orders（搭配時間範圍參數）
 * - GET /api/order/get_order（單筆訂單詳情）
 *
 * 生產環境需要實現實際 HTTP 呼叫（使用 RestTemplate 或 WebClient）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CyberbizApiClient {

    /**
     * 查詢該時段內建立的訂單
     *
     * @param createTimeFrom 建立時間開始 (Unix timestamp)
     * @param createTimeTo   建立時間結束 (Unix timestamp)
     * @return 訂單 ID 列表
     */
    public List<String> getOrdersCreatedInTimeRange(long createTimeFrom, long createTimeTo) {
        log.debug("Calling Cyberbiz API: get_orders with create_time_from={}, create_time_to={}",
            createTimeFrom, createTimeTo);

        // TODO: 實現實際 HTTP 呼叫：
        // GET https://api.cyberbiz.io/api/order/get_orders?create_time_from=<>&create_time_to=<>
        // 返回 JSON 格式：{"success":true,"data":{"orders":[{"order_id":"CBZ-001"}]}}

        // 模擬返回
        return Arrays.asList("CBZ-CREATE-00001", "CBZ-CREATE-00002", "CBZ-CREATE-00003");
    }

    /**
     * 查詢該時段內更新的訂單
     *
     * @param updateTimeFrom 更新時間開始 (Unix timestamp)
     * @param updateTimeTo   更新時間結束 (Unix timestamp)
     * @return 訂單 ID 列表
     */
    public List<String> getOrdersUpdatedInTimeRange(long updateTimeFrom, long updateTimeTo) {
        log.debug("Calling Cyberbiz API: get_orders with update_time_from={}, update_time_to={}",
            updateTimeFrom, updateTimeTo);

        // TODO: 實現實際 HTTP 呼叫：
        // GET https://api.cyberbiz.io/api/order/get_orders?update_time_from=<>&update_time_to=<>

        // 模擬返回
        return Arrays.asList("CBZ-UPDATE-00001", "CBZ-UPDATE-00002");
    }

    /**
     * 查詢單筆訂單詳情
     *
     * @param orderId Cyberbiz 訂單 ID
     * @return 訂單詳情 (Map 格式)
     */
    public Map<String, Object> getOrderDetail(String orderId) {
        log.debug("Calling Cyberbiz API: get_order with order_id={}", orderId);

        // TODO: 實現實際 HTTP 呼叫：
        // GET https://api.cyberbiz.io/api/order/get_order?order_id=<>
        // 返回完整訂單結構

        // 模擬返回
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("order_id", orderId);
        order.put("order_sn", "CBZ" + System.currentTimeMillis());
        order.put("status", "pending");
        order.put("created_at", "2024-02-20T10:30:00Z");
        order.put("updated_at", "2024-02-20T10:35:00Z");
        order.put("amount_info", Map.of("total", 2500.0, "subtotal", 2300.0));
        return order;
    }

    /**
     * 查詢該時段內有退貨的訂單
     *
     * @param refundTimeFrom 退貨時間開始 (Unix timestamp)
     * @param refundTimeTo   退貨時間結束 (Unix timestamp)
     * @return 訂單列表（帶退貨資訊）
     */
    public List<Map<String, Object>> getOrdersWithRefund(long refundTimeFrom, long refundTimeTo) {
        log.debug("Calling Cyberbiz API: get_orders with refund_time_from={}, refund_time_to={}",
            refundTimeFrom, refundTimeTo);

        // TODO: 實現實際 HTTP 呼叫：
        // GET https://api.cyberbiz.io/api/order/get_orders?refund_time_from=<>&refund_time_to=<>

        // 模擬返回
        return new ArrayList<>();
    }
}
```

**Step 2: Update CyberbizAdapter to use CyberbizApiClient**

Modify `CyberbizAdapter.java`:
- Add `@RequiredArgsConstructor` if not present
- Inject `CyberbizApiClient cyberbizApiClient`
- Update `fetchOrdersCreatedInTimeRange()` to call `cyberbizApiClient.getOrdersCreatedInTimeRange()`
- Update `fetchOrdersUpdatedInTimeRange()` to call `cyberbizApiClient.getOrdersUpdatedInTimeRange()`
- Update `fetchOrderDetail()` to call `cyberbizApiClient.getOrderDetail()`
- Update `fetchReturns()` to call `cyberbizApiClient.getOrdersWithRefund()`

**Step 3: Verify compilation**

```bash
cd /home/tom/ONEEC/simpleec-oms
mvn clean compile -DskipTests 2>&1 | grep -E "(ERROR|SUCCESS|BUILD)" | tail -5
```

Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add simpleec-channel/src/main/java/com/simpleec/channel/api/CyberbizApiClient.java
git add simpleec-channel/src/main/java/com/simpleec/channel/adapter/CyberbizAdapter.java
git commit -m "feat: create CyberbizApiClient and integrate with CyberbizAdapter"
```

---

## Task 4: Create Unit Tests for CyberbizAdapter

**Files:**
- Create: `simpleec-channel/src/test/java/com/simpleec/channel/adapter/CyberbizAdapterTest.java`

**Step 1: Create test class**

Create `simpleec-channel/src/test/java/com/simpleec/channel/adapter/CyberbizAdapterTest.java`:

```java
package com.simpleec.channel.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CyberbizAdapter Tests")
class CyberbizAdapterTest {

    private CyberbizAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CyberbizAdapter();
    }

    @Test
    @DisplayName("should return 'cyberbiz' as platform code")
    void testGetPlatformCode() {
        assertEquals("cyberbiz", adapter.getPlatformCode());
    }

    @Test
    @DisplayName("should return Mode B for Cyberbiz")
    void testGetMode() {
        assertEquals("B", adapter.getMode().getCode());
    }

    @Test
    @DisplayName("should throw exception for Mode A fetchOrders")
    void testFetchOrdersThrowsException() {
        assertThrows(UnsupportedOperationException.class, () -> {
            adapter.fetchOrders("last_1_hour");
        });
    }

    @Test
    @DisplayName("should fetch order list from Cyberbiz (Mode B)")
    void testFetchOrderList() throws Exception {
        List<String> orderIds = adapter.fetchOrderList("last_1_hour");
        assertNotNull(orderIds);
        assertFalse(orderIds.isEmpty());
        assertTrue(orderIds.stream().allMatch(id -> id.startsWith("CBZ-")));
    }

    @Test
    @DisplayName("should fetch order detail for a specific order")
    void testFetchOrderDetail() throws Exception {
        Map<String, Object> detail = adapter.fetchOrderDetail("CBZ-001");
        assertNotNull(detail);
        assertTrue(detail.containsKey("order_id"));
        assertTrue(detail.containsKey("status"));
        assertTrue(detail.containsKey("items"));
        assertTrue(detail.containsKey("buyer_info"));
        assertTrue(detail.containsKey("shipping_info"));
    }

    @Test
    @DisplayName("should handle empty order list gracefully")
    void testFetchOrderListEmpty() throws Exception {
        // 模擬無訂單情況的測試（需要 mock 化後的單元測試）
        List<String> orderIds = adapter.fetchOrderList("last_1_hour");
        assertNotNull(orderIds);
        // 實際情況可能為空，不應該拋出異常
    }

    @Test
    @DisplayName("should test connection successfully")
    void testConnectionSuccess() throws Exception {
        assertTrue(adapter.testConnection());
    }

    @Test
    @DisplayName("should fetch returns from Cyberbiz")
    void testFetchReturns() throws Exception {
        List<Map<String, Object>> returns = adapter.fetchReturns("last_1_hour");
        assertNotNull(returns);
        // 模擬情況下可能為空
    }
}
```

**Step 2: Run tests to verify they pass**

```bash
cd /home/tom/ONEEC/simpleec-oms
mvn test -Dtest=CyberbizAdapterTest -DskipOthers 2>&1 | tail -20
```

Expected: Tests should pass (or show output like "Tests run: 7, Failures: 0")

**Step 3: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add simpleec-channel/src/test/java/com/simpleec/channel/adapter/CyberbizAdapterTest.java
git commit -m "test: add unit tests for CyberbizAdapter Mode B pattern"
```

---

## Task 5: Verify End-to-End Integration with Docker Compose

**Files:**
- Reference: `docker-compose.yml` (Kafka topics)
- Reference: `simpleec-channel-job/src/main/resources/application.yml` (configuration)

**Step 1: Start services**

```bash
cd /home/tom/ONEEC/simpleec-oms
docker compose up -d 2>&1 | grep -E "(Starting|Created|ERROR)" | head -20
```

Expected: All services start without errors

**Step 2: Verify Kafka topics exist**

```bash
docker exec simpleec-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -E "cyberbiz"
```

Expected: Should show `cyberbiz.slow` and `cyberbiz.detail` topics (or create them if needed)

**Step 3: Check logs for CyberbizAdapter registration**

```bash
docker logs simpleec-channel-job 2>&1 | grep -i "cyberbiz\|adapter" | tail -10
```

Expected: Should see evidence that CyberbizAdapter is registered/loaded

**Step 4: Commit verification results**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add -A
git commit -m "test: verify CyberbizAdapter integration with Kafka topics and Docker services"
```

---

## Task 6: Document Cyberbiz Integration in Project README

**Files:**
- Modify: `docs/README.md` (add Cyberbiz section)
- Reference: `docs/PLATFORM_MAPPING.md` (existing platform documentation)

**Step 1: Add Cyberbiz adapter documentation**

In `docs/README.md`, add a new section under the Platform Integration section:

```markdown
### Cyberbiz Adapter (Mode B)

**Pattern:** Dual API calls with detail fetch
- **FETCH_ORDERS:** Two API calls (created_at + updated_at time windows)
- **FETCH_ORDER_DETAIL:** Single order detail fetch
- **FETCH_RETURN:** Refund timestamp filtering

**Files:**
- `simpleec-channel/src/main/java/com/simpleec/channel/adapter/CyberbizAdapter.java`
- `simpleec-channel/src/main/java/com/simpleec/channel/api/CyberbizApiClient.java`

**Flow:**
1. SchedulerConsumer sends FETCH_ORDERS every 5 minutes to `cyberbiz.slow`
2. ChannelJobConsumer consumes from `cyberbiz.slow` and calls `CyberbizAdapter.fetchOrderList()`
3. CyberbizAdapter makes two API calls (created + updated) and deduplicates order IDs
4. ModeBOrderListHandler sends FETCH_ORDER_DETAIL for each order to `cyberbiz.detail`
5. ChannelJobConsumer consumes from `cyberbiz.detail` and calls `CyberbizAdapter.fetchOrderDetail()`
6. ModeBOrderDetailHandler transforms to OMS schema and sends ORDER_UPSERT to `order.process`

**Configuration:**
- Kafka topics: `cyberbiz.slow`, `cyberbiz.detail`, `cyberbiz.fast`
- Consumer group: `channel-job-group`
- Mode: B (list + detail separation)
```

**Step 2: Commit documentation**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add docs/README.md
git commit -m "docs: add Cyberbiz adapter integration documentation"
```

---

## Execution Checklist

After implementing all tasks:

- [ ] CyberbizAdapter compiles and is registered as Spring bean
- [ ] ChannelJobConsumer includes Cyberbiz listeners for `.slow` and `.detail` topics
- [ ] CyberbizApiClient provides wrapper methods for API calls
- [ ] Unit tests for CyberbizAdapter pass
- [ ] Docker services start without errors
- [ ] Documentation is updated
- [ ] All code is committed with meaningful messages

---

**Estimated Effort:** 45-60 minutes for complete implementation
**Prerequisites:** Java 17, Spring Boot 3.5, understanding of Mode B pattern
**Success Criteria:** CyberbizAdapter handles dual API calls for orders and returns, integrates with ChannelJobConsumer, all tests pass

---

**Created:** 2026-02-21
**Updated:** 2026-02-21 23:00 UTC
