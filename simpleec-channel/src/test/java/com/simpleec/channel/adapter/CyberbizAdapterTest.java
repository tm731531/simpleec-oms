package com.simpleec.channel.adapter;

import com.simpleec.channel.api.CyberbizApiClient;
import com.simpleec.common.enums.ModeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("CyberbizAdapter Tests")
class CyberbizAdapterTest {

    private CyberbizAdapter adapter;

    @Mock
    private CyberbizApiClient cyberbizApiClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        adapter = new CyberbizAdapter(cyberbizApiClient);
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
        assertEquals(ModeEnum.B, adapter.getMode());
    }

    @Test
    @DisplayName("should throw UnsupportedOperationException for Mode A fetchOrders")
    void testFetchOrdersThrowsException() {
        assertThrows(UnsupportedOperationException.class, () -> {
            adapter.fetchOrders("last_1_hour");
        });
    }

    @Test
    @DisplayName("should fetch order list from Cyberbiz (Mode B) - dedup orders")
    void testFetchOrderList() throws Exception {
        // Arrange: Mock the API client responses
        List<String> createdOrders = Arrays.asList("CBZ-CREATE-00001", "CBZ-CREATE-00002", "CBZ-CREATE-00003");
        List<String> updatedOrders = Arrays.asList("CBZ-UPDATE-00001", "CBZ-UPDATE-00002");

        when(cyberbizApiClient.getOrdersCreatedInTimeRange(anyLong(), anyLong()))
            .thenReturn(createdOrders);
        when(cyberbizApiClient.getOrdersUpdatedInTimeRange(anyLong(), anyLong()))
            .thenReturn(updatedOrders);

        // Act
        List<String> orderIds = adapter.fetchOrderList("last_1_hour");

        // Assert
        assertNotNull(orderIds);
        assertFalse(orderIds.isEmpty());
        // Should have unique order IDs from both created and updated queries
        assertTrue(orderIds.size() >= 5, "Should have at least 5 unique orders");
        assertTrue(orderIds.contains("CBZ-CREATE-00001"));
        assertTrue(orderIds.contains("CBZ-UPDATE-00001"));

        // Verify API calls were made
        verify(cyberbizApiClient, times(1)).getOrdersCreatedInTimeRange(anyLong(), anyLong());
        verify(cyberbizApiClient, times(1)).getOrdersUpdatedInTimeRange(anyLong(), anyLong());
    }

    @Test
    @DisplayName("should fetch order detail for a specific order")
    void testFetchOrderDetail() throws Exception {
        // Arrange - use LinkedHashMap for mutable response
        Map<String, Object> mockDetail = new java.util.LinkedHashMap<>();
        mockDetail.put("order_id", "CBZ-001");
        mockDetail.put("status", "pending");
        mockDetail.put("created_at", "2024-02-20T10:30:00Z");
        mockDetail.put("updated_at", "2024-02-20T10:35:00Z");

        when(cyberbizApiClient.getOrderDetail("CBZ-001"))
            .thenReturn(mockDetail);

        // Act
        Map<String, Object> detail = adapter.fetchOrderDetail("CBZ-001");

        // Assert
        assertNotNull(detail);
        assertTrue(detail.containsKey("order_id"));
        assertEquals("CBZ-001", detail.get("order_id"));
        assertTrue(detail.containsKey("status"));
        assertTrue(detail.containsKey("items"));
        assertTrue(detail.containsKey("buyer_info"));
        assertTrue(detail.containsKey("shipping_info"));

        // Verify API was called
        verify(cyberbizApiClient, times(1)).getOrderDetail("CBZ-001");
    }

    @Test
    @DisplayName("should handle empty order list gracefully")
    void testFetchOrderListEmpty() throws Exception {
        // Arrange: Mock empty responses
        when(cyberbizApiClient.getOrdersCreatedInTimeRange(anyLong(), anyLong()))
            .thenReturn(List.of());
        when(cyberbizApiClient.getOrdersUpdatedInTimeRange(anyLong(), anyLong()))
            .thenReturn(List.of());

        // Act
        List<String> orderIds = adapter.fetchOrderList("last_1_hour");

        // Assert
        assertNotNull(orderIds);
        assertTrue(orderIds.isEmpty());
    }

    @Test
    @DisplayName("should test connection successfully")
    void testConnectionSuccess() throws Exception {
        boolean result = adapter.testConnection();
        assertTrue(result);
    }

    @Test
    @DisplayName("should fetch returns from Cyberbiz")
    void testFetchReturns() throws Exception {
        // Arrange
        List<Map<String, Object>> mockReturns = List.of(
            Map.of("order_id", "CBZ-RET-001", "refund_status", "processing")
        );

        when(cyberbizApiClient.getOrdersWithRefund(anyLong(), anyLong()))
            .thenReturn(mockReturns);

        // Act
        List<Map<String, Object>> returns = adapter.fetchReturns("last_1_hour");

        // Assert
        assertNotNull(returns);

        // Verify API was called
        verify(cyberbizApiClient, times(1)).getOrdersWithRefund(anyLong(), anyLong());
    }

    @Test
    @DisplayName("should populate default item info when missing from API response")
    void testFetchOrderDetailWithDefaultItems() throws Exception {
        // Arrange: Mock response without items (simulate API returning partial data)
        Map<String, Object> mockDetail = new java.util.LinkedHashMap<>();
        mockDetail.put("order_id", "CBZ-002");
        mockDetail.put("status", "pending");

        when(cyberbizApiClient.getOrderDetail("CBZ-002"))
            .thenReturn(mockDetail);

        // Act
        Map<String, Object> detail = adapter.fetchOrderDetail("CBZ-002");

        // Assert
        assertNotNull(detail);
        assertTrue(detail.containsKey("items"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertFalse(items.isEmpty());
        assertTrue(items.get(0).containsKey("sku"));
        assertTrue(items.get(0).containsKey("name"));
        assertTrue(items.get(0).containsKey("quantity"));
        assertTrue(items.get(0).containsKey("unit_price"));
    }

    @Test
    @DisplayName("should populate default buyer info when missing from API response")
    void testFetchOrderDetailWithDefaultBuyerInfo() throws Exception {
        // Arrange
        Map<String, Object> mockDetail = new java.util.LinkedHashMap<>();
        mockDetail.put("order_id", "CBZ-003");
        mockDetail.put("status", "pending");

        when(cyberbizApiClient.getOrderDetail("CBZ-003"))
            .thenReturn(mockDetail);

        // Act
        Map<String, Object> detail = adapter.fetchOrderDetail("CBZ-003");

        // Assert
        assertNotNull(detail);
        assertTrue(detail.containsKey("buyer_info"));
        Map<String, Object> buyerInfo = (Map<String, Object>) detail.get("buyer_info");
        assertTrue(buyerInfo.containsKey("user_id"));
        assertTrue(buyerInfo.containsKey("username"));
        assertTrue(buyerInfo.containsKey("email"));
        assertTrue(buyerInfo.containsKey("phone"));
    }

    @Test
    @DisplayName("should populate default shipping info when missing from API response")
    void testFetchOrderDetailWithDefaultShippingInfo() throws Exception {
        // Arrange
        Map<String, Object> mockDetail = new java.util.LinkedHashMap<>();
        mockDetail.put("order_id", "CBZ-004");
        mockDetail.put("status", "pending");

        when(cyberbizApiClient.getOrderDetail("CBZ-004"))
            .thenReturn(mockDetail);

        // Act
        Map<String, Object> detail = adapter.fetchOrderDetail("CBZ-004");

        // Assert
        assertNotNull(detail);
        assertTrue(detail.containsKey("shipping_info"));
        Map<String, Object> shippingInfo = (Map<String, Object>) detail.get("shipping_info");
        assertTrue(shippingInfo.containsKey("name"));
        assertTrue(shippingInfo.containsKey("phone"));
        assertTrue(shippingInfo.containsKey("address"));
        assertTrue(shippingInfo.containsKey("city"));
        assertTrue(shippingInfo.containsKey("postal_code"));
        assertTrue(shippingInfo.containsKey("country"));
    }
}
