package com.simpleec.channeljob.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

/**
 * Channel entity - represents merchant's channel configuration on a platform
 */
@Entity
@Table(name = "channel")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Channel {
    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "platform_id", nullable = false)
    private String platformId;

    @Column(nullable = false)
    private String token;

    @Column(name = "token2")
    private String token2;

    @Column(name = "enable_sync", nullable = false)
    private Boolean enableSync;

    @Column(name = "channel_name")
    private String channelName;
}
