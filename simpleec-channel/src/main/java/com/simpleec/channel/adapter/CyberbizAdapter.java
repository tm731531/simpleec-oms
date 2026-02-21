package com.simpleec.channel.adapter;

import com.simpleec.common.enums.ModeEnum;
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
public class CyberbizAdapter implements ChannelAdapter {

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
        // TODO: 實現實際 Cyberbiz API 呼叫
        // 模擬返回訂單 ID 列表
        return Arrays.asList(
            "CBZ-CREATE-00001",
            "CBZ-CREATE-00002",
            "CBZ-CREATE-00003"
        );
    }

    /**
     * 輔助方法：查詢該時段內更新的訂單
     * 模擬 Cyberbiz API: GET /api/order/get_orders?update_time_from=<timestamp>&update_time_to=<timestamp>
     */
    private List<String> fetchOrdersUpdatedInTimeRange(String timeRange) {
        log.debug("Fetching orders updated in timeRange: {}", timeRange);
        // TODO: 實現實際 Cyberbiz API 呼叫
        // 模擬返回訂單 ID 列表
        return Arrays.asList(
            "CBZ-UPDATE-00001",
            "CBZ-UPDATE-00002"
        );
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

        // TODO: 實現實際 Cyberbiz API 呼叫以取得訂單詳情
        // 目前回傳模擬數據
        Map<String, Object> orderDetail = new LinkedHashMap<>();
        orderDetail.put("order_id", orderId);
        orderDetail.put("order_sn", "CBZ" + System.currentTimeMillis());
        orderDetail.put("status", "pending");
        orderDetail.put("created_at", "2024-02-20T10:30:00Z");
        orderDetail.put("updated_at", "2024-02-20T10:35:00Z");

        // 金額資訊
        Map<String, Object> amountInfo = new LinkedHashMap<>();
        amountInfo.put("total", 2500.0);
        amountInfo.put("subtotal", 2300.0);
        amountInfo.put("shipping_fee", 200.0);
        amountInfo.put("discount", 0.0);
        orderDetail.put("amount_info", amountInfo);

        // 商品列表
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("sku", "CBZ-ITEM-001");
        item1.put("product_id", "9876543210");
        item1.put("name", "Cyberbiz 商品");
        item1.put("quantity", 1);
        item1.put("unit_price", 2300.0);
        items.add(item1);
        orderDetail.put("items", items);

        // 買家資訊
        Map<String, Object> buyer = new LinkedHashMap<>();
        buyer.put("user_id", "CBZ_BUYER_123");
        buyer.put("username", "cyberbuyer");
        buyer.put("email", "buyer@cyberbiz.tw");
        buyer.put("phone", "0922334455");
        orderDetail.put("buyer_info", buyer);

        // 配送地址
        Map<String, Object> shipping = new LinkedHashMap<>();
        shipping.put("name", "王小明");
        shipping.put("phone", "0922334455");
        shipping.put("address", "台北市信義區忠孝東路 100 號");
        shipping.put("city", "台北");
        shipping.put("postal_code", "11001");
        shipping.put("country", "TW");
        orderDetail.put("shipping_info", shipping);

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

        // TODO: 實現實際 Cyberbiz 退貨查詢邏輯
        // 使用 refund_time_from/refund_time_to 參數查詢該時段內的退貨訂單
        return new ArrayList<>();
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
