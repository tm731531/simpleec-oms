package com.simpleec.channel.adapter;

import com.simpleec.channel.api.CyberbizApiClient;
import com.simpleec.common.enums.ModeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
@RequiredArgsConstructor
public class CyberbizAdapter implements ChannelAdapter {

    private final CyberbizApiClient cyberbizApiClient;
    private String token;      // 由 handler 設置（作為 username）
    private String secret;     // 由 handler 設置（Channel.token2）

    /**
     * 設置 Cyberbiz API credentials（由 handler 調用）
     * @param token  Channel.token（作為 username）
     * @param secret Channel.token2（作為 secret key）
     */
    public void setCredentials(String token, String secret) {
        this.token = token;
        this.secret = secret;
    }

    @Override
    public String getPlatformCode() {
        return "cyberbiz";
    }

    @Override
    public ModeEnum getMode() {
        return ModeEnum.B;
    }

    /**
     * Legacy fetchOrders (kept to satisfy ChannelAdapter interface).
     * Cyberbiz primary flow is Mode B: fetchOrderListByTimestamp() + fetchOrderDetail().
     * This method is retained for backward compatibility only.
     *
     * Time window: orders updated within the past 1 day (based on updated_at)
     *
     * @param channelId 通路 ID
     * @param timeRange 時間範圍（暫未使用，預留未來擴充）
     * @return 完整訂單列表
     */
    @Override
    public List<Map<String, Object>> fetchOrders(String channelId, String timeRange) throws Exception {
        log.info("Fetching Cyberbiz complete orders for channel {} with timeRange: {}", channelId, timeRange);

        if (token == null || token.isEmpty() || secret == null || secret.isEmpty()) {
            log.error("Credentials not set for channel {}", channelId);
            throw new IllegalArgumentException("Credentials not set - call setCredentials() first");
        }

        // Mode A: 直接使用 1 天時間窗口（Cyberbiz API 特性）
        long now = Instant.now().getEpochSecond();
        long oneDayAgo = now - 86400;  // 1 day window for complete orders

        try {
            List<Map<String, Object>> orders = cyberbizApiClient.getCompleteOrdersUpdatedInTimeRange(
                token, secret, oneDayAgo, now
            );
            log.info("Fetched {} complete orders from Cyberbiz API", orders.size());
            return orders;
        } catch (Exception e) {
            log.error("Error fetching orders from Cyberbiz API", e);
            throw e;
        }
    }

    /**
     * Mode B: 拉取訂單 ID 列表（通過兩次 API 呼叫）
     *
     * Cyberbiz API 特性：
     * - 第一次呼叫：get_orders 搭配 create_time_from/create_time_to 參數，獲取該時段內建立的訂單
     * - 第二次呼叫：get_orders 搭配 update_time_from/update_time_to 參數，獲取該時段內更新的訂單
     * - 合併兩次結果並去重（同一訂單可能在兩次查詢中都出現）
     *
     * @param channelId 通路 ID（用於獲取 channel.token）
     * @param timeRange 時間範圍 (e.g., "last_1_hour")
     * @return 訂單 ID 列表（去重後）
     */
    @Override
    public List<String> fetchOrderList(String channelId, String timeRange) throws Exception {
        log.info("Fetching Cyberbiz order list for channel {} with timeRange: {}", channelId, timeRange);

        if (token == null || token.isEmpty() || secret == null || secret.isEmpty()) {
            log.error("Credentials not set for channel {}", channelId);
            throw new IllegalArgumentException("Credentials not set - call setCredentials() first");
        }

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
     * Mode B: 拉取訂單 ID 列表（使用 baseTimestamp 作為時間窗口基準）
     *
     * 根據心跳時間戳（heartbeat timestamp）計算時間窗口：
     * - 建立訂單：baseTimestamp - 7 天 ～ baseTimestamp
     * - 更新訂單：baseTimestamp - 1 天 ～ baseTimestamp
     *
     * @param channelId     通路 ID
     * @param baseTimestamp 心跳時間戳（秒），來自 Kafka 消息 header.timestamp
     * @return 訂單 ID 列表（去重後）
     */
    @Override
    public List<String> fetchOrderListByTimestamp(String channelId, long baseTimestamp) throws Exception {
        log.info("Fetching Cyberbiz order list for channel {} using baseTimestamp: {}", channelId, baseTimestamp);

        if (token == null || token.isEmpty() || secret == null || secret.isEmpty()) {
            log.error("Credentials not set for channel {}", channelId);
            throw new IllegalArgumentException("Credentials not set - call setCredentials() first");
        }

        Set<String> orderIds = new LinkedHashSet<>();

        // 第一次呼叫：查詢該時段內建立的訂單（7 天窗口）
        try {
            List<String> createdOrders = fetchOrdersCreatedInTimeRangeByTimestamp(baseTimestamp);
            orderIds.addAll(createdOrders);
            log.debug("Fetched {} orders created in timeRange from Cyberbiz", createdOrders.size());
        } catch (Exception e) {
            log.error("Error fetching created orders from Cyberbiz", e);
            throw e;
        }

        // 第二次呼叫：查詢該時段內更新的訂單（1 天窗口）
        try {
            List<String> updatedOrders = fetchOrdersUpdatedInTimeRangeByTimestamp(baseTimestamp);
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
     * 輔助方法：查詢該時段內建立的訂單（使用 baseTimestamp）
     *
     * 時間窗口：baseTimestamp - 7 天 ～ baseTimestamp
     */
    private List<String> fetchOrdersCreatedInTimeRangeByTimestamp(long baseTimestamp) {
        log.debug("Fetching orders created relative to baseTimestamp: {}", baseTimestamp);
        // Cyberbiz start_time 需要 7 天的時間窗口才能找到訂單
        long sevenDaysAgo = baseTimestamp - (7 * 86400);  // 7 days before baseTimestamp
        return cyberbizApiClient.getOrdersCreatedInTimeRange(token, secret, sevenDaysAgo, baseTimestamp);
    }

    /**
     * 輔助方法：查詢該時段內更新的訂單（使用 baseTimestamp）
     *
     * 時間窗口：baseTimestamp - 1 天 ～ baseTimestamp
     */
    private List<String> fetchOrdersUpdatedInTimeRangeByTimestamp(long baseTimestamp) {
        log.debug("Fetching orders updated relative to baseTimestamp: {}", baseTimestamp);
        long oneDayAgo = baseTimestamp - 86400;  // 1 day before baseTimestamp
        return cyberbizApiClient.getOrdersUpdatedInTimeRange(token, secret, oneDayAgo, baseTimestamp);
    }

    /**
     * 輔助方法：查詢該時段內建立的訂單（已過時，保留為向後相容）
     * 模擬 Cyberbiz API: GET /api/order/get_orders?create_time_from=<timestamp>&create_time_to=<timestamp>
     *
     * 預設時間窗口：過去 7 天內建立的訂單（測試環境用於拉取歷史數據）
     * timeRange 參數目前先記錄但不解析，預留未來擴充
     *
     * @deprecated 使用 {@link #fetchOrderListByTimestamp(String, long)} 代替
     */
    @Deprecated(since = "1.0", forRemoval = false)
    private List<String> fetchOrdersCreatedInTimeRange(String timeRange) {
        log.debug("Fetching orders created in timeRange: {} (using current time as base)", timeRange);
        long now = Instant.now().getEpochSecond();
        long sevenDaysAgo = now - (7 * 86400);  // 7 days window for testing
        return cyberbizApiClient.getOrdersCreatedInTimeRange(token, secret, sevenDaysAgo, now);
    }

    /**
     * 輔助方法：查詢該時段內更新的訂單（已過時，保留為向後相容）
     * 模擬 Cyberbiz API: GET /api/order/get_orders?update_time_from=<timestamp>&update_time_to=<timestamp>
     *
     * 預設時間窗口：過去 24 小時內更新的訂單（確保不漏掉已出貨、已完成等狀態更新）
     * timeRange 參數目前先記錄但不解析，預留未來擴充
     *
     * @deprecated 使用 {@link #fetchOrderListByTimestamp(String, long)} 代替
     */
    @Deprecated(since = "1.0", forRemoval = false)
    private List<String> fetchOrdersUpdatedInTimeRange(String timeRange) {
        log.debug("Fetching orders updated in timeRange: {} (using current time as base)", timeRange);
        long now = Instant.now().getEpochSecond();
        long oneDayAgo = now - 86400;  // 24 hour window for updated orders
        return cyberbizApiClient.getOrdersUpdatedInTimeRange(token, secret, oneDayAgo, now);
    }

    /**
     * Mode B: 拉取單筆訂單詳情
     *
     * Cyberbiz API: GET /api/order/get_order?order_id=<order_id>
     * 回傳：完整訂單詳情（商品、收貨人、金額等）
     *
     * @param channelId 通路 ID（用於獲取 channel.token）
     * @param orderId   Cyberbiz 訂單 ID
     */
    @Override
    public Map<String, Object> fetchOrderDetail(String channelId, String orderId) throws Exception {
        log.info("Fetching order detail from Cyberbiz for channelId: {}, orderId: {}", channelId, orderId);

        if (token == null || token.isEmpty() || secret == null || secret.isEmpty()) {
            log.error("Credentials not set for channel {}", channelId);
            throw new IllegalArgumentException("Credentials not set - call setCredentials() first");
        }

        // Call the API client to get order detail
        Map<String, Object> orderDetail = cyberbizApiClient.getOrderDetail(token, secret, orderId);

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
     * Cyberbiz API: GET /api/order/get_orders?refund_time_from=<timestamp>&refund_time_to=<timestamp>
     * 回傳：該時段內有退貨的訂單列表
     *
     * 預設時間窗口：過去 1 小時內發生退貨的訂單
     *
     * @param channelId 通路 ID（用於獲取 channel.token）
     * @param timeRange 時間範圍 (e.g., "last_1_hour")
     */
    @Override
    public List<Map<String, Object>> fetchReturns(String channelId, String timeRange) throws Exception {
        log.info("Fetching returns from Cyberbiz for channel {} with timeRange: {}", channelId, timeRange);

        if (token == null || token.isEmpty() || secret == null || secret.isEmpty()) {
            log.error("Credentials not set for channel {}", channelId);
            throw new IllegalArgumentException("Credentials not set - call setCredentials() first");
        }

        long now = Instant.now().getEpochSecond();
        long oneHourAgo = now - 3600;  // 1 hour window for recent refunds
        return cyberbizApiClient.getOrdersWithRefund(token, secret, oneHourAgo, now);
    }

    /**
     * 拉取所有商品（含 product_variants），自動分頁直到無更多資料。
     *
     * 每筆商品包含完整的 product_variants 陣列，供 SYNC_PACK 使用。
     * 最大每頁 50 筆，若某頁返回空則停止分頁。
     *
     * @param channelId 通路 ID（credentials 必須已透過 setCredentials() 設置）
     * @return 所有商品列表
     */
    public List<Map<String, Object>> fetchProducts(String channelId) throws Exception {
        if (token == null || token.isEmpty() || secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Credentials not set for channel " + channelId);
        }

        List<Map<String, Object>> allProducts = new ArrayList<>();
        int page = 1;
        final int perPage = 50;

        while (true) {
            List<Map<String, Object>> page_result = cyberbizApiClient.getProducts(token, secret, page, perPage);
            if (page_result.isEmpty()) {
                break;
            }
            allProducts.addAll(page_result);
            log.debug("Fetched products page={}, count={}, total so far={}", page, page_result.size(), allProducts.size());
            if (page_result.size() < perPage) {
                break; // last page
            }
            page++;
        }

        log.info("fetchProducts complete for channel={}: {} products total", channelId, allProducts.size());
        return allProducts;
    }

    /**
     * 確認訂單出貨（自定義物流）— 實作 ChannelAdapter 介面。
     *
     * 從 shippingInfo 提取 lineItemIds, trackingNumber, carrier, notifyCustomer 欄位，
     * 呼叫 POST /v1/orders/{orderId}/fulfillments/custom_shipping。
     *
     * @param orderId      Cyberbiz 訂單 ID（channelOrderId）
     * @param shippingInfo 出貨資訊，需含 trackingNumber、carrier（及可選 lineItemIds）
     */
    @Override
    public void shipOrder(String orderId, Map<String, Object> shippingInfo) throws Exception {
        String lineItemIds  = shippingInfo.getOrDefault("lineItemIds", "").toString();
        String tracking     = shippingInfo.getOrDefault("trackingNumber", "").toString();
        String carrier      = shippingInfo.getOrDefault("carrier", "other").toString();
        boolean notify      = Boolean.parseBoolean(shippingInfo.getOrDefault("notifyCustomer", "false").toString());

        if (tracking.isBlank()) {
            throw new IllegalArgumentException("shipOrder: trackingNumber is required for orderId=" + orderId);
        }

        boolean ok = cyberbizApiClient.fulfillOrderCustomShipping(token, secret, orderId, lineItemIds, tracking, carrier, notify);
        if (!ok) {
            throw new RuntimeException("Cyberbiz fulfillOrderCustomShipping failed for orderId=" + orderId);
        }
    }

    /**
     * 更新商品庫存（ChannelAdapter 介面 — 只有 productId，無 variantId）。
     *
     * ⚠️ 此方法無法呼叫 Cyberbiz API（需要 product_id + variant_id 兩個參數）。
     * 請使用 {@link #updateVariantInventory(String, String, int)} 代替。
     *
     * @deprecated 使用 updateVariantInventory(channelProductId, channelSpecId, quantity) 代替
     */
    @Override
    @Deprecated
    public void updateInventory(String productId, int quantity) throws Exception {
        log.warn("CyberbizAdapter.updateInventory(productId, quantity) called — this method cannot call Cyberbiz API " +
                 "because Cyberbiz requires both product_id AND variant_id. " +
                 "Use updateVariantInventory(channelProductId, channelSpecId, quantity) instead.");
    }

    /**
     * Mode A (改進版)：拉取完整訂單列表（根據 baseTimestamp）
     *
     * 調用兩次 API：
     * 1. 新建訂單：baseTimestamp - 7 天 ～ baseTimestamp（創建時間）
     * 2. 更新訂單：baseTimestamp - 1 天 ～ baseTimestamp（更新時間）
     * 然後合併結果去重
     *
     * @param channelId 通路 ID
     * @param baseTimestamp 基礎時間戳（秒），來自 Kafka 消息 header.timestamp
     * @return 訂單列表（完整數據，按 ID 去重）
     */
    @Override
    public List<Map<String, Object>> fetchOrdersByTimestamp(String channelId, long baseTimestamp) throws Exception {
        log.info("Fetching Cyberbiz complete orders for channel {} using baseTimestamp: {}", channelId, baseTimestamp);

        if (token == null || token.isEmpty() || secret == null || secret.isEmpty()) {
            log.error("Credentials not set for channel {}", channelId);
            throw new IllegalArgumentException("Credentials not set - call setCredentials() first");
        }

        // 使用 LinkedHashMap 去重：key = order id，value = order data
        Map<Integer, Map<String, Object>> ordersMap = new LinkedHashMap<>();

        // 並行呼叫兩個 API（從序列改為並行，性能提升 ~300-400ms per message）
        long sevenDaysAgo = baseTimestamp - (7 * 86400);
        long oneDayAgo = baseTimestamp - 86400;

        log.debug("Parallel fetching orders created: {} to {} and updated: {} to {}",
            sevenDaysAgo, baseTimestamp, oneDayAgo, baseTimestamp);

        try {
            // 使用 CompletableFuture 並行調用兩個 API
            java.util.concurrent.CompletableFuture<List<Map<String, Object>>> createdFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return cyberbizApiClient.getCompleteOrdersCreatedInTimeRange(
                            token, secret, sevenDaysAgo, baseTimestamp
                        );
                    } catch (Exception e) {
                        log.error("Error fetching created orders", e);
                        return Collections.emptyList();
                    }
                });

            java.util.concurrent.CompletableFuture<List<Map<String, Object>>> updatedFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return cyberbizApiClient.getCompleteOrdersUpdatedInTimeRange(
                            token, secret, oneDayAgo, baseTimestamp
                        );
                    } catch (Exception e) {
                        log.error("Error fetching updated orders", e);
                        return Collections.emptyList();
                    }
                });

            // 等待兩個 API 調用完成
            List<Map<String, Object>> createdOrders = createdFuture.get();
            List<Map<String, Object>> updatedOrders = updatedFuture.get();

            log.debug("Fetched {} orders created in timeRange from Cyberbiz", createdOrders.size());
            log.debug("Fetched {} orders updated in timeRange from Cyberbiz", updatedOrders.size());

            // 合併結果：先加入 created，再用 updated 覆蓋（更新優先）
            for (Map<String, Object> order : createdOrders) {
                Integer orderId = ((Number) order.get("id")).intValue();
                ordersMap.put(orderId, order);
            }

            for (Map<String, Object> order : updatedOrders) {
                Integer orderId = ((Number) order.get("id")).intValue();
                ordersMap.put(orderId, order);  // 覆蓋或新增（去重邏輯）
            }

        } catch (Exception e) {
            log.error("Error in parallel API calls", e);
            throw e;
        }

        List<Map<String, Object>> result = new ArrayList<>(ordersMap.values());
        log.info("Fetched {} unique complete orders from Cyberbiz (after dedup)", result.size());
        return result;
    }

    @Override
    public boolean testConnection() throws Exception {
        log.info("Testing connection to Cyberbiz API");
        // 模擬連接測試
        return true;
    }

    /**
     * 更新商品規格庫存量
     *
     * PUT /v1/products/{channelProductId}/product_variants/{channelSpecId}
     * form-data: inventory_quantity={quantity}
     *
     * @param channelProductId Cyberbiz product ID（sell_pack.channel_product_id）
     * @param channelSpecId    Cyberbiz variant ID（sell_pack.channel_spec_id）
     * @param quantity         新的庫存量
     */
    public void updateVariantInventory(String channelProductId, String channelSpecId, int quantity) throws Exception {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("inventory_quantity", String.valueOf(quantity));
        boolean ok = cyberbizApiClient.updateProductVariant(token, secret, channelProductId, channelSpecId, params);
        if (!ok) {
            throw new RuntimeException("Cyberbiz updateProductVariant (inventory) failed: productId=" + channelProductId + " variantId=" + channelSpecId);
        }
    }

    /**
     * 更新商品規格售價
     *
     * PUT /v1/products/{channelProductId}/product_variants/{channelSpecId}
     * form-data: price={price}
     *
     * @param channelProductId Cyberbiz product ID
     * @param channelSpecId    Cyberbiz variant ID
     * @param price            新的售價（字串，保留小數點精度）
     */
    public void updateVariantPrice(String channelProductId, String channelSpecId, String price) throws Exception {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("price", price);
        boolean ok = cyberbizApiClient.updateProductVariant(token, secret, channelProductId, channelSpecId, params);
        if (!ok) {
            throw new RuntimeException("Cyberbiz updateProductVariant (price) failed: productId=" + channelProductId + " variantId=" + channelSpecId);
        }
    }

    /**
     * 通知平台：退貨申請已核准（進入驗貨流程）
     *
     * PUT /v1/orders/{channelOrderId}/manual_return  operation=manual_returning
     *
     * @param channelOrderId Cyberbiz 訂單 ID
     */
    public void approveReturn(String channelOrderId) throws Exception {
        boolean ok = cyberbizApiClient.updateOrderManualReturn(token, secret, channelOrderId, "manual_returning");
        if (!ok) {
            throw new RuntimeException("Cyberbiz approveReturn failed: orderId=" + channelOrderId);
        }
    }

    /**
     * 通知平台：退貨申請已拒絕
     *
     * PUT /v1/orders/{channelOrderId}/manual_return  operation=manual_return_refuse
     *
     * @param channelOrderId Cyberbiz 訂單 ID
     */
    public void rejectReturn(String channelOrderId) throws Exception {
        boolean ok = cyberbizApiClient.updateOrderManualReturn(token, secret, channelOrderId, "manual_return_refuse");
        if (!ok) {
            throw new RuntimeException("Cyberbiz rejectReturn failed: orderId=" + channelOrderId);
        }
    }

    /**
     * 拉取退貨記錄列表（以 baseTimestamp 為基準的時間窗口）
     *
     * 流程：
     *  1. 呼叫 getOrdersWithRefund() 取得近 1 小時有退貨的訂單列表
     *  2. 對每筆訂單呼叫 getOrderReturns() 取得退貨記錄
     *  3. 在每筆退貨記錄中附加 channelOrderId（供 ReturnUpsertHandler 查詢 OMS orderId 用）
     *
     * @param baseTimestamp 基礎時間戳（秒）
     * @return list of return records, each containing a "channelOrderId" field
     */
    public List<Map<String, Object>> fetchReturnsByTimestamp(long baseTimestamp) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();

        long oneHourAgo = baseTimestamp - 3600;
        List<Map<String, Object>> ordersWithRefund =
                cyberbizApiClient.getOrdersWithRefund(token, secret, oneHourAgo, baseTimestamp);

        log.info("fetchReturnsByTimestamp: found {} orders with refunds in window [{}, {}]",
                ordersWithRefund.size(), oneHourAgo, baseTimestamp);

        for (Map<String, Object> order : ordersWithRefund) {
            Object orderIdObj = order.get("id");
            if (orderIdObj == null) continue;
            String channelOrderId = String.valueOf(orderIdObj);

            List<Map<String, Object>> returns =
                    cyberbizApiClient.getOrderReturns(token, secret, channelOrderId);
            for (Map<String, Object> ret : returns) {
                // Attach channelOrderId so ReturnUpsertHandler can resolve OMS orderId
                ret.put("channelOrderId", channelOrderId);
                result.add(ret);
            }
        }

        log.info("fetchReturnsByTimestamp: {} total return records", result.size());
        return result;
    }
}
