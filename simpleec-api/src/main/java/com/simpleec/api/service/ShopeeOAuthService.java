package com.simpleec.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.repository.ChannelRepository;
import com.simpleec.core.repository.PlatformRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Shopee OAuth 2.0 Token 管理服務
 *
 * 負責：
 * 1. 生成授權 URL（帶 state → Redis，防 CSRF）
 * 2. 處理 callback（code exchange → 存 token1~4）
 * 3. Refresh token（主動 / 被動 + Redis distributed lock）
 * 4. Disconnect（清空 token1~4）
 *
 * Token 欄位語意（Shopee）：
 *   token  = access_token   (4h TTL)
 *   token2 = refresh_token  (30d TTL，Shopee 使用後自動延長)
 *   token3 = shop_id        (固定，授權時取得)
 *   token4 = token_expires_at (ISO-8601 Instant，用於到期判斷)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopeeOAuthService {

    private static final String SHOPEE_PARTNER_BASE = "https://partner.shopeemobile.com";
    private static final String AUTH_PATH           = "/api/v2/shop/auth_partner";
    private static final String TOKEN_GET_PATH      = "/api/v2/auth/token/get";
    private static final String TOKEN_REFRESH_PATH  = "/api/v2/auth/access_token/get";

    private static final String STATE_KEY_PREFIX = "shopee:oauth:state:";
    private static final String LOCK_KEY_PREFIX  = "shopee:token:refresh:lock:";
    private static final Duration STATE_TTL      = Duration.ofMinutes(10);
    private static final Duration LOCK_TTL       = Duration.ofSeconds(30);

    private final ChannelRepository   channelRepository;
    private final PlatformRepository  platformRepository;
    private final StringRedisTemplate redisTemplate;
    private final RestTemplate        restTemplate;
    private final ObjectMapper        objectMapper;

    @Value("${simpleec.shopee.callback-url}")
    private String callbackUrl;

    // ──────────────────────────────────────────────────────────
    // 1. 生成授權 URL
    // ──────────────────────────────────────────────────────────

    /**
     * 為指定 channel 生成 Shopee OAuth 授權 URL。
     * state 存入 Redis（TTL 10 分鐘），callback 時驗證。
     */
    public String generateAuthUrl(String channelId) {
        ShopeeAppCredentials creds = loadAppCredentials();
        long timestamp = Instant.now().getEpochSecond();

        // 存 state → channelId（10 分鐘內有效）
        String state = NanoIdUtil.generate();
        redisTemplate.opsForValue().set(STATE_KEY_PREFIX + state, channelId, STATE_TTL);

        // 公開 API 簽名：partner_id + path + timestamp（無 access_token / shop_id）
        String sign     = hmacSign(creds.partnerKey(), creds.partnerId(), AUTH_PATH, timestamp, null, null);
        String redirect = URLEncoder.encode(callbackUrl + "?state=" + state, StandardCharsets.UTF_8);

        return SHOPEE_PARTNER_BASE + AUTH_PATH
            + "?partner_id=" + creds.partnerId()
            + "&timestamp=" + timestamp
            + "&sign=" + sign
            + "&redirect=" + redirect;
    }

    // ──────────────────────────────────────────────────────────
    // 2. 處理 OAuth callback
    // ──────────────────────────────────────────────────────────

    /**
     * 處理 Shopee callback：驗證 state → 用 code 換 token → 存入 channel。
     *
     * @return channelId（給 controller 用來回傳給前端）
     */
    public String handleCallback(String code, String shopId, String state) {
        // 驗證 state
        String channelId = redisTemplate.opsForValue().get(STATE_KEY_PREFIX + state);
        if (channelId == null) {
            throw new IllegalArgumentException("Invalid or expired OAuth state");
        }
        redisTemplate.delete(STATE_KEY_PREFIX + state);

        Channel channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));

        ShopeeAppCredentials creds = loadAppCredentials();
        long timestamp = Instant.now().getEpochSecond();

        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("shop_id", Long.parseLong(shopId));
        body.put("partner_id", Long.parseLong(creds.partnerId()));

        // 公開 API 簽名（code exchange 不需要 access_token）
        JsonNode response = callShopeeApi(TOKEN_GET_PATH, creds, timestamp, null, null, body);
        storeTokens(channel, shopId, response);

        log.info("Shopee OAuth complete for channel={} shopId={}", channelId, shopId);
        return channelId;
    }

    // ──────────────────────────────────────────────────────────
    // 3. Token 刷新（帶 distributed lock）
    // ──────────────────────────────────────────────────────────

    /**
     * 刷新指定 channel 的 access_token（帶 Redis lock，防並發互蓋）。
     * 由 BackendJob SHOPEE_TOKEN_REFRESH 每小時呼叫。
     */
    public void refreshToken(String channelId) {
        String lockKey = LOCK_KEY_PREFIX + channelId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);

        if (Boolean.TRUE.equals(acquired)) {
            try {
                doRefresh(channelId);
            } finally {
                redisTemplate.delete(lockKey);
            }
        } else {
            // 其他 instance 正在刷新，跳過即可（它刷好後 DB 會有新 token）
            log.debug("Token refresh lock held by another instance for channel={}, skipping", channelId);
        }
    }

    /**
     * 強制刷新（由 API 手動觸發，移除舊 lock 後立即刷新）。
     */
    public void forceRefreshToken(String channelId) {
        String lockKey = LOCK_KEY_PREFIX + channelId;
        redisTemplate.delete(lockKey); // 清除可能殘留的 lock
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            try {
                doRefresh(channelId);
            } finally {
                redisTemplate.delete(lockKey);
            }
        }
    }

    private void doRefresh(String channelId) {
        Channel channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));

        String refreshToken = channel.getToken2();
        String shopId       = channel.getToken3();
        String accessToken  = channel.getToken();

        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("No refresh token for channel={}, cannot refresh", channelId);
            return;
        }

        ShopeeAppCredentials creds = loadAppCredentials();
        long timestamp = Instant.now().getEpochSecond();

        Map<String, Object> body = new HashMap<>();
        body.put("refresh_token", refreshToken);
        body.put("shop_id", Long.parseLong(shopId));
        body.put("partner_id", Long.parseLong(creds.partnerId()));

        // Shop API 簽名需帶 access_token + shop_id
        JsonNode response = callShopeeApi(TOKEN_REFRESH_PATH, creds, timestamp,
            accessToken, shopId, body);

        storeTokens(channel, shopId, response);
        log.info("Token refreshed for channel={}", channelId);
    }

    // ──────────────────────────────────────────────────────────
    // 4. Disconnect
    // ──────────────────────────────────────────────────────────

    public void disconnect(String channelId) {
        Channel channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));

        // token 欄位 NOT NULL — 用空字串表示「未連接」，ChannelVO 會回傳 oauthStatus=NOT_CONNECTED
        channel.setToken("");
        channel.setToken2(null);
        channel.setToken3(null);
        channel.setToken4(null);
        channel.setActived(false);
        channel.setEnableSync(false);
        channelRepository.save(channel);

        log.info("Shopee channel disconnected: {}", channelId);
    }

    // ──────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────

    /** 儲存 Shopee 回傳的 token 到 channel entity */
    private void storeTokens(Channel channel, String shopId, JsonNode response) {
        String error = response.path("error").asText("");
        if (!error.isBlank()) {
            throw new RuntimeException("Shopee token API error: " + error
                + " — " + response.path("message").asText(""));
        }

        String newAccessToken  = response.path("access_token").asText();
        String newRefreshToken = response.path("refresh_token").asText();
        long   expireIn        = response.path("expire_in").asLong(14400); // 預設 4h

        channel.setToken(newAccessToken);
        channel.setToken2(newRefreshToken);
        channel.setToken3(shopId);
        channel.setToken4(Instant.now().plusSeconds(expireIn).toString()); // token4 = expires_at
        channelRepository.save(channel);
    }

    /** 呼叫 Shopee API（POST，帶 HMAC-SHA256 簽名） */
    private JsonNode callShopeeApi(String path, ShopeeAppCredentials creds,
                                   long timestamp, String accessToken,
                                   String shopId, Map<String, Object> body) {
        String sign = hmacSign(creds.partnerKey(), creds.partnerId(),
            path, timestamp, accessToken, shopId);

        String url = SHOPEE_PARTNER_BASE + path
            + "?partner_id=" + creds.partnerId()
            + "&timestamp=" + timestamp
            + "&sign=" + sign;

        if (shopId != null) url += "&shop_id=" + shopId;

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, body, String.class);
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Shopee API call failed for path=" + path, e);
        }
    }

    /**
     * Shopee HMAC-SHA256 簽名。
     *
     * Public API: partner_id + path + timestamp
     * Shop API:   partner_id + path + timestamp + access_token + shop_id
     */
    private String hmacSign(String partnerKey, String partnerId, String path,
                             long timestamp, String accessToken, String shopId) {
        StringBuilder sb = new StringBuilder();
        sb.append(partnerId).append(path).append(timestamp);
        if (accessToken != null) sb.append(accessToken);
        if (shopId != null) sb.append(shopId);
        return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, partnerKey).hmacHex(sb.toString());
    }

    private ShopeeAppCredentials loadAppCredentials() {
        // Platform.id = "shopee" (not platformName — platformName is the display name like "Shopee蝦皮")
        Platform platform = platformRepository.findById("shopee")
            .orElseThrow(() -> new IllegalStateException("Shopee platform not configured in DB (id='shopee')"));
        return new ShopeeAppCredentials(platform.getCredential1(), platform.getCredential2());
    }

    private record ShopeeAppCredentials(String partnerId, String partnerKey) {}
}
