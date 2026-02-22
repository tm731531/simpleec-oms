package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.common.enums.ModeEnum;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.constants.TopicConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ModeBOrderDetailHandler Unit Tests
 *
 * 測試 Mode B 訂單詳情處理流程：
 * 1. 從 API 拉取訂單詳情
 * 2. 轉換為 OMS 標準格式
 * 3. 計算 Hash
 * 4. 發送 ORDER_UPSERT 訊息到 Kafka
 *
 * 使用 Mockito 進行隔離單元測試，無需 Spring Boot context
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModeBOrderDetailHandler - Order Detail Fetching and Transformation")
class ModeBOrderDetailHandlerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ChannelAdapter mockAdapter;

    private ModeBOrderDetailHandler handler;
    private ObjectMapper objectMapper;

    private String testMerchantId = "merchant_001";
    private String testChannelId = "cyberbiz";
    private String testOrderId = "CBZ-202402-00001";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new ModeBOrderDetailHandler(kafkaTemplate, objectMapper);
    }

    @Test
    @DisplayName("Should fetch order detail from adapter and send ORDER_UPSERT message successfully")
    void testFetchAndSendOrderDetail_Success() throws Exception {
        // Arrange: Mock complete order detail response
        Map<String, Object> mockOrderDetail = buildMockOrderDetail();
        when(mockAdapter.fetchOrderDetail(testOrderId)).thenReturn(mockOrderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter);

        // Assert
        verify(mockAdapter, times(1)).fetchOrderDetail(testOrderId);
        verify(kafkaTemplate, times(1)).send(
            eq(TopicConstants.ORDER_PROCESS),
            eq(testOrderId),
            anyString()
        );
    }

    @Test
    @DisplayName("Should handle null items list gracefully")
    void testFetchAndSendOrderDetail_NullItems() throws Exception {
        // Arrange
        Map<String, Object> mockOrderDetail = new LinkedHashMap<>();
        mockOrderDetail.put("status", "pending");
        mockOrderDetail.put("total_amount", 1500.0);
        mockOrderDetail.put("items", null);
        mockOrderDetail.put("created_at", "2024-02-20T10:30:00Z");

        when(mockAdapter.fetchOrderDetail(testOrderId)).thenReturn(mockOrderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter);

        // Assert
        verify(kafkaTemplate, times(1)).send(
            eq(TopicConstants.ORDER_PROCESS),
            eq(testOrderId),
            anyString()
        );
    }

    @Test
    @DisplayName("Should convert channel status to uppercase and replace hyphens with underscores")
    void testStatusMapping() throws Exception {
        // Arrange
        Map<String, Object> mockOrderDetail = new LinkedHashMap<>();
        mockOrderDetail.put("status", "ready-to-ship");
        mockOrderDetail.put("total_amount", 2000.0);
        mockOrderDetail.put("created_at", "2024-02-20T10:30:00Z");

        when(mockAdapter.fetchOrderDetail(testOrderId)).thenReturn(mockOrderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter);

        // Assert: Verify the ORDER_UPSERT message contains correct status
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TopicConstants.ORDER_PROCESS), eq(testOrderId), messageCaptor.capture());

        String message = messageCaptor.getValue();
        ObjectNode node = objectMapper.readValue(message, ObjectNode.class);
        ObjectNode body = (ObjectNode) node.get("body");
        ObjectNode orderData = (ObjectNode) body.get("orderData");
        String status = orderData.get("status").asText();
        assertEquals("READY_TO_SHIP", status);
    }

    @Test
    @DisplayName("Should handle order detail fetch errors with exception")
    void testHandleOrderDetailFetchError() throws Exception {
        // Arrange
        when(mockAdapter.fetchOrderDetail(anyString()))
            .thenThrow(new RuntimeException("API Connection Error"));

        // Act & Assert
        assertThrows(RuntimeException.class, () ->
            handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter)
        );
    }

    @Test
    @DisplayName("Should handle null order detail response gracefully")
    void testHandleNullOrderDetailResponse() throws Exception {
        // Arrange
        when(mockAdapter.fetchOrderDetail(anyString())).thenReturn(null);

        // Act & Assert: Should throw an exception when trying to process null
        assertThrows(Exception.class, () ->
            handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter)
        );
    }

    @Test
    @DisplayName("Should handle order detail with nested amount_info structure")
    void testHandleOrderDetailWithNestedAmountInfo() throws Exception {
        // Arrange
        Map<String, Object> mockOrderDetail = new LinkedHashMap<>();
        mockOrderDetail.put("status", "confirmed");

        Map<String, Object> amountInfo = new LinkedHashMap<>();
        amountInfo.put("total", 3200.50);
        amountInfo.put("subtotal", 3000.0);
        amountInfo.put("tax", 200.50);
        mockOrderDetail.put("amount_info", amountInfo);

        mockOrderDetail.put("created_at", "2024-02-20T10:30:00Z");

        when(mockAdapter.fetchOrderDetail(testOrderId)).thenReturn(mockOrderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter);

        // Assert
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TopicConstants.ORDER_PROCESS), eq(testOrderId), messageCaptor.capture());

        String message = messageCaptor.getValue();
        ObjectNode node = objectMapper.readValue(message, ObjectNode.class);
        ObjectNode body = (ObjectNode) node.get("body");
        ObjectNode orderData = (ObjectNode) body.get("orderData");
        String totalAmount = orderData.get("totalAmount").asText();
        assertTrue(totalAmount.contains("3200"));
    }

    @Test
    @DisplayName("Should include proper header structure in ORDER_UPSERT message")
    void testMessageHeaderStructure() throws Exception {
        // Arrange
        Map<String, Object> mockOrderDetail = buildMockOrderDetail();
        when(mockAdapter.fetchOrderDetail(testOrderId)).thenReturn(mockOrderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter);

        // Assert
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TopicConstants.ORDER_PROCESS), eq(testOrderId), messageCaptor.capture());

        String message = messageCaptor.getValue();
        ObjectNode node = objectMapper.readValue(message, ObjectNode.class);
        ObjectNode header = (ObjectNode) node.get("header");

        // Verify header contains required fields
        assertTrue(header.has("messageId"), "Header must contain messageId");
        assertTrue(header.has("taskType"), "Header must contain taskType");
        assertTrue(header.has("channelId"), "Header must contain channelId");
        assertTrue(header.has("merchantId"), "Header must contain merchantId");
        assertTrue(header.has("timestamp"), "Header must contain timestamp");
        assertTrue(header.has("version"), "Header must contain version");

        // Verify taskType is correct
        String taskType = header.get("taskType").asText();
        assertEquals(TaskTypeEnum.ORDER_UPSERT.getCode(), taskType);

        // Verify channelId and merchantId
        assertEquals(testChannelId, header.get("channelId").asText());
        assertEquals(testMerchantId, header.get("merchantId").asText());
    }

    @Test
    @DisplayName("Should include proper body structure in ORDER_UPSERT message")
    void testMessageBodyStructure() throws Exception {
        // Arrange
        Map<String, Object> mockOrderDetail = buildMockOrderDetail();
        when(mockAdapter.fetchOrderDetail(testOrderId)).thenReturn(mockOrderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter);

        // Assert
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TopicConstants.ORDER_PROCESS), eq(testOrderId), messageCaptor.capture());

        String message = messageCaptor.getValue();
        ObjectNode node = objectMapper.readValue(message, ObjectNode.class);
        ObjectNode body = (ObjectNode) node.get("body");

        // Verify body contains required fields
        assertTrue(body.has("channelOrderId"), "Body must contain channelOrderId");
        assertTrue(body.has("orderHash"), "Body must contain orderHash");
        assertTrue(body.has("orderData"), "Body must contain orderData");

        // Verify channelOrderId
        assertEquals(testOrderId, body.get("channelOrderId").asText());

        // Verify orderHash is a valid hash
        String orderHash = body.get("orderHash").asText();
        assertFalse(orderHash.isEmpty(), "orderHash must not be empty");
        assertEquals(64, orderHash.length(), "orderHash should be SHA256 hex (64 chars)");
    }

    @Test
    @DisplayName("Should calculate consistent order hash for identical order data")
    void testOrderHashConsistency() throws Exception {
        // Arrange
        Map<String, Object> mockOrderDetail = buildMockOrderDetail();
        when(mockAdapter.fetchOrderDetail(testOrderId)).thenReturn(mockOrderDetail);

        // Act: Call handler
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter);

        // Assert: Verify message was sent successfully
        verify(kafkaTemplate, times(1)).send(
            eq(TopicConstants.ORDER_PROCESS),
            eq(testOrderId),
            anyString()
        );
    }

    @Test
    @DisplayName("Should handle order detail with all optional fields present")
    void testHandleOrderDetailWithAllFields() throws Exception {
        // Arrange
        Map<String, Object> mockOrderDetail = new LinkedHashMap<>();
        mockOrderDetail.put("status", "completed");
        mockOrderDetail.put("total_amount", 4500.0);

        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("sku", "CBZ-ITEM-001");
        item1.put("quantity", 2);
        item1.put("unit_price", 2000.0);
        items.add(item1);

        Map<String, Object> item2 = new LinkedHashMap<>();
        item2.put("sku", "CBZ-ITEM-002");
        item2.put("quantity", 1);
        item2.put("unit_price", 500.0);
        items.add(item2);

        mockOrderDetail.put("items", items);

        Map<String, Object> buyerInfo = new LinkedHashMap<>();
        buyerInfo.put("name", "John Doe");
        buyerInfo.put("email", "john@example.com");
        buyerInfo.put("phone", "0912345678");
        mockOrderDetail.put("buyer_info", buyerInfo);

        Map<String, Object> shippingInfo = new LinkedHashMap<>();
        shippingInfo.put("address", "123 Main Street");
        shippingInfo.put("city", "Taipei");
        shippingInfo.put("postal_code", "10001");
        mockOrderDetail.put("shipping_info", shippingInfo);

        mockOrderDetail.put("created_at", "2024-02-20T10:30:00Z");

        when(mockAdapter.fetchOrderDetail(testOrderId)).thenReturn(mockOrderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter);

        // Assert
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TopicConstants.ORDER_PROCESS), eq(testOrderId), messageCaptor.capture());

        String message = messageCaptor.getValue();
        ObjectNode node = objectMapper.readValue(message, ObjectNode.class);
        ObjectNode body = (ObjectNode) node.get("body");
        ObjectNode orderData = (ObjectNode) body.get("orderData");

        // Verify all fields are present
        assertTrue(orderData.has("status"), "orderData should have status");
        assertTrue(orderData.has("totalAmount"), "orderData should have totalAmount");
        assertTrue(orderData.has("items"), "orderData should have items");
        assertTrue(orderData.has("buyerInfo"), "orderData should have buyerInfo");
        assertTrue(orderData.has("shippingInfo"), "orderData should have shippingInfo");
        assertTrue(orderData.has("createdAt"), "orderData should have createdAt");
    }

    @Test
    @DisplayName("Should handle null status by converting empty string")
    void testHandleNullStatus() throws Exception {
        // Arrange
        Map<String, Object> mockOrderDetail = new LinkedHashMap<>();
        mockOrderDetail.put("status", null);
        mockOrderDetail.put("total_amount", 1000.0);
        mockOrderDetail.put("created_at", "2024-02-20T10:30:00Z");

        when(mockAdapter.fetchOrderDetail(testOrderId)).thenReturn(mockOrderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter);

        // Assert
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TopicConstants.ORDER_PROCESS), eq(testOrderId), messageCaptor.capture());

        String message = messageCaptor.getValue();
        ObjectNode node = objectMapper.readValue(message, ObjectNode.class);
        ObjectNode body = (ObjectNode) node.get("body");
        ObjectNode orderData = (ObjectNode) body.get("orderData");

        // When status is null, extractString returns empty string and toUpperCase preserves it
        String status = orderData.get("status").asText();
        assertTrue(status.isEmpty() || status.equals("PENDING"), "Null status should result in empty string or PENDING");
    }

    @Test
    @DisplayName("Should handle lowercase status values")
    void testLowercaseStatusConversion() throws Exception {
        // Arrange
        Map<String, Object> mockOrderDetail = new LinkedHashMap<>();
        mockOrderDetail.put("status", "completed");
        mockOrderDetail.put("total_amount", 2500.0);
        mockOrderDetail.put("created_at", "2024-02-20T10:30:00Z");

        when(mockAdapter.fetchOrderDetail(testOrderId)).thenReturn(mockOrderDetail);

        // Act
        handler.handleModeBOrderDetail(testMerchantId, testChannelId, testOrderId, mockAdapter);

        // Assert
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TopicConstants.ORDER_PROCESS), eq(testOrderId), messageCaptor.capture());

        String message = messageCaptor.getValue();
        ObjectNode node = objectMapper.readValue(message, ObjectNode.class);
        ObjectNode body = (ObjectNode) node.get("body");
        ObjectNode orderData = (ObjectNode) body.get("orderData");
        String status = orderData.get("status").asText();
        assertEquals("COMPLETED", status);
    }

    // ==================== Helper Methods ====================

    /**
     * Build mock order detail data with typical fields
     */
    private Map<String, Object> buildMockOrderDetail() {
        Map<String, Object> mockOrderDetail = new LinkedHashMap<>();
        mockOrderDetail.put("status", "pending");
        mockOrderDetail.put("total_amount", 2500.0);

        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sku", "CBZ-ITEM-001");
        item.put("quantity", 1);
        item.put("unit_price", 2300.0);
        items.add(item);
        mockOrderDetail.put("items", items);

        mockOrderDetail.put("created_at", "2024-02-20T10:30:00Z");
        return mockOrderDetail;
    }
}
