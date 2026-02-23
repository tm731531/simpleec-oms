package com.simpleec.schedulerjob.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

/**
 * Channel entity - represents merchant's channel on a platform
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

    @Column(nullable = false, name = "platform_id")
    private String platformId;

    @Column(nullable = false)
    private Boolean actived = true;

    @Column(name = "enable_sync", nullable = false)
    private Boolean enableSync;

    @Column(name = "channel_name")
    private String channelName;
}
