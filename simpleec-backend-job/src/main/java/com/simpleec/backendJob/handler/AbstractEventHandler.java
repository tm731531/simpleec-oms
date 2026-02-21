package com.simpleec.backendJob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 抽象事件處理器
 *
 * 提供通用的事件處理邏輯
 * 所有具體的報表生成器繼承此類
 */
@Slf4j
public abstract class AbstractEventHandler implements EventHandler {

    /**
     * 從事件中提取商家 ID
     */
    protected String extractMerchantId(JsonNode event) {
        JsonNode body = event.get("body");
        if (body != null) {
            JsonNode merchantId = body.get("merchantId");
            if (merchantId != null && merchantId.isTextual()) {
                return merchantId.asText();
            }
        }
        return null;
    }

    /**
     * 從事件中提取時間戳
     */
    protected String extractTimestamp(JsonNode event) {
        JsonNode header = event.get("header");
        if (header != null) {
            JsonNode timestamp = header.get("timestamp");
            if (timestamp != null && timestamp.isTextual()) {
                return timestamp.asText();
            }
        }
        return null;
    }

    /**
     * 從事件中提取消息 ID
     */
    protected String extractMessageId(JsonNode event) {
        JsonNode header = event.get("header");
        if (header != null) {
            JsonNode messageId = header.get("messageId");
            if (messageId != null && messageId.isTextual()) {
                return messageId.asText();
            }
        }
        return null;
    }

    /**
     * 處理事件的主流程
     */
    @Override
    public void handle(JsonNode event) {
        String messageId = extractMessageId(event);
        String merchantId = extractMerchantId(event);
        String timestamp = extractTimestamp(event);

        log.info("Processing {} - merchantId: {}, messageId: {}, timestamp: {}",
            getTaskType(), merchantId, messageId, timestamp);

        try {
            // 執行具體的報表生成邏輯
            processReport(event, merchantId, timestamp);
            log.info("{} completed successfully - messageId: {}", getTaskType(), messageId);
        } catch (Exception e) {
            log.error("Error processing {} - messageId: {}", getTaskType(), messageId, e);
            throw e;
        }
    }

    /**
     * 由子類實現的具體報表生成邏輯
     * @param event 完整的 Kafka 消息
     * @param merchantId 商家 ID
     * @param timestamp 事件時間戳
     */
    protected abstract void processReport(JsonNode event, String merchantId, String timestamp);
}
