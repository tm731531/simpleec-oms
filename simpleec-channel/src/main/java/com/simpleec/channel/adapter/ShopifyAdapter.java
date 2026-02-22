package com.simpleec.channel.adapter;

import com.simpleec.common.enums.ModeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shopify 適配器（Mode A）
 *
 * 列表 API 已包含完整資訊：items, shipping_address, customer
 */
@Slf4j
@RequiredArgsConstructor
public class ShopifyAdapter implements ChannelAdapter {

    @Override
    public String getPlatformCode() {
        return "SHOPIFY";
    }

    @Override
    public ModeEnum getMode() {
        return ModeEnum.A;
    }

    /**
     * Mode A: 直接拉取完整訂單
     */
    @Override
    public List<Map<String, Object>> fetchOrders(String timeRange) throws Exception {
        log.info("Fetching Shopify orders for time range: {}", timeRange);

        // TODO: 實際調用 Shopify API
        // return shopifyApiClient.getOrders(timeRange);

        // 模擬數據（用於測試）
        List<Map<String, Object>> orders = new ArrayList<>();

        Map<String, Object> order = new HashMap<>();
        order.put("order_id", "4388901046456");
        order.put("order_number", 1001);
        order.put("status", "paid");
        order.put("financial_status", "paid");
        order.put("fulfillment_status", "unfulilled");
        order.put("total_price", "99.99");
        order.put("currency", "USD");

        // items 完整
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("id", "1234567890");
        item.put("sku", "PROD_001");
        item.put("title", "Test Product");
        item.put("quantity", 1);
        item.put("price", "99.99");
        items.add(item);
        order.put("line_items", items);

        // shipping_address 完整
        Map<String, Object> shippingAddress = new HashMap<>();
        shippingAddress.put("first_name", "John");
        shippingAddress.put("last_name", "Doe");
        shippingAddress.put("phone", "1234567890");
        shippingAddress.put("address1", "123 Main St");
        shippingAddress.put("city", "New York");
        shippingAddress.put("zip", "10001");
        order.put("shipping_address", shippingAddress);

        // customer 完整
        Map<String, Object> customer = new HashMap<>();
        customer.put("id", "123456");
        customer.put("first_name", "John");
        customer.put("last_name", "Doe");
        customer.put("email", "john@example.com");
        customer.put("phone", "1234567890");
        order.put("customer", customer);

        order.put("created_at", "2024-02-20T10:00:00Z");
        order.put("updated_at", "2024-02-20T10:00:00Z");

        orders.add(order);
        return orders;
    }

    @Override
    public List<String> fetchOrderList(String timeRange) throws Exception {
        // Mode A 不需要此方法
        throw new UnsupportedOperationException("Shopify is Mode A, no need for fetchOrderList");
    }

    @Override
    public Map<String, Object> fetchOrderDetail(String orderId) throws Exception {
        // Mode A 不需要此方法
        throw new UnsupportedOperationException("Shopify is Mode A, no need for fetchOrderDetail");
    }

    @Override
    public List<Map<String, Object>> fetchReturns(String timeRange) throws Exception {
        log.info("Fetching Shopify returns for time range: {}", timeRange);
        // TODO: 實際調用 Shopify API
        return new ArrayList<>();
    }

    @Override
    public void shipOrder(String orderId, Map<String, Object> shippingInfo) throws Exception {
        log.info("Shipping Shopify order: {} with info: {}", orderId, shippingInfo);
        // TODO: 實際調用 Shopify API
    }

    @Override
    public void updateInventory(String productId, int quantity) throws Exception {
        log.info("Updating Shopify inventory for product: {} quantity: {}", productId, quantity);
        // TODO: 實際調用 Shopify API
    }

    @Override
    public boolean testConnection() throws Exception {
        log.info("Testing Shopify connection...");
        // TODO: 實際測試連接
        return true;
    }
}
