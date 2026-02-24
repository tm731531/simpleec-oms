package com.simpleec.api.controller;

import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.repository.ChannelRepository;
import com.simpleec.core.repository.PlatformRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 用戶通路管理控制器
 */
@RestController
@RequestMapping("/api/user/channels")
@RequiredArgsConstructor
public class UserChannelController {
    private final ChannelRepository channelRepository;
    private final PlatformRepository platformRepository;

    @GetMapping
    public ResponseEntity<List<Channel>> listChannels(@AuthenticationPrincipal UserPrincipal principal) {
        List<Channel> channels = channelRepository.findByMerchantId(principal.getMerchantId());
        return ResponseEntity.ok(channels);
    }

    @GetMapping("/platforms")
    public ResponseEntity<?> listPlatforms() {
        var platforms = platformRepository.findByActived(true, PageRequest.of(0, 100))
            .map(p -> new Object() {
                public final String id = p.getId();
                public final String platformName = p.getPlatformName();
                public final String queueTopic = p.getQueueTopic();
            })
            .getContent();
        return ResponseEntity.ok(platforms);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Channel> getChannel(@PathVariable String id, @AuthenticationPrincipal UserPrincipal principal) {
        return channelRepository.findById(id)
            .filter(channel -> channel.getMerchantId().equals(principal.getMerchantId()))
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Channel> createChannel(
        @RequestBody Channel channel,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        channel.setMerchantId(principal.getMerchantId());
        Channel savedChannel = channelRepository.save(channel);
        return ResponseEntity.ok(savedChannel);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Channel> updateChannel(
        @PathVariable String id,
        @RequestBody Channel updateData,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return channelRepository.findById(id)
            .filter(channel -> channel.getMerchantId().equals(principal.getMerchantId()))
            .map(channel -> {
                if (updateData.getChannelName() != null) {
                    channel.setChannelName(updateData.getChannelName());
                }
                if (updateData.getChannelSn() != null) {
                    channel.setChannelSn(updateData.getChannelSn());
                }
                if (updateData.getToken() != null) {
                    channel.setToken(updateData.getToken());
                }
                if (updateData.getToken2() != null) {
                    channel.setToken2(updateData.getToken2());
                }
                if (updateData.getToken3() != null) {
                    channel.setToken3(updateData.getToken3());
                }
                if (updateData.getToken4() != null) {
                    channel.setToken4(updateData.getToken4());
                }
                if (updateData.getToken5() != null) {
                    channel.setToken5(updateData.getToken5());
                }
                Channel updated = channelRepository.save(channel);
                return ResponseEntity.ok(updated);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/toggle-status")
    public ResponseEntity<Channel> toggleStatus(
        @PathVariable String id,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return channelRepository.findById(id)
            .filter(channel -> channel.getMerchantId().equals(principal.getMerchantId()))
            .map(channel -> {
                channel.setActived(!channel.getActived());
                Channel updated = channelRepository.save(channel);
                return ResponseEntity.ok(updated);
            })
            .orElse(ResponseEntity.notFound().build());
    }
}
