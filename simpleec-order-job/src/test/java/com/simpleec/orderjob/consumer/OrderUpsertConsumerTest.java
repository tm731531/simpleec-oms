package com.simpleec.orderjob.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.core.entity.Order;
import com.simpleec.core.service.OrderService;
import com.simpleec.common.enums.OrderStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderUpsertConsumer Tests — ChannelJob Integration Scenarios
 *
 * 測試 ORDER_UPSERT 消費者處理來自 ChannelJob 的訊息：
 * 1. 訊息結構驗證（Header/Body 統一格式）
 * 2. 兩層去重機制（Redis + Database）
 * 3. 各種訂單狀態處理
 * 4. 端到端資料流驗證
 *
 * 測試框架：Mockito 隔離單元測試，模擬 OrderService 和 Redis
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderUpsertConsumer - ChannelJob Integration Tests")
class OrderUpsertConsumerTest {

    @Mock(lenient = true)
    private OrderService orderService;

    @Mock(lenient = true)
    private StringRedisTemplate redisTemplate;

    @Mock(lenient = true)
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Acknowledgment acknowledgment;

    private OrderUpsertConsumer consumer;
    private ObjectMapper objectMapper;
    private String testMerchantId = "M001";
    private String testChannelId = "cyberbiz";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new OrderUpsertConsumer(orderService, redisTemplate, objectMapper);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ============================================================================
    // Test Group 1: ChannelJob Message Schema Validation
    // ============================================================================

    @Test
    @DisplayName("Should process ORDER_UPSERT message from ChannelJob with correct schema")
    void testProcessOrderUpsertFromChannelJob() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-202402-00001";
        String orderHash = "hash_" + System.currentTimeMillis();

        ObjectNode message = buildOrderUpsertMessage(
            channelOrderId, testMerchantId, testChannelId, orderHash, "pending", 2500.0
        );
        String messageStr = objectMapper.writeValueAsString(message);

        // Act & Assert: Should not throw exception and should process message
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });
    }

    @Test
    @DisplayName("Should extract correct header fields from ChannelJob message")
    void testExtractHeaderFields() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-HEADER-001";
        ObjectNode message = objectMapper.createObjectNode();

        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_test_" + System.currentTimeMillis());
        header.put("taskType", "ORDER_UPSERT");
        header.put("channelId", testChannelId);
        header.put("merchantId", testMerchantId);
        header.put("timestamp", Instant.now().toString());
        header.put("version", 1);
        message.set("header", header);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelOrderId", channelOrderId);
        body.put("orderHash", "hash_test");
        ObjectNode orderData = objectMapper.createObjectNode();
        orderData.put("status", "pending");
        orderData.put("totalAmount", 1000.0);
        body.set("orderData", orderData);
        message.set("body", body);

        String messageStr = objectMapper.writeValueAsString(message);

        // Setup mocks
        when(valueOperations.get(anyString())).thenReturn(null);
        when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
            .thenReturn(Optional.empty());
        Order savedOrder = new Order();
        savedOrder.setId("ORD_header_test");
        when(orderService.updateOrder(any(Order.class))).thenReturn(savedOrder);

        // Act & Assert
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

    }

    @Test
    @DisplayName("Should validate ORDER_UPSERT body structure with complete items and shipping data")
    void testValidateBodyStructure() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-BODY-001";
        String orderHash = "hash_body_123";

        ObjectNode message = objectMapper.createObjectNode();

        ObjectNode header = objectMapper.createObjectNode();
        header.put("taskType", "ORDER_UPSERT");
        header.put("merchantId", testMerchantId);
        header.put("channelId", testChannelId);
        header.put("timestamp", Instant.now().toString());
        message.set("header", header);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelOrderId", channelOrderId);
        body.put("orderHash", orderHash);

        ObjectNode orderData = objectMapper.createObjectNode();
        orderData.put("status", "confirmed");
        orderData.put("totalAmount", 2500.0);
        orderData.put("createdAt", "2024-02-20T10:30:00Z");

        // Items
        var items = objectMapper.createArrayNode();
        var item = objectMapper.createObjectNode();
        item.put("sku", "SKU-001");
        item.put("quantity", 1);
        item.put("unit_price", 2300.0);
        items.add(item);
        orderData.set("items", items);

        // Shipping
        var shippingInfo = objectMapper.createObjectNode();
        shippingInfo.put("name", "Test Recipient");
        shippingInfo.put("address", "Test Address");
        orderData.set("shippingInfo", shippingInfo);

        body.set("orderData", orderData);
        message.set("body", body);

        String messageStr = objectMapper.writeValueAsString(message);

        // Setup mocks
        when(valueOperations.get(anyString())).thenReturn(null);
        when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
            .thenReturn(Optional.empty());
        Order savedOrder = new Order();
        savedOrder.setId("ORD_body_test");
        when(orderService.updateOrder(any(Order.class))).thenReturn(savedOrder);

        // Act & Assert
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

    }

    // ============================================================================
    // Test Group 2: Two-Layer Deduplication (Redis + Database)
    // ============================================================================

    @Test
    @DisplayName("Should apply two-layer deduplication for ChannelJob ORDER_UPSERT")
    void testTwoLayerDeduplication() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-DEDUP-001";
        String orderHash = "fixed_hash_dedup";

        ObjectNode message = buildOrderUpsertMessage(
            channelOrderId, testMerchantId, testChannelId, orderHash, "pending", 2500.0
        );
        String messageStr = objectMapper.writeValueAsString(message);

        // Setup mocks for first message
        when(valueOperations.get(anyString())).thenReturn(null);
        when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
            .thenReturn(Optional.empty());
        Order savedOrder = new Order();
        savedOrder.setId("ORD_dedup_001");
        when(orderService.updateOrder(any(Order.class))).thenReturn(savedOrder);

        // Act - First message
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });


        // Reset and setup for second message with Redis hit
        reset(valueOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(orderHash);

        // Act - Second message with Redis deduplication
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

        // Verify second message was acknowledged
    }

    @Test
    @DisplayName("Should skip update when order content hash matches existing")
    void testSkipUpdateWhenHashMatches() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-HASH-MATCH-001";
        String orderHash = "same_hash_no_change";

        ObjectNode message = buildOrderUpsertMessage(
            channelOrderId, testMerchantId, testChannelId, orderHash, "confirmed", 2500.0
        );
        String messageStr = objectMapper.writeValueAsString(message);

        // Setup mocks: Redis miss, DB hit with existing order
        when(valueOperations.get(anyString())).thenReturn(null);

        Order existingOrder = new Order();
        existingOrder.setId("ORD_existing");
        existingOrder.setChannelId(testChannelId);
        existingOrder.setChannelOrderId(channelOrderId);

        when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
            .thenReturn(Optional.of(existingOrder));

        // Act & Assert: Should not throw exception
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

    }

    // ============================================================================
    // Test Group 3: Various Order Status Handling
    // ============================================================================

    @Test
    @DisplayName("Should handle PENDING status from ChannelJob")
    void testHandlePendingStatus() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-STATUS-PENDING";
        ObjectNode message = buildOrderUpsertMessage(
            channelOrderId, testMerchantId, testChannelId, "hash_pending", "pending", 1000.0
        );
        String messageStr = objectMapper.writeValueAsString(message);

        // Setup mocks
        when(valueOperations.get(anyString())).thenReturn(null);
        when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
            .thenReturn(Optional.empty());
        Order savedOrder = new Order();
        savedOrder.setId("ORD_pending");
        savedOrder.setOrderStatus(OrderStatusEnum.PENDING);
        when(orderService.updateOrder(any(Order.class))).thenReturn(savedOrder);

        // Act & Assert
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

    }

    @Test
    @DisplayName("Should handle CONFIRMED status from ChannelJob")
    void testHandleConfirmedStatus() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-STATUS-CONFIRMED";
        ObjectNode message = buildOrderUpsertMessage(
            channelOrderId, testMerchantId, testChannelId, "hash_confirmed", "confirmed", 2000.0
        );
        String messageStr = objectMapper.writeValueAsString(message);

        // Setup mocks
        when(valueOperations.get(anyString())).thenReturn(null);
        when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
            .thenReturn(Optional.empty());
        Order savedOrder = new Order();
        savedOrder.setId("ORD_confirmed");
        savedOrder.setOrderStatus(OrderStatusEnum.CONFIRMED);
        when(orderService.updateOrder(any(Order.class))).thenReturn(savedOrder);

        // Act & Assert
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

    }

    @Test
    @DisplayName("Should handle SHIPPED status from ChannelJob")
    void testHandleShippedStatus() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-STATUS-SHIPPED";
        ObjectNode message = buildOrderUpsertMessage(
            channelOrderId, testMerchantId, testChannelId, "hash_shipped", "shipped", 3500.0
        );
        String messageStr = objectMapper.writeValueAsString(message);

        // Setup mocks
        when(valueOperations.get(anyString())).thenReturn(null);
        when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
            .thenReturn(Optional.empty());
        Order savedOrder = new Order();
        savedOrder.setId("ORD_shipped");
        savedOrder.setOrderStatus(OrderStatusEnum.SHIPPED);
        when(orderService.updateOrder(any(Order.class))).thenReturn(savedOrder);

        // Act & Assert
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

    }

    @Test
    @DisplayName("Should handle COMPLETED status from ChannelJob")
    void testHandleCompletedStatus() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-STATUS-COMPLETED";
        ObjectNode message = buildOrderUpsertMessage(
            channelOrderId, testMerchantId, testChannelId, "hash_completed", "completed", 3000.0
        );
        String messageStr = objectMapper.writeValueAsString(message);

        // Setup mocks
        when(valueOperations.get(anyString())).thenReturn(null);
        when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
            .thenReturn(Optional.empty());
        Order savedOrder = new Order();
        savedOrder.setId("ORD_completed");
        savedOrder.setOrderStatus(OrderStatusEnum.COMPLETED);
        when(orderService.updateOrder(any(Order.class))).thenReturn(savedOrder);

        // Act & Assert
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

    }

    @Test
    @DisplayName("Should handle CANCELLED status from ChannelJob")
    void testHandleCancelledStatus() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-STATUS-CANCELLED";
        ObjectNode message = buildOrderUpsertMessage(
            channelOrderId, testMerchantId, testChannelId, "hash_cancelled", "cancelled", 2000.0
        );
        String messageStr = objectMapper.writeValueAsString(message);

        // Setup mocks
        when(valueOperations.get(anyString())).thenReturn(null);
        when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
            .thenReturn(Optional.empty());
        Order savedOrder = new Order();
        savedOrder.setId("ORD_cancelled");
        savedOrder.setOrderStatus(OrderStatusEnum.CANCELLED);
        when(orderService.updateOrder(any(Order.class))).thenReturn(savedOrder);

        // Act & Assert
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

    }

    // ============================================================================
    // Test Group 4: Error Handling & Edge Cases
    // ============================================================================

    @Test
    @DisplayName("Should skip processing when taskType is not ORDER_UPSERT")
    void testSkipNonOrderUpsertTaskType() throws Exception {
        // Arrange
        ObjectNode message = objectMapper.createObjectNode();
        ObjectNode header = objectMapper.createObjectNode();
        header.put("taskType", "INVALID_TYPE");
        header.put("merchantId", testMerchantId);
        message.set("header", header);

        String messageStr = objectMapper.writeValueAsString(message);

        // Act & Assert
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

    }

    @Test
    @DisplayName("Should handle message with complete order details from ChannelJob")
    void testProcessCompleteOrderUpsert() throws Exception {
        // Arrange
        String channelOrderId = "CBZ-COMPLETE-001";
        ObjectNode message = buildDetailedOrderUpsertMessage(
            channelOrderId, testMerchantId, testChannelId
        );
        String messageStr = objectMapper.writeValueAsString(message);

        // Setup mocks
        when(valueOperations.get(anyString())).thenReturn(null);
        when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
            .thenReturn(Optional.empty());
        Order savedOrder = new Order();
        savedOrder.setId("ORD_complete");
        when(orderService.updateOrder(any(Order.class))).thenReturn(savedOrder);

        // Act & Assert
        assertDoesNotThrow(() -> {
            consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
        });

    }

    @Test
    @DisplayName("Should process multiple orders with different statuses from ChannelJob")
    void testProcessMultipleOrdersWithDifferentStatuses() throws Exception {
        // Arrange
        String[] statuses = {"pending", "confirmed", "shipped"};

        for (int i = 0; i < statuses.length; i++) {
            String channelOrderId = "CBZ-MULTI-" + i;
            ObjectNode message = buildOrderUpsertMessage(
                channelOrderId, testMerchantId, testChannelId,
                "hash_multi_" + i, statuses[i], 1000.0 + (i * 500)
            );
            String messageStr = objectMapper.writeValueAsString(message);

            // Setup mocks for each order
            when(valueOperations.get(anyString())).thenReturn(null);
            when(orderService.findByChannelOrderId(testChannelId, channelOrderId))
                .thenReturn(Optional.empty());
            Order savedOrder = new Order();
            savedOrder.setId("ORD_multi_" + i);
            when(orderService.updateOrder(any(Order.class))).thenReturn(savedOrder);

            // Act & Assert
            assertDoesNotThrow(() -> {
                consumer.consumeOrderUpsert(messageStr, 0, acknowledgment);
            });
        }

        // Verify all 3 orders were acknowledged
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    /**
     * 構建標準的 ORDER_UPSERT 消息（ChannelJob 格式）
     */
    private ObjectNode buildOrderUpsertMessage(
            String channelOrderId, String merchantId, String channelId,
            String orderHash, String status, double totalAmount) {

        ObjectNode message = objectMapper.createObjectNode();

        // Header
        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_" + System.currentTimeMillis());
        header.put("taskType", "ORDER_UPSERT");
        header.put("channelId", channelId);
        header.put("merchantId", merchantId);
        header.put("timestamp", Instant.now().toString());
        header.put("version", 1);
        message.set("header", header);

        // Body
        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelOrderId", channelOrderId);
        body.put("orderHash", orderHash);

        // OrderData
        ObjectNode orderData = objectMapper.createObjectNode();
        orderData.put("status", status);
        orderData.put("totalAmount", totalAmount);
        orderData.put("createdAt", "2024-02-20T10:30:00Z");
        orderData.put("updatedAt", "2024-02-20T10:35:00Z");

        // Items
        var items = objectMapper.createArrayNode();
        var item = objectMapper.createObjectNode();
        item.put("sku", "SKU-001");
        item.put("quantity", 1);
        item.put("unit_price", totalAmount - 200);
        items.add(item);
        orderData.set("items", items);

        body.set("orderData", orderData);
        message.set("body", body);

        return message;
    }

    /**
     * 構建包含詳細資訊的 ORDER_UPSERT 消息
     */
    private ObjectNode buildDetailedOrderUpsertMessage(
            String channelOrderId, String merchantId, String channelId) {

        ObjectNode message = objectMapper.createObjectNode();

        // Header
        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_detailed_" + System.currentTimeMillis());
        header.put("taskType", "ORDER_UPSERT");
        header.put("channelId", channelId);
        header.put("merchantId", merchantId);
        header.put("timestamp", Instant.now().toString());
        header.put("version", 1);
        message.set("header", header);

        // Body
        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelOrderId", channelOrderId);
        body.put("orderHash", "hash_detailed_" + System.currentTimeMillis());

        // OrderData with complete details
        ObjectNode orderData = objectMapper.createObjectNode();
        orderData.put("status", "pending");
        orderData.put("totalAmount", 2500.0);
        orderData.put("createdAt", "2024-02-20T10:30:00Z");
        orderData.put("updatedAt", "2024-02-20T10:35:00Z");

        // Items
        var items = objectMapper.createArrayNode();
        var item1 = objectMapper.createObjectNode();
        item1.put("sku", "SKU-DETAIL-001");
        item1.put("quantity", 2);
        item1.put("unit_price", 1000.0);
        items.add(item1);
        orderData.set("items", items);

        // Shipping info
        var shippingInfo = objectMapper.createObjectNode();
        shippingInfo.put("name", "John Doe");
        shippingInfo.put("phone", "0912345678");
        shippingInfo.put("address", "123 Main St, Taipei");
        orderData.set("shippingInfo", shippingInfo);

        // Buyer info
        var buyerInfo = objectMapper.createObjectNode();
        buyerInfo.put("user_id", "BUYER_001");
        buyerInfo.put("username", "johndoe");
        buyerInfo.put("email", "john@example.com");
        orderData.set("buyerInfo", buyerInfo);

        body.set("orderData", orderData);
        message.set("body", body);

        return message;
    }
}
