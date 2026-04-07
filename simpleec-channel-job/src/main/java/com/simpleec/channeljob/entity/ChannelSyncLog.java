package com.simpleec.channeljob.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Channel sync log - tracks health check and sync status
 */
@Entity
@Table(name = "channel_sync_logs",
       indexes = {
           @Index(name = "idx_sync_log_channel", columnList = "channel_id, created_at DESC"),
           @Index(name = "idx_sync_log_platform", columnList = "platform_id, created_at DESC")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelSyncLog {
    @Id
    private String id;

    @Column(nullable = true)
    private String channelId;

    @Column(nullable = true, name = "platform_id")
    private String platformId;

    @Column(nullable = false, name = "sync_type")
    private String syncType; // e.g., "CHANNEL_HEALTH_CHECK", "PLATFORM_HEALTH_CHECK", "ORDER_SYNC"

    @Column(nullable = true)
    private Integer httpStatus;

    @Column(nullable = false)
    private String status; // success, failed, error, skipped

    @Column(nullable = true)
    private String errorMessage;

    @Column(nullable = true)
    private String health; // "healthy" or "unhealthy"

    @Column(name = "request_payload", columnDefinition = "TEXT")
    private String requestPayload;

    @Column(name = "response_payload", columnDefinition = "TEXT")
    private String responsePayload;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
