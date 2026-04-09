package com.simpleec.channel.adapter;

import com.simpleec.common.enums.ModeEnum;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 客製通路適配器（Custom Platform）
 *
 * 此平台不對接真實電商 API。訂單由後台排程器透過測試資料注入工具（SeedTestOrdersHandler）
 * 直接 POST 到 /api/user/orders，經由 order.process Kafka 流入資料庫。
 *
 * FETCH_ORDERS / FETCH_RETURNS → 回傳空 list（訂單不來自 API pull）
 * 健康檢查 → PlatformApiClientImpl 中直接回傳 200（無需打外部端點）
 */
@Slf4j
public class CustomAdapter implements ChannelAdapter {

    @Override
    public String getPlatformCode() {
        return "custom";
    }

    @Override
    public ModeEnum getMode() {
        return ModeEnum.A; // Mode A: fetchOrdersByTimestamp returns full order list
    }

    @Override
    public List<Map<String, Object>> fetchOrders(String channelId, String timeRange) {
        log.debug("CustomAdapter.fetchOrders called for {} — no-op (orders via seeder)", channelId);
        return Collections.emptyList();
    }

    @Override
    public List<Map<String, Object>> fetchOrdersByTimestamp(String channelId, long baseTimestamp) {
        log.debug("CustomAdapter.fetchOrdersByTimestamp called for {} — no-op (orders via seeder)", channelId);
        return Collections.emptyList();
    }

    @Override
    public List<String> fetchOrderList(String channelId, String timeRange) {
        return Collections.emptyList();
    }

    @Override
    public List<String> fetchOrderListByTimestamp(String channelId, long baseTimestamp) {
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> fetchOrderDetail(String channelId, String orderId) {
        return Collections.emptyMap();
    }

    @Override
    public List<Map<String, Object>> fetchReturns(String channelId, String timeRange) {
        return Collections.emptyList();
    }

    @Override
    public void shipOrder(String orderId, Map<String, Object> shippingInfo) {
        log.warn("CustomAdapter.shipOrder called for {} — not supported", orderId);
    }

    @Override
    public void updateInventory(String productId, int quantity) {
        log.warn("CustomAdapter.updateInventory called for {} — not supported", productId);
    }

    @Override
    public boolean testConnection() {
        return true; // always healthy
    }
}
