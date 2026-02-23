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
           @Index(name = "idx_sync_log_merchant", columnList = "merchant_id, created_at DESC")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelSyncLog {
    @Id
    private String id;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = true)
    private String channelId;

    @Column(nullable = false)
    private Integer httpStatus;

    @Column(nullable = false)
    private String status; // success, failed, error, skipped

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

    /**
     * Computed property: health status based on HTTP status
     */
    @Transient
    public String getHealth() {
        return httpStatus >= 400 ? "unhealthy" : "healthy";
    }
}
