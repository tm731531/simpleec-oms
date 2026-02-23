package com.simpleec.channeljob.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents incoming message from Kafka
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelMessage {
    private String merchantId;
    private String channelId;
    private String platformCode;
    private String taskType;
    private String orderId;
    private Long timestamp;
    private String payload;
}
