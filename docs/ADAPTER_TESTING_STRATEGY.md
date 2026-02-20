# SimpleEC OMS 平台適配器測試策略

> 問題：如何測試 Momo/Shopee/Yahoo/PChome/Cyberbiz 等平台的 API 集成？

---

## 1. 難點分析

### 1.1 為什麼平台測試難？

| 挑戰 | 原因 | 影響 |
|------|------|------|
| **API 不穩定** | 平台版本更新、維護停機 | 測試時斷時通 |
| **不能亂呼叫** | 可能修改真實訂單、計費 | 必須 Mock 或沙盒環境 |
| **格式差異大** | 每個平台不同的欄位名稱、結構 | Mode A/B 判定複雜 |
| **邊界情況多** | 網路超時、API 限流、部分欄位缺失 | 難以重現 |
| **非決定性測試** | API 響應速度不同、順序隨機 | 片狀故障 |

### 1.2 理想的測試流程

```
開發環境 (Local Dev):
  Adapter 開發 → 使用 Mock JSON
                 (讀取 fixture)
                 ↓
                 快速迭代

Staging 環境 (Pre-Prod):
  真實 API (沙盒)
  ↓
  錄製真實響應 → VCR 磁帶

生產環境 (Prod):
  真實 API
  ↓
  監控 + 告警
```

---

## 2. 測試策略分層

### 2.1 Level 1：JSON Fixture 測試（本機，無網路）

```java
// MomoOrderAdapterTest.java
class MomoOrderAdapterTest {

    private MomoOrderAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new MomoOrderAdapter();
    }

    @Test
    @DisplayName("Momo API 回應：解析成 OMS OrderData")
    void testParsingMomoApiResponse() {
        // Arrange - 讀取固定的 JSON fixture（來自真實 Momo API 的紀錄）
        String jsonResponse = readResource("fixtures/momo/order_response_single_item.json");
        MomoApiResponse apiResponse = JsonUtil.parse(jsonResponse, MomoApiResponse.class);

        // Act
        OrderData orderData = adapter.parseOrder(apiResponse);

        // Assert
        assertThat(orderData)
            .hasFieldOrPropertyWithValue("channelOrderId", "2202130001")
            .hasFieldOrPropertyWithValue("buyerName", "王小明");

        assertThat(orderData.getItems())
            .hasSize(1)
            .anySatisfy(item -> assertThat(item.getChannelProductId()).isEqualTo("12345678"));
    }

    @Test
    @DisplayName("Momo 多商品：應正確展開 items 陣列")
    void testParsingMultipleItems() {
        String jsonResponse = readResource("fixtures/momo/order_response_multi_items.json");
        MomoApiResponse apiResponse = JsonUtil.parse(jsonResponse, MomoApiResponse.class);

        OrderData orderData = adapter.parseOrder(apiResponse);

        assertThat(orderData.getItems()).hasSize(3);
        // 驗證每個 item 都有必填欄位
        assertThat(orderData.getItems()).allMatch(item ->
            item.getChannelProductId() != null &&
            item.getQuantity() > 0 &&
            item.getUnitPrice() != null
        );
    }

    @Test
    @DisplayName("Momo 缺失 buyerPhone：應填入預設值")
    void testMomoMissingPhoneFallback() {
        String jsonResponse = readResource("fixtures/momo/order_response_no_phone.json");
        MomoApiResponse apiResponse = JsonUtil.parse(jsonResponse, MomoApiResponse.class);

        OrderData orderData = adapter.parseOrder(apiResponse);

        // Momo 某些舊訂單沒有 phone
        assertThat(orderData.getBuyerPhone())
            .isEqualTo("[Phone Not Provided]");
    }
}
```

**Fixture 位置**：
```
src/test/resources/
└─ fixtures/
   ├─ momo/
   │  ├─ order_response_single_item.json
   │  ├─ order_response_multi_items.json
   │  ├─ order_response_no_phone.json
   │  └─ ...
   ├─ shopee/
   │  ├─ order_response_single_item.json
   │  └─ ...
   └─ pchome/
      └─ ...
```

**如何獲得 Fixture**：
1. 連接平台沙盒 API，手動建立測試訂單
2. 記錄完整的 JSON 回應
3. 保存到 `fixtures/` 目錄
4. 定期更新（平台 API 版本更新時）

---

### 2.2 Level 2：Mock HTTP Client 測試（本機，模擬 HTTP）

```java
// MomoOrderAdapterIntegrationTest.java
@ExtendWith(MockServerExtension.class)
class MomoOrderAdapterIntegrationTest {

    private MomoOrderAdapter adapter;
    private MockHttpClient mockClient;

    @BeforeEach
    void setup(MockHttpServer server) {
        this.mockClient = new MockHttpClient(server.baseUrl());
        this.adapter = new MomoOrderAdapter(mockClient);
    }

    @Test
    @DisplayName("Momo API 模擬：應發送正確的請求並解析回應")
    void testMomoApiCall() {
        // Arrange - 設置 Mock Server 期望
        mockClient.stubGet("/api/orders?status=pending")
            .withResponse(readResource("fixtures/momo/order_response_single_item.json"))
            .withStatusCode(200);

        // Act
        List<OrderData> orders = adapter.fetchOrders("pending");

        // Assert
        assertThat(orders).hasSize(1);
        assertThat(mockClient).hasReceivedRequest()
            .withMethod("GET")
            .withPath("/api/orders")
            .withQueryParam("status", "pending")
            .withHeader("Authorization", "Bearer momo_token_xyz");
    }

    @Test
    @DisplayName("Momo API 限流（429）：應重試")
    void testMomoRateLimitRetry() {
        // Arrange - 第一次返回 429，第二次成功
        mockClient.stubGet("/api/orders")
            .willReturnOnce(MockResponse.status(429)
                .withHeader("Retry-After", "1"))
            .willReturnOnce(MockResponse.ok(readResource("fixtures/momo/order_response_single_item.json")));

        // Act
        List<OrderData> orders = adapter.fetchOrders("pending");

        // Assert
        assertThat(orders).hasSize(1);
        assertThat(mockClient).hasReceivedRequestCount(2); // 重試一次
    }

    @Test
    @DisplayName("Momo API 超時（504）：應拋出例外")
    void testMomoApiTimeout() {
        // Arrange
        mockClient.stubGet("/api/orders")
            .willReturn(MockResponse.status(504));

        // Act & Assert
        assertThatThrownBy(() -> adapter.fetchOrders("pending"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("504");
    }

    @Test
    @DisplayName("Momo API 部分欄位缺失：應降級處理")
    void testMomoPartialData() {
        // Arrange
        String partialResponse = readResource("fixtures/momo/order_response_partial.json");
        mockClient.stubGet("/api/orders")
            .withResponse(partialResponse)
            .withStatusCode(200);

        // Act
        List<OrderData> orders = adapter.fetchOrders("pending");

        // Assert - 應該解析成功，缺失欄位用預設值
        assertThat(orders).allMatch(order ->
            order.getBuyerName() != null && // 有預設值
            order.getItems().size() > 0
        );
    }
}
```

---

### 2.3 Level 3：VCR (Video Cassette Recorder) 測試（回放真實 API）

**原理**：第一次連接真實 API，記錄所有請求/回應；之後的測試回放 tape。

```java
// MomoOrderAdapterVcrTest.java
@ExtendWith(VcrExtension.class)
class MomoOrderAdapterVcrTest {

    private MomoOrderAdapter adapter;

    @BeforeEach
    void setup(VcrRecorder vcr) {
        // 使用真實 HTTP Client，但所有請求被 VCR 攔截
        HttpClient realClient = HttpClient.newHttpClient();
        this.adapter = new MomoOrderAdapter(realClient, vcr);
    }

    @Test
    @Vcr("momo_fetch_pending_orders.yaml") // ← 指定磁帶檔案
    void testFetchPendingOrders() {
        // 第一次運行：連接真實 Momo Sandbox API
        // 之後運行：從 momo_fetch_pending_orders.yaml 回放

        // Act
        List<OrderData> orders = adapter.fetchOrders("pending");

        // Assert
        assertThat(orders).hasSize(greaterThan(0));
        assertThat(orders.get(0).getBuyerName()).isNotEmpty();
    }

    @Test
    @Vcr("momo_fetch_detail_order.yaml")
    void testFetchOrderDetail() {
        // 第一次：連接真實 API → Mode B 驗證
        // 之後：使用錄製的回應

        OrderData order = adapter.fetchOrderDetail("2202130001");

        assertThat(order)
            .hasFieldOrPropertyWithValue("orderId", "ord_abc123")
            .hasFieldOrPropertyWithValue("refundPolicy", notNullValue()); // Mode B 特性
    }
}
```

**VCR Tape 結構**（YAML）：
```yaml
# src/test/resources/vcr/momo_fetch_pending_orders.yaml
interactions:
  - request:
      method: GET
      uri: https://api-sandbox.momoshop.com.tw/api/v2/orders?status=pending&limit=100
      headers:
        Authorization: Bearer momo_sandbox_token_xyz
    response:
      status: 200
      headers:
        Content-Type: application/json
      body: |
        {
          "orders": [
            {
              "orderId": "2202130001",
              "buyerName": "王小明",
              ...
            }
          ]
        }
      recorded_at: 2026-02-20T10:00:00Z
```

**何時運行 VCR 測試**：
```bash
# 開發時（重放磁帶，快速）
mvn test -Dvcr.mode=playback

# 更新 API（連接真實 API，記錄新回應）
mvn test -Dvcr.mode=record

# CI/CD（只用磁帶，不連接真實 API）
mvn test -Dvcr.mode=playback
```

---

### 2.4 Level 4：沙盒環境集成測試（真實 API，但沙盒）

```java
// MomoOrderAdapterSandboxTest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Profile("sandbox")  // ← 只在沙盒環境運行
class MomoOrderAdapterSandboxTest {

    @Autowired
    private MomoOrderAdapter adapter;

    @Autowired
    @Value("${momo.sandbox.api-key}") // 從 application-sandbox.yml 讀取
    private String apiKey;

    @Test
    @DisplayName("Momo 沙盒：實際連接沙盒 API 並驗證")
    @Tag("sandbox")
    void testRealSandboxApi() {
        // 注意：這個測試連接真實的 Momo 沙盒 API
        // 會實際建立/修改沙盒訂單

        // Act
        List<OrderData> orders = adapter.fetchOrders("pending");

        // Assert
        assertThat(orders).isNotNull();

        // 清理：刪除測試訂單
        if (!orders.isEmpty()) {
            adapter.deleteOrder(orders.get(0).getChannelOrderId());
        }
    }

    @Test
    @DisplayName("Momo 沙盒：測試 Mode A vs Mode B 偵測")
    @Tag("sandbox")
    void testModeDetection() {
        // Mode A 判定：API 是否支持 detail endpoint？
        boolean hasDetailEndpoint = adapter.isDetailApiAvailable();

        if (hasDetailEndpoint) {
            // Mode B：應該能取得詳細資訊（退貨政策等）
            OrderData detail = adapter.fetchOrderDetail("test-order-id");
            assertThat(detail.getRefundPolicy()).isNotNull();
        } else {
            // Mode A：只用列表 API
            assertThat(detail.getRefundPolicy()).isNull();
        }
    }
}
```

**執行條件**：
```bash
# 只在有沙盒金鑰時運行
mvn test -P sandbox -Dmomo.sandbox.api-key=xxx

# CI 跳過沙盒測試（沒有金鑰）
mvn test -P \!sandbox
```

---

## 3. 各平台的特殊處理

### 3.1 Momo（相對完整的 API）

```java
// MomoAdapterSpecifics.java

/**
 * Momo 特點：
 * - 支持 Mode A（列表 API）和 Mode B（詳情 API）
 * - 某些舊訂單欄位缺失（buyerPhone）
 * - 商品陣列可能為空（空訂單）
 */

@Test
void testMomoEmptyItems() {
    // Momo 可能返回沒有商品的訂單（罕見但存在）
    String response = readResource("fixtures/momo/order_empty_items.json");

    // 應該拒絕此訂單
    assertThatThrownBy(() -> adapter.parseOrder(response))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("items cannot be empty");
}

@Test
void testMomoOldFormatCompatibility() {
    // Momo 舊 API 版本（2023）的格式
    String oldFormat = readResource("fixtures/momo/order_response_2023_format.json");

    // Adapter 應該兼容
    OrderData order = adapter.parseOrder(oldFormat);
    assertThat(order).isNotNull();
}
```

### 3.2 Shopee（複雜的規格映射）

```java
// ShopeeAdapterSpecifics.java

/**
 * Shopee 特點：
 * - 規格概念複雜（model_id + tier_variation）
 * - SKU 和 item_id 對應關係複雜
 * - 有銷售屬性（attribute_name/value）
 */

@Test
void testShopeeVariationMapping() {
    String response = readResource("fixtures/shopee/order_with_variations.json");
    ShopeeApiResponse apiResponse = JsonUtil.parse(response);

    OrderData order = adapter.parseOrder(apiResponse);

    // Shopee 商品應該有變體資訊
    OrderItem item = order.getItems().get(0);
    assertThat(item)
        .hasFieldOrPropertyWithValue("channelSpecId", "tier_variation_123")
        .hasFieldOrPropertyWithValue("channelSpecName", "紅色/M");

    // channelSpecAttrs 應該包含銷售屬性
    Map<String, String> attrs = item.get("channelSpecAttrs");
    assertThat(attrs).containsKeys("color", "size");
}

@Test
void testShopeeDynamicPricing() {
    // Shopee 有動態定價，同一 SKU 可能不同價格
    String response = readResource("fixtures/shopee/order_dynamic_price.json");

    OrderData order = adapter.parseOrder(response);

    // 應該使用實際支付價格，不是標價
    assertThat(order.getItems().get(0).getUnitPrice())
        .isEqualTo(new BigDecimal("3999.00")); // 實付價，不是掛牌價
}
```

### 3.3 PChome（簡化的結構）

```java
// PChomeAdapterSpecifics.java

/**
 * PChome 特點：
 * - 無規格概念（所有商品都是「一規格」）
 * - 商品編碼簡單（直接用 product_id）
 * - 付款資訊簡單（無複雜支付方式）
 */

@Test
void testPChomeNoSpec() {
    String response = readResource("fixtures/pchome/order_no_spec.json");

    OrderData order = adapter.parseOrder(response);

    // PChome 不應該有 channelSpecId
    assertThat(order.getItems().get(0))
        .hasFieldOrPropertyWithValue("channelSpecId", null);
}
```

### 3.4 Cyberbiz（完整的電商平台）

```java
// CyberbizAdapterSpecifics.java

/**
 * Cyberbiz 特點：
 * - 支持多種商品類型（實體、虛擬、服務）
 * - 複雜的優惠計算
 * - 詳細的出貨配置
 */

@Test
void testCyberbizVirtualProduct() {
    // Cyberbiz 支持虛擬商品（無實體）
    String response = readResource("fixtures/cyberbiz/order_virtual_product.json");

    OrderData order = adapter.parseOrder(response);

    // 虛擬商品應被標記（無實體出貨）
    assertThat(order.getItems().get(0).get("productType"))
        .isEqualTo("VIRTUAL");
}

@Test
void testCyberbizComplexDiscount() {
    // Cyberbiz 優惠計算複雜（優惠碼 + 分級優惠 + 會員優惠）
    String response = readResource("fixtures/cyberbiz/order_complex_discount.json");

    OrderData order = adapter.parseOrder(response);

    // 驗證最終金額正確
    BigDecimal totalPrice = order.getItems().stream()
        .map(OrderItem::getSubtotal)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    assertThat(totalPrice.add(order.getDiscountAmount()))
        .isEqualByComparingTo(order.getTotalAmount());
}
```

---

## 4. Mode A vs Mode B 測試

### 4.1 如何檢測 Mode？

```java
// AdapterModeDetectionTest.java

@Test
void testMomoModeADetection() {
    // Mode A：只有列表 API，無詳情 API
    // 模擬：detail endpoint 返回 404

    mockClient.stubGet("/api/orders/{orderId}")
        .willReturn(MockResponse.notFound());

    boolean isDetailAvailable = adapter.isDetailApiAvailable();

    assertThat(isDetailAvailable).isFalse();
    assertThat(adapter.getMode()).isEqualTo("A");
}

@Test
void testMomoModeBDetection() {
    // Mode B：有詳情 API，能獲得額外資訊

    mockClient.stubGet("/api/orders/{orderId}")
        .willReturn(MockResponse.ok(readResource("fixtures/momo/order_detail.json")));

    boolean isDetailAvailable = adapter.isDetailApiAvailable();

    assertThat(isDetailAvailable).isTrue();
    assertThat(adapter.getMode()).isEqualTo("B");
}

@Test
void testAdapterModeAFallback() {
    // Mode A 下，詳情應該從列表 API 的資訊推導

    adapter.setMode("A");
    OrderData order = adapter.parseOrder(listApiResponse);

    // 退貨政策應為 null（Mode A 無法獲得）
    assertThat(order.getRefundPolicy()).isNull();
}

@Test
void testAdapterModeBEnhancement() {
    // Mode B 下，應該包含額外資訊

    adapter.setMode("B");
    mockClient.stubDetail(detailApiResponse);

    OrderData order = adapter.fetchOrderDetail("123");

    // 應該包含詳情資訊
    assertThat(order.getRefundPolicy()).isNotNull();
    assertThat(order.getWarrantyInfo()).isNotNull();
}
```

---

## 5. 實施路線圖

### Phase 1：基礎結構（第 1 週）

```java
✅ 完成：
  - JsonFixture 加載機制
  - MomoOrderAdapterTest (JSON fixtures)
  - MockHttpClient 設置
  - VCR 錄製基礎設施

❌ 跳過：
  - 實際連接 Momo 沙盒（沒有金鑰還）
  - E2E 測試（優先 Unit + Integration）
```

### Phase 2：Platform Adapters（第 2-3 週）

```
Week 2:
  ✅ MomoOrderAdapter (Mode A/B)
  ✅ MockHttpClient integration tests

Week 3:
  ✅ ShopeeOrderAdapter
  ✅ PChomeOrderAdapter
  ⏳ Cyberbiz (待 API 金鑰)
```

### Phase 3：VCR + Sandbox（第 4 週）

```
  ✅ 記錄 VCR tapes（連接各平台沙盒）
  ✅ Sandbox integration tests
  ✅ Mode detection tests
```

### Phase 4：生產環境（Release 時）

```
  ✅ 實際監控生產 API（告警、重試邏輯）
  ✅ 建立 incident response 流程
```

---

## 6. 測試金鑰管理

### 6.1 本機開發

```bash
# .env.local（永遠別 commit）
MOMO_SANDBOX_API_KEY=sandbox_key_xxx
SHOPEE_SANDBOX_API_KEY=sandbox_key_yyy

# application-dev.yml
spring:
  profiles: dev
  momo:
    api-key: ${MOMO_SANDBOX_API_KEY}
    sandbox: true
```

### 6.2 CI/CD

```yaml
# .github/workflows/test.yml
jobs:
  test:
    runs-on: ubuntu-latest
    env:
      MOMO_SANDBOX_API_KEY: ${{ secrets.MOMO_SANDBOX_API_KEY }}
      SHOPEE_SANDBOX_API_KEY: ${{ secrets.SHOPEE_SANDBOX_API_KEY }}
    steps:
      - run: mvn test -P sandbox
```

---

## 7. 總結表：各層級測試對比

| 層級 | 工具 | 網路 | 速度 | 覆蓋 | 何時用 |
|------|------|------|------|------|--------|
| **Fixture** | JSON | ❌ | ⚡ 快 | 解析邏輯 | 每日開發 |
| **Mock HTTP** | MockServer | ❌ | ⚡ 快 | 請求/回應 | pre-commit |
| **VCR** | HTTP + 回放 | ① | ⚡ 中 | 完整流程 | CI/CD |
| **Sandbox** | 真實 API | ✅ | 🐢 慢 | 實際行為 | 週期檢查 |

---

**文件日期**：2026-02-20
**狀態**：設計完成，建議即刻實施 Phase 1
