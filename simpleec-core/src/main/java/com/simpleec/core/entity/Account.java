package com.simpleec.core.entity;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 帳號實體
 * 代表商家的使用者帳號（商店管理員、店員等）
 */
@Entity
@Table(name = "account", indexes = {
    @Index(name = "idx_account_email", columnList = "account_email", unique = true),
    @Index(name = "idx_account_merchant", columnList = "merchant_id"),
    @Index(name = "idx_account_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Account {

    /**
     * 帳號 ID (NanoID, max 20 chars)
     */
    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    /**
     * 帳號名稱
     */
    @Column(name = "account_name", length = 256)
    private String accountName;

    /**
     * 帳號信箱（用於登入）
     */
    @Column(name = "account_email", length = 256, nullable = false, unique = true)
    private String accountEmail;

    /**
     * 帳號密碼（加密存儲）
     */
    @Column(name = "account_password", length = 512)
    private String accountPassword;

    /**
     * 帳號電話
     */
    @Column(name = "account_tel", length = 20)
    private String accountTel;

    /**
     * 是否為主帳號（僅此帳號可管理其他帳號）
     */
    @Column(name = "is_main_account")
    private Boolean isMainAccount = false;

    /**
     * 帳號訪問權限等級 (0=只讀, 1=操作員, 2=管理員)
     */
    @Column(name = "access_level")
    private Integer accessLevel = 0;

    /**
     * 所屬商家 ID
     */
    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    /**
     * 帳號狀態 (active, inactive, locked 等)
     */
    @Column(name = "status", length = 20)
    private String status;

    /**
     * TOTP 雙因素認證密鑰（可選）
     */
    @Column(name = "totp_secret", length = 20)
    private String totpSecret;

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
