package com.simpleec.schedulerjob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Health check message for platform-level checks
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformHealthCheckMessage {
    private String taskType;        // "CHECK_HEALTH_PLATFORM"
    private String platformCode;
    private Long timestamp;
}
