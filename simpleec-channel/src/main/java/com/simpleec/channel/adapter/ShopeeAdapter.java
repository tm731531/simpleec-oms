package com.simpleec.channel.adapter;

import com.simpleec.common.enums.ModeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Shopee 平台適配器 — Mode B (分離的訂單列表 + 詳情)
 *
 * Shopee API 特性：
 * - 訂單列表只包含訂單 ID 和基本狀態（不含詳情）
 * - 必須單獨呼叫訂單詳情 API 獲取完整資訊
 * - 支援時間範圍和狀態過濾
 * - Rate limit: ~100 requests/min
 */
@Slf4j
public class ShopeeAdapter implements ChannelAdapter {

    @Override
    public String getPlatformCode() {
        return "shopee";
    }

    @Override
    public ModeEnum getMode() {
        return ModeEnum.B;
    }

    /**
     * Mode A 不支援（Shopee 使用 Mode B）
     */
    @Override
    public List<Map<String, Object>> fetchOrders(String channelId, String timeRange) throws Exception {
        throw new UnsupportedOperationException("Shopee 使用 Mode B，需分兩步：先 fetchOrderList，再 fetchOrderDetail");
    }

    /**
     * Mode B: 拉取訂單列表（僅 ID 和基本狀態）
     *
     * 模擬 Shopee API: GET /api/v2/orders?status=all&create_time_from=<timestamp>
     * 回傳：訂單 ID 列表（無詳情）
     */
    @Override
    public List<String> fetchOrderList(String channelId, String timeRange) throws Exception {
        log.info("Fetching Shopee order list for channel {} with timeRange: {}", channelId, timeRange);

        // 模擬 Shopee API 回傳的訂單 ID 列表
        // 實際 Shopee API 只返回 order_id 和 status，詳情需分開查詢
        List<String> orderIds = Arrays.asList(
            "SHP-202402-00001",
            "SHP-202402-00002",
            "SHP-202402-00003"
        );

        log.info("Fetched {} order IDs from Shopee", orderIds.size());
        return orderIds;
    }

    /**
     * Mode B: 拉取單筆訂單詳情
     *
     * 模擬 Shopee API: GET /api/v2/orders/{order_id}/details
     * 回傳：完整訂單詳情（商品、收貨人、運費等）
     */
    @Override
    public Map<String, Object> fetchOrderDetail(String channelId, String orderId) throws Exception {
        log.info("Fetching order detail from Shopee for channel {} with orderId: {}", channelId, orderId);

        Map<String, Object> orderDetail = new LinkedHashMap<>();
        orderDetail.put("order_id", orderId);
        orderDetail.put("order_sn", "SHP" + System.currentTimeMillis());
        orderDetail.put("status", "READY_TO_SHIP");
        orderDetail.put("created_at", "2024-02-20T10:30:00Z");
        orderDetail.put("updated_at", "2024-02-20T10:35:00Z");

        // 金額資訊
        Map<String, Object> amountInfo = new LinkedHashMap<>();
        amountInfo.put("total", 3200.0);
        amountInfo.put("subtotal", 3000.0);
        amountInfo.put("shipping_fee", 200.0);
        amountInfo.put("discount", 0.0);
        orderDetail.put("amount_info", amountInfo);

        // 商品列表
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("sku", "SHP-ITEM-001");
        item1.put("product_id", "1234567890");
        item1.put("name", "Shopee 運動套裝");
        item1.put("quantity", 1);
        item1.put("unit_price", 3000.0);
        item1.put("image_url", "https://cf.shopee.tw/file/product.jpg");
        items.add(item1);
        orderDetail.put("items", items);

        // 買家資訊
        Map<String, Object> buyer = new LinkedHashMap<>();
        buyer.put("user_id", "SHOP_BUYER_123");
        buyer.put("username", "coolbuyer");
        buyer.put("email", "buyer@shopee.com");
        buyer.put("phone", "0911223344");
        orderDetail.put("buyer_info", buyer);

        // 配送地址
        Map<String, Object> shipping = new LinkedHashMap<>();
        shipping.put("name", "陳俊傑");
        shipping.put("phone", "0911223344");
        shipping.put("address", "台中市西屯區大雅路 99 號");
        shipping.put("city", "台中");
        shipping.put("postal_code", "40701");
        shipping.put("country", "TW");
        orderDetail.put("shipping_info", shipping);

        // Shopee 特有欄位
        orderDetail.put("payment_method", "CREDIT_CARD");
        orderDetail.put("logistics_status", "NOT_YET_SHIP");
        orderDetail.put("tracking_number", null);

        log.info("Fetched order detail from Shopee: {}", orderId);
        return orderDetail;
    }

    @Override
    public List<Map<String, Object>> fetchReturns(String channelId, String timeRange) throws Exception {
        log.info("Fetching returns from Shopee for channel {} with timeRange: {}", channelId, timeRange);
        // TODO: 實現 Shopee 退貨列表邏輯
        return new ArrayList<>();
    }

    @Override
    public void shipOrder(String orderId, Map<String, Object> shippingInfo) throws Exception {
        log.info("Shipping order {} on Shopee with info: {}", orderId, shippingInfo);
        // TODO: 實現 Shopee 出貨確認邏輯
    }

    @Override
    public void updateInventory(String productId, int quantity) throws Exception {
        log.info("Updating inventory for product {} to quantity {} on Shopee", productId, quantity);
        // TODO: 實現 Shopee 庫存更新邏輯
    }

    @Override
    public boolean testConnection() throws Exception {
        log.info("Testing connection to Shopee API");
        // 模擬連接測試
        return true;
    }
}
