package com.simpleec.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Order shipment entity — tracks logistics / tracking info for each shipment.
 *
 * One order may have multiple shipments (split shipment, re-ship, etc.).
 * PK is a VARCHAR(20) NanoID generated on the application side.
 */
@Entity
@Table(name = "order_shipments", indexes = {
    @Index(name = "idx_shipment_order", columnList = "order_id"),
    @Index(name = "idx_shipment_tracking", columnList = "tracking_number")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderShipment {

    /**
     * Primary key: NanoID 20 chars
     */
    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    /**
     * FK to orders.id
     */
    @Column(name = "order_id", length = 20, nullable = false)
    private String orderId;

    /**
     * Logistics tracking number (e.g., "7123456789")
     */
    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    /**
     * Logistics company / carrier name (e.g., "黑貓", "宅配通", "7-11")
     */
    @Column(name = "logistics_company", length = 100)
    private String logisticsCompany;

    /**
     * Shipment status: pending / shipped / delivered / exception
     */
    @Column(name = "shipping_status", length = 20, nullable = false)
    @Builder.Default
    private String shippingStatus = "pending";

    /**
     * Timestamp when the package was handed over to carrier
     */
    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    /**
     * Timestamp when the package was delivered to recipient
     */
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    /**
     * Record creation timestamp
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
