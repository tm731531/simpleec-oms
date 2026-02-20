# SimpleEC OMS 測試框架設計

> 設計日期：2026-02-20
> 目標：完整的分層測試策略，從 Unit 到 E2E

---

## 1. 測試金字塔架構

```
                  ▲
                 ╱│╲
                ╱ │ ╲      E2E Tests (5%)
               ╱  │  ╲     ├─ Full flow: API → Kafka → Handler → DB
              ╱   │   ╲    └─ Multi-channel scenarios
             ╱────┼────╲
            ╱     │     ╲   Integration Tests (20%)
           ╱      │      ╲  ├─ Kafka message contract
          ╱       │       ╲ ├─ Handler + DB interaction
         ╱────────┼────────╲└─ Multi-handler workflows
        ╱         │         ╲
       ╱          │          ╲ Unit Tests (75%)
      ╱───────────┼───────────╲├─ Handler logic
     ╱            │            ╲├─ Adapter parsing
    ╱             │             ╲├─ Validator rules
   ╱              │              ╲└─ Schema conversion
  ╰───────────────┴───────────────╯
```

---

## 2. 分層測試策略

### 第 1 層：Unit Tests (75%) — 快速、隔離

**目標**：測試單一函數/類的邏輯

**工具**：JUnit 5 + Mockito + AssertJ

**覆蓋範圍**：

#### 2.1a Handler 邏輯測試

```java
// OrderUpsertHandlerTest.java
class OrderUpsertHandlerTest {

    private OrderUpsertHandler handler;
    private OrderRepository mockOrderRepo;
    private OrderShipmentRepository mockShipmentRepo;

    @BeforeEach
    void setup() {
        mockOrderRepo = mock(OrderRepository.class);
        mockShipmentRepo = mock(OrderShipmentRepository.class);
        handler = new OrderUpsertHandler(mockOrderRepo, mockShipmentRepo);
    }

    @Test
    @DisplayName("新訂單：應該 INSERT 並生成 orderId")
    void testNewOrderInsert() {
        // Arrange
        ProcessOrderMessage msg = createSampleOrderMessage();
        when(mockOrderRepo.findByChannelOrderId("MOMO-123")).thenReturn(Optional.empty());

        // Act
        OrderInsertResult result = handler.handle(msg);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOrderId()).isNotNull().startsWith("ord_");
        verify(mockOrderRepo).save(argThat(order ->
            order.getChannelOrderId().equals("MOMO-123") &&
            order.getBuyerName().equals("王小明") &&
            order.getItems().size() == 1
        ));
    }

    @Test
    @DisplayName("訂單更新：Hash 相同應跳過（冪等性）")
    void testOrderUpdateIdempotency() {
        // Arrange
        ProcessOrderMessage msg = createSampleOrderMessage();
        Order existing = new Order();
        existing.setId("ord_existing");
        existing.setHash("abc123"); // Same hash
        when(mockOrderRepo.findByChannelOrderId("MOMO-123"))
            .thenReturn(Optional.of(existing));

        // Act
        OrderInsertResult result = handler.handle(msg);

        // Assert
        assertThat(result.isSkipped()).isTrue();
        verify(mockOrderRepo, never()).update(any());
    }

    @Test
    @DisplayName("物流映射失敗：應使用預設值並記錄警告")
    void testShippingMappingFallback() {
        // Arrange
        ProcessOrderMessage msg = createSampleOrderMessage();
        msg.getOrderData().setShippingMethod("UNKNOWN_METHOD");
        when(mockOrderRepo.findByChannelOrderId(anyString())).thenReturn(Optional.empty());

        // Act
        OrderInsertResult result = handler.handle(msg);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        verify(logger).warn(contains("shipping method mapping not found"));
    }

    @Test
    @DisplayName("JSONB items 驗證：缺少 channelProductId 應拒絕")
    void testInvalidItemsJsonb() {
        // Arrange
        ProcessOrderMessage msg = createSampleOrderMessage();
        msg.getOrderData().getItems().get(0).setChannelProductId(null);

        // Act & Assert
        assertThatThrownBy(() -> handler.handle(msg))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("channelProductId is required");
    }

    @Test
    @DisplayName("價格驗證：小計不符（舍入誤差 > 0.01）應拒絕")
    void testSubtotalMismatch() {
        // Arrange
        ProcessOrderMessage msg = createSampleOrderMessage();
        OrderItem item = msg.getOrderData().getItems().get(0);
        item.setQuantity(2);
        item.setUnitPrice(100.00);
        item.setSubtotal(99.99); // Should be 200.00, off by > 0.01

        // Act & Assert
        assertThatThrownBy(() -> handler.handle(msg))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("subtotal mismatch");
    }
}
```

#### 2.1b Adapter 解析測試

```java
// MomoOrderAdapterTest.java
class MomoOrderAdapterTest {

    private MomoOrderAdapter adapter;
    private MomoApiResponse apiResponse;

    @BeforeEach
    void setup() {
        adapter = new MomoOrderAdapter();
        apiResponse = loadJsonFixture("momo_order_response.json");
    }

    @Test
    @DisplayName("Momo API 回應：應正確映射到 OMS OrderData")
    void testMomoApiParsing() {
        // Act
        OrderData orderData = adapter.parseOrder(apiResponse);

        // Assert
        assertThat(orderData)
            .hasFieldOrPropertyWithValue("channelOrderId", "MOMO-2026021300001")
            .hasFieldOrPropertyWithValue("buyerName", "王小明")
            .hasFieldOrPropertyWithValue("buyerPhone", "0912345678");

        assertThat(orderData.getItems())
            .hasSize(1)
            .anySatisfy(item -> {
                assertThat(item.getChannelProductId()).isEqualTo("MOMO-SKU-001");
                assertThat(item.getQuantity()).isEqualTo(1);
                assertThat(item.getUnitPrice()).isEqualByComparingTo("44900.00");
            });
    }

    @Test
    @DisplayName("Momo 缺少名字：應設置預設值")
    void testMomoMissingNameFallback() {
        // Arrange
        apiResponse.getOrder().setBuyerName(null);

        // Act
        OrderData orderData = adapter.parseOrder(apiResponse);

        // Assert
        assertThat(orderData.getBuyerName()).isEqualTo("[Unknown Buyer]");
    }

    @Test
    @DisplayName("Momo 多個商品：應全部解析")
    void testMomoMultipleItems() {
        // Arrange
        apiResponse = loadJsonFixture("momo_order_multiitem.json");

        // Act
        OrderData orderData = adapter.parseOrder(apiResponse);

        // Assert
        assertThat(orderData.getItems()).hasSize(3);
        assertThat(orderData.getItems().stream()
            .mapToDouble(OrderItem::getSubtotal)
            .sum()).isCloseTo(5000.00, within(0.01));
    }

    @Test
    @DisplayName("Momo Mode A（無詳情 API）：應使用列表資料")
    void testMomoModeAWithoutDetail() {
        // Act
        OrderData orderData = adapter.parseOrder(apiResponse);

        // Assert - 應該沒有詳細的退貨政策資訊
        assertThat(orderData.getRefundPolicy()).isNull();
    }

    @Test
    @DisplayName("Momo Mode B（有詳情 API）：應包含附加信息")
    void testMomoModeBWithDetail() {
        // Arrange
        apiResponse = loadJsonFixture("momo_order_with_detail.json");

        // Act
        OrderData orderData = adapter.parseOrder(apiResponse);

        // Assert
        assertThat(orderData.getRefundPolicy())
            .isNotNull()
            .hasFieldOrPropertyWithValue("days", 30);
    }
}
```

#### 2.1c Validator 規則測試

```java
// OrderItemValidatorTest.java
class OrderItemValidatorTest {

    private OrderItemValidator validator;

    @BeforeEach
    void setup() {
        validator = new OrderItemValidator();
    }

    @ParameterizedTest(name = "quantity={0}, unitPrice={1}, subtotal={2} → {3}")
    @CsvSource({
        "1, 44900.00, 44900.00, true",   // 正常
        "2, 100.00, 200.00, true",       // 正常
        "1, 0.01, 0.01, true",           // 最小價格
        "1, 44900.00, 44900.01, true",   // 舍入誤差 0.01
        "1, 44900.00, 44900.02, false",  // 舍入誤差超出
        "0, 100.00, 0.00, false",        // 數量為 0
        "-1, 100.00, -100.00, false",    // 負數數量
    })
    void testQuantityAndPriceValidation(int qty, String price, String subtotal, boolean expected) {
        // Act
        OrderItem item = new OrderItem();
        item.setQuantity(qty);
        item.setUnitPrice(new BigDecimal(price));
        item.setSubtotal(new BigDecimal(subtotal));

        boolean result = validator.isValid(item);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("必填欄位缺失：應驗證失敗")
    void testMissingRequiredFields() {
        // Arrange
        OrderItem item = new OrderItem();
        item.setQuantity(1);
        // channelProductId 缺失

        // Act & Assert
        assertThat(validator.isValid(item)).isFalse();
        assertThat(validator.getErrors())
            .contains("channelProductId is required");
    }
}
```

---

### 第 2 層：Integration Tests (20%) — 模塊互動

**目標**：測試 Handler + Database + Kafka 的互動

**工具**：Testcontainers (PostgreSQL + Kafka) + Spring Boot Test

**覆蓋範圍**：

#### 2.2a Kafka 消息合約測試

```java
// KafkaMessageContractTest.java
@SpringBootTest
@ActiveProfiles("test")
class KafkaMessageContractTest {

    @Autowired
    private KafkaTemplate<String, ProcessOrderMessage> kafkaTemplate;

    @Autowired
    private TestEventCaptor eventCaptor;

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest"));

    @Test
    @DisplayName("PROCESS_ORDER 消息：應符合 EVENT_SAMPLES.md 結構")
    void testProcessOrderMessageSchema() {
        // Arrange
        ProcessOrderMessage msg = ProcessOrderMessage.fromJson(
            readResource("event_samples/PROCESS_ORDER_sample.json")
        );

        // Act
        kafkaTemplate.send("order.process", msg.getHeader().getRequestId(), msg);

        // Assert
        ProcessOrderMessage received = eventCaptor.captureMessage(ProcessOrderMessage.class);
        assertThat(received)
            .satisfies(m -> {
                assertThat(m.getHeader().getTaskType()).isEqualTo("PROCESS_ORDER");
                assertThat(m.getBody().getOrderData().getOrderId()).matches("^ord_[a-zA-Z0-9]{16}$");
                assertThat(m.getBody().getOrderData().getItems()).isNotEmpty();
            });
    }

    @Test
    @DisplayName("消息重複發送：應幂等（同 requestId）")
    void testKafkaIdempotency() {
        // Arrange
        ProcessOrderMessage msg1 = createSampleOrderMessage();
        ProcessOrderMessage msg2 = createSampleOrderMessage(); // Same content
        msg2.getHeader().setRequestId(msg1.getHeader().getRequestId());

        // Act
        kafkaTemplate.send("order.process", msg1.getHeader().getRequestId(), msg1);
        kafkaTemplate.send("order.process", msg2.getHeader().getRequestId(), msg2);

        // Assert
        List<Order> orders = orderRepository.findByChannelOrderId("MOMO-123");
        assertThat(orders).hasSize(1); // Should be idempotent
    }

    @Test
    @DisplayName("PROCESS_RETURN 消息：orderId 應正確 FK 到 orders")
    void testProcessReturnForeignKey() {
        // Arrange - 先建立訂單
        Order order = createAndSaveOrder();
        ProcessReturnMessage returnMsg = ProcessReturnMessage.builder()
            .orderId(order.getId())
            .channelReturnId("YH-RET-001")
            .returnData(ReturnData.builder()
                .items(List.of(
                    ReturnItem.builder()
                        .productId("pd_123")
                        .channelProductId("YH-SKU-001")
                        .quantity(1)
                        .unitPrice(new BigDecimal("5000"))
                        .build()
                ))
                .build())
            .build();

        // Act
        kafkaTemplate.send("return.process", returnMsg.getHeader().getRequestId(), returnMsg);

        // Assert
        RefundOrder refund = refundOrderRepository.findByChannelReturnId("YH-RET-001").orElseThrow();
        assertThat(refund.getOrderId()).isEqualTo(order.getId());
    }
}
```

#### 2.2b Handler + DB 互動測試

```java
// OrderUpsertHandlerIntegrationTest.java
@SpringBootTest
@ActiveProfiles("test")
@DataJpaTest
class OrderUpsertHandlerIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderShipmentRepository shipmentRepository;

    @Autowired
    private OrderUpsertHandler handler;

    @Autowired
    private TestTransactionManager txManager;

    @Test
    @DisplayName("訂單建立 + 出貨單建立：原子性操作")
    void testOrderAndShipmentAtomicity() {
        // Arrange
        ProcessOrderMessage msg = createSampleOrderMessage();
        msg.getOrderData().setShippingMethod("HOME_DELIVERY");
        msg.getOrderData().setShippingStatus("PENDING");

        // Act
        OrderInsertResult result = handler.handle(msg);

        // Assert - 檢查 order 和 shipment 都被建立
        Order order = orderRepository.findById(result.getOrderId()).orElseThrow();
        assertThat(order.getId()).isEqualTo(result.getOrderId());
        assertThat(order.getChannelOrderId()).isEqualTo("MOMO-123");

        OrderShipment shipment = shipmentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(shipment.getShippingStatus()).isEqualTo("PENDING");
        assertThat(shipment.getLogisticsCompany()).isEqualTo("黑貓宅急便"); // 從 mapping 表查出
    }

    @Test
    @DisplayName("訂單更新：items JSONB 應正確更新")
    void testOrderJsonbUpdate() {
        // Arrange - 先建立訂單
        Order existing = createAndSaveOrder("MOMO-123", 1);
        assertThat(existing.getItems().size()).isEqualTo(1);

        // Act - 更新同一訂單，增加商品
        ProcessOrderMessage updateMsg = createSampleOrderMessage();
        updateMsg.getOrderData().setOrderId(existing.getId());
        updateMsg.getOrderData().getItems().add(createOrderItem("MOMO-SKU-002", 2));

        handler.handle(updateMsg);

        // Assert
        Order updated = orderRepository.findById(existing.getId()).orElseThrow();
        assertThat(updated.getItems().size()).isEqualTo(2);
        assertThat(updated.getItems().stream()
            .map(item -> item.get("channelProductId"))
            .toList()).containsExactlyInAnyOrder("MOMO-SKU-001", "MOMO-SKU-002");
    }

    @Test
    @DisplayName("訂單物流信息映射：查詢 channel_shipping_mapping")
    void testShippingMappingResolution() {
        // Arrange
        createShippingMapping("ch_momo", "HOME_DELIVERY", "黑貓宅急便", "BLACKCAT");
        ProcessOrderMessage msg = createSampleOrderMessage();
        msg.getOrderData().setShippingMethod("HOME_DELIVERY");

        // Act
        handler.handle(msg);

        // Assert
        OrderShipment shipment = shipmentRepository.findByChannelId("ch_momo").get(0);
        assertThat(shipment.getLogisticsCompany()).isEqualTo("黑貓宅急便");
        assertThat(shipment.getLogisticsCompanyCode()).isEqualTo("BLACKCAT");
    }

    @Test
    @DisplayName("訂單建立失敗：應回滾所有更改")
    void testOrderCreationRollback() {
        // Arrange
        ProcessOrderMessage msg = createSampleOrderMessage();
        // 模擬 constraint violation
        when(mockBuyer).thenThrow(new DataIntegrityViolationException("..."));

        // Act & Assert
        assertThatThrownBy(() -> handler.handle(msg))
            .isInstanceOf(DataIntegrityViolationException.class);

        // 確認沒有訂單被建立
        assertThat(orderRepository.findByChannelOrderId("MOMO-123")).isEmpty();
    }
}
```

#### 2.2c 多處理器工作流測試

```java
// MultiChannelOrderProcessingTest.java
@SpringBootTest
@ActiveProfiles("test")
class MultiChannelOrderProcessingTest {

    @Autowired
    private KafkaTemplate<String, ProcessOrderMessage> kafkaTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TestEventCaptor eventCaptor;

    @Test
    @DisplayName("多通路：同商品在 Momo/Shopee 上應有不同 sell_pack")
    void testMultiChannelSameSku() {
        // Arrange - 建立同樣商品在兩個通路
        ProcessOrderMessage momoMsg = createOrderMessage("ch_momo", "SKU-001", "MOMO-SKU-001");
        ProcessOrderMessage shopeeMsg = createOrderMessage("ch_shopee", "SKU-001", "SHOPEE-SKU-001");

        // Act
        kafkaTemplate.send("order.process", "momo_1", momoMsg);
        kafkaTemplate.send("order.process", "shopee_1", shopeeMsg);

        // Assert
        List<Order> momoOrders = orderRepository.findByChannelId("ch_momo");
        List<Order> shopeeOrders = orderRepository.findByChannelId("ch_shopee");

        assertThat(momoOrders).hasSize(1);
        assertThat(shopeeOrders).hasSize(1);

        // 同一 SKU，但不同通路上的價格可能不同
        OrderItem momoItem = momoOrders.get(0).getItems().get(0);
        OrderItem shopeeItem = shopeeOrders.get(0).getItems().get(0);
        assertThat(momoItem.get("unitPrice")).isNotEqualTo(shopeeItem.get("unitPrice"));
    }

    @Test
    @DisplayName("訂單 → 退貨 → 出貨：完整生命週期")
    void testCompleteOrderLifecycle() {
        // 1. PROCESS_ORDER - 建立訂單
        ProcessOrderMessage orderMsg = createSampleOrderMessage();
        kafkaTemplate.send("order.process", "req_1", orderMsg);
        Order order = eventCaptor.captureEntity(Order.class);

        // 2. PROCESS_RETURN - 建立退貨
        ProcessReturnMessage returnMsg = ProcessReturnMessage.builder()
            .orderId(order.getId())
            .channelReturnId("RET-001")
            .build();
        kafkaTemplate.send("return.process", "req_2", returnMsg);
        RefundOrder refund = eventCaptor.captureEntity(RefundOrder.class);

        // 3. SHIP_ORDER - 出貨
        ShipOrderMessage shipMsg = ShipOrderMessage.builder()
            .orderId(order.getId())
            .trackingNumber("1234567890")
            .build();
        kafkaTemplate.send("momo.fast", "req_3", shipMsg);
        OrderShipment shipment = eventCaptor.captureEntity(OrderShipment.class);

        // Assert
        assertThat(shipment.getShippingStatus()).isEqualTo("SHIPPED");
        assertThat(refund.getOrderId()).isEqualTo(order.getId());
    }
}
```

---

### 第 3 層：E2E Tests (5%) — 完整流程

**目標**：從 API 到 Database 的完整業務流程

**工具**：Spring Boot TestRestTemplate + Testcontainers + Rest Assured

**覆蓋範圍**：

```java
// OrderManagementE2ETest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderManagementE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private KafkaTemplate<String, ProcessOrderMessage> kafkaTemplate;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(...);

    @Container
    static KafkaContainer kafka = new KafkaContainer(...);

    @Test
    @DisplayName("完整業務流程：Web UI → Kafka → Handler → Database")
    void testCompleteOrderFlow() {
        // 1. API：建立訂單
        CreateOrderRequest request = CreateOrderRequest.builder()
            .channelId("ch_momo")
            .buyerName("王小明")
            .items(List.of(
                OrderItemRequest.builder()
                    .channelProductId("MOMO-SKU-001")
                    .quantity(1)
                    .unitPrice(new BigDecimal("44900.00"))
                    .build()
            ))
            .build();

        // Act
        ResponseEntity<CreateOrderResponse> response = restTemplate.postForEntity(
            "/api/orders", request, CreateOrderResponse.class
        );

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = response.getBody().getOrderId();

        // 2. Kafka：監聽 PROCESS_ORDER 消息
        ProcessOrderMessage msg = kafkaEventCaptor.capture(ProcessOrderMessage.class, Duration.ofSeconds(5));
        assertThat(msg.getBody().getOrderData().getOrderId()).isEqualTo(orderId);

        // 3. Database：驗證訂單已建立
        Order order = restTemplate.getForObject("/api/orders/{id}", Order.class, orderId);
        assertThat(order)
            .hasFieldOrPropertyWithValue("buyerName", "王小明")
            .hasFieldOrPropertyWithValue("status", "PENDING");

        // 4. 出貨：SHIP_ORDER
        ShipRequest shipRequest = ShipRequest.builder()
            .orderId(orderId)
            .trackingNumber("BLACKCAT-123")
            .build();
        restTemplate.postForObject("/api/orders/{id}/ship", shipRequest, ShipResponse.class, orderId);

        // 5. 驗證出貨狀態
        Order shipped = restTemplate.getForObject("/api/orders/{id}", Order.class, orderId);
        assertThat(shipped.getShipments().get(0).getShippingStatus()).isEqualTo("SHIPPED");

        // 6. 退貨：PROCESS_RETURN
        ReturnRequest returnRequest = ReturnRequest.builder()
            .itemIds(List.of(order.getItems().get(0).getId()))
            .reason("商品瑕疵")
            .build();
        restTemplate.postForObject("/api/orders/{id}/return", returnRequest, ReturnResponse.class, orderId);

        // 7. 驗證退貨已建立
        ResponseEntity<List> refunds = restTemplate.getForEntity(
            "/api/orders/{id}/refunds", List.class, orderId
        );
        assertThat(refunds.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("多通路並行訂單：應獨立處理")
    void testConcurrentMultiChannelOrders() {
        // Arrange
        int orderCount = 10;
        List<CreateOrderRequest> requests = IntStream.range(0, orderCount)
            .mapToObj(i -> CreateOrderRequest.builder()
                .channelId(i % 2 == 0 ? "ch_momo" : "ch_shopee")
                .buyerName("Buyer-" + i)
                .build())
            .toList();

        // Act - 並行建立訂單
        List<String> orderIds = requests.parallelStream()
            .map(req -> restTemplate.postForObject(
                "/api/orders", req, CreateOrderResponse.class
            ).getOrderId())
            .toList();

        // Assert
        assertThat(orderIds).hasSize(orderCount);
        List<Order> allOrders = restTemplate.getForObject("/api/orders", List.class);
        assertThat(allOrders).hasSizeGreaterThanOrEqualTo(orderCount);
    }
}
```

---

## 3. 測試資料策略

### 3.1 Fixtures（夾具）

```yaml
# src/test/resources/fixtures/orders/order_momo_single_item.json
{
  "header": {
    "taskType": "PROCESS_ORDER",
    "merchantId": "M001",
    "channelId": "ch_momo",
    "timestamp": "2026-02-20T10:00:00Z"
  },
  "body": {
    "orderData": {
      "orderId": "ord_test001",
      "channelOrderId": "MOMO-TEST-001",
      "buyerName": "測試用戶",
      ...
    }
  }
}
```

### 3.2 Data Builders（建造者）

```java
// TestOrderBuilder.java
public class TestOrderBuilder {
    public static Order newOrder() {
        return new Order()
            .withId("ord_" + nanoid())
            .withChannelId("ch_momo")
            .withBuyerName("Test Buyer")
            .withBuyerPhone("0912345678")
            .withItems(List.of(newOrderItem()));
    }

    public static OrderItem newOrderItem() {
        return new OrderItem()
            .withSku("TEST-SKU-001")
            .withProductId("pd_test001")
            .withChannelProductId("MOMO-TEST-001")
            .withQuantity(1)
            .withUnitPrice(new BigDecimal("1000.00"))
            .withSubtotal(new BigDecimal("1000.00"));
    }
}
```

---

## 4. 測試執行策略

### 4.1 分類執行

```bash
# 只執行 Unit 測試（快速，適合開發中）
mvn test -Dgroups=unit

# 執行 Unit + Integration（完整驗證）
mvn test -Dgroups="unit|integration"

# 執行全部包括 E2E（完整驗證，較慢）
mvn test

# 執行特定測試類
mvn test -Dtest=OrderUpsertHandlerTest
```

### 4.2 持續集成配置

```yaml
# .github/workflows/test.yml
name: Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-java@v2
        with:
          java-version: '17'
      - run: mvn test -Dgroups=unit

  integration-tests:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:14
      kafka:
        image: confluentinc/cp-kafka:latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-java@v2
      - run: mvn test -Dgroups=integration

  e2e-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-java@v2
      - run: mvn test -Dgroups=e2e
```

---

## 5. 關鍵測試場景矩陣

### 5.1 Adapter 適配器測試矩陣

| Adapter | Mode | 場景 | 覆蓋 |
|---------|------|------|------|
| **Momo** | A | 列表 API 訂單 | ✅ |
| | B | 列表 + 詳情 API | ✅ |
| | | 多商品訂單 | ✅ |
| | | 缺失欄位降級 | ✅ |
| **Shopee** | A | 列表 API | ✅ |
| | B | 列表 + 詳情 API | ✅ |
| | | 規格映射 | ✅ |
| **PChome** | A | 特殊格式解析 | ✅ |
| **Cyberbiz** | B | 完整 API | ✅ |

### 5.2 Handler 處理器測試矩陣

| Handler | 場景 | 驗證 |
|---------|------|------|
| **OrderUpsertHandler** | 新訂單 INSERT | JSONB items, shipping mapping |
| | | 訂單更新 UPDATE | Hash 驗證, 冪等性 |
| | | 多商品 | Items 合計驗證 |
| | | 失敗回滾 | 原子性 |
| **ReturnUpsertHandler** | 退貨建立 | FK 驗證, items 映射 |
| | | 部分退貨 | Items 子集驗證 |
| **ShipOrderHandler** | 出貨狀態更新 | Shipping status 轉換 |

### 5.3 多通路場景測試

| 場景 | 驗證點 |
|------|--------|
| 同商品不同通路 | 價格獨立, 庫存獨立 |
| 同商品不同規格 | 規格映射正確 |
| 孤立上架 (productId=null) | 允許 INSERT, 記錄警告 |
| 物流映射缺失 | 使用預設值, 不中斷 |
| 幂等重複消息 | 檢測 requestId, 跳過 |

---

## 6. 測試質量指標

### 6.1 覆蓋率目標

```
整體：> 80%
Critical paths (Handler 邏輯)：> 95%
Adapter：> 85%
Utility：> 70%
```

### 6.2 測試執行時間目標

| 層級 | 數量 | 時間 | 環境 |
|------|------|------|------|
| Unit | 200+ | < 30s | 本機 |
| Integration | 50+ | 1-2min | 本機 (containers) |
| E2E | 10+ | 3-5min | CI 環境 |
| **總計** | 260+ | **< 8min** | CI 環境 |

### 6.3 錯誤檢測能力

```
缺失欄位：100% 檢測
invalid schema：100% 檢測
FK 違反：100% 檢測
Race conditions：80% 檢測（通過並行測試）
```

---

## 7. 推薦實施順序

### Phase 1：基礎架構 (Week 1)
1. 建立測試工具類 (TestOrderBuilder, fixtures)
2. 建立 Unit 測試基類
3. 執行 Validator 單元測試

### Phase 2：Handler 邏輯 (Week 2)
4. OrderUpsertHandler Unit 測試
5. ReturnUpsertHandler Unit 測試
6. Handler + DB Integration 測試

### Phase 3：Adapter (Week 3)
7. MomoOrderAdapter Unit 測試
8. ShopeeOrderAdapter Unit 測試
9. 其他平台 Adapter

### Phase 4：E2E (Week 4)
10. 完整業務流程 E2E 測試
11. 多通路場景 E2E 測試
12. 性能和壓力測試

---

## 總結

此測試框架提供：
- ✅ **分層設計**：快速反饋（Unit）→ 可信度（Integration）→ 完整驗證（E2E）
- ✅ **多維度覆蓋**：Handler、Adapter、多通路、邊界情況
- ✅ **可維護性**：Builders、Fixtures、重用測試數據
- ✅ **持續集成友好**：按時間 tier 分類執行，快速失敗
- ✅ **實時反饋**：開發中可運行 Unit 測試（30秒），CI 完整驗證（8分鐘）

---

**文件日期**：2026-02-20
**狀態**：設計完成，待實施
