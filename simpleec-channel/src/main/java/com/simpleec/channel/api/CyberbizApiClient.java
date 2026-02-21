package com.simpleec.channel.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Cyberbiz API 客戶端
 *
 * 封裝 Cyberbiz API 呼叫邏輯，包括：
 * - GET /api/order/get_orders（搭配時間範圍參數）
 * - GET /api/order/get_order（單筆訂單詳情）
 *
 * 生產環境需要實現實際 HTTP 呼叫（使用 RestTemplate 或 WebClient）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CyberbizApiClient {

    /**
     * 查詢該時段內建立的訂單
     *
     * @param createTimeFrom 建立時間開始 (Unix timestamp)
     * @param createTimeTo   建立時間結束 (Unix timestamp)
     * @return 訂單 ID 列表
     */
    public List<String> getOrdersCreatedInTimeRange(long createTimeFrom, long createTimeTo) {
        log.debug("Calling Cyberbiz API: get_orders with create_time_from={}, create_time_to={}",
            createTimeFrom, createTimeTo);

        // TODO: 實現實際 HTTP 呼叫：
        // GET https://api.cyberbiz.io/api/order/get_orders?create_time_from=<>&create_time_to=<>
        // 返回 JSON 格式：{"success":true,"data":{"orders":[{"order_id":"CBZ-001"}]}}

        // 模擬返回
        return Arrays.asList("CBZ-CREATE-00001", "CBZ-CREATE-00002", "CBZ-CREATE-00003");
    }

    /**
     * 查詢該時段內更新的訂單
     *
     * @param updateTimeFrom 更新時間開始 (Unix timestamp)
     * @param updateTimeTo   更新時間結束 (Unix timestamp)
     * @return 訂單 ID 列表
     */
    public List<String> getOrdersUpdatedInTimeRange(long updateTimeFrom, long updateTimeTo) {
        log.debug("Calling Cyberbiz API: get_orders with update_time_from={}, update_time_to={}",
            updateTimeFrom, updateTimeTo);

        // TODO: 實現實際 HTTP 呼叫：
        // GET https://api.cyberbiz.io/api/order/get_orders?update_time_from=<>&update_time_to=<>

        // 模擬返回
        return Arrays.asList("CBZ-UPDATE-00001", "CBZ-UPDATE-00002");
    }

    /**
     * 查詢單筆訂單詳情
     *
     * @param orderId Cyberbiz 訂單 ID
     * @return 訂單詳情 (Map 格式)
     */
    public Map<String, Object> getOrderDetail(String orderId) {
        log.debug("Calling Cyberbiz API: get_order with order_id={}", orderId);

        // TODO: 實現實際 HTTP 呼叫：
        // GET https://api.cyberbiz.io/api/order/get_order?order_id=<>
        // 返回完整訂單結構

        // 模擬返回
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("order_id", orderId);
        order.put("order_sn", "CBZ" + System.currentTimeMillis());
        order.put("status", "pending");
        order.put("created_at", "2024-02-20T10:30:00Z");
        order.put("updated_at", "2024-02-20T10:35:00Z");
        order.put("amount_info", Map.of("total", 2500.0, "subtotal", 2300.0));
        return order;
    }

    /**
     * 查詢該時段內有退貨的訂單
     *
     * @param refundTimeFrom 退貨時間開始 (Unix timestamp)
     * @param refundTimeTo   退貨時間結束 (Unix timestamp)
     * @return 訂單列表（帶退貨資訊）
     */
    public List<Map<String, Object>> getOrdersWithRefund(long refundTimeFrom, long refundTimeTo) {
        log.debug("Calling Cyberbiz API: get_orders with refund_time_from={}, refund_time_to={}",
            refundTimeFrom, refundTimeTo);

        // TODO: 實現實際 HTTP 呼叫：
        // GET https://api.cyberbiz.io/api/order/get_orders?refund_time_from=<>&refund_time_to=<>

        // 模擬返回
        return new ArrayList<>();
    }
}
