package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 退貨報表處理器
 * 定時生成退貨統計報表
 */
@Slf4j
@Component
public class ReturnReportHandler extends AbstractEventHandler {

    @Override
    public String getTaskType() {
        return "RETURN_REPORT";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        if (merchantId == null) {
            log.warn("No merchantId provided for RETURN_REPORT");
            return;
        }
        log.debug("Generating RETURN_REPORT for merchant: {}, timestamp: {}", merchantId, timestamp);
    }
}
