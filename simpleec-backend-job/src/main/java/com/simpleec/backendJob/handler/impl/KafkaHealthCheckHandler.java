package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Kafka 健檢處理器
 * 定期驗證 Kafka 消息流通暢
 */
@Slf4j
@Component
public class KafkaHealthCheckHandler extends AbstractEventHandler {

    @Override
    public String getTaskType() {
        return "KAFKA_HEALTH_CHECK";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        log.debug("Performing KAFKA_HEALTH_CHECK at timestamp: {}", timestamp);
        // TODO: 檢查 Kafka 連線、消費者組狀態等
    }
}
