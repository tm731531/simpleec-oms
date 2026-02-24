package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

/**
 * 通路實體（e-commerce platform store configuration）
 */
@Entity
@Table(name = "channel")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Channel {
    @Id
    @Column(name = "id", length = 20)
    private String id;

    @Column(name = "platform_id", length = 20, nullable = false)
    private String platformId;

    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    @Column(name = "channel_sn", length = 128)
    private String channelSn;

    @Column(name = "channel_name", length = 256)
    private String channelName;

    @Column(name = "multi_spec", nullable = false)
    private Boolean multiSpec = false;

    @Column(name = "token", nullable = false)
    private String token;

    @Column(name = "token2", length = 4096)
    private String token2;

    @Column(name = "token3", length = 4096)
    private String token3;

    @Column(name = "token4", length = 4096)
    private String token4;

    @Column(name = "token5", length = 4096)
    private String token5;

    @Column(name = "actived", nullable = false)
    private Boolean actived = true;

    @Column(name = "write_actived", nullable = false)
    private Boolean writeActived = false;

    @Column(name = "enable_sync", nullable = false)
    private Boolean enableSync = false;

    @Column(name = "first_sync_start_time")
    private LocalDateTime firstSyncStartTime;

    @Column(name = "first_sync_end_time")
    private LocalDateTime firstSyncEndTime;

    @Column(name = "last_sync_time")
    private LocalDateTime lastSyncTime;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
