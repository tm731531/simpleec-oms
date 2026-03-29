package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "shipment_items", indexes = {
    @Index(name = "idx_shipment_items_shipment", columnList = "shipment_id"),
    @Index(name = "idx_shipment_items_order",    columnList = "order_id"),
    @Index(name = "idx_shipment_items_merchant", columnList = "merchant_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentItem {

    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    @Column(name = "shipment_id", length = 20, nullable = false)
    private String shipmentId;

    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    @Column(name = "order_id", length = 20, nullable = false)
    private String orderId;

    @Column(name = "channel_order_id", length = 100)
    private String channelOrderId;

    /** JSON array: [{channel_item_id, sku, name, quantity, picked, warehouse_location, barcode}] */
    @Column(name = "items", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    @ColumnDefault("'[]'::jsonb")
    @Builder.Default
    private String items = "[]";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
