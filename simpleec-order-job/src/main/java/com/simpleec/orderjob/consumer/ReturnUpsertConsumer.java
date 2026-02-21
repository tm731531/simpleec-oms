package com.simpleec.orderjob.consumer;

import com.simpleec.orderjob.handler.ReturnUpsertHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
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

    /**
     * 消費 return.process topic
     */
    @KafkaListener(topics = "return.process", groupId = "return-job-group", concurrency = "4")
    @Transactional
    public void consumeReturnUpsert(@Payload String message,
                                    @Header(name = "kafka_receivedPartitionId") int partition,
                                    Acknowledgment acknowledgment) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            String taskType = header.get("taskType").asText();

            if (!taskType.equals("RETURN_UPSERT")) {
                log.warn("Unexpected taskType: {} in ReturnUpsertConsumer", taskType);
                acknowledgment.acknowledge();
                return;
            }

            // 解析訊息
            String merchantId = header.get("merchantId").asText();
            String channelId = header.get("channelId").asText();
            String channelRefundId = body.get("channelRefundId").asText();
            String returnHash = body.get("returnHash").asText();
            JsonNode returnDataJson = body.get("returnData");

            log.info("Processing RETURN_UPSERT: {} from {} (hash: {})",
                channelRefundId, channelId, returnHash.substring(0, 8) + "...");

            // 執行退貨入庫邏輯
            returnUpsertHandler.handleReturnUpsert(merchantId, channelId, channelRefundId, returnHash, returnDataJson);

            // 手動提交 offset（確保退貨已入庫）
            acknowledgment.acknowledge();

            log.info("Successfully processed RETURN_UPSERT: {}", channelRefundId);

        } catch (Exception e) {
            log.error("Error processing RETURN_UPSERT", e);
            // TODO: 發送到 task.failed 重試隊列
        }
    }
}
