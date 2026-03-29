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
     * 查詢特定時間範圍內建立的完整訂單（包含所有詳情）
     * 使用 start_time 和 end_time 參數篩選訂單
     *
     * @param username    API username
     * @param secret      API secret key
     * @param createTimeFrom 建立時間開始（Unix 秒）
     * @param createTimeTo   建立時間結束（Unix 秒）
     * @return 完整訂單列表（已包含 items, buyer_info, shipping_info 等詳情）
     */
    public List<Map<String, Object>> getCompleteOrdersCreatedInTimeRange(String username, String secret, long createTimeFrom, long createTimeTo) {
        try {
            String path = "/v1/orders";
            String startTime = formatTimestamp(createTimeFrom);
            String endTime = formatTimestamp(createTimeTo);
            String encodedStart = java.net.URLEncoder.encode(startTime, "UTF-8").replace("+", "%20");
            String encodedEnd = java.net.URLEncoder.encode(endTime, "UTF-8").replace("+", "%20");
            String queryString = String.format("start_time=%s&end_time=%s&page=1&per_page=50&offset=0",
                encodedStart, encodedEnd);
            String url = baseUrl + path + "?" + queryString;

            log.debug("Calling Cyberbiz API for complete orders (created): GET {}", url);

            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", path, null);
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            return parseCompleteOrderResponse(response);

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getCompleteOrdersCreatedInTimeRange", e);
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
     * 拉取商品列表（含 product_variants），支援分頁
     *
     * @param username  Cyberbiz username
     * @param secret    Cyberbiz secret key
     * @param page      頁碼，從 1 開始
     * @param perPage   每頁筆數（最大 50）
     * @return 商品列表，每筆含 product_variants 陣列；若無更多則返回空列表
     */
    public List<Map<String, Object>> getProducts(String username, String secret, int page, int perPage) {
        try {
            String path = "/v1/products";
            String queryString = String.format("page=%d&per_page=%d", page, perPage);
            String url = baseUrl + path + "?" + queryString;

            log.debug("Calling Cyberbiz API: GET {}", url);

            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", path, null);
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Unexpected response from Cyberbiz getProducts: {}", response.getStatusCode());
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            List<Map<String, Object>> products = new ArrayList<>();

            // Cyberbiz returns a JSON array directly
            if (root.isArray()) {
                root.forEach(p -> products.add(objectMapper.convertValue(p, Map.class)));
            }
            // Fallback: { data: { products: [...] } }
            else if (root.has("data") && root.get("data").has("products")) {
                root.get("data").get("products")
                        .forEach(p -> products.add(objectMapper.convertValue(p, Map.class)));
            }

            log.debug("Fetched {} products from Cyberbiz (page={})", products.size(), page);
            return products;

        } catch (Exception e) {
            log.error("Error calling Cyberbiz API getProducts page={}", page, e);
            return Collections.emptyList();
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
     * 確認訂單出貨（自定義物流）
     *
     * POST /v1/orders/{order_id}/fulfillments/custom_shipping
     * form-data: line_item_ids (comma-separated), tracking_number, tracking_company, notify_customer
     *
     * @param lineItemIds  comma-separated Cyberbiz line item IDs (e.g. "12345,67890")
     * @param carrier      carrier code (e.g. "hct", "kerry_express", "other")
     * @param notifyCustomer whether to notify the customer by email
     * @return true if successful (2xx response)
     */
    public boolean fulfillOrderCustomShipping(String username, String secret,
                                               String orderId, String lineItemIds,
                                               String trackingNumber, String carrier,
                                               boolean notifyCustomer) {
        try {
            String path = "/v1/orders/" + orderId + "/fulfillments/custom_shipping";
            String url = baseUrl + path;

            HttpHeaders headers = buildHmacHeaders(username, secret, "POST", path, null);
            headers.set("Content-Type", "application/x-www-form-urlencoded");

            StringBuilder form = new StringBuilder();
            form.append("line_item_ids=").append(java.net.URLEncoder.encode(lineItemIds, "UTF-8"));
            form.append("&tracking_number=").append(java.net.URLEncoder.encode(trackingNumber, "UTF-8"));
            form.append("&tracking_company=").append(java.net.URLEncoder.encode(carrier, "UTF-8"));
            form.append("&notify_customer=").append(notifyCustomer ? "true" : "false");

            HttpEntity<String> entity = new HttpEntity<>(form.toString(), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            boolean success = response.getStatusCode().is2xxSuccessful();
            if (success) {
                log.info("fulfillOrderCustomShipping success: orderId={} tracking={}", orderId, trackingNumber);
            } else {
                log.warn("fulfillOrderCustomShipping failed: orderId={} status={} body={}",
                        orderId, response.getStatusCode(), response.getBody());
            }
            return success;

        } catch (Exception e) {
            log.error("Error calling fulfillOrderCustomShipping for orderId={}", orderId, e);
            return false;
        }
    }

    /**
     * 更新商品規格（庫存量、售價等）
     *
     * PUT /v1/products/{product_id}/product_variants/{product_variant_id}
     * form-data: any subset of inventory_quantity, price, compare_at_price, sku, etc.
     *
     * @param params map of fields to update (e.g. {"inventory_quantity": "10"} or {"price": "299.00"})
     * @return true if successful (2xx response)
     */
    public boolean updateProductVariant(String username, String secret,
                                         String productId, String variantId,
                                         Map<String, String> params) {
        try {
            String path = "/v1/products/" + productId + "/product_variants/" + variantId;
            String url = baseUrl + path;

            HttpHeaders headers = buildHmacHeaders(username, secret, "PUT", path, null);
            headers.set("Content-Type", "application/x-www-form-urlencoded");

            StringBuilder form = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (form.length() > 0) form.append("&");
                form.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"))
                    .append("=")
                    .append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
            }

            HttpEntity<String> entity = new HttpEntity<>(form.toString(), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);

            boolean success = response.getStatusCode().is2xxSuccessful();
            if (success) {
                log.info("updateProductVariant success: productId={} variantId={} params={}", productId, variantId, params);
            } else {
                log.warn("updateProductVariant failed: productId={} variantId={} status={} body={}",
                        productId, variantId, response.getStatusCode(), response.getBody());
            }
            return success;

        } catch (Exception e) {
            log.error("Error calling updateProductVariant for productId={} variantId={}", productId, variantId, e);
            return false;
        }
    }

    /**
     * 更新訂單退貨狀態（手動操作）
     *
     * PUT /v1/orders/{order_id}/manual_return
     * form-data: operation (enum: manual_returning | manual_check_goods | manual_return_refuse | manual_return_done)
     *
     * @param operation one of: "manual_returning", "manual_check_goods", "manual_return_refuse", "manual_return_done"
     * @return true if successful (2xx response)
     */
    public boolean updateOrderManualReturn(String username, String secret,
                                            String orderId, String operation) {
        try {
            String path = "/v1/orders/" + orderId + "/manual_return";
            String url = baseUrl + path;

            HttpHeaders headers = buildHmacHeaders(username, secret, "PUT", path, null);
            headers.set("Content-Type", "application/x-www-form-urlencoded");

            String form = "operation=" + java.net.URLEncoder.encode(operation, "UTF-8");

            HttpEntity<String> entity = new HttpEntity<>(form, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);

            boolean success = response.getStatusCode().is2xxSuccessful();
            if (success) {
                log.info("updateOrderManualReturn success: orderId={} operation={}", orderId, operation);
            } else {
                log.warn("updateOrderManualReturn failed: orderId={} operation={} status={} body={}",
                        orderId, operation, response.getStatusCode(), response.getBody());
            }
            return success;

        } catch (Exception e) {
            log.error("Error calling updateOrderManualReturn for orderId={}", orderId, e);
            return false;
        }
    }

    /**
     * 查詢訂單的退貨記錄列表
     *
     * GET /v1/orders/{order_id}/returns
     * Response: array of return records with id, created_at, tracking_number, line_items, return_reason
     *
     * @return list of return records; empty if none or on error
     */
    public List<Map<String, Object>> getOrderReturns(String username, String secret, String orderId) {
        try {
            String path = "/v1/orders/" + orderId + "/returns";
            String url = baseUrl + path;

            HttpHeaders headers = buildHmacHeaders(username, secret, "GET", path, null);
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.debug("getOrderReturns: no data for orderId={} status={}", orderId, response.getStatusCode());
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            List<Map<String, Object>> returns = new ArrayList<>();

            if (root.isArray()) {
                root.forEach(r -> returns.add(objectMapper.convertValue(r, Map.class)));
            } else if (root.has("data") && root.get("data").isArray()) {
                root.get("data").forEach(r -> returns.add(objectMapper.convertValue(r, Map.class)));
            }

            log.debug("getOrderReturns: orderId={} found {} records", orderId, returns.size());
            return returns;

        } catch (Exception e) {
            log.error("Error calling getOrderReturns for orderId={}", orderId, e);
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
                    // 統一日期格式為 ISO-8601
                    normalizeDateTime(orderMap);
                    orders.add(orderMap);
                });
            }
            // 備用：如果是舊格式 { data: { orders: [...] } }
            else if (root.has("data") && root.get("data").has("orders")) {
                JsonNode ordersNode = root.get("data").get("orders");
                if (ordersNode.isArray()) {
                    ordersNode.forEach(order -> {
                        Map<String, Object> orderMap = objectMapper.convertValue(order, Map.class);
                        // 統一日期格式為 ISO-8601
                        normalizeDateTime(orderMap);
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

    /**
     * 遞迴地標準化 Map 中的所有日期時間字段為 ISO-8601 格式
     * Cyberbiz API 返回的日期格式為 "YYYY-MM-DD HH:MM:SS"，需要轉換為 ISO-8601
     *
     * @param map 要處理的 Map（原址修改）
     */
    @SuppressWarnings("unchecked")
    private void normalizeDateTime(Map<String, Object> map) {
        if (map == null) {
            return;
        }

        for (String key : map.keySet()) {
            Object value = map.get(key);

            if (value == null) {
                continue;
            }

            // 如果是日期相關的字段名，嘗試轉換
            if (isDateTimeField(key) && value instanceof String) {
                String dateStr = (String) value;
                try {
                    String isoStr = convertToISO8601(dateStr);
                    map.put(key, isoStr);
                    log.debug("Converted {} from '{}' to '{}'", key, dateStr, isoStr);
                } catch (Exception e) {
                    log.debug("Unable to convert {} field: {} - {}", key, dateStr, e.getMessage());
                }
            }
            // 遞迴處理嵌套的 Map
            else if (value instanceof Map) {
                normalizeDateTime((Map<String, Object>) value);
            }
            // 遞迴處理 List（可能包含 Map）
            else if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    if (item instanceof Map) {
                        normalizeDateTime((Map<String, Object>) item);
                    }
                }
            }
        }
    }

    /**
     * 判斷是否為日期時間字段
     */
    private boolean isDateTimeField(String key) {
        String lowerKey = key.toLowerCase();
        return lowerKey.contains("date") || lowerKey.contains("time") || lowerKey.contains("at") ||
               lowerKey.contains("created") || lowerKey.contains("updated") || lowerKey.contains("paid") ||
               lowerKey.contains("shipped");
    }

    /**
     * 將 Cyberbiz 格式日期 "YYYY-MM-DD HH:MM:SS" 轉換為 ISO-8601
     * 輸出格式：YYYY-MM-DDTHH:MM:SSZ
     */
    private String convertToISO8601(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return dateStr;
        }

        try {
            // 嘗試解析 "YYYY-MM-DD HH:MM:SS" 格式
            java.time.format.DateTimeFormatter inputFormatter =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(dateStr, inputFormatter);

            // 轉換為 ISO-8601 格式（假設為 UTC）
            java.time.ZonedDateTime zdt = ldt.atZone(java.time.ZoneId.of("UTC"));
            return zdt.format(java.time.format.DateTimeFormatter.ISO_INSTANT);
        } catch (Exception e) {
            // 如果已經是 ISO-8601 格式，直接返回
            if (dateStr.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")) {
                return dateStr;
            }
            throw new IllegalArgumentException("Unable to parse date: " + dateStr, e);
        }
    }
}
