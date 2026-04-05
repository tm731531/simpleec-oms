package com.simpleec.channeljob.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

/**
 * Lightweight read-only projection of the orders table.
 * Channel-job only needs id → channel_order_id translation.
 */
@Entity
@Table(name = "orders")
@Data
@NoArgsConstructor
public class OrderRef {
    @Id
    private String id;

    @Column(name = "channel_order_id", nullable = false)
    private String channelOrderId;
}
