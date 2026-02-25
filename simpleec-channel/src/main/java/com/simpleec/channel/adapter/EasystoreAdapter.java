package com.simpleec.channel.adapter;

import com.simpleec.common.enums.ModeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Easystore 平台適配器 — Mode A (完整訂單列表)
 *
 * Easystore API 特性：
 * - 拉取訂單列表時包含完整訂單詳情（無需第二次 API 呼叫）
 * - 訂單物件包含：orderNumber, status, items, shippingInfo, customerInfo
 * - 時間範圍過濾支援 created_at/updated_at 比對
 */
@Slf4j
public class EasystoreAdapter implements ChannelAdapter {

    @Override
    public String getPlatformCode() {
        return "easystore";
    }

    @Override
    public ModeEnum getMode() {
        return ModeEnum.A;
    }

    /**
     * Mode A: 拉取完整訂單列表（包含詳情）
     *
     * 模擬 Easystore API: GET /api/orders?status=all&since=<timestamp>
     */
    @Override
    public List<Map<String, Object>> fetchOrders(String channelId, String timeRange) throws Exception {
        log.info("Fetching orders from Easystore for channel {} with timeRange: {}", channelId, timeRange);

        // 模擬 API 回傳的訂單列表（包含完整詳情）
        List<Map<String, Object>> orders = new ArrayList<>();

        // 訂單 1: 待出貨
        Map<String, Object> order1 = new LinkedHashMap<>();
        order1.put("order_id", "EST-202402-00001");
        order1.put("order_number", "202402-001");
        order1.put("status", "pending");  // Easystore 狀態
        order1.put("created_at", "2024-02-20T08:00:00Z");
        order1.put("updated_at", "2024-02-20T08:05:00Z");
        order1.put("total_amount", 1500.0);
        order1.put("currency", "TWD");

        // 商品清單
        List<Map<String, Object>> items1 = new ArrayList<>();
        Map<String, Object> item1_1 = new LinkedHashMap<>();
        item1_1.put("sku", "PROD-001");
        item1_1.put("title", "藍色 T 恤");
        item1_1.put("quantity", 2);
        item1_1.put("unit_price", 600.0);
        item1_1.put("image_url", "https://easystore.example.com/products/item1.jpg");
        items1.add(item1_1);
        order1.put("items", items1);

        // 買家資訊
        Map<String, Object> buyerInfo1 = new LinkedHashMap<>();
        buyerInfo1.put("name", "王小明");
        buyerInfo1.put("email", "wang@example.com");
        buyerInfo1.put("phone", "0912345678");
        order1.put("buyer_info", buyerInfo1);

        // 配送資訊
        Map<String, Object> shippingInfo1 = new LinkedHashMap<>();
        shippingInfo1.put("recipient_name", "王小明");
        shippingInfo1.put("phone", "0912345678");
        shippingInfo1.put("address", "台北市信義區忠孝東路 5 號");
        shippingInfo1.put("city", "台北");
        shippingInfo1.put("postal_code", "11001");
        order1.put("shipping_info", shippingInfo1);

        orders.add(order1);

        // 訂單 2: 已出貨
        Map<String, Object> order2 = new LinkedHashMap<>();
        order2.put("order_id", "EST-202402-00002");
        order2.put("order_number", "202402-002");
        order2.put("status", "shipped");
        order2.put("created_at", "2024-02-19T10:00:00Z");
        order2.put("updated_at", "2024-02-20T06:00:00Z");
        order2.put("total_amount", 2500.0);
        order2.put("currency", "TWD");

        List<Map<String, Object>> items2 = new ArrayList<>();
        Map<String, Object> item2_1 = new LinkedHashMap<>();
        item2_1.put("sku", "PROD-002");
        item2_1.put("title", "黑色牛仔褲");
        item2_1.put("quantity", 1);
        item2_1.put("unit_price", 2500.0);
        items2.add(item2_1);
        order2.put("items", items2);

        Map<String, Object> buyerInfo2 = new LinkedHashMap<>();
        buyerInfo2.put("name", "李小花");
        buyerInfo2.put("email", "li@example.com");
        buyerInfo2.put("phone", "0987654321");
        order2.put("buyer_info", buyerInfo2);

        Map<String, Object> shippingInfo2 = new LinkedHashMap<>();
        shippingInfo2.put("recipient_name", "李小花");
        shippingInfo2.put("phone", "0987654321");
        shippingInfo2.put("address", "高雄市前鎮區中山二路 100 號");
        shippingInfo2.put("city", "高雄");
        shippingInfo2.put("postal_code", "80001");
        order2.put("shipping_info", shippingInfo2);

        orders.add(order2);

        log.info("Fetched {} orders from Easystore", orders.size());
        return orders;
    }

    /**
     * Mode A 不支援單獨的訂單列表（無詳情）
     */
    @Override
    public List<String> fetchOrderList(String channelId, String timeRange) throws Exception {
        throw new UnsupportedOperationException("Easystore 使用 Mode A，不支援分離的訂單列表");
    }

    /**
     * Mode A 不支援單獨的訂單列表（無詳情）
     */
    @Override
    public List<String> fetchOrderListByTimestamp(String channelId, long baseTimestamp) throws Exception {
        throw new UnsupportedOperationException("Easystore 使用 Mode A，不支援分離的訂單列表");
    }

    /**
     * Mode A 不支援單獨的訂單詳情
     */
    @Override
    public Map<String, Object> fetchOrderDetail(String channelId, String orderId) throws Exception {
        throw new UnsupportedOperationException("Easystore 使用 Mode A，不支援分離的訂單詳情查詢");
    }

    @Override
    public List<Map<String, Object>> fetchReturns(String channelId, String timeRange) throws Exception {
        log.info("Fetching returns from Easystore for channel {} with timeRange: {}", channelId, timeRange);
        // TODO: 實現 Easystore 退貨列表邏輯
        return new ArrayList<>();
    }

    @Override
    public void shipOrder(String orderId, Map<String, Object> shippingInfo) throws Exception {
        log.info("Shipping order {} on Easystore with info: {}", orderId, shippingInfo);
        // TODO: 實現 Easystore 出貨確認邏輯
    }

    @Override
    public void updateInventory(String productId, int quantity) throws Exception {
        log.info("Updating inventory for product {} to quantity {} on Easystore", productId, quantity);
        // TODO: 實現 Easystore 庫存更新邏輯
    }

    @Override
    public List<Map<String, Object>> fetchOrdersByTimestamp(String channelId, long baseTimestamp) throws Exception {
        log.info("Fetching Easystore complete orders for channel {} using baseTimestamp", channelId);
        // TODO: 實現 Easystore Mode A 時間戳查詢邏輯
        return new ArrayList<>();
    }

    @Override
    public boolean testConnection() throws Exception {
        log.info("Testing connection to Easystore API");
        // 模擬連接測試
        return true;
    }
}
