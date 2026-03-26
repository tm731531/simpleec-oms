package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Kafka health check handler — runs every 10 minutes (minute % 10 == 5).
 *
 * Logs that the health check ran at the given timestamp and marks status OK.
 * TODO: add consumer lag check via AdminClient when monitoring is set up.
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
        log.info("Kafka health check at {}: OK", timestamp);
        // TODO: add consumer lag check via AdminClient when monitoring is set up
    }
}
