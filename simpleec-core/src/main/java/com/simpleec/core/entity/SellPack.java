package com.simpleec.core.entity;

import com.simpleec.core.converter.SyncStatusConverter;
import com.simpleec.core.dto.SyncStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品上架實體（Product x Channel listing mapping）
 * Represents a single product's listing on a specific e-commerce platform
 */
@Entity
@Table(name = "sell_pack",
    indexes = {
        @Index(name = "idx_sellpack_merchant", columnList = "merchant_id"),
        @Index(name = "idx_sellpack_channel", columnList = "channel_id"),
        @Index(name = "idx_sellpack_product", columnList = "product_id")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SellPack {
    @Id
    @Column(name = "id", length = 20)
    private String id;

    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    @Column(name = "product_id", length = 20, nullable = false)
    private String productId;

    @Column(name = "channel_id", length = 20, nullable = false)
    private String channelId;

    @Column(name = "sku", length = 100, nullable = false)
    private String sku;

    @Column(name = "channel_product_id", length = 256)
    private String channelProductId;

    @Column(name = "channel_spec_id", length = 256)
    private String channelSpecId;

    @Column(name = "channel_product_name", length = 512)
    private String channelProductName;

    @Column(name = "channel_spec_name", length = 256)
    private String channelSpecName;

    @Column(name = "channel_product_url", length = 1024)
    private String channelProductUrl;

    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "selling_price")
    private BigDecimal sellingPrice;

    @Column(name = "quantity", nullable = false)
    private Integer quantity = 0;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "draft";

    @Column(name = "visibility", length = 20)
    private String visibility;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Convert(converter = SyncStatusConverter.class)
    @Column(name = "sync_status", columnDefinition = "jsonb")
    private SyncStatus syncStatus;

    /**
     * 通路規格屬性（顏色、尺寸等平台特定欄位，JSONB 存放）
     */
    @Column(name = "channel_spec_attrs", columnDefinition = "jsonb")
    private String channelSpecAttrs;

    /**
     * 平台特定 metadata（以平台名稱為 key）
     * 用於存放無法塞入通用欄位的平台 ID。
     * 例如 Shopify: { "shopify": { "inventory_item_id": "457924702", "location_id": "905684977" } }
     */
    @Column(name = "platform_metadata", columnDefinition = "jsonb")
    private String platformMetadata;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
