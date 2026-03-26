package com.simpleec.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.gateway.security.WebhookVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Webhook 入口控制器
 *
 * 接收來自各平台的 webhook 事件，轉換為 OMS 標準格式，發送到 Kafka
 *
 * 支援的 webhook 類型：
 * - 訂單更新（狀態變化、新訂單等）
 * - 退貨申請
 * - 商品信息變更
 * - 庫存警告
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final WebhookVerifier webhookVerifier;

    /**
     * Shopify Webhook 入口
     * POST /webhook/shopify/{event-type}
     * 例如: /webhook/shopify/orders/create, /webhook/shopify/orders/update
     */
    @PostMapping("/shopify/{eventType}")
    public ResponseEntity<Void> handleShopifyWebhook(
            @PathVariable String eventType,
            @RequestHeader(value = "X-Shopify-Hmac-Sha256", required = false) String hmacHeader,
            @RequestBody String rawBody) {
        try {
            if (!webhookVerifier.verifyShopify(rawBody, hmacHeader)) {
                log.warn("Shopify webhook signature verification failed — ignoring payload");
                return ResponseEntity.ok().build();
            }

            log.info("Received Shopify webhook: {}", eventType);
            JsonNode shopifyEvent = objectMapper.readTree(rawBody);

            // 將 Shopify webhook 轉換為 OMS 格式
            ObjectNode omsMessage = convertShopifyWebhook(eventType, shopifyEvent);

            // 發送到對應的 topic
            String topic = mapEventToTopic(eventType);
            kafkaTemplate.send(topic, omsMessage.get("header").get("requestId").asText(),
                objectMapper.writeValueAsString(omsMessage));

            log.info("Shopify webhook processed: {} -> {}", eventType, topic);

        } catch (Exception e) {
            log.error("Error processing Shopify webhook", e);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Shopee Webhook 入口
     */
    @PostMapping("/shopee/{eventType}")
    public ResponseEntity<Void> handleShopeeWebhook(
            @PathVariable String eventType,
            @RequestHeader(value = "X-Shopee-Signature", required = false) String signatureHeader,
            @RequestBody String rawBody) {
        try {
            if (!webhookVerifier.verifyShopee(rawBody, signatureHeader)) {
                log.warn("Shopee webhook signature verification failed — ignoring payload");
                return ResponseEntity.ok().build();
            }

            log.info("Received Shopee webhook: {}", eventType);
            JsonNode shopeeEvent = objectMapper.readTree(rawBody);

            // 將 Shopee webhook 轉換為 OMS 格式
            ObjectNode omsMessage = convertShopeeWebhook(eventType, shopeeEvent);

            // 發送到對應的 topic
            String topic = mapEventToTopic(eventType);
            kafkaTemplate.send(topic, omsMessage.get("header").get("requestId").asText(),
                objectMapper.writeValueAsString(omsMessage));

            log.info("Shopee webhook processed: {} -> {}", eventType, topic);

        } catch (Exception e) {
            log.error("Error processing Shopee webhook", e);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Easystore Webhook 入口
     */
    @PostMapping("/easystore/{eventType}")
    public ResponseEntity<Void> handleEasystoreWebhook(
            @PathVariable String eventType,
            @RequestHeader(value = "X-Easystore-Hmac-Sha256", required = false) String hmacHeader,
            @RequestBody String rawBody) {
        try {
            if (!webhookVerifier.verifyEasystore(rawBody, hmacHeader)) {
                log.warn("Easystore webhook signature verification failed — ignoring payload");
                return ResponseEntity.ok().build();
            }

            log.info("Received Easystore webhook: {}", eventType);
            JsonNode easystoreEvent = objectMapper.readTree(rawBody);

            // 將 Easystore webhook 轉換為 OMS 格式
            ObjectNode omsMessage = convertEasystoreWebhook(eventType, easystoreEvent);

            // 發送到對應的 topic
            String topic = mapEventToTopic(eventType);
            kafkaTemplate.send(topic, omsMessage.get("header").get("requestId").asText(),
                objectMapper.writeValueAsString(omsMessage));

            log.info("Easystore webhook processed: {} -> {}", eventType, topic);

        } catch (Exception e) {
            log.error("Error processing Easystore webhook", e);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 轉換 Shopify webhook 為 OMS 格式
     */
    private ObjectNode convertShopifyWebhook(String eventType, JsonNode shopifyEvent) {
        ObjectNode omsMessage = objectMapper.createObjectNode();

        ObjectNode header = buildHeader(eventType, "shopify", shopifyEvent);
        omsMessage.set("header", header);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("webhookData", shopifyEvent);
        omsMessage.set("body", body);

        return omsMessage;
    }

    /**
     * 轉換 Shopee webhook 為 OMS 格式
     */
    private ObjectNode convertShopeeWebhook(String eventType, JsonNode shopeeEvent) {
        ObjectNode omsMessage = objectMapper.createObjectNode();

        ObjectNode header = buildHeader(eventType, "shopee", shopeeEvent);
        omsMessage.set("header", header);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("webhookData", shopeeEvent);
        omsMessage.set("body", body);

        return omsMessage;
    }

    /**
     * 轉換 Easystore webhook 為 OMS 格式
     */
    private ObjectNode convertEasystoreWebhook(String eventType, JsonNode easystoreEvent) {
        ObjectNode omsMessage = objectMapper.createObjectNode();

        ObjectNode header = buildHeader(eventType, "easystore", easystoreEvent);
        omsMessage.set("header", header);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("webhookData", easystoreEvent);
        omsMessage.set("body", body);

        return omsMessage;
    }

    /**
     * Builds a unified header following the OMS Header/Body contract.
     * merchantId and channelId are extracted from the webhook payload where available;
     * platformId is set to the incoming platform name.
     */
    private ObjectNode buildHeader(String eventType, String platformId, JsonNode payload) {
        ObjectNode header = objectMapper.createObjectNode();
        header.put("taskType", mapEventTypeToTaskType(eventType));
        // merchantId must be verified via webhook signature in production; placeholder until implemented
        String merchantId = payload.has("merchant_id") ? payload.get("merchant_id").asText() : null;
        if (merchantId != null) {
            header.put("merchantId", merchantId);
        } else {
            header.putNull("merchantId");
        }
        header.put("platformId", platformId);
        header.putNull("channelId");
        header.put("requestId", java.util.UUID.randomUUID().toString());
        header.put("timestamp", Instant.now().toString());
        header.put("source", "webhook");
        header.put("version", 1);
        header.put("isRollback", false);
        return header;
    }

    /**
     * 根據 webhook 事件類型映射到 OMS taskType
     */
    private String mapEventTypeToTaskType(String eventType) {
        if (eventType.contains("order") && (eventType.contains("create") || eventType.contains("new"))) {
            return "ORDER_CREATED";
        } else if (eventType.contains("order") && eventType.contains("update")) {
            return "ORDER_UPDATED";
        } else if (eventType.contains("return")) {
            return "RETURN_REQUEST";
        } else if (eventType.contains("product")) {
            return "PRODUCT_UPDATED";
        } else if (eventType.contains("inventory")) {
            return "INVENTORY_ALERT";
        }
        return "WEBHOOK_EVENT";
    }

    /**
     * 根據事件類型映射到 Kafka topic
     */
    private String mapEventToTopic(String eventType) {
        if (eventType.contains("order")) {
            return TopicConstants.ORDER_PROCESS;
        } else if (eventType.contains("return")) {
            return TopicConstants.RETURN_PROCESS;
        } else if (eventType.contains("product") || eventType.contains("inventory")) {
            return TopicConstants.TASK_BACKEND;
        }
        return "webhook.events";
    }

    /**
     * 健康檢查端點
     */
    @GetMapping("/health")
    public String health() {
        return "Gateway is running";
    }
}
