package com.simpleec.orderjob.consumer;

import com.simpleec.core.crypto.EncryptionContext;
import com.simpleec.orderjob.handler.ReturnUpsertHandler;
import com.simpleec.common.kafka.SchemaVersionHandler;
import com.simpleec.common.kafka.TaskMdcHelper;
import com.simpleec.common.kafka.UnsupportedSchemaVersionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReturnUpsert Consumer — 退貨入庫的 Kafka 監聽器
 *
 * 消費 return.process topic 中的 RETURN_UPSERT 消息
 * 執行兩層去重（Redis + 資料庫），然後入庫
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnUpsertConsumer {

    private final ReturnUpsertHandler returnUpsertHandler;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 提取並驗證字串欄位 — 確保欄位存在、非空且非 null
     */
    private String extractAndValidateString(JsonNode node, String fieldName) throws IllegalArgumentException {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
        String value = node.get(fieldName).asText().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty required field: " + fieldName);
        }
        return value;
    }

    /**
     * Consume return.process topic
     */
    @KafkaListener(topics = "return.process", groupId = "return-job-group", concurrency = "3")
    @Transactional
    public void consumeReturnUpsert(@Payload JsonNode json,
                                    @Header(name = "kafka_receivedPartitionId") int partition,
                                    Acknowledgment acknowledgment) {
        try {
            SchemaVersionHandler.validate(json);
        } catch (UnsupportedSchemaVersionException e) {
            log.error("Unsupported schema version in RETURN_UPSERT message: {}", e.getMessage());
            kafkaTemplate.send("task.dlt", "ReturnUpsert", json);
            acknowledgment.acknowledge();
            return;
        }

        TaskMdcHelper.set(json);
        try {
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            try {
                String taskType = extractAndValidateString(header, "taskType");

                if (!taskType.equals("RETURN_UPSERT")) {
                    log.warn("Unexpected taskType: {} in ReturnUpsertConsumer", taskType);
                    acknowledgment.acknowledge();
                    return;
                }

                String merchantId = extractAndValidateString(header, "merchantId");
                String channelId = extractAndValidateString(header, "channelId");
                String channelRefundId = extractAndValidateString(body, "channelRefundId");
                String returnHash = extractAndValidateString(body, "returnHash");

                if (!body.has("returnData") || body.get("returnData").isNull()) {
                    throw new IllegalArgumentException("Missing returnData");
                }
                JsonNode returnDataJson = body.get("returnData");

                log.info("Processing RETURN_UPSERT: {} from {} (hash: {})",
                    channelRefundId, channelId, returnHash.substring(0, Math.min(8, returnHash.length())) + "...");

                // Set encryption context for PII field encryption/decryption
                EncryptionContext.setMerchantId(merchantId);
                try {
                    returnUpsertHandler.handleReturnUpsert(merchantId, channelId, channelRefundId, returnHash, returnDataJson);
                    acknowledgment.acknowledge();
                    log.info("Successfully processed RETURN_UPSERT: {}", channelRefundId);
                } catch (Exception e) {
                    log.error("Error processing RETURN_UPSERT for {}: {}", channelRefundId, e.getMessage(), e);
                    try {
                        // Send to failed queue for async retry or manual intervention
                        kafkaTemplate.send("task.failed", "ReturnUpsert", json);
                        log.info("Message sent to task.failed topic");
                    } catch (Exception sendError) {
                        log.error("Failed to send message to task.failed", sendError);
                    }
                    // Always acknowledge to avoid infinite reprocessing
                    acknowledgment.acknowledge();
                } finally {
                    EncryptionContext.clear();
                }

            } catch (IllegalArgumentException e) {
                log.error("Invalid message structure: {}", e.getMessage());
                acknowledgment.acknowledge();
            }

        } catch (Exception e) {
            log.error("Error processing RETURN_UPSERT message: {}", e.getMessage(), e);
            try {
                // Send to failed queue for async retry or manual intervention
                kafkaTemplate.send("task.failed", "ReturnUpsert", json);
                log.info("Message sent to task.failed topic");
            } catch (Exception sendError) {
                log.error("Failed to send message to task.failed", sendError);
            }
            // Always acknowledge to avoid infinite reprocessing
            try {
                acknowledgment.acknowledge();
            } catch (Exception ackError) {
                log.error("Failed to acknowledge message during error handling", ackError);
            }
        } finally {
            TaskMdcHelper.clear();
        }
    }
}
