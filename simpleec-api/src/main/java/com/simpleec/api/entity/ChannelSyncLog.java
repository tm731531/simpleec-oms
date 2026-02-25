package com.simpleec.api.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Channel sync log - tracks health check and sync status
 * 注：与 simpleec-channel-job 的 ChannelSyncLog entity 相同
 *
 * platformId 是必填的，merchantId 和 channelId 是可選的：
 * - 平台級檢查（PLATFORM_HEALTH_CHECK）：只需 platformId
 * - 通路級檢查（CHANNEL_HEALTH_CHECK）：platformId + merchantId + channelId
 */
@Entity
@Table(name = "channel_sync_logs",
       indexes = {
           @Index(name = "idx_sync_log_platform", columnList = "platform_id, created_at DESC"),
           @Index(name = "idx_sync_log_channel", columnList = "channel_id, created_at DESC"),
           @Index(name = "idx_sync_log_merchant", columnList = "merchant_id, created_at DESC")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelSyncLog {
    @Id
    private String id;

    @Column(nullable = false, name = "platform_id")
    private String platformId;  // FK to platform(id) - 必填

    @Column(nullable = true, name = "merchant_id")
    private String merchantId;  // FK to merchant(id) - 可選

    @Column(nullable = true, name = "channel_id")
    private String channelId;   // FK to channel(id) - 可選

    @Column(nullable = false, name = "sync_type")
    private String syncType;    // e.g., "CHANNEL_HEALTH_CHECK", "PLATFORM_HEALTH_CHECK", "ORDER_SYNC"

    @Column(nullable = true, name = "http_status")
    private Integer httpStatus; // HTTP status (200, 401, 404, 500, etc.)

    @Column(nullable = false)
    private String status;      // success, failed, error, skipped

    @Column(nullable = true)
    private String health;      // "healthy" or "unhealthy"

    @Column(nullable = true)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
