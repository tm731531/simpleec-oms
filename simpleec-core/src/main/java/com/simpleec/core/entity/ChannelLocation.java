package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

/**
 * 通路倉庫/位置（僅有 location 概念的平台才有資料，如 Shopify）
 *
 * 無 location 概念的平台（Shopee、Cyberbiz、Shopline 等）不建此表資料。
 *
 * is_sync_target = true → OMS 執行 UPDATE_INVENTORY 時推送到此 location。
 * 每個 channel 只能有一個 sync target（DB unique index 保證）。
 */
@Entity
@Table(name = "channel_location",
    indexes = {
        @Index(name = "idx_channel_location_channel", columnList = "channel_id"),
        @Index(name = "idx_channel_location_sync_target", columnList = "channel_id, is_sync_target")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelLocation {

    @Id
    @Column(name = "id", length = 20)
    private String id;

    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    @Column(name = "channel_id", length = 20, nullable = false)
    private String channelId;

    @Column(name = "platform_location_id", length = 256, nullable = false)
    private String platformLocationId;

    @Column(name = "location_name", length = 256)
    private String locationName;

    @Column(name = "is_sync_target", nullable = false)
    private boolean isSyncTarget = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
