package com.simpleec.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * Shopify Webhook 入口
     * POST /webhook/shopify/{event-type}
     * 例如: /webhook/shopify/orders/create, /webhook/shopify/orders/update
     */
    @PostMapping("/shopify/{eventType}")
    public void handleShopifyWebhook(@PathVariable String eventType, @RequestBody String payload) {
        try {
            log.info("Received Shopify webhook: {}", eventType);
            JsonNode shopifyEvent = objectMapper.readTree(payload);

            // 將 Shopify webhook 轉換為 OMS 格式
            ObjectNode omsMessage = convertShopifyWebhook(eventType, shopifyEvent);

            // 發送到對應的 topic
            String topic = mapEventToTopic(eventType);
            kafkaTemplate.send(topic, omsMessage.get("header").get("messageId").asText(),
                objectMapper.writeValueAsString(omsMessage));

            log.info("Shopify webhook processed: {} -> {}", eventType, topic);

        } catch (Exception e) {
            log.error("Error processing Shopify webhook", e);
        }
    }

    /**
     * Shopee Webhook 入口
     */
    @PostMapping("/shopee/{eventType}")
    public void handleShopeeWebhook(@PathVariable String eventType, @RequestBody String payload) {
        try {
            log.info("Received Shopee webhook: {}", eventType);
            JsonNode shopeeEvent = objectMapper.readTree(payload);

            // 將 Shopee webhook 轉換為 OMS 格式
            ObjectNode omsMessage = convertShopeeWebhook(eventType, shopeeEvent);

            // 發送到對應的 topic
            String topic = mapEventToTopic(eventType);
            kafkaTemplate.send(topic, omsMessage.get("header").get("messageId").asText(),
                objectMapper.writeValueAsString(omsMessage));

            log.info("Shopee webhook processed: {} -> {}", eventType, topic);

        } catch (Exception e) {
            log.error("Error processing Shopee webhook", e);
        }
    }

    /**
     * Easystore Webhook 入口
     */
    @PostMapping("/easystore/{eventType}")
    public void handleEasystoreWebhook(@PathVariable String eventType, @RequestBody String payload) {
        try {
            log.info("Received Easystore webhook: {}", eventType);
            JsonNode easystoreEvent = objectMapper.readTree(payload);

            // 將 Easystore webhook 轉換為 OMS 格式
            ObjectNode omsMessage = convertEasystoreWebhook(eventType, easystoreEvent);

            // 發送到對應的 topic
            String topic = mapEventToTopic(eventType);
            kafkaTemplate.send(topic, omsMessage.get("header").get("messageId").asText(),
                objectMapper.writeValueAsString(omsMessage));

            log.info("Easystore webhook processed: {} -> {}", eventType, topic);

        } catch (Exception e) {
            log.error("Error processing Easystore webhook", e);
        }
    }

    /**
     * 轉換 Shopify webhook 為 OMS 格式
     */
    private ObjectNode convertShopifyWebhook(String eventType, JsonNode shopifyEvent) {
        ObjectNode omsMessage = objectMapper.createObjectNode();

        // 構建 header
        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_" + NanoIdUtil.generate());
        header.put("taskType", mapEventTypeToTaskType(eventType));
        header.put("channelId", "shopify");
        header.put("merchantId", "M001");  // TODO: 從 webhook 簽名驗證中提取
        header.put("timestamp", Instant.now().toString());
        header.put("version", "1.0");
        omsMessage.set("header", header);

        // 構建 body - 複製 Shopify 數據
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

        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_" + NanoIdUtil.generate());
        header.put("taskType", mapEventTypeToTaskType(eventType));
        header.put("channelId", "shopee");
        header.put("merchantId", "M001");  // TODO: 從 webhook 簽名驗證中提取
        header.put("timestamp", Instant.now().toString());
        header.put("version", "1.0");
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

        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_" + NanoIdUtil.generate());
        header.put("taskType", mapEventTypeToTaskType(eventType));
        header.put("channelId", "easystore");
        header.put("merchantId", "M001");  // TODO: 從 webhook 簽名驗證中提取
        header.put("timestamp", Instant.now().toString());
        header.put("version", "1.0");
        omsMessage.set("header", header);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("webhookData", easystoreEvent);
        omsMessage.set("body", body);

        return omsMessage;
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
