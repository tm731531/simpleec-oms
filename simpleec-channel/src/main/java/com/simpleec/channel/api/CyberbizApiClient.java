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
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.security.MessageDigest;

/**
 * Cyberbiz API 客戶端
 *
 * 使用 HMAC-SHA256 簽名認證（非 Bearer Token）
 * Token 格式：{"username":"xxx", "secret":"xxx"}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CyberbizApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${cyberbiz.api.base-url:https://api.cyberbiz.co}")
    private String baseUrl;

    /**
     * 查詢該時段內建立的訂單
     *
     * @param username Cyberbiz username (from channel.token)
     * @param secret   Cyberbiz secret key (from channel.token2)
     */
    public List<String> getOrdersCreatedInTimeRange(String username, String secret, long createTimeFrom, long createTimeTo) {
        try {
            String path = "/v1/orders";
            // 根據 Cyberbiz API 文檔，時間參數應為日期時間字符串
            // 格式：YYYY-MM-DD HH:MM:SS
            String startTime = formatTimestamp(createTimeFrom);
            String endTime = formatTimestamp(createTimeTo);
            // 使用 %20 而不是 + 來編碼空格（HTTP 簽名規範要求）
            String encodedStart = java.net.URLEncoder.encode(startTime, "UTF-8").replace("+", "%20");
            String encodedEnd = java.net.URLEncoder.encode(endTime, "UTF-8").replace("+", "%20");
            String queryString = String.format("start_time=%s&end_time=%s&page=1&per_page=50&offset=0",
                encodedStart, encodedEnd);
            String url = baseUrl + path + "?" + queryString;

            log.debug("Calling Cyberbiz API: GET {}", url);
            log.debug("Credentials - username: {}, secret: {}", username != null ? "***" : "null", secret != null ? "***" : "null");

            // 重要：HTTP 簽名中的 request-line 只包含路徑，不包含查詢參數
            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", path, null);
            log.debug("HMAC headers built successfully");
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            return parseOrderResponse(response);

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getOrdersCreatedInTimeRange - username: {}, secret: {}", username, secret, e);
            return Collections.emptyList();
        }
    }

    /**
     * 查詢該時段內更新的訂單（返回訂單 ID 列表）
     */
    public List<String> getOrdersUpdatedInTimeRange(String username, String secret, long updateTimeFrom, long updateTimeTo) {
        try {
            String path = "/v1/orders";
            // 更新時間使用 updated_at_start_time 和 updated_at_end_time 參數
            String startTime = formatTimestamp(updateTimeFrom);
            String endTime = formatTimestamp(updateTimeTo);
            // 使用 %20 而不是 + 來編碼空格（HTTP 簽名規範要求）
            String encodedStart = java.net.URLEncoder.encode(startTime, "UTF-8").replace("+", "%20");
            String encodedEnd = java.net.URLEncoder.encode(endTime, "UTF-8").replace("+", "%20");
            String queryString = String.format("updated_at_start_time=%s&updated_at_end_time=%s&page=1&per_page=50&offset=0",
                encodedStart, encodedEnd);
            String url = baseUrl + path + "?" + queryString;

            log.debug("Calling Cyberbiz API: GET {}", url);

            // 重要：HTTP 簽名中的 request-line 只包含路徑，不包含查詢參數
            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", path, null);
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            return parseOrderResponse(response);

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getOrdersUpdatedInTimeRange", e);
            return Collections.emptyList();
        }
    }

    /**
     * 查詢該時段內更新的訂單（返回完整訂單數據 - 用於 Mode A）
     */
    public List<Map<String, Object>> getCompleteOrdersUpdatedInTimeRange(String username, String secret, long updateTimeFrom, long updateTimeTo) {
        try {
            String path = "/v1/orders";
            String startTime = formatTimestamp(updateTimeFrom);
            String endTime = formatTimestamp(updateTimeTo);
            String encodedStart = java.net.URLEncoder.encode(startTime, "UTF-8").replace("+", "%20");
            String encodedEnd = java.net.URLEncoder.encode(endTime, "UTF-8").replace("+", "%20");
            String queryString = String.format("updated_at_start_time=%s&updated_at_end_time=%s&page=1&per_page=50&offset=0",
                encodedStart, encodedEnd);
            String url = baseUrl + path + "?" + queryString;

            log.debug("Calling Cyberbiz API for complete orders: GET {}", url);

            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", path, null);
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            return parseCompleteOrderResponse(response);

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getCompleteOrdersUpdatedInTimeRange", e);
            return Collections.emptyList();
        }
    }

    /**
     * 查詢單筆訂單詳情
     */
    public Map<String, Object> getOrderDetail(String username, String secret, String orderId) {
        try {
            String path = "/v1/orders/" + orderId;
            String url = baseUrl + path;

            log.debug("Calling Cyberbiz API: GET {}", url);

            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", path, null);
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("success") && root.get("success").asBoolean() && root.has("data")) {
                    Map<String, Object> orderMap = objectMapper.convertValue(root.get("data"), Map.class);
                    log.debug("Retrieved order detail from Cyberbiz: {}", orderId);
                    return orderMap;
                }
            }

            log.warn("Unexpected response from Cyberbiz for order: {}", orderId);
            return Collections.emptyMap();

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getOrderDetail for order: {}", orderId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 查詢該時段內有退貨的訂單
     */
    public List<Map<String, Object>> getOrdersWithRefund(String username, String secret, long refundTimeFrom, long refundTimeTo) {
        try {
            String path = "/v1/orders";
            String queryString = String.format("refund_time_from=%d&refund_time_to=%d", refundTimeFrom, refundTimeTo);
            String url = baseUrl + path + "?" + queryString;

            log.debug("Calling Cyberbiz API: GET {}", url);

            // 重要：HTTP 簽名中的 request-line 只包含路徑，不包含查詢參數
            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", path, null);
            HttpEntity<?> entity = new HttpEntity<>(headers);
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

    /**
     * 建立 HMAC-SHA256 認證 Headers
     * 使用 Channel.token（username）和 Channel.token2（secret）
     */
    private HttpHeaders buildHmacHeaders(String username, String secret, String method, String path, String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");

        try {
            if (username == null || username.isEmpty() || secret == null || secret.isEmpty()) {
                throw new IllegalArgumentException("Username or secret is empty");
            }

            // 生成 X-Date 頭（GMT 格式）
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            String xDate = dateFormat.format(new Date());
            headers.set("X-Date", xDate);

            // 如果有 body（POST），計算 Digest
            String digestHeader = null;
            if (body != null && !body.isEmpty()) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(body.getBytes(StandardCharsets.UTF_8));
                String encodedDigest = Base64.getEncoder().encodeToString(digest);
                digestHeader = "SHA-256=" + encodedDigest;
                headers.set("Digest", digestHeader);
            }

            // 計算簽名
            String signature = computeHmacSignature(username, secret, method, path, xDate, digestHeader);
            headers.set("Authorization", signature);

        } catch (Exception e) {
            log.warn("Failed to build HMAC headers: {}", e.getMessage());
            throw e;
        }

        return headers;
    }

    /**
     * 計算 HMAC-SHA256 簽名
     * 根據 Cyberbiz API 規範，簽名必須包含 "x-date request-line"
     */
    private String computeHmacSignature(String username, String secret, String method, String path,
                                        String xDate, String digest) throws Exception {
        StringBuilder headersToSign = new StringBuilder("x-date request-line");

        // 構建簽名基準字符串：根據 HTTP Signature RFC，request-line 是特殊的偽頭
        // 格式應該是：
        // x-date: {value}
        // {method} {path} HTTP/1.1
        String requestLine = String.format("%s %s HTTP/1.1", method, path);
        String stringToSign = String.format("x-date: %s\n%s", xDate, requestLine);

        if (digest != null) {
            headersToSign.append(" digest");
            stringToSign += String.format("\ndigest: %s", digest);
        }

        // 計算 HMAC-SHA256
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hmacBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getEncoder().encodeToString(hmacBytes);

        // 構建 Authorization 頭
        return String.format("hmac username=\"%s\", algorithm=\"hmac-sha256\", headers=\"%s\", signature=\"%s\"",
            username, headersToSign.toString(), encodedSignature);
    }

    /**
     * 解析訂單 API 回應 - 返回訂單 ID 列表
     * Cyberbiz API 返回的是直接的數組：[{ id, order_number, ... }, ...]
     */
    private List<String> parseOrderResponse(ResponseEntity<String> response) throws Exception {
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());
            List<String> orderIds = new ArrayList<>();

            // Cyberbiz API 返回的是直接的 JSON 數組
            if (root.isArray()) {
                root.forEach(order -> {
                    if (order.has("id")) {
                        // 使用 id（訂單的內部 ID）或 order_number（訂單號）
                        String orderId = order.has("id") ? order.get("id").asText() : null;
                        if (orderId != null && !orderId.isEmpty()) {
                            orderIds.add(orderId);
                        }
                    }
                });
            }
            // 備用：如果是舊格式 { data: { orders: [...] } }
            else if (root.has("data") && root.get("data").has("orders")) {
                JsonNode ordersNode = root.get("data").get("orders");
                if (ordersNode.isArray()) {
                    ordersNode.forEach(order -> {
                        if (order.has("order_id")) {
                            orderIds.add(order.get("order_id").asText());
                        }
                    });
                }
            }

            log.debug("Retrieved {} orders from Cyberbiz", orderIds.size());
            return orderIds;
        }

        log.warn("Unexpected response status from Cyberbiz: {}", response.getStatusCode());
        return Collections.emptyList();
    }

    /**
     * 解析訂單 API 回應 - 返回完整訂單數據（用於 Mode A）
     */
    private List<Map<String, Object>> parseCompleteOrderResponse(ResponseEntity<String> response) throws Exception {
        List<Map<String, Object>> orders = new ArrayList<>();

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());

            // Cyberbiz API 返回的是直接的 JSON 數組
            if (root.isArray()) {
                root.forEach(order -> {
                    Map<String, Object> orderMap = objectMapper.convertValue(order, Map.class);
                    orders.add(orderMap);
                });
            }
            // 備用：如果是舊格式 { data: { orders: [...] } }
            else if (root.has("data") && root.get("data").has("orders")) {
                JsonNode ordersNode = root.get("data").get("orders");
                if (ordersNode.isArray()) {
                    ordersNode.forEach(order -> {
                        Map<String, Object> orderMap = objectMapper.convertValue(order, Map.class);
                        orders.add(orderMap);
                    });
                }
            }

            log.debug("Retrieved {} complete orders from Cyberbiz", orders.size());
            return orders;
        }

        log.warn("Unexpected response status from Cyberbiz: {}", response.getStatusCode());
        return Collections.emptyList();
    }

    /**
     * 將 Unix timestamp 轉換為 Cyberbiz API 所需的日期時間字符串格式
     * 格式：YYYY-MM-DD HH:MM:SS
     */
    private String formatTimestamp(long timestamp) {
        java.time.Instant instant = java.time.Instant.ofEpochSecond(timestamp);
        java.time.ZonedDateTime zdt = instant.atZone(java.time.ZoneId.of("UTC"));
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return formatter.format(zdt);
    }
}
