package com.simpleec.channeljob.handler;

import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * FETCH_RETURNS handler — fetches return/refund list from the platform.
 *
 * Current status: stub implementation.
 * Logs receipt, publishes a placeholder RETURN_UPSERT to return.process
 * so the message is never silently dropped.
 *
 * TODO: integrate actual platform return-list APIs per adapter (Mode A / Mode B).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FetchReturnsHandler {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Handle a FETCH_RETURNS task.
     *
     * @param platformCode platform identifier extracted from the consumer group ID
     * @param channelId    channel instance ID
     * @param merchantId   merchant ID resolved from the database
     * @param baseTimestamp base heartbeat timestamp (epoch seconds) used to compute time windows
     */
    public void handleFetchReturns(String platformCode, String channelId,
                                   String merchantId, long baseTimestamp) {
        log.info("FETCH_RETURNS received: platform={}, channel={}, merchant={}, baseTimestamp={}",
                platformCode, channelId, merchantId, baseTimestamp);

        // TODO: call adapter.fetchReturnsByTimestamp() once platform adapters expose a return-list API.
        // For now, publish a placeholder acknowledgement to return.process so the message
        // is visible in the event stream and not silently dropped.
        try {
            publishReturnPlaceholder(platformCode, channelId, merchantId);
        } catch (Exception e) {
            log.error("Failed to publish FETCH_RETURNS placeholder for platform={}, channel={}",
                    platformCode, channelId, e);
        }
    }

    /**
     * Publish a placeholder RETURN_UPSERT to return.process.
     * This prevents FETCH_RETURNS from being silently swallowed until
     * real return-list adapter methods are implemented.
     */
    private void publishReturnPlaceholder(String platformCode, String channelId, String merchantId)
            throws Exception {

        ObjectNode message = objectMapper.createObjectNode();

        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_" + NanoIdUtil.generate());
        header.put("requestId", "req_" + NanoIdUtil.generate());
        header.put("taskType", TaskTypeEnum.RETURN_UPSERT.getCode());
        header.put("platformId", platformCode);
        header.put("channelId", channelId);
        header.put("merchantId", merchantId);
        header.put("timestamp", Instant.now().toString());
        header.put("source", "channel_job");
        header.put("version", 1);
        header.put("isRollback", false);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("stub", true);
        body.put("note", "FETCH_RETURNS not yet implemented for platform: " + platformCode);

        message.set("header", header);
        message.set("body", body);

        kafkaTemplate.send(TopicConstants.RETURN_PROCESS, channelId, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish FETCH_RETURNS placeholder to return.process for channel={}",
                                channelId, ex);
                    } else {
                        log.info("Published FETCH_RETURNS placeholder to return.process: channel={}, platform={}",
                                channelId, platformCode);
                    }
                });
    }
}
