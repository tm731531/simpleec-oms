package com.simpleec.channeljob.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

/**
 * Lightweight read-only projection of the refund_orders table.
 * Channel-job needs returnId → orderId translation to then look up channel_order_id.
 */
@Entity
@Table(name = "refund_orders")
@Data
@NoArgsConstructor
public class ReturnOrderRef {
    @Id
    private String id;

    @Column(name = "order_id", nullable = false)
    private String orderId;
}
