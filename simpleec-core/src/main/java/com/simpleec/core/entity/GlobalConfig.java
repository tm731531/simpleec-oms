package com.simpleec.core.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "global_config")
public class GlobalConfig {

    @Id
    @Column(name = "id", length = 128, nullable = false)
    private String id;

    @Column(name = "data", length = 2048, nullable = false)
    private String data;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public String getData() { return data; }
    public String getDescription() { return description; }
}
