package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 日終報表處理器
 * 每日 00:00 和 12:00 生成日終或午間報表
 */
@Slf4j
@Component
public class DailyReportHandler extends AbstractEventHandler {

    @Override
    public String getTaskType() {
        return "DAILY_REPORT";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        log.debug("Generating DAILY_REPORT at timestamp: {}", timestamp);
        // TODO: 生成日終或午間報表
    }
}
