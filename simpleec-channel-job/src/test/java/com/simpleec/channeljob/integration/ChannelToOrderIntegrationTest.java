package com.simpleec.channeljob.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channeljob.handler.ModeBOrderDetailHandler;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.constants.TopicConstants;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration Tests: ChannelJob to OrderJob Flow
 *
 * 測試完整的事件流程：
 * 1. FETCH_ORDER_DETAIL 消息 → ChannelJobConsumer
 * 2. 從 Adapter 拉取訂單詳情 → ModeBOrderDetailHandler
 * 3. 轉換為 OMS 標準格式 → buildOmsOrderData()
 * 4. 計算 Hash → calculateOrderHash()
 * 5. 發送 ORDER_UPSERT 到 order.process topic → Kafka
 * 6. 驗證訊息結構和內容
 *
 * 使用 Mockito 進行隔離測試，模擬 Kafka 訊息流
 */
@ExtendWith({MockitoExtension.class})
@DisplayName("ChannelJob to OrderJob Integration - Complete Event Flow")
class ChannelToOrderIntegrationTest {

    private static final Logger logger = Logger.getLogger(ChannelToOrderIntegrationTest.class.getName());

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ChannelAdapter cyberbizAdapter;

    private ModeBOrderDetailHandler handler;
    private ObjectMapper objectMapper;

    private String testMerchantId = "M001";
    private String testChannelId = "cyberbiz";
    private String testOrderId = "CBZ-202402-00001";

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        handler = new ModeBOrderDetailHandler(kafkaTemplate, objectMapper);

        // Mock adapter to return test order
        when(cyberbizAdapter.fetchOrderDetail(anyString())).thenReturn(buildMockOrderDetail());
    }

    @Test
    @DisplayName("Should flow: ChannelJob receives FETCH_ORDER_DETAIL → produces ORDER_UPSERT → captured on order.process")
    void testCompleteOrderFlowChannelToOrderJob() throws Exception {
        logger.info("Test 1: Complete order flow from FETCH_ORDER_DETAIL to ORDER_UPSERT");

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, cyberbizAdapter);

        // Assert: Verify ORDER_UPSERT message was sent to order.process
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate, times(1)).send(topicCaptor.capture(), keyCaptor.capture(), messageCaptor.capture());

        assertEquals(TopicConstants.ORDER_PROCESS, topicCaptor.getValue());
        assertEquals(testOrderId, keyCaptor.getValue());

        ObjectNode orderMsg = objectMapper.readValue(messageCaptor.getValue(), ObjectNode.class);
        assertEquals("ORDER_UPSERT", orderMsg.get("header").get("taskType").asText());
        assertEquals(testMerchantId, orderMsg.get("header").get("merchantId").asText());
        assertEquals(testChannelId, orderMsg.get("header").get("channelId").asText());

        logger.info("OK: ORDER_UPSERT message verified");
    }

    @Test
    @DisplayName("Should handle Mode B flow: fetch order detail from adapter and convert to OMS format")
    void testModeBOrderDetailHandling() throws Exception {
        logger.info("Test 2: Mode B order detail handling and OMS conversion");

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, cyberbizAdapter);

        // Assert: Verify adapter was called to fetch detail
        verify(cyberbizAdapter, times(1)).fetchOrderDetail(testOrderId);

        // Verify message was sent
        verify(kafkaTemplate, times(1)).send(
            eq(TopicConstants.ORDER_PROCESS),
            eq(testOrderId),
            anyString()
        );

        logger.info("OK: Mode B order detail handling completed");
    }

    @Test
    @DisplayName("Should preserve order data integrity through transformation")
    void testOrderDataIntegrity() throws Exception {
        logger.info("Test 3: Order data integrity through schema transformation");

        // Arrange: Create detailed test order with all fields
        Map<String, Object> orderDetail = buildCompleteOrderDetail();
        when(cyberbizAdapter.fetchOrderDetail(anyString())).thenReturn(orderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, cyberbizAdapter);

        // Assert: Verify ORDER_UPSERT message contains all critical fields
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
            eq(TopicConstants.ORDER_PROCESS),
            eq(testOrderId),
            messageCaptor.capture()
        );

        ObjectNode orderMsg = objectMapper.readValue(messageCaptor.getValue(), ObjectNode.class);
        ObjectNode body = (ObjectNode) orderMsg.get("body");
        ObjectNode orderData = (ObjectNode) body.get("orderData");

        // Verify critical fields are preserved
        assertNotNull(body.get("channelOrderId"), "channelOrderId must be present");
        assertNotNull(body.get("orderHash"), "orderHash must be present");
        assertNotNull(orderData.get("status"), "status must be present");
        assertNotNull(orderData.get("totalAmount"), "totalAmount must be present");
        assertNotNull(orderData.get("items"), "items must be present");
        assertNotNull(orderData.get("buyerInfo"), "buyerInfo must be present");
        assertNotNull(orderData.get("shippingInfo"), "shippingInfo must be present");

        logger.info("OK: Order data integrity verified");
    }

    @Test
    @DisplayName("Should generate consistent hash for deduplication")
    void testOrderHashConsistency() throws Exception {
        logger.info("Test 4: Order hash consistency for deduplication");

        // Act: Call handler twice with same order
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, cyberbizAdapter);
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, cyberbizAdapter);

        // Assert: Both should produce messages with same hash
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(
            eq(TopicConstants.ORDER_PROCESS),
            eq(testOrderId),
            messageCaptor.capture()
        );

        String hash1 = objectMapper.readValue(messageCaptor.getAllValues().get(0), ObjectNode.class)
            .get("body").get("orderHash").asText();
        String hash2 = objectMapper.readValue(messageCaptor.getAllValues().get(1), ObjectNode.class)
            .get("body").get("orderHash").asText();

        assertEquals(hash1, hash2, "Hash should be consistent for same order");

        logger.info("OK: Order hash consistency verified: hash=" + hash1.substring(0, 8) + "...");
    }

    @Test
    @DisplayName("Should include valid header structure in ORDER_UPSERT message")
    void testMessageHeaderStructure() throws Exception {
        logger.info("Test 5: Message header structure validation");

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, cyberbizAdapter);

        // Assert: Verify header structure
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
            eq(TopicConstants.ORDER_PROCESS),
            eq(testOrderId),
            messageCaptor.capture()
        );

        ObjectNode orderMsg = objectMapper.readValue(messageCaptor.getValue(), ObjectNode.class);
        ObjectNode header = (ObjectNode) orderMsg.get("header");

        // Verify required header fields
        assertTrue(header.has("messageId"), "Header must contain messageId");
        assertTrue(header.has("taskType"), "Header must contain taskType");
        assertTrue(header.has("channelId"), "Header must contain channelId");
        assertTrue(header.has("merchantId"), "Header must contain merchantId");
        assertTrue(header.has("timestamp"), "Header must contain timestamp");
        assertTrue(header.has("version"), "Header must contain version");

        // Verify header values
        assertEquals("ORDER_UPSERT", header.get("taskType").asText());
        assertEquals(testChannelId, header.get("channelId").asText());
        assertEquals(testMerchantId, header.get("merchantId").asText());
        assertEquals(1, header.get("version").asInt());

        // Verify messageId is not empty
        assertNotNull(header.get("messageId").asText());
        assertFalse(header.get("messageId").asText().isEmpty());

        logger.info("OK: Message header structure verified");
    }

    @Test
    @DisplayName("Should include valid body structure with all required fields")
    void testMessageBodyStructure() throws Exception {
        logger.info("Test 6: Message body structure validation");

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, cyberbizAdapter);

        // Assert: Verify body structure
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
            eq(TopicConstants.ORDER_PROCESS),
            eq(testOrderId),
            messageCaptor.capture()
        );

        ObjectNode orderMsg = objectMapper.readValue(messageCaptor.getValue(), ObjectNode.class);
        ObjectNode body = (ObjectNode) orderMsg.get("body");

        // Verify required body fields
        assertTrue(body.has("channelOrderId"), "Body must contain channelOrderId");
        assertTrue(body.has("orderHash"), "Body must contain orderHash");
        assertTrue(body.has("orderData"), "Body must contain orderData");

        // Verify channelOrderId matches
        assertEquals(testOrderId, body.get("channelOrderId").asText());

        // Verify orderHash is a valid SHA256 hash (64 hex characters)
        String orderHash = body.get("orderHash").asText();
        assertFalse(orderHash.isEmpty(), "orderHash must not be empty");
        assertEquals(64, orderHash.length(), "orderHash should be SHA256 hex (64 chars)");
        assertTrue(orderHash.matches("[a-f0-9]{64}"), "orderHash must be valid hex");

        // Verify orderData is present
        ObjectNode orderData = (ObjectNode) body.get("orderData");
        assertNotNull(orderData, "orderData must not be null");
        assertTrue(orderData.has("status"), "orderData must contain status");
        assertTrue(orderData.has("totalAmount"), "orderData must contain totalAmount");

        logger.info("OK: Message body structure verified");
    }

    @Test
    @DisplayName("Should handle order status mapping correctly")
    void testStatusMapping() throws Exception {
        logger.info("Test 7: Order status mapping (ready-to-ship -> READY_TO_SHIP)");

        // Arrange: Create order with lowercase status with hyphens
        Map<String, Object> orderDetail = new LinkedHashMap<>();
        orderDetail.put("status", "ready-to-ship");
        orderDetail.put("total_amount", 2000.0);
        orderDetail.put("created_at", "2024-02-20T10:30:00Z");

        when(cyberbizAdapter.fetchOrderDetail(anyString())).thenReturn(orderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, cyberbizAdapter);

        // Assert: Verify status is mapped correctly
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
            eq(TopicConstants.ORDER_PROCESS),
            eq(testOrderId),
            messageCaptor.capture()
        );

        ObjectNode orderMsg = objectMapper.readValue(messageCaptor.getValue(), ObjectNode.class);
        ObjectNode orderData = (ObjectNode) orderMsg.get("body").get("orderData");
        String status = orderData.get("status").asText();

        assertEquals("READY_TO_SHIP", status, "Status should be uppercase with underscores");

        logger.info("OK: Status mapping verified: ready-to-ship -> " + status);
    }

    @Test
    @DisplayName("Should handle adapter API errors gracefully")
    void testAdapterApiErrorHandling() throws Exception {
        logger.info("Test 8: Adapter API error handling");

        // Arrange: Mock adapter to throw exception
        when(cyberbizAdapter.fetchOrderDetail(anyString()))
            .thenThrow(new RuntimeException("API Connection Error"));

        // Act & Assert: Should throw exception when adapter fails
        assertThrows(Exception.class, () ->
            handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, cyberbizAdapter),
            "Should throw exception when adapter API fails"
        );

        logger.info("OK: Adapter API error handling verified");
    }

    // ==================== Helper Methods ====================

    /**
     * Build mock order detail with typical Cyberbiz API response format
     */
    private Map<String, Object> buildMockOrderDetail() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("order_id", testOrderId);
        order.put("status", "pending");
        order.put("total_amount", 2500.0);
        order.put("created_at", "2024-02-20T10:30:00Z");
        order.put("updated_at", "2024-02-20T10:35:00Z");

        // Items list
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sku", "CBZ-ITEM-001");
        item.put("product_id", "9876543210");
        item.put("name", "Test Product");
        item.put("quantity", 1);
        item.put("unit_price", 2300.0);
        items.add(item);
        order.put("items", items);

        // Shipping info
        Map<String, Object> shipping = new LinkedHashMap<>();
        shipping.put("name", "Test Recipient");
        shipping.put("phone", "0912345678");
        shipping.put("address", "Test Address");
        order.put("shipping_info", shipping);

        // Customer info
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("user_id", "CBZ_BUYER_001");
        customer.put("username", "testbuyer");
        customer.put("email", "test@example.com");
        order.put("buyer_info", customer);

        return order;
    }

    /**
     * Build complete order detail with all optional fields
     */
    private Map<String, Object> buildCompleteOrderDetail() {
        Map<String, Object> order = buildMockOrderDetail();

        // Add more complete data
        Map<String, Object> amountInfo = new LinkedHashMap<>();
        amountInfo.put("total", 2500.0);
        amountInfo.put("subtotal", 2300.0);
        amountInfo.put("tax", 200.0);
        order.put("amount_info", amountInfo);

        // Add more items
        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
        Map<String, Object> item2 = new LinkedHashMap<>();
        item2.put("sku", "CBZ-ITEM-002");
        item2.put("product_id", "9876543211");
        item2.put("name", "Second Product");
        item2.put("quantity", 1);
        item2.put("unit_price", 200.0);
        items.add(item2);

        return order;
    }
}
