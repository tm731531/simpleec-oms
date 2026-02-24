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
            String queryString = String.format("create_time_from=%d&create_time_to=%d", createTimeFrom, createTimeTo);
            String fullPath = path + "?" + queryString;
            String url = baseUrl + fullPath;

            log.debug("Calling Cyberbiz API: GET {}", url);
            log.debug("Credentials - username: {}, secret: {}", username != null ? "***" : "null", secret != null ? "***" : "null");

            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", fullPath, null);
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
     * 查詢該時段內更新的訂單
     */
    public List<String> getOrdersUpdatedInTimeRange(String username, String secret, long updateTimeFrom, long updateTimeTo) {
        try {
            String path = "/v1/orders";
            String queryString = String.format("update_time_from=%d&update_time_to=%d", updateTimeFrom, updateTimeTo);
            String fullPath = path + "?" + queryString;
            String url = baseUrl + fullPath;

            log.debug("Calling Cyberbiz API: GET {}", url);

            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", fullPath, null);
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            return parseOrderResponse(response);

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getOrdersUpdatedInTimeRange", e);
            return Collections.emptyList();
        }
    }

    /**
     * 查詢單筆訂單詳情
     */
    public Map<String, Object> getOrderDetail(String username, String secret, String orderId) {
        try {
            String path = "/v1/orders/" + orderId;
            String fullPath = path;
            String url = baseUrl + fullPath;

            log.debug("Calling Cyberbiz API: GET {}", url);

            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", fullPath, null);
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
            String fullPath = path + "?" + queryString;
            String url = baseUrl + fullPath;

            log.debug("Calling Cyberbiz API: GET {}", url);

            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", fullPath, null);
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

        // 構建簽名基準字符串：包含 x-date 和 request-line
        String requestLine = String.format("%s %s HTTP/1.1", method, path);
        String stringToSign = String.format("x-date: %s\nrequest-line: %s", xDate, requestLine);

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
     * 解析訂單 API 回應
     */
    private List<String> parseOrderResponse(ResponseEntity<String> response) throws Exception {
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

            log.debug("Retrieved {} orders from Cyberbiz", orderIds.size());
            return orderIds;
        }

        log.warn("Unexpected response status from Cyberbiz: {}", response.getStatusCode());
        return Collections.emptyList();
    }
}
