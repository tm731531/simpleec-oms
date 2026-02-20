package com.simpleec.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.simpleec.common.enums.OrderStatusEnum;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 訂單實體
 *
 * 存儲統一的 OMS 訂單，對應所有平台訂單的標準結構
 */
@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_channel_order_id", columnList = "channel_id,channel_order_id"),
    @Index(name = "idx_order_status", columnList = "order_status"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    /**
     * 主鍵：OMS 訂單 ID (NanoID 20 字元)
     */
    @Id
    @Column(name = "order_id", length = 20, nullable = false)
    private String orderId;

    /**
     * 商家 ID
     */
    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    /**
     * 通路實例 ID (e.g., SHOPEE_001, SHOPIFY_001)
     */
    @Column(name = "channel_id", length = 50, nullable = false)
    private String channelId;

    /**
     * 通路的原始訂單 ID (e.g., Shopee order_id, Shopify order id)
     */
    @Column(name = "channel_order_id", length = 100, nullable = false)
    private String channelOrderId;

    /**
     * OMS 統一訂單狀態
     */
    @Column(name = "order_status", length = 50, nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatusEnum orderStatus;

    /**
     * 訂單總金額（含運費、折扣）
     */
    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    /**
     * 運費
     */
    @Column(name = "shipping_fee")
    private BigDecimal shippingFee;

    /**
     * 折扣金額
     */
    @Column(name = "discount_amount")
    private BigDecimal discountAmount;

    /**
     * 訂單項目 (JSONB 格式)
     * 存儲 OrderItem 陣列的 JSON 字符串
     */
    @Column(name = "items", columnDefinition = "jsonb")
    private String items;  // JSON array

    /**
     * 配送方式
     */
    @Column(name = "shipping_method", length = 50)
    private String shippingMethod;

    /**
     * 買家信息 (JSONB，包含加密的 name/phone/email)
     */
    @Column(name = "buyer_info", columnDefinition = "jsonb")
    private String buyerInfo;  // JSON object

    /**
     * 配送地址信息 (JSONB，包含加密的地址)
     */
    @Column(name = "shipping_info", columnDefinition = "jsonb")
    private String shippingInfo;  // JSON object

    /**
     * 支付方式
     */
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    /**
     * 支付時間
     */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /**
     * 通路上的訂單建立時間
     */
    @Column(name = "channel_created_at")
    private LocalDateTime channelCreatedAt;

    /**
     * 是否為回補訂單（遺漏的過往訂單）
     */
    @Column(name = "is_rollback", nullable = false)
    private Boolean isRollback = false;

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
