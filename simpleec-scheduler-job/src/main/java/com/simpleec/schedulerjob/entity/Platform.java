package com.simpleec.schedulerjob.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

/**
 * Platform entity - represents e-commerce platform configuration
 */
@Entity
@Table(name = "platform")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Platform {
    @Id
    private String id;

    @Column(name = "platform_name")
    private String name;

    @Column(name = "queue_topic")
    private String queueTopic;

    @Column(nullable = false)
    private Boolean actived;
}
