package com.simpleec.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.simpleec.common.enums.ReturnStatusEnum;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退貨實體
 */
@Entity
@Table(name = "refund_orders", indexes = {
    @Index(name = "idx_order_id", columnList = "order_id"),
    @Index(name = "idx_refund_status", columnList = "refund_status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnOrder {

    /**
     * 主鍵：OMS 退貨 ID (NanoID)
     */
    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    /**
     * 商家 ID
     */
    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    /**
     * 關聯的訂單 ID
     */
    @Column(name = "order_id", length = 20, nullable = false)
    private String orderId;

    /**
     * 通路的原始退貨 ID
     */
    @Column(name = "channel_refund_id", length = 100)
    private String channelRefundId;

    /**
     * 退貨狀態
     */
    @Column(name = "refund_status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private ReturnStatusEnum returnStatus;

    /**
     * 退貨原因
     */
    @Column(name = "reason", length = 500)
    private String reason;

    /**
     * 退貨項目 (JSONB)
     */
    @Column(name = "items", columnDefinition = "jsonb")
    private String items;  // JSON array

    /**
     * 退款金額
     */
    @Column(name = "refund_amount")
    private BigDecimal refundAmount;

    /**
     * 退貨申請時間
     */
    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    /**
     * 建立時間
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 最後更新時間
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
