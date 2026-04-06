package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.repository.ChannelRepository;
import com.simpleec.core.repository.PlatformRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shopee Token 定時刷新處理器
 *
 * 每小時整點由 Scheduler 派發 SHOPEE_TOKEN_REFRESH 觸發。
 * 掃描所有啟用的 Shopee 通路，對 token_expires_at < now+90min 的通路執行刷新。
 *
 * 刷新使用 Redis distributed lock（key: shopee:token:refresh:lock:{channelId}，TTL 30s）
 * 確保多 instance 不並發刷新同一 channel。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopeeTokenRefreshHandler extends AbstractEventHandler {

    private static final String SHOPEE_BASE        = "https://partner.shopeemobile.com";
    private static final String TOKEN_REFRESH_PATH = "/api/v2/auth/access_token/get";
    private static final String LOCK_PREFIX        = "shopee:token:refresh:lock:";
    private static final Duration LOCK_TTL         = Duration.ofSeconds(30);

    /** 提前刷新閾值：token 到期前 90 分鐘觸發 */
    private static final long REFRESH_BEFORE_MINUTES = 90;

    private final ChannelRepository   channelRepository;
    private final PlatformRepository  platformRepository;
    private final StringRedisTemplate redisTemplate;
    private final RestTemplate        restTemplate;
    private final ObjectMapper        objectMapper;

    @Override
    public String getTaskType() {
        return "SHOPEE_TOKEN_REFRESH";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        // Platform.id = "shopee" (not platformName)
        Platform platform = platformRepository.findById("shopee").orElse(null);
        if (platform == null) {
            log.warn("Shopee platform not configured (id='shopee'), skipping token refresh");
            return;
        }

        List<Channel> channels = channelRepository.findByPlatformIdAndActivedTrue("shopee");
        if (channels.isEmpty()) {
            log.debug("No active Shopee channels found");
            return;
        }

        log.info("SHOPEE_TOKEN_REFRESH: scanning {} channels", channels.size());
        int refreshed = 0;
        int skipped   = 0;

        for (Channel channel : channels) {
            if (needsRefresh(channel)) {
                try {
                    refreshWithLock(channel, platform);
                    refreshed++;
                } catch (Exception e) {
                    log.error("Token refresh failed for channel={}", channel.getId(), e);
                }
            } else {
                skipped++;
            }
        }

        log.info("SHOPEE_TOKEN_REFRESH complete: refreshed={} skipped={}", refreshed, skipped);
    }

    /**
     * 判斷是否需要刷新：token_expires_at < now + 90 分鐘
     */
    private boolean needsRefresh(Channel channel) {
        if (channel.getToken2() == null || channel.getToken2().isBlank()) {
            log.debug("Channel {} has no refresh token, skipping", channel.getId());
            return false;
        }
        if (channel.getToken4() == null || channel.getToken4().isBlank()) {
            // 無到期時間記錄 → 樂觀假設需要刷新（可能是舊資料）
            return true;
        }
        try {
            Instant expiresAt = Instant.parse(channel.getToken4());
            long minutesLeft = ChronoUnit.MINUTES.between(Instant.now(), expiresAt);
            return minutesLeft < REFRESH_BEFORE_MINUTES;
        } catch (Exception e) {
            log.warn("Cannot parse token4 for channel={}, will refresh", channel.getId());
            return true;
        }
    }

    /**
     * 帶 Redis distributed lock 的刷新，防止多 instance 並發互蓋。
     */
    private void refreshWithLock(Channel channel, Platform platform) {
        String lockKey = LOCK_PREFIX + channel.getId();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Lock held for channel={}, skipping", channel.getId());
            return;
        }
        try {
            doRefresh(channel, platform);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private void doRefresh(Channel channel, Platform platform) {
        String refreshToken = channel.getToken2();
        String shopId       = channel.getToken3();
        String accessToken  = channel.getToken();
        String partnerId    = platform.getCredential1();
        String partnerKey   = platform.getCredential2();

        long timestamp = Instant.now().getEpochSecond();

        Map<String, Object> body = new HashMap<>();
        body.put("refresh_token", refreshToken);
        body.put("shop_id", Long.parseLong(shopId));
        body.put("partner_id", Long.parseLong(partnerId));

        String sign = hmacSign(partnerKey, partnerId, TOKEN_REFRESH_PATH, timestamp, accessToken, shopId);
        String url  = SHOPEE_BASE + TOKEN_REFRESH_PATH
            + "?partner_id=" + partnerId
            + "&timestamp=" + timestamp
            + "&sign=" + sign
            + "&shop_id=" + shopId;

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, body, String.class);
            JsonNode response = objectMapper.readTree(resp.getBody());

            String error = response.path("error").asText("");
            if (!error.isBlank()) {
                log.error("Shopee refresh API error for channel={}: {} — {}",
                    channel.getId(), error, response.path("message").asText(""));
                return;
            }

            String newAccessToken  = response.path("access_token").asText();
            String newRefreshToken = response.path("refresh_token").asText();
            long   expireIn        = response.path("expire_in").asLong(14400);

            channel.setToken(newAccessToken);
            channel.setToken2(newRefreshToken);
            channel.setToken4(Instant.now().plusSeconds(expireIn).toString());
            channelRepository.save(channel);

            log.info("Token refreshed for channel={} shopId={} expiresIn={}s",
                channel.getId(), shopId, expireIn);

        } catch (Exception e) {
            throw new RuntimeException("Token refresh HTTP failed for channel=" + channel.getId(), e);
        }
    }

    private String hmacSign(String partnerKey, String partnerId, String path,
                             long timestamp, String accessToken, String shopId) {
        StringBuilder sb = new StringBuilder();
        sb.append(partnerId).append(path).append(timestamp);
        if (accessToken != null) sb.append(accessToken);
        if (shopId != null) sb.append(shopId);
        return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, partnerKey).hmacHex(sb.toString());
    }
}
