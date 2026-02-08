package com.oneec.web.controller;

import com.oneec.channel.message.ChannelActionMessage;
import com.oneec.channel.message.ChannelActionProducer;
import com.oneec.common.enums.ActionType;
import com.oneec.common.enums.ChannelType;
import com.oneec.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/channel-actions")
@RequiredArgsConstructor
public class ChannelActionController {

    private final ChannelActionProducer producer;

    @PostMapping("/send")
    public ApiResponse<String> sendAction(
            @RequestParam Long merchantId,
            @RequestParam Long channelId,
            @RequestParam String channelType,
            @RequestParam String actionType,
            @RequestBody Map<String, Object> payload) {

        ChannelActionMessage message = ChannelActionMessage.create(
                merchantId, channelId,
                ChannelType.fromCode(channelType),
                ActionType.valueOf(actionType),
                payload
        );

        producer.send(message);
        return ApiResponse.ok(message.getMessageId());
    }
}
