package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "failed_task_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedTaskLog {

    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    @Column(name = "message_id", length = 50)
    private String messageId;

    @Column(name = "task_type", length = 50)
    private String taskType;

    @Column(name = "task_action", length = 100)
    private String taskAction;

    @Column(name = "source_job_type", length = 50)
    private String sourceJobType;

    @Column(name = "merchant_id", length = 20)
    private String merchantId;

    @Column(name = "owner_id", length = 50)
    private String ownerId;

    @Column(name = "original_topic", length = 100)
    private String originalTopic;

    @Column(name = "original_key", length = 200)
    private String originalKey;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "reason", length = 50)
    private String reason;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
