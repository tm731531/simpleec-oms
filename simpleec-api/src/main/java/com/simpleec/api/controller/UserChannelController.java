package com.simpleec.api.controller;

import com.simpleec.api.security.UserPrincipal;
import com.simpleec.api.service.ShopeeOAuthService;
import com.simpleec.api.vo.ChannelVO;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.repository.ChannelRepository;
import com.simpleec.core.repository.PlatformRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用戶通路管理控制器
 *
 * Token 欄位：回傳 ChannelVO（遮罩顯示），不回傳原始 token 值
 * OAuth 平台（如 Shopee）額外提供：auth-url / refresh-token / disconnect
 * 非 OAuth 平台：直接 PUT token1~5
 */
@Slf4j
@RestController
@RequestMapping("/api/user/channels")
@RequiredArgsConstructor
public class UserChannelController {

    private final ChannelRepository  channelRepository;
    private final PlatformRepository platformRepository;
    private final ShopeeOAuthService shopeeOAuthService;

    // ──────────────────────────────────────────────────────────
    // 基本 CRUD
    // ──────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<ChannelVO>> listChannels(
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<Channel> channels = channelRepository.findByMerchantId(principal.getMerchantId());
        List<ChannelVO> vos = channels.stream()
            .map(c -> ChannelVO.from(c, findPlatform(c.getPlatformId())))
            .toList();
        return ResponseEntity.ok(vos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChannelVO> getChannel(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return channelRepository.findById(id)
            .filter(c -> c.getMerchantId().equals(principal.getMerchantId()))
            .map(c -> ResponseEntity.ok(ChannelVO.from(c, findPlatform(c.getPlatformId()))))
            .orElse(ResponseEntity.notFound().build());
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
        channel.setMerchantId(principal.getMerchantId());
        Channel saved = channelRepository.save(channel);
        return ResponseEntity.ok(ChannelVO.from(saved, findPlatform(saved.getPlatformId())));
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
        return channelRepository.findById(id)
            .filter(c -> c.getMerchantId().equals(principal.getMerchantId()))
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
    }

    @PutMapping("/{id}/toggle-status")
    public ResponseEntity<ChannelVO> toggleStatus(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return channelRepository.findById(id)
            .filter(c -> c.getMerchantId().equals(principal.getMerchantId()))
            .map(channel -> {
                channel.setActived(!channel.getActived());
                Channel updated = channelRepository.save(channel);
                return ResponseEntity.ok(ChannelVO.from(updated, findPlatform(updated.getPlatformId())));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ──────────────────────────────────────────────────────────
    // Shopee OAuth 端點
    // ──────────────────────────────────────────────────────────

    /**
     * 生成 Shopee 授權 URL（前端用小視窗打開）。
     */
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

    /**
     * 手動強制刷新 Shopee token（不等排程）。
     */
    @PostMapping("/{id}/shopee/refresh-token")
    public ResponseEntity<ChannelVO> refreshShopeeToken(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return channelRepository.findById(id)
            .filter(c -> c.getMerchantId().equals(principal.getMerchantId()))
            .map(c -> {
                shopeeOAuthService.forceRefreshToken(id);
                // 重新讀 DB 取最新 token 狀態
                Channel updated = channelRepository.findById(id).orElse(c);
                return ResponseEntity.ok(ChannelVO.from(updated, findPlatform(updated.getPlatformId())));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 斷開 Shopee 授權（清空 token，停用同步）。
     */
    @PostMapping("/{id}/shopee/disconnect")
    public ResponseEntity<ChannelVO> disconnectShopee(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return channelRepository.findById(id)
            .filter(c -> c.getMerchantId().equals(principal.getMerchantId()))
            .map(c -> {
                shopeeOAuthService.disconnect(id);
                Channel updated = channelRepository.findById(id).orElse(c);
                return ResponseEntity.ok(ChannelVO.from(updated, findPlatform(updated.getPlatformId())));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ──────────────────────────────────────────────────────────
    // Helper
    // ──────────────────────────────────────────────────────────

    /** 依 platformId（如 "shopee"）查詢 Platform entity，找不到回傳 null（不阻斷主流程） */
    private Platform findPlatform(String platformId) {
        if (platformId == null) return null;
        return platformRepository.findByPlatformName(platformId).orElse(null);
    }
}
