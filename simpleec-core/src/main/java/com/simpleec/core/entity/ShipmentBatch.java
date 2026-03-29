package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "shipment_batches", indexes = {
    @Index(name = "idx_shipment_batches_merchant", columnList = "merchant_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentBatch {

    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    @Column(name = "batch_no", length = 50)
    private String batchNo;

    @Column(name = "carrier", length = 100)
    private String carrier;

    @Column(name = "logistics_cost", precision = 10, scale = 2)
    private BigDecimal logisticsCost;

    @Column(name = "scheduled_pickup_at")
    private LocalDateTime scheduledPickupAt;

    @Column(name = "actual_pickup_at")
    private LocalDateTime actualPickupAt;

    @Column(name = "carrier_driver_id", length = 100)
    private String carrierDriverId;

    @Column(name = "handoff_box_count")
    private Integer handoffBoxCount;

    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private String status = "PREPARING";

    @Column(name = "force_ready_note")
    private String forceReadyNote;

    @Column(name = "notes")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
