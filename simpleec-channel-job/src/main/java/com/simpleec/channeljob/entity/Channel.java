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

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String platformCode;

    @Column(nullable = false)
    private String token;

    @Column(name = "enable_sync", nullable = false)
    private Boolean enableSync;

    @Column(name = "channel_name")
    private String channelName;
}
