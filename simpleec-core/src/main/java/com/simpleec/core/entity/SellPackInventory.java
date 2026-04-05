package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

/**
 * 賣場檔各平台庫存快照（per sell_pack × per location）
 *
 * 無 location 概念的平台（Shopee、Cyberbiz 等）：
 *   - 一筆，channel_location_id = NULL
 *
 * 有 location 概念的平台（Shopify）：
 *   - 多筆，每個倉庫一筆，channel_location_id → channel_location
 *
 * 此表是平台庫存的「同步快照」，sell_pack.quantity 仍為 OMS 主庫存。
 */
@Entity
@Table(name = "sell_pack_inventory",
    indexes = {
        @Index(name = "idx_sell_pack_inventory_pack", columnList = "sell_pack_id")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SellPackInventory {

    @Id
    @Column(name = "id", length = 20)
    private String id;

    @Column(name = "sell_pack_id", length = 20, nullable = false)
    private String sellPackId;

    /**
     * NULL = 平台無 location 概念（Shopee、Cyberbiz、Shopline 等）
     * Non-NULL = 有 location 概念（Shopify），FK → channel_location
     */
    @Column(name = "channel_location_id", length = 20)
    private String channelLocationId;

    @Column(name = "quantity", nullable = false)
    private int quantity = 0;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
