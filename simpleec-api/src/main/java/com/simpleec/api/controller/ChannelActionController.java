package com.simpleec.api.controller;

import com.simpleec.common.enums.ChannelType;
import com.simpleec.common.model.ApiResponse;
import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/channel-actions")
@RequiredArgsConstructor
public class ChannelActionController {

    private final TaskProducer taskProducer;

    @PostMapping("/send")
    public ApiResponse<String> sendAction(
            @RequestParam String merchantId,
            @RequestParam String channelId,
            @RequestParam String channelType,
            @RequestParam String actionType,
            @RequestBody Map<String, Object> payload) {

        ChannelType type = ChannelType.fromCode(channelType);

        // Determine topic: fast actions go to {platform}.fast, slow to {platform}.slow
        boolean isFast = isFastAction(actionType);
        String topic = type.getCode() + (isFast ? ".fast" : ".slow");

        TaskMessage msg = TaskMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .taskType("channel_action")
                .taskAction(actionType)
                .sourceJobType("simpleec-api")
                .merchantId(merchantId)
                .ownerType("channel")
                .ownerId(channelId)
                .payload(payload)
                .createdAt(Instant.now())
                .retryCount(0)
                .build();

        taskProducer.send(topic, null, msg);
        return ApiResponse.ok(msg.getMessageId());
    }

    private boolean isFastAction(String action) {
        return switch (action) {
            case "MODIFY_PRICE", "MODIFY_QUANTITY", "START_SELLING", "STOP_SELLING",
                 "SHIPPING_CONFIRMED", "ACCEPT_BUYER_CANCELLATION", "REJECT_BUYER_CANCELLATION",
                 "CHECK_HEALTH" -> true;
            default -> false;
        };
    }
}
