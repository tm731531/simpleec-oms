package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.repository.ChannelRepository;
import com.simpleec.core.repository.GlobalConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 每小時假訂單注入處理器（僅供測試環境使用）
 *
 * 由 Scheduler 在每小時整點 (:00) 觸發。
 * 受 global_config.test_seeder_enabled 控管 — 設為 false 則靜默跳過。
 *
 * 流程：
 *  1. 讀取 global_config.test_seeder_enabled，不是 "true" 就跳過
 *  2. 登入 API 取得 JWT
 *  3. 查詢啟用中的 channel（最多取前 2 個）
 *  4. 每個 channel 產生 3–5 筆隨機假訂單
 *  5. POST 到 /api/user/orders
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedTestOrdersHandler extends AbstractEventHandler {

    private static final String API_BASE_URL    = "http://simpleec-api:8080";
    private static final String TEST_EMAIL      = "admin@a00000.com";
    private static final String TEST_PASSWORD   = "pass123456";
    /** 假訂單統一收編到此客製測試通路，不污染真實通路 */
    private static final String FAKE_CHANNEL_ID = "ch_fake_123";

    private static final List<Map<String, Object>> PRODUCTS = List.of(
        Map.of("name", "有機燕麥片 500g",   "price", 299, "sku", "OAT-500"),
        Map.of("name", "天然蜂蜜 350ml",    "price", 450, "sku", "HONEY-350"),
        Map.of("name", "綜合堅果禮盒",      "price", 890, "sku", "NUTS-GIFT"),
        Map.of("name", "有機綠茶粉 100g",   "price", 320, "sku", "GREEN-TEA-100"),
        Map.of("name", "冷壓橄欖油 500ml",  "price", 680, "sku", "OLIVE-500"),
        Map.of("name", "奇亞籽 300g",       "price", 199, "sku", "CHIA-300")
    );

    private static final List<String> STATUSES = List.of(
        "PENDING", "CONFIRMED", "READY_TO_SHIP", "SHIPPED", "COMPLETED", "CANCELLED"
    );

    private static final List<String> BUYER_NAMES = List.of(
        "陳小明", "林美麗", "張大華", "王志偉", "李淑芬", "吳建志"
    );

    private final GlobalConfigRepository globalConfigRepository;
    private final ChannelRepository channelRepository;
    private final RestTemplate restTemplate;

    @Override
    public String getTaskType() {
        return "SEED_TEST_ORDERS";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        // 1. Check global_config toggle
        boolean enabled = globalConfigRepository.findById("test_seeder_enabled")
            .map(cfg -> "true".equalsIgnoreCase(cfg.getData()))
            .orElse(false);

        if (!enabled) {
            log.info("SEED_TEST_ORDERS: test_seeder_enabled is false — skipping");
            return;
        }

        // 2. Login to get JWT
        String token;
        try {
            token = login();
        } catch (Exception e) {
            log.error("SEED_TEST_ORDERS: login failed", e);
            return;
        }

        // 3. Verify fake channel exists AND is enabled (actived + enable_sync)
        Channel fakeChannel = channelRepository.findById(FAKE_CHANNEL_ID).orElse(null);
        if (fakeChannel == null) {
            log.warn("SEED_TEST_ORDERS: fake channel {} not found — skipping", FAKE_CHANNEL_ID);
            return;
        }
        // Boolean (boxed) fields → Lombok @Data generates getXxx(), not isXxx()
        if (!Boolean.TRUE.equals(fakeChannel.getActived())
                || !Boolean.TRUE.equals(fakeChannel.getEnableSync())) {
            log.info("SEED_TEST_ORDERS: fake channel {} is not active/enable_sync — skipping", FAKE_CHANNEL_ID);
            return;
        }

        // 4+5. Generate and post orders to fake channel only
        Random rng = new Random();
        int count = 3 + rng.nextInt(3); // 3–5
        int totalPosted = 0;

        for (int i = 0; i < count; i++) {
            try {
                Map<String, Object> order = buildRandomOrder(FAKE_CHANNEL_ID, rng);
                postOrder(token, order);
                totalPosted++;
            } catch (Exception e) {
                log.warn("SEED_TEST_ORDERS: failed to post order: {}", e.getMessage());
            }
        }

        log.info("SEED_TEST_ORDERS: injected {} test orders to channel {}",
                 totalPosted, FAKE_CHANNEL_ID);
    }

    private String login() {
        String url = API_BASE_URL + "/api/auth/login";
        Map<String, String> body = Map.of("email", TEST_EMAIL, "password", TEST_PASSWORD);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Login returned status " + response.getStatusCode());
        }
        Object token = response.getBody().get("token");
        if (token == null) {
            throw new RuntimeException("Login response missing 'token' field");
        }
        return token.toString();
    }

    private Map<String, Object> buildRandomOrder(String channelId, Random rng) {
        Map<String, Object> product = PRODUCTS.get(rng.nextInt(PRODUCTS.size()));
        String status    = STATUSES.get(rng.nextInt(STATUSES.size()));
        String buyer     = BUYER_NAMES.get(rng.nextInt(BUYER_NAMES.size()));
        int qty          = 1 + rng.nextInt(3);
        int price        = (int) product.get("price");
        double total     = price * qty;
        String orderId   = "TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Map<String, Object> item = new HashMap<>();
        item.put("sku",                product.get("sku"));
        item.put("channelProductName", product.get("name"));
        item.put("quantity",           qty);
        item.put("unitPrice",          (double) price);
        item.put("subtotal",           total);

        Map<String, Object> order = new HashMap<>();
        order.put("channelId",       channelId);
        order.put("channelOrderId",  orderId);
        order.put("orderStatus",     status);
        order.put("buyerName",       buyer);
        order.put("buyerPhone",      "09" + String.format("%08d", rng.nextInt(100000000)));
        order.put("buyerEmail",      "test-" + rng.nextInt(9999) + "@example.com");
        order.put("shippingAddress", "台北市大安區測試路" + (rng.nextInt(200) + 1) + "號");
        order.put("shippingMethod",  "宅配");
        order.put("paymentMethod",   "信用卡");
        order.put("totalAmount",     total);
        order.put("shippingFee",     0.0);
        order.put("discountAmount",  0.0);
        order.put("items",           List.of(item));
        order.put("channelCreatedAt", Instant.now().toString());  // ISO-8601 with Z suffix for Instant.parse()

        return order;
    }

    private void postOrder(String token, Map<String, Object> orderPayload) {
        String url = API_BASE_URL + "/api/user/orders";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(orderPayload, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("SEED_TEST_ORDERS: POST /api/user/orders returned {}",
                     response.getStatusCode());
        }
    }
}
