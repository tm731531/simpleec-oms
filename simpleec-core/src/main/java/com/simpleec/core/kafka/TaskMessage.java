package com.simpleec.core.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskMessage {
    private String messageId;
    private String taskType;
    private String taskAction;
    private String sourceJobType;
    private String merchantId;
    private String ownerType;
    private String ownerId;
    private String timezone;
    private Map<String, Object> payload;
    private Instant createdAt;
    private int retryCount;
    private String topic;
    private String partitionKey;

    // Observability
    private String traceId;
    @Builder.Default
    private int schemaVersion = 1;
}
