package com.simpleec.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Order status change log — append-only audit trail for every status transition.
 *
 * Records who changed what and when, plus an optional remark.
 * PK is a VARCHAR(20) NanoID generated on the application side.
 */
@Entity
@Table(name = "order_status_logs", indexes = {
    @Index(name = "idx_order_status_log_order", columnList = "order_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatusLog {

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
     * The status before this transition (null = initial status assignment)
     */
    @Column(name = "from_status", length = 20)
    private String fromStatus;

    /**
     * The status after this transition
     */
    @Column(name = "to_status", length = 20, nullable = false)
    private String toStatus;

    /**
     * Who triggered the change: "system", "api", account email, etc.
     */
    @Column(name = "operator", length = 100)
    private String operator;

    /**
     * Optional note or reason for the status change
     */
    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    /**
     * Record creation timestamp
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
