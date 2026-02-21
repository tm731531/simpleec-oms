package com.simpleec.channel.adapter;

import com.simpleec.channel.api.CyberbizApiClient;
import com.simpleec.common.enums.ModeEnum;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class CyberbizAdapter implements ChannelAdapter {

    private final CyberbizApiClient cyberbizApiClient;

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
        // Call the API client (timestamps should be computed from timeRange in real implementation)
        long now = System.currentTimeMillis() / 1000;  // current time in seconds
        long oneHourAgo = now - 3600;
        return cyberbizApiClient.getOrdersCreatedInTimeRange(oneHourAgo, now);
    }

    /**
     * 輔助方法：查詢該時段內更新的訂單
     * 模擬 Cyberbiz API: GET /api/order/get_orders?update_time_from=<timestamp>&update_time_to=<timestamp>
     */
    private List<String> fetchOrdersUpdatedInTimeRange(String timeRange) {
        log.debug("Fetching orders updated in timeRange: {}", timeRange);
        // Call the API client (timestamps should be computed from timeRange in real implementation)
        long now = System.currentTimeMillis() / 1000;  // current time in seconds
        long oneHourAgo = now - 3600;
        return cyberbizApiClient.getOrdersUpdatedInTimeRange(oneHourAgo, now);
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

        // Call the API client to get order detail
        Map<String, Object> orderDetail = cyberbizApiClient.getOrderDetail(orderId);

        // Ensure required fields are present (with defaults if missing)
        if (!orderDetail.containsKey("items")) {
            List<Map<String, Object>> items = new ArrayList<>();
            Map<String, Object> item1 = new LinkedHashMap<>();
            item1.put("sku", "CBZ-ITEM-001");
            item1.put("product_id", "9876543210");
            item1.put("name", "Cyberbiz 商品");
            item1.put("quantity", 1);
            item1.put("unit_price", 2300.0);
            items.add(item1);
            orderDetail.put("items", items);
        }

        if (!orderDetail.containsKey("buyer_info")) {
            Map<String, Object> buyer = new LinkedHashMap<>();
            buyer.put("user_id", "CBZ_BUYER_123");
            buyer.put("username", "cyberbuyer");
            buyer.put("email", "buyer@cyberbiz.tw");
            buyer.put("phone", "0922334455");
            orderDetail.put("buyer_info", buyer);
        }

        if (!orderDetail.containsKey("shipping_info")) {
            Map<String, Object> shipping = new LinkedHashMap<>();
            shipping.put("name", "王小明");
            shipping.put("phone", "0922334455");
            shipping.put("address", "台北市信義區忠孝東路 100 號");
            shipping.put("city", "台北");
            shipping.put("postal_code", "11001");
            shipping.put("country", "TW");
            orderDetail.put("shipping_info", shipping);
        }

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

        // Call the API client (timestamps should be computed from timeRange in real implementation)
        long now = System.currentTimeMillis() / 1000;  // current time in seconds
        long oneHourAgo = now - 3600;
        return cyberbizApiClient.getOrdersWithRefund(oneHourAgo, now);
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
