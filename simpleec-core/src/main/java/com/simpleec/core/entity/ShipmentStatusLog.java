package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "shipment_status_logs", indexes = {
    @Index(name = "idx_shipment_status_log", columnList = "shipment_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentStatusLog {

    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    @Column(name = "shipment_id", length = 20, nullable = false)
    private String shipmentId;

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", length = 30, nullable = false)
    private String toStatus;

    @Column(name = "operator_id", length = 100)
    private String operatorId;

    @Column(name = "remark")
    private String remark;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
