package com.simpleec.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.sql.Types;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * 銷售平台實體
 * 代表系統接入的電商平台（Shopee, Momo, Yahoo 等）的配置
 */
@Entity
@Table(name = "platform", indexes = {
    @Index(name = "idx_platform_name", columnList = "platform_name")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Platform {

    /**
     * 平台 ID (NanoID, max 20 chars)
     */
    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    /**
     * 平台名稱 (shopee, momo, yahoo, pchome, cyberbiz, easystore 等)
     */
    @Column(name = "platform_name", length = 50)
    private String platformName;

    /**
     * 認證憑證 1 (API Key 或 Client ID 等，已加密)
     */
    @Column(name = "credential1", length = 4096)
    private String credential1;

    /**
     * 認證憑證 2 (API Secret 或 Access Token 等，已加密)
     */
    @Column(name = "credential2", length = 4096)
    private String credential2;

    /**
     * 平台是否啟用
     */
    @Column(name = "actived")
    private Boolean actived = true;

    /**
     * 對應的 Kafka Topic (e.g., "momo.fast", "shopee.slow")
     */
    @Column(name = "queue_topic", length = 128)
    private String queueTopic;

    /**
     * 平台幣種 (預設 TWD)
     */
    @Column(name = "currency", length = 3)
    private String currency = "TWD";

    /**
     * 配送選項 JSON (平台支援的物流方式等)
     */
    @JdbcTypeCode(Types.OTHER)
    @Column(name = "ship_options", columnDefinition = "jsonb")
    private JsonNode shipOptions;

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
