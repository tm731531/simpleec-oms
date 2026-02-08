package com.oneec.channel.message;

import com.oneec.common.enums.ActionType;
import com.oneec.common.enums.ChannelType;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
public class ChannelActionMessage implements Serializable {

    private String messageId;
    private Long merchantId;
    private Long channelId;
    private ChannelType channelType;
    private ActionType actionType;
    private Map<String, Object> payload;
    private LocalDateTime createdAt;
    private int retryCount;

    public static ChannelActionMessage create(Long merchantId, Long channelId,
                                               ChannelType channelType, ActionType actionType,
                                               Map<String, Object> payload) {
        ChannelActionMessage msg = new ChannelActionMessage();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setMerchantId(merchantId);
        msg.setChannelId(channelId);
        msg.setChannelType(channelType);
        msg.setActionType(actionType);
        msg.setPayload(payload);
        msg.setCreatedAt(LocalDateTime.now());
        msg.setRetryCount(0);
        return msg;
    }
}
