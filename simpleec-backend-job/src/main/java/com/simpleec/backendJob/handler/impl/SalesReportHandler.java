package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 銷售報表處理器
 * 定時生成銷售統計報表
 */
@Slf4j
@Component
public class SalesReportHandler extends AbstractEventHandler {

    @Override
    public String getTaskType() {
        return "SALES_REPORT";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        if (merchantId == null) {
            log.warn("No merchantId provided for SALES_REPORT");
            return;
        }
        log.debug("Generating SALES_REPORT for merchant: {}, timestamp: {}", merchantId, timestamp);
    }
}
