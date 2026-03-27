package com.simpleec.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.simpleec.common.enums.OrderStatusEnum;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.simpleec.core.crypto.EncryptedAttributeConverter;
import jakarta.persistence.*;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ColumnDefault;
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
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    /**
     * 商家 ID
     */
    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    /**
     * 通路實例 ID (e.g., SHOPEE_001, SHOPIFY_001)
     */
    @Column(name = "channel_id", length = 20, nullable = false)
    private String channelId;

    /**
     * 通路的原始訂單 ID (e.g., Shopee order_id, Shopify order id)
     */
    @Column(name = "channel_order_id", length = 100, nullable = false)
    private String channelOrderId;

    /**
     * 通路的人可讀訂單號碼 (e.g., Cyberbiz order_number 4319)
     */
    @Column(name = "channel_order_number", length = 100)
    private String channelOrderNumber;

    /**
     * OMS 統一訂單狀態
     */
    @Column(name = "order_status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatusEnum orderStatus;

    /**
     * 訂單總金額（含運費、折扣）
     * 注：数据库约束 NOT NULL DEFAULT 0，允许 null，使用 insertable=false 让 DB 处理
     */
    @Column(name = "total_amount", nullable = false, insertable = false, updatable = true)
    @ColumnDefault("0")
    private BigDecimal totalAmount;

    /**
     * 運費
     * 注：數據庫約束 NOT NULL DEFAULT 0，允許 null，使用 insertable=false 讓 DB 處理
     */
    @Column(name = "shipping_fee", nullable = false, insertable = false, updatable = true)
    @ColumnDefault("0")
    private BigDecimal shippingFee;

    /**
     * 折扣金額
     * 注：數據庫約束 NOT NULL DEFAULT 0，允許 null，使用 insertable=false 讓 DB 處理
     */
    @Column(name = "discount_amount", nullable = false, insertable = false, updatable = true)
    @ColumnDefault("0")
    private BigDecimal discountAmount;

    /**
     * 訂單項目 (JSONB 格式)
     * 存儲 OrderItem 陣列的 JSON 字符串
     * 注：數據庫約束 NOT NULL DEFAULT '[]'::jsonb，允許 null，使用 insertable=false 讓 DB 處理
     */
    @Column(name = "items", columnDefinition = "jsonb", nullable = false, insertable = false, updatable = true)
    @JdbcTypeCode(SqlTypes.JSON)
    @ColumnDefault("'[]'::jsonb")
    private String items;  // JSON array

    /**
     * 配送方式
     */
    @Column(name = "shipping_method", length = 50)
    private String shippingMethod;

    /**
     * 買家名稱 (PII — AES-256-GCM encrypted at rest)
     */
    @Convert(converter = EncryptedAttributeConverter.class)
    @Column(name = "buyer_name", length = 512)
    private String buyerName;

    /**
     * 買家電話 (PII — AES-256-GCM encrypted at rest)
     */
    @Convert(converter = EncryptedAttributeConverter.class)
    @Column(name = "buyer_phone", length = 256)
    private String buyerPhone;

    /**
     * 買家郵箱 (PII — AES-256-GCM encrypted at rest)
     */
    @Convert(converter = EncryptedAttributeConverter.class)
    @Column(name = "buyer_email", length = 512)
    private String buyerEmail;

    /**
     * 配送地址 (PII — AES-256-GCM encrypted at rest)
     */
    @Convert(converter = EncryptedAttributeConverter.class)
    @Column(name = "shipping_address")
    private String shippingAddress;

    /**
     * 支付方式
     */
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    /**
     * 買家資訊 (JSONB 格式)
     * 存儲買家相關的詳細資訊（如地址、特殊標籤等）
     */
    @Column(name = "buyer_info", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String buyerInfo;

    /**
     * 配送資訊 (JSONB 格式)
     * 存儲配送相關的詳細資訊（如物流追蹤、估計送達時間等）
     */
    @Column(name = "shipping_info", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String shippingInfo;

    /**
     * 是否為回補訂單
     * true = 回補訂單（回補過去遺漏的訂單）
     * false = 新訂單
     */
    @Column(name = "is_rollback", nullable = false, columnDefinition = "boolean default false")
    private Boolean isRollback;

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
     * 出貨時間
     */
    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    /**
     * 累計退款金額（每次退款後更新）
     */
    @Builder.Default
    @Column(name = "refund_amount", precision = 15, scale = 2, columnDefinition = "NUMERIC(15,2) DEFAULT 0")
    private BigDecimal refundAmount = BigDecimal.ZERO;

    /**
     * 是否有退款（快速過濾用 index）
     */
    @Builder.Default
    @Column(name = "has_refund", nullable = false, columnDefinition = "boolean default false")
    private Boolean hasRefund = false;

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
