package com.simpleec.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 平台管理員帳號（獨立於商家帳號體系）
 */
@Entity
@Table(name = "platform_account")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformAccount {

    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "email", length = 256, nullable = false, unique = true)
    private String email;

    @Column(name = "password", length = 512, nullable = false)
    private String password;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
