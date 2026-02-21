package com.simpleec.frontendjob.broadcaster;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FrontendEvent {
    @JsonProperty("taskType")
    private String taskType;
    @JsonProperty("merchantId")
    private String merchantId;
    @JsonProperty("channelId")
    private String channelId;
    @JsonProperty("platformId")
    private String platformId;
    @JsonProperty("requestId")
    private String requestId;
    @JsonProperty("timestamp")
    private LocalDateTime timestamp;
    @JsonProperty("source")
    private String source;
    @JsonProperty("version")
    private int version = 1;
    @JsonProperty("status")
    private String status;
    @JsonProperty("statusMessage")
    private String statusMessage;
    @JsonProperty("progress")
    private int progress;
    @JsonProperty("totalRecords")
    private int totalRecords;
    @JsonProperty("processedRecords")
    private int processedRecords;
    @JsonProperty("failedRecords")
    private int failedRecords;
    @JsonProperty("metadata")
    private Object metadata;
}
