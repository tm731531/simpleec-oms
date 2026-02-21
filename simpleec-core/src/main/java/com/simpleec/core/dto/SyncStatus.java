package com.simpleec.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncStatus {
    private String status;       // pending|syncing|completed|failed
    private String operation;    // QUANTITY_UPDATE|PRICE_UPDATE|LISTING_UPDATE|IMAGE_UPDATE|DESC_UPDATE|SHIPMENT|CANCEL
    private Object oldValue;
    private Object newValue;
    private String startTime;    // ISO 8601
    private String completedTime;
    private String error;
    private int retryCount;
}
