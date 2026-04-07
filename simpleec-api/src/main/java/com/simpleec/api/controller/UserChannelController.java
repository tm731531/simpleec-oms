package com.simpleec.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.api.entity.ChannelSyncLog;
import com.simpleec.api.repository.ChannelSyncLogRepository;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.api.service.ShopeeOAuthService;
import com.simpleec.api.vo.ChannelVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.core.crypto.EncryptionContext;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.repository.ChannelRepository;
import com.simpleec.core.repository.PlatformRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 用戶通路管理控制器
 *
 * Token 欄位：回傳 ChannelVO（遮罩顯示），不回傳原始 token 值
 * OAuth 平台（如 Shopee）額外提供：auth-url / refresh-token / disconnect
 * 非 OAuth 平台：直接 PUT token1~5
 *
 * ⚠️ 所有讀取/寫入加密欄位的操作必須包在 EncryptionContext.setMerchantId / clear() 之間
 */
@Slf4j
@RestController
@RequestMapping("/api/user/channels")
@RequiredArgsConstructor
public class UserChannelController {

    private static final String HEALTH_CACHE_PREFIX   = "channel:health:";
    private static final String PLATFORM_CACHE_PREFIX = "platform:health:";

    private final ChannelRepository       channelRepository;
    private final PlatformRepository      platformRepository;
    private final ShopeeOAuthService      shopeeOAuthService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ChannelSyncLogRepository syncLogRepository;
    private final StringRedisTemplate     redisTemplate;
    private final ObjectMapper            objectMapper;

    // ──────────────────────────────────────────────────────────
    // 基本 CRUD
    // ──────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<ChannelVO>> listChannels(
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        String merchantId = principal.getMerchantId();
        EncryptionContext.setMerchantId(merchantId);
        try {
            List<Channel> channels = channelRepository.findByMerchantId(merchantId);
            List<ChannelVO> vos = channels.stream()
                .map(c -> ChannelVO.from(c, findPlatform(c.getPlatformId())))
                .toList();
            return ResponseEntity.ok(vos);
        } finally {
            EncryptionContext.clear();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChannelVO> getChannel(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        String merchantId = principal.getMerchantId();
        EncryptionContext.setMerchantId(merchantId);
        try {
            return channelRepository.findById(id)
                .filter(c -> c.getMerchantId().equals(merchantId))
                .map(c -> ResponseEntity.ok(ChannelVO.from(c, findPlatform(c.getPlatformId()))))
                .orElse(ResponseEntity.notFound().build());
        } finally {
            EncryptionContext.clear();
        }
    }

    @GetMapping("/platforms")
    public ResponseEntity<?> listPlatforms() {
        var platforms = platformRepository.findByActived(true, PageRequest.of(0, 100))
            .map(p -> new Object() {
                public final String id           = p.getId();
                public final String platformName = p.getPlatformName();
                public final String queueTopic   = p.getQueueTopic();
                public final Object capabilities = p.getCapabilities();
            })
            .getContent();
        return ResponseEntity.ok(platforms);
    }

    @PostMapping
    public ResponseEntity<ChannelVO> createChannel(
        @RequestBody Channel channel,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        String merchantId = principal.getMerchantId();
        channel.setMerchantId(merchantId);
        EncryptionContext.setMerchantId(merchantId);
        try {
            Channel saved = channelRepository.save(channel);
            return ResponseEntity.ok(ChannelVO.from(saved, findPlatform(saved.getPlatformId())));
        } finally {
            EncryptionContext.clear();
        }
    }

    /**
     * 更新通路設定，包含 token1~5（通用，適用所有平台）。
     * OAuth 平台的 token 可直接在此填入（如需繞過 OAuth flow 手動修正）。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ChannelVO> updateChannel(
        @PathVariable String id,
        @RequestBody Channel updateData,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        String merchantId = principal.getMerchantId();
        EncryptionContext.setMerchantId(merchantId);
        try {
            return channelRepository.findById(id)
                .filter(c -> c.getMerchantId().equals(merchantId))
                .map(channel -> {
                    if (updateData.getChannelName() != null)  channel.setChannelName(updateData.getChannelName());
                    if (updateData.getChannelSn() != null)    channel.setChannelSn(updateData.getChannelSn());
                    if (updateData.getToken() != null)        channel.setToken(updateData.getToken());
                    if (updateData.getToken2() != null)       channel.setToken2(updateData.getToken2());
                    if (updateData.getToken3() != null)       channel.setToken3(updateData.getToken3());
                    if (updateData.getToken4() != null)       channel.setToken4(updateData.getToken4());
                    if (updateData.getToken5() != null)       channel.setToken5(updateData.getToken5());
                    if (updateData.getWriteActived() != null) channel.setWriteActived(updateData.getWriteActived());
                    if (updateData.getEnableSync() != null)   channel.setEnableSync(updateData.getEnableSync());
                    Channel updated = channelRepository.save(channel);
                    return ResponseEntity.ok(ChannelVO.from(updated, findPlatform(updated.getPlatformId())));
                })
                .orElse(ResponseEntity.notFound().build());
        } finally {
            EncryptionContext.clear();
        }
    }

    @PutMapping("/{id}/toggle-status")
    public ResponseEntity<ChannelVO> toggleStatus(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        String merchantId = principal.getMerchantId();
        EncryptionContext.setMerchantId(merchantId);
        try {
            return channelRepository.findById(id)
                .filter(c -> c.getMerchantId().equals(merchantId))
                .map(channel -> {
                    channel.setActived(!channel.getActived());
                    Channel updated = channelRepository.save(channel);
                    return ResponseEntity.ok(ChannelVO.from(updated, findPlatform(updated.getPlatformId())));
                })
                .orElse(ResponseEntity.notFound().build());
        } finally {
            EncryptionContext.clear();
        }
    }

    // ──────────────────────────────────────────────────────────
    // Shopee OAuth 端點
    // ──────────────────────────────────────────────────────────

    @GetMapping("/{id}/shopee/auth-url")
    public ResponseEntity<Map<String, String>> getShopeeAuthUrl(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return channelRepository.findById(id)
            .filter(c -> c.getMerchantId().equals(principal.getMerchantId()))
            .map(c -> {
                String authUrl = shopeeOAuthService.generateAuthUrl(id);
                return ResponseEntity.ok(Map.of("authUrl", authUrl));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/shopee/refresh-token")
    public ResponseEntity<ChannelVO> refreshShopeeToken(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        String merchantId = principal.getMerchantId();
        EncryptionContext.setMerchantId(merchantId);
        try {
            return channelRepository.findById(id)
                .filter(c -> c.getMerchantId().equals(merchantId))
                .map(c -> {
                    shopeeOAuthService.forceRefreshToken(id);
                    Channel updated = channelRepository.findById(id).orElse(c);
                    return ResponseEntity.ok(ChannelVO.from(updated, findPlatform(updated.getPlatformId())));
                })
                .orElse(ResponseEntity.notFound().build());
        } finally {
            EncryptionContext.clear();
        }
    }

    @PostMapping("/{id}/shopee/disconnect")
    public ResponseEntity<ChannelVO> disconnectShopee(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        String merchantId = principal.getMerchantId();
        EncryptionContext.setMerchantId(merchantId);
        try {
            return channelRepository.findById(id)
                .filter(c -> c.getMerchantId().equals(merchantId))
                .map(c -> {
                    shopeeOAuthService.disconnect(id);
                    Channel updated = channelRepository.findById(id).orElse(c);
                    return ResponseEntity.ok(ChannelVO.from(updated, findPlatform(updated.getPlatformId())));
                })
                .orElse(ResponseEntity.notFound().build());
        } finally {
            EncryptionContext.clear();
        }
    }

    // ──────────────────────────────────────────────────────────
    // 同步日誌
    // ──────────────────────────────────────────────────────────

    /**
     * 查詢通路的同步日誌（分頁，僅限自己的通路）
     */
    @GetMapping("/{id}/sync-logs")
    public ResponseEntity<?> getSyncLogs(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        String merchantId = principal.getMerchantId();
        return channelRepository.findById(id)
            .filter(c -> c.getMerchantId().equals(merchantId))
            .map(channel -> {
                var pageable = PageRequest.of(page - 1, pageSize, org.springframework.data.domain.Sort.by("createdAt").descending());
                var logsPage = syncLogRepository.findByChannelIdOrderByCreatedAtDesc(id, pageable);
                Map<String, Object> result = new HashMap<>();
                result.put("data", logsPage.getContent());
                result.put("pagination", Map.of(
                    "total", logsPage.getTotalElements(),
                    "pages", logsPage.getTotalPages(),
                    "page", page,
                    "pageSize", pageSize
                ));
                return ResponseEntity.ok(result);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查詢特定平台的同步日誌（包含所有通路 + 平台檢查）
     */
    @GetMapping("/platforms/{platformId}/sync-logs")
    public ResponseEntity<?> getPlatformSyncLogs(
        @PathVariable String platformId,
        @AuthenticationPrincipal UserPrincipal principal,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        String merchantId = principal.getMerchantId();
        var pageable = PageRequest.of(page - 1, pageSize, org.springframework.data.domain.Sort.by("createdAt").descending());
        var logsPage = syncLogRepository.findByPlatformIdOrderByCreatedAtDesc(platformId, pageable);

        Map<String, Object> result = new HashMap<>();
        result.put("data", logsPage.getContent());
        result.put("pagination", Map.of(
            "total", logsPage.getTotalElements(),
            "pages", logsPage.getTotalPages(),
            "page", page,
            "pageSize", pageSize
        ));
        return ResponseEntity.ok(result);
    }

    /**
     * 通路健康狀態一覽（從 Redis cache 讀取）
     * cache key: channel:health:{channelId}，TTL 10分鐘
     * cache 不存在 → health=unknown（表示尚未檢查或已過期）
     */
    @GetMapping("/health-overview")
    public ResponseEntity<List<Map<String, Object>>> healthOverview(
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        String merchantId = principal.getMerchantId();
        List<Channel> channels = channelRepository.findByMerchantId(merchantId);

        EncryptionContext.setMerchantId(merchantId);
        try {
            List<Map<String, Object>> result = channels.stream().map(ch -> {
                Map<String, Object> row = new HashMap<>();
                row.put("channelId",   ch.getId());
                row.put("channelName", ch.getChannelName());
                row.put("channelSn",   ch.getChannelSn());
                row.put("actived",     ch.getActived());

                // platformName
                String platformId = ch.getPlatformId();
                String platformName = findPlatform(platformId) != null
                    ? findPlatform(platformId).getPlatformName() : platformId;
                row.put("platformName", platformName);

                // 通路層 Redis cache (channel:health:{channelId})
                try {
                    String cached = redisTemplate.opsForValue().get(HEALTH_CACHE_PREFIX + ch.getId());
                    if (cached != null) {
                        Map<?, ?> cacheData = objectMapper.readValue(cached, Map.class);
                        row.put("health",       cacheData.get("health"));
                        row.put("httpStatus",   cacheData.get("httpStatus"));
                        row.put("checkedAt",    cacheData.get("checkedAt"));
                        row.put("errorMessage", cacheData.get("errorMessage"));
                    } else {
                        row.put("health",       "unknown");
                        row.put("httpStatus",   null);
                        row.put("checkedAt",    null);
                        row.put("errorMessage", null);
                    }
                } catch (Exception e) {
                    row.put("health", "unknown");
                }

                // 平台層 Redis cache (platform:health:{platformId})
                // 用途：區分「帳號掛了」vs「整個平台掛了」
                try {
                    String platformCached = redisTemplate.opsForValue().get(PLATFORM_CACHE_PREFIX + platformId);
                    if (platformCached != null) {
                        Map<?, ?> pd = objectMapper.readValue(platformCached, Map.class);
                        row.put("platformHealth",        pd.get("health"));
                        row.put("platformHttpStatus",    pd.get("httpStatus"));
                        row.put("platformCheckedAt",     pd.get("checkedAt"));
                        row.put("platformErrorMessage",  pd.get("errorMessage"));
                    } else {
                        row.put("platformHealth",        "unknown");
                        row.put("platformHttpStatus",    null);
                        row.put("platformCheckedAt",     null);
                        row.put("platformErrorMessage",  null);
                    }
                } catch (Exception e) {
                    row.put("platformHealth", "unknown");
                }

                return row;
            }).toList();

            return ResponseEntity.ok(result);
        } finally {
            EncryptionContext.clear();
        }
    }

    // ──────────────────────────────────────────────────────────
    // 同步操作
    // ──────────────────────────────────────────────────────────

    /**
     * 觸發通路 SellPack 同步
     * 發送 SYNC_PACK 任務到 {platform}.slow topic
     * Channel Job 會拉取通路商品目錄並寫入 DB
     */
    @PostMapping("/{id}/sync-sellpack")
    public ResponseEntity<Map<String, String>> syncSellPack(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        String merchantId = principal.getMerchantId();
        return channelRepository.findById(id)
            .filter(c -> c.getMerchantId().equals(merchantId))
            .map(channel -> {
                Optional<Platform> platformOpt = platformRepository.findById(channel.getPlatformId());
                if (platformOpt.isEmpty()) {
                    return ResponseEntity.badRequest()
                        .<Map<String, String>>body(Map.of("error", "Platform not found"));
                }
                String platformCode = channel.getPlatformId();
                String topic = TopicConstants.platformSlowTopic(platformCode);

                Map<String, Object> header = new HashMap<>();
                header.put("taskType", "SYNC_PACK");
                header.put("merchantId", merchantId);
                header.put("platformId", platformCode);
                header.put("channelId", id);
                header.put("requestId", UUID.randomUUID().toString());
                header.put("timestamp", Instant.now().toString());
                header.put("source", "api");
                header.put("version", 1);
                header.put("isRollback", false);

                Map<String, Object> message = new HashMap<>();
                message.put("header", header);
                message.put("body", Map.of());

                kafkaTemplate.send(topic, id, message);
                log.info("SYNC_PACK triggered: channel={}, platform={}, topic={}", id, platformCode, topic);

                return ResponseEntity.accepted()
                    .<Map<String, String>>body(Map.of("message", "同步已觸發", "topic", topic));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 觸發所有通路健康檢查
     * 對每個啟用通路發送 CHECK_HEALTH 到對應 {platform}.slow topic
     * 同時對每個唯一平台發送 CHECK_HEALTH_PLATFORM
     * Channel Job 執行後寫入 Redis cache，前端再呼叫 /health-overview 取得結果
     */
    @PostMapping("/trigger-health-check")
    public ResponseEntity<Map<String, Object>> triggerHealthCheck(
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        String merchantId = principal.getMerchantId();
        List<Channel> channels = channelRepository.findByMerchantId(merchantId);

        int channelCount = 0;
        java.util.Set<String> platformsSent = new java.util.HashSet<>();

        for (Channel channel : channels) {
            if (!Boolean.TRUE.equals(channel.getActived())) continue;

            String platformCode = channel.getPlatformId();
            String topic = TopicConstants.platformFastTopic(platformCode);

            // CHECK_HEALTH：通路層健康檢查
            Map<String, Object> header = new HashMap<>();
            header.put("taskType", "CHECK_HEALTH");
            header.put("merchantId", merchantId);
            header.put("platformId", platformCode);
            header.put("channelId", channel.getId());
            header.put("requestId", UUID.randomUUID().toString());
            header.put("timestamp", Instant.now().toString());
            header.put("source", "api");
            header.put("version", 1);
            header.put("isRollback", false);

            Map<String, Object> message = new HashMap<>();
            message.put("header", header);
            message.put("body", Map.of());
            kafkaTemplate.send(topic, channel.getId(), message);
            channelCount++;

            // CHECK_HEALTH_PLATFORM：平台層健康檢查（每個平台只送一次）
            if (!platformsSent.contains(platformCode)) {
                Map<String, Object> platformHeader = new HashMap<>();
                platformHeader.put("taskType", "CHECK_HEALTH_PLATFORM");
                platformHeader.put("merchantId", merchantId);
                platformHeader.put("platformId", platformCode);
                platformHeader.put("channelId", "");
                platformHeader.put("requestId", UUID.randomUUID().toString());
                platformHeader.put("timestamp", Instant.now().toString());
                platformHeader.put("source", "api");
                platformHeader.put("version", 1);
                platformHeader.put("isRollback", false);

                Map<String, Object> platformMessage = new HashMap<>();
                platformMessage.put("header", platformHeader);
                platformMessage.put("body", Map.of());
                kafkaTemplate.send(topic, platformCode, platformMessage);
                platformsSent.add(platformCode);
            }
        }

        log.info("Health check triggered: merchantId={}, channels={}, platforms={}",
            merchantId, channelCount, platformsSent);

        return ResponseEntity.accepted().body(Map.of(
            "channelsTriggered", channelCount,
            "platformsTriggered", platformsSent.size()
        ));
    }

    // ──────────────────────────────────────────────────────────
    // Helper
    // ──────────────────────────────────────────────────────────

    /**
     * 依 platformId（Channel.platformId 欄位值，例如 "shopee"）查詢 Platform entity。
     * Channel.platformId = Platform.id（非 Platform.platformName）。
     */
    private Platform findPlatform(String platformId) {
        if (platformId == null) return null;
        return platformRepository.findById(platformId).orElse(null);
    }
}
