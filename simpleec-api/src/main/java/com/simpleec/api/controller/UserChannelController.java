package com.simpleec.api.controller;

import com.simpleec.api.security.UserPrincipal;
import com.simpleec.api.service.ShopeeOAuthService;
import com.simpleec.api.vo.ChannelVO;
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

    private final ChannelRepository  channelRepository;
    private final PlatformRepository platformRepository;
    private final ShopeeOAuthService shopeeOAuthService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

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
                Platform platform = platformOpt.get();
                String platformCode = platform.getPlatformName().toLowerCase();
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
