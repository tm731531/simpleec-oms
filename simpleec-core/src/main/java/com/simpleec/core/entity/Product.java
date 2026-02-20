package com.simpleec.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品實體（OMS 倉庫 SKU）
 *
 * 代表我們的商品，與各平台無關
 */
@Entity
@Table(name = "products", indexes = {
    @Index(name = "idx_merchant_sku", columnList = "merchant_id,sku", unique = true),
    @Index(name = "idx_product_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    /**
     * 主鍵：OMS 商品 ID (NanoID)
     */
    @Id
    @Column(name = "product_id", length = 20, nullable = false)
    private String productId;

    /**
     * 商家 ID
     */
    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    /**
     * OMS SKU（商家內唯一）
     */
    @Column(name = "sku", length = 100, nullable = false)
    private String sku;

    /**
     * 商品名稱
     */
    @Column(name = "product_name", length = 255)
    private String productName;

    /**
     * 規格摘要 (e.g., "紅色/M")
     */
    @Column(name = "spec_summary", length = 255)
    private String specSummary;

    /**
     * 商品群組 ID (可選)
     */
    @Column(name = "product_group_id", length = 20)
    private String productGroupId;

    /**
     * 成本價
     */
    @Column(name = "cost_price")
    private BigDecimal costPrice;

    /**
     * 建議售價
     */
    @Column(name = "suggest_price")
    private BigDecimal suggestPrice;

    /**
     * 當前庫存量
     */
    @Column(name = "quantity", nullable = false)
    private Integer quantity = 0;

    /**
     * 安全庫存量（低於此值警告）
     */
    @Column(name = "safety_quantity")
    private Integer safetyQuantity = 10;

    /**
     * 商品狀態
     */
    @Column(name = "status", length = 20, nullable = false)
    private String status = "active";  // active, inactive

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
