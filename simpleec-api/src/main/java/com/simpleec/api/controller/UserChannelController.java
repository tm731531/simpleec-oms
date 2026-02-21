package com.simpleec.api.controller;

import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 用戶通路管理控制器
 */
@RestController
@RequestMapping("/user/channels")
@RequiredArgsConstructor
public class UserChannelController {
    private final ChannelRepository channelRepository;

    @GetMapping
    public ResponseEntity<List<Channel>> listChannels(@AuthenticationPrincipal UserPrincipal principal) {
        List<Channel> channels = channelRepository.findByMerchantIdAndActivedTrue(principal.getMerchantId());
        return ResponseEntity.ok(channels);
    }
}
