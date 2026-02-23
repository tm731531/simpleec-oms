package com.simpleec.schedulerjob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Health check message for channel-level checks
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HealthCheckMessage {
    private String taskType;        // "CHECK_HEALTH"
    private String merchantId;
    private String channelId;
    private String platformCode;
    private Long timestamp;
}
