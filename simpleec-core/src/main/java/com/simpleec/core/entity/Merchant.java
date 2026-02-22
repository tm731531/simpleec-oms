package com.simpleec.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 商家實體
 * 代表接入系統的商家（電商店主）
 */
@Entity
@Table(name = "merchant", indexes = {
    @Index(name = "idx_merchant_email", columnList = "merchant_email", unique = true),
    @Index(name = "idx_merchant_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Merchant {

    /**
     * 商家 ID (NanoID, max 20 chars)
     */
    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    /**
     * 商家名稱
     */
    @Column(name = "merchant_name", length = 256, nullable = false)
    private String merchantName;

    /**
     * 商家聯絡信箱
     */
    @Column(name = "merchant_email", length = 256, nullable = false, unique = true)
    private String merchantEmail;

    /**
     * 商家聯絡電話
     */
    @Column(name = "merchant_phone_number", length = 20)
    private String merchantPhoneNumber;

    /**
     * 稅號
     */
    @Column(name = "tax_id_number", length = 20)
    private String taxIdNumber;

    /**
     * 營業地址 - 城市
     */
    @Column(name = "address_city", length = 50)
    private String addressCity;

    /**
     * 營業地址 - 鄉鎮市區
     */
    @Column(name = "address_region", length = 50)
    private String addressRegion;

    /**
     * 營業地址 - 國家
     */
    @Column(name = "address_country", length = 50)
    private String addressCountry;

    /**
     * 營業地址 - 郵編
     */
    @Column(name = "address_zip", length = 10)
    private String addressZip;

    /**
     * 營業地址 - 地址第一行
     */
    @Column(name = "address_line1", length = 256)
    private String addressLine1;

    /**
     * 營業地址 - 地址第二行
     */
    @Column(name = "address_line2", length = 256)
    private String addressLine2;

    /**
     * 營業地址 - 電話
     */
    @Column(name = "address_phone_number", length = 20)
    private String addressPhoneNumber;

    /**
     * VIP 等級 (0=一般, 1=銀牌, 2=金牌等)
     */
    @Column(name = "vip_level")
    private Integer vipLevel = 0;

    /**
     * 商家所在時區
     */
    @Column(name = "user_local_time_zone", length = 50)
    private String userLocalTimeZone = "UTC";

    /**
     * 結算負責人名稱
     */
    @Column(name = "payer_name", length = 256)
    private String payerName;

    /**
     * 結算負責人信箱
     */
    @Column(name = "payer_email", length = 256)
    private String payerEmail;

    /**
     * 結算負責人電話
     */
    @Column(name = "payer_phone_number", length = 20)
    private String payerPhoneNumber;

    /**
     * 商家狀態 (active, inactive, suspended 等)
     */
    @Column(name = "status", length = 20)
    private String status;

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
