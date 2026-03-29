package com.simpleec.channeljob.handler;

import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channel.adapter.CyberbizAdapter;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.service.ChannelService;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FETCH_RETURNS handler — fetches return/refund records from the platform
 * and publishes RETURN_UPSERT events to return.process.
 *
 * Cyberbiz flow:
 *   1. Call CyberbizAdapter.fetchReturnsByTimestamp() — returns return records
 *      with channelOrderId attached
 *   2. For each return record, publish RETURN_UPSERT to return.process
 *
 * Other platforms: stub (logs + publishes placeholder).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FetchReturnsHandler {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ChannelService channelService;
    private final ChannelAdapter cyberbizAdapter;

    public void handleFetchReturns(String platformCode, String channelId,
                                   String merchantId, long baseTimestamp) {
        log.info("FETCH_RETURNS: platform={}, channel={}, merchant={}, baseTimestamp={}",
                platformCode, channelId, merchantId, baseTimestamp);

        if ("cyberbiz".equalsIgnoreCase(platformCode)) {
            Channel channel = channelService.getChannel(channelId);
            if (channel == null) {
                log.error("FETCH_RETURNS: channel not found: {}", channelId);
                return;
            }
            try {
                CyberbizAdapter adapter = (CyberbizAdapter) cyberbizAdapter;
                adapter.setCredentials(channel.getToken(), channel.getToken2());

                List<Map<String, Object>> returns = adapter.fetchReturnsByTimestamp(baseTimestamp);
                log.info("FETCH_RETURNS: fetched {} return records for channel={}", returns.size(), channelId);

                int published = 0;
                for (Map<String, Object> ret : returns) {
                    try {
                        publishReturnUpsert(platformCode, channelId, merchantId, ret);
                        published++;
                    } catch (Exception e) {
                        log.error("FETCH_RETURNS: failed to publish RETURN_UPSERT for return={} channel={}",
                                ret.get("id"), channelId, e);
                    }
                }
                log.info("FETCH_RETURNS completed: channel={} published={}/{}", channelId, published, returns.size());

            } catch (Exception e) {
                log.error("FETCH_RETURNS failed for platform={} channel={}", platformCode, channelId, e);
            }
        } else {
            // Other platforms: publish placeholder so the message isn't silently dropped
            try {
                publishReturnPlaceholder(platformCode, channelId, merchantId);
            } catch (Exception e) {
                log.error("Failed to publish FETCH_RETURNS placeholder for platform={}, channel={}",
                        platformCode, channelId, e);
            }
        }
    }

    /**
     * Publish a real RETURN_UPSERT event from a Cyberbiz return record.
     *
     * Cyberbiz return fields:
     *   id              — return record ID (channelRefundId)
     *   channelOrderId  — attached by fetchReturnsByTimestamp
     *   created_at      — return creation time
     *   return_reason   — reason text
     *   line_items      — array of returned items
     *   tracking_number — return tracking number
     */
    @SuppressWarnings("unchecked")
    private void publishReturnUpsert(String platformCode, String channelId, String merchantId,
                                     Map<String, Object> ret) throws Exception {
        Object returnIdObj = ret.get("id");
        if (returnIdObj == null) {
            log.warn("FETCH_RETURNS: return record missing id, skipping");
            return;
        }
        String channelRefundId  = String.valueOf(returnIdObj);
        String channelOrderId   = ret.getOrDefault("channelOrderId", "").toString();
        String returnReason     = ret.getOrDefault("return_reason", "").toString();
        String createdAt        = ret.getOrDefault("created_at", "").toString();
        Object lineItems        = ret.getOrDefault("line_items", List.of());

        // Compute hash from stable fields for deduplication
        TreeMap<String, Object> hashFields = new TreeMap<>();
        hashFields.put("channelRefundId", channelRefundId);
        hashFields.put("reason", returnReason);
        hashFields.put("createdAt", createdAt);
        String returnHash = DigestUtils.sha256Hex(objectMapper.writeValueAsString(hashFields));

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
        body.put("channelRefundId", channelRefundId);
        body.put("returnHash", returnHash);

        ObjectNode returnData = objectMapper.createObjectNode();
        // channelOrderId lets ReturnUpsertHandler resolve the OMS internal orderId
        returnData.put("channelOrderId", channelOrderId);
        returnData.put("reason", returnReason);
        if (!createdAt.isBlank()) {
            returnData.put("requestedAt", createdAt);
        }
        returnData.set("items", objectMapper.valueToTree(lineItems));
        body.set("returnData", returnData);

        message.set("header", header);
        message.set("body", body);

        kafkaTemplate.send(TopicConstants.RETURN_PROCESS, channelId, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish RETURN_UPSERT for refundId={} channel={}",
                                channelRefundId, channelId, ex);
                    } else {
                        log.debug("Published RETURN_UPSERT: refundId={} orderId={} channel={}",
                                channelRefundId, channelOrderId, channelId);
                    }
                });
    }

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
                        log.error("Failed to publish FETCH_RETURNS placeholder for channel={}", channelId, ex);
                    } else {
                        log.info("Published FETCH_RETURNS placeholder: channel={} platform={}", channelId, platformCode);
                    }
                });
    }
}
