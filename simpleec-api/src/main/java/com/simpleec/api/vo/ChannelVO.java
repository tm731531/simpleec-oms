package com.simpleec.api.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.entity.Platform;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 通路視圖對象 — token 欄位以遮罩形式回傳
 *
 * 規則：token1~5 顯示前4後4，中間以 "..." 代替
 * oauthStatus 僅 oauthFlow=shopee_oauth 平台才有值
 */
@Data
public class ChannelVO {

    private String id;
    private String merchantId;
    private String platformId;
    private String platformName;
    private String channelSn;
    private String channelName;
    private Boolean multiSpec;
    private Boolean actived;
    private Boolean writeActived;
    private Boolean enableSync;
    private LocalDateTime firstSyncStartTime;
    private LocalDateTime firstSyncEndTime;
    private LocalDateTime lastSyncTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Token 遮罩顯示值（前4...後4，空值為 null）
    private String token1Masked;
    private String token2Masked;
    private String token3Masked;
    private String token4Masked;
    private String token5Masked;

    // 平台 token 欄位語意標籤（來自 Platform.capabilities.tokenLabels）
    private JsonNode tokenLabels;

    // OAuth 相關（僅 oauthFlow=shopee_oauth 等 OAuth 平台）
    private String oauthFlow;
    private String oauthStatus;       // NOT_CONNECTED | CONNECTED | EXPIRING_SOON | EXPIRED
    private String tokenExpiresAt;    // ISO-8601，給前端算倒數
    private Long tokenExpiresInMinutes;

    public static ChannelVO from(Channel channel, Platform platform) {
        ChannelVO vo = new ChannelVO();

        vo.setId(channel.getId());
        vo.setMerchantId(channel.getMerchantId());
        vo.setPlatformId(channel.getPlatformId());
        vo.setPlatformName(platform != null ? platform.getPlatformName() : channel.getPlatformId());
        vo.setChannelSn(channel.getChannelSn());
        vo.setChannelName(channel.getChannelName());
        vo.setMultiSpec(channel.getMultiSpec());
        vo.setActived(channel.getActived());
        vo.setWriteActived(channel.getWriteActived());
        vo.setEnableSync(channel.getEnableSync());
        vo.setFirstSyncStartTime(channel.getFirstSyncStartTime());
        vo.setFirstSyncEndTime(channel.getFirstSyncEndTime());
        vo.setLastSyncTime(channel.getLastSyncTime());
        vo.setCreatedAt(channel.getCreatedAt());
        vo.setUpdatedAt(channel.getUpdatedAt());

        // Token 遮罩
        vo.setToken1Masked(mask(channel.getToken()));
        vo.setToken2Masked(mask(channel.getToken2()));
        vo.setToken3Masked(mask(channel.getToken3()));
        vo.setToken4Masked(mask(channel.getToken4()));
        vo.setToken5Masked(mask(channel.getToken5()));

        // Platform capabilities
        if (platform != null && platform.getCapabilities() != null) {
            JsonNode caps = platform.getCapabilities();
            vo.setOauthFlow(caps.path("oauthFlow").asText(null));
            vo.setTokenLabels(caps.path("tokenLabels").isMissingNode() ? null : caps.path("tokenLabels"));
        }

        // OAuth 狀態（僅 OAuth 平台計算）
        if ("shopee_oauth".equals(vo.getOauthFlow())) {
            vo.setOauthStatus(computeOauthStatus(channel));
            if (channel.getToken4() != null && !channel.getToken4().isBlank()) {
                try {
                    Instant expiresAt = Instant.parse(channel.getToken4());
                    vo.setTokenExpiresAt(expiresAt.toString());
                    long minutesLeft = ChronoUnit.MINUTES.between(Instant.now(), expiresAt);
                    vo.setTokenExpiresInMinutes(minutesLeft);
                } catch (Exception ignored) {
                    // token4 不是有效的 ISO-8601 Instant，跳過
                }
            }
        }

        return vo;
    }

    /**
     * Token 遮罩：前4...後4
     * ≤ 4 chars → 全顯示（shop_id 等短 ID）
     * 5-8 chars → 前2...後2
     * > 8 chars → 前4...後4
     */
    private static String mask(String token) {
        if (token == null || token.isBlank()) return null;
        int len = token.length();
        if (len <= 4) return token;
        if (len <= 8) return token.substring(0, 2) + "..." + token.substring(len - 2);
        return token.substring(0, 4) + "..." + token.substring(len - 4);
    }

    private static String computeOauthStatus(Channel channel) {
        if (channel.getToken() == null || channel.getToken().isBlank()) {
            return "NOT_CONNECTED";
        }
        if (channel.getToken4() == null || channel.getToken4().isBlank()) {
            return "CONNECTED"; // 有 token 但無到期時間 → 樂觀假設有效
        }
        try {
            Instant expiresAt = Instant.parse(channel.getToken4());
            long minutesLeft = ChronoUnit.MINUTES.between(Instant.now(), expiresAt);
            if (minutesLeft > 60) return "CONNECTED";
            if (minutesLeft > 0) return "EXPIRING_SOON";
            return "EXPIRED";
        } catch (Exception e) {
            return "CONNECTED";
        }
    }
}
