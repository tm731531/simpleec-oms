package com.simpleec.schedulerjob.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

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

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private Boolean actived;

    public String getCode() {
        return code;
    }
}
