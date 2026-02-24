package com.simpleec.channel.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Cyberbiz API 客戶端
 *
 * 封裝 Cyberbiz API 呼叫邏輯，包括：
 * - GET /api/order/get_orders（搭配時間範圍參數）
 * - GET /api/order/get_order（單筆訂單詳情）
 *
 * 使用 OAuth Bearer Token 認證
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CyberbizApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${cyberbiz.api.base-url:https://api.cyberbiz.io}")
    private String baseUrl;

    @Value("${cyberbiz.api.token:}")
    private String apiToken;

    /**
     * 建立 HTTP headers，包含 OAuth Bearer Token
     */
    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiToken);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    /**
     * 查詢該時段內建立的訂單
     *
     * @param createTimeFrom 建立時間開始 (Unix timestamp)
     * @param createTimeTo   建立時間結束 (Unix timestamp)
     * @return 訂單 ID 列表
     */
    public List<String> getOrdersCreatedInTimeRange(long createTimeFrom, long createTimeTo) {
        try {
            String url = String.format("%s/api/order/get_orders?create_time_from=%d&create_time_to=%d",
                    baseUrl, createTimeFrom, createTimeTo);

            log.debug("Calling Cyberbiz API: GET {}", url);

            HttpEntity<?> entity = new HttpEntity<>(buildHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                List<String> orderIds = new ArrayList<>();

                if (root.has("data") && root.get("data").has("orders")) {
                    JsonNode ordersNode = root.get("data").get("orders");
                    if (ordersNode.isArray()) {
                        ordersNode.forEach(order -> {
                            if (order.has("order_id")) {
                                orderIds.add(order.get("order_id").asText());
                            }
                        });
                    }
                }

                log.debug("Retrieved {} orders from Cyberbiz (created)", orderIds.size());
                return orderIds;
            }

            log.warn("Unexpected response status from Cyberbiz: {}", response.getStatusCode());
            return Collections.emptyList();

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getOrdersCreatedInTimeRange", e);
            return Collections.emptyList();
        }
    }

    /**
     * 查詢該時段內更新的訂單
     *
     * @param updateTimeFrom 更新時間開始 (Unix timestamp)
     * @param updateTimeTo   更新時間結束 (Unix timestamp)
     * @return 訂單 ID 列表
     */
    public List<String> getOrdersUpdatedInTimeRange(long updateTimeFrom, long updateTimeTo) {
        try {
            String url = String.format("%s/api/order/get_orders?update_time_from=%d&update_time_to=%d",
                    baseUrl, updateTimeFrom, updateTimeTo);

            log.debug("Calling Cyberbiz API: GET {}", url);

            HttpEntity<?> entity = new HttpEntity<>(buildHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                List<String> orderIds = new ArrayList<>();

                if (root.has("data") && root.get("data").has("orders")) {
                    JsonNode ordersNode = root.get("data").get("orders");
                    if (ordersNode.isArray()) {
                        ordersNode.forEach(order -> {
                            if (order.has("order_id")) {
                                orderIds.add(order.get("order_id").asText());
                            }
                        });
                    }
                }

                log.debug("Retrieved {} orders from Cyberbiz (updated)", orderIds.size());
                return orderIds;
            }

            log.warn("Unexpected response status from Cyberbiz: {}", response.getStatusCode());
            return Collections.emptyList();

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getOrdersUpdatedInTimeRange", e);
            return Collections.emptyList();
        }
    }

    /**
     * 查詢單筆訂單詳情
     *
     * @param orderId Cyberbiz 訂單 ID
     * @return 訂單詳情 (Map 格式)
     */
    public Map<String, Object> getOrderDetail(String orderId) {
        try {
            String url = String.format("%s/api/order/get_order?order_id=%s", baseUrl, orderId);

            log.debug("Calling Cyberbiz API: GET {}", url);

            HttpEntity<?> entity = new HttpEntity<>(buildHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                if (root.has("success") && root.get("success").asBoolean() && root.has("data")) {
                    JsonNode dataNode = root.get("data");

                    // Convert JsonNode to Map<String, Object>
                    Map<String, Object> orderMap = objectMapper.convertValue(dataNode, Map.class);

                    log.debug("Retrieved order detail from Cyberbiz: {}", orderId);
                    return orderMap;
                }

                log.warn("Unexpected response format from Cyberbiz for order: {}", orderId);
                return Collections.emptyMap();
            }

            log.warn("Unexpected response status from Cyberbiz: {}", response.getStatusCode());
            return Collections.emptyMap();

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getOrderDetail for order: {}", orderId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 查詢該時段內有退貨的訂單
     *
     * @param refundTimeFrom 退貨時間開始 (Unix timestamp)
     * @param refundTimeTo   退貨時間結束 (Unix timestamp)
     * @return 訂單列表（帶退貨資訊）
     */
    public List<Map<String, Object>> getOrdersWithRefund(long refundTimeFrom, long refundTimeTo) {
        try {
            String url = String.format("%s/api/order/get_orders?refund_time_from=%d&refund_time_to=%d",
                    baseUrl, refundTimeFrom, refundTimeTo);

            log.debug("Calling Cyberbiz API: GET {}", url);

            HttpEntity<?> entity = new HttpEntity<>(buildHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                List<Map<String, Object>> orders = new ArrayList<>();

                if (root.has("data") && root.get("data").has("orders")) {
                    JsonNode ordersNode = root.get("data").get("orders");
                    if (ordersNode.isArray()) {
                        ordersNode.forEach(order -> {
                            Map<String, Object> orderMap = objectMapper.convertValue(order, Map.class);
                            orders.add(orderMap);
                        });
                    }
                }

                log.debug("Retrieved {} orders with refunds from Cyberbiz", orders.size());
                return orders;
            }

            log.warn("Unexpected response status from Cyberbiz: {}", response.getStatusCode());
            return Collections.emptyList();

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getOrdersWithRefund", e);
            return Collections.emptyList();
        }
    }
}
