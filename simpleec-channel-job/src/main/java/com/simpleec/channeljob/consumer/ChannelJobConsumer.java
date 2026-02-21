package com.simpleec.channeljob.consumer;

import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channeljob.handler.ModeAOrderListHandler;
import com.simpleec.channeljob.handler.ModeBOrderListHandler;
import com.simpleec.channeljob.handler.ModeBOrderDetailHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Channel Job Consumer
 *
 * 消費 {platform}.slow、{platform}.detail 和 {platform}.fast topic 中的任務
 * 根據 taskType 和 Mode 路由到不同的處理器：
 * - Mode A: FETCH_ORDERS → ModeAOrderListHandler
 * - Mode B: FETCH_ORDERS → ModeBOrderListHandler
 * - Mode B: FETCH_ORDER_DETAIL → ModeBOrderDetailHandler
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelJobConsumer {

    private final ModeAOrderListHandler modeAOrderListHandler;
    private final ModeBOrderListHandler modeBOrderListHandler;
    private final ModeBOrderDetailHandler modeBOrderDetailHandler;
    private final ObjectMapper objectMapper;

    // Platform-specific adapters (must be registered as Spring beans)
    private final ChannelAdapter shopifyAdapter;
    private final ChannelAdapter easystoreAdapter;
    private final ChannelAdapter shopeeAdapter;
    private final ChannelAdapter cyberbizAdapter;

    /**
     * 消費 Shopify slow channel
     */
    @KafkaListener(topics = "shopify.slow", groupId = "channel-job-group")
    public void consumeShopifySlow(String message) {
        consumeChannelMessage(message, "shopify");
    }

    /**
     * 消費 Easystore slow channel
     */
    @KafkaListener(topics = "easystore.slow", groupId = "channel-job-group")
    public void consumeEasystoreSlow(String message) {
        consumeChannelMessage(message, "easystore");
    }

    /**
     * 消費 Shopee slow channel
     */
    @KafkaListener(topics = "shopee.slow", groupId = "channel-job-group")
    public void consumeShopeeSlowChannel(String message) {
        consumeChannelMessage(message, "shopee");
    }

    /**
     * 消費 Shopee detail channel（Mode B）
     */
    @KafkaListener(topics = "shopee.detail", groupId = "channel-job-group")
    public void consumeShopeeDetailChannel(String message) {
        consumeDetailChannel(message, "shopee");
    }

    /**
     * 消費 Cyberbiz slow channel
     */
    @KafkaListener(topics = "cyberbiz.slow", groupId = "channel-job-group")
    public void consumeCyberbizSlowChannel(String message) {
        consumeChannelMessage(message, "cyberbiz");
    }

    /**
     * 消費 Cyberbiz detail channel（Mode B）
     */
    @KafkaListener(topics = "cyberbiz.detail", groupId = "channel-job-group")
    public void consumeCyberbizDetailChannel(String message) {
        consumeDetailChannel(message, "cyberbiz");
    }

    /**
     * 通用的 Channel 消息消費邏輯（slow topics）
     */
    private void consumeChannelMessage(String message, String platformCode) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            String taskType = header.get("taskType").asText();
            String channelId = header.get("channelId").asText();
            String merchantId = header.get("merchantId").asText();

            log.info("Processing {} message for {} (channel: {})", taskType, platformCode, channelId);

            // 根據 taskType 路由
            if ("FETCH_ORDERS".equals(taskType)) {
                handleFetchOrders(platformCode, channelId, merchantId, body);
            } else if ("SYNC_PACK".equals(taskType)) {
                log.info("SYNC_PACK not implemented yet");
            } else if ("SHIP_ORDER".equals(taskType) || "UPDATE_INVENTORY".equals(taskType) || "UPDATE_PRICE".equals(taskType)) {
                log.info("{} not implemented yet", taskType);
            } else {
                log.warn("Unknown taskType: {}", taskType);
            }

        } catch (Exception e) {
            log.error("Error processing channel message", e);
        }
    }

    /**
     * Detail channel 消息消費邏輯（Mode B）
     * 消費 {platform}.detail topics 中的 FETCH_ORDER_DETAIL 消息
     */
    private void consumeDetailChannel(String message, String platformCode) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            String taskType = header.get("taskType").asText();
            String channelId = header.get("channelId").asText();
            String merchantId = header.get("merchantId").asText();
            String channelOrderId = body.get("channelOrderId").asText();

            log.info("Processing {} for order {} from {}", taskType, channelOrderId, platformCode);

            if ("FETCH_ORDER_DETAIL".equals(taskType)) {
                handleFetchOrderDetail(platformCode, channelId, merchantId, channelOrderId);
            } else {
                log.warn("Unexpected taskType in detail channel: {}", taskType);
            }

        } catch (Exception e) {
            log.error("Error processing detail channel message", e);
        }
    }

    /**
     * 處理 FETCH_ORDERS（slow topic）
     * Mode A: 直接拉取完整訂單
     * Mode B: 拉取訂單列表，發送 FETCH_ORDER_DETAIL 消息
     */
    private void handleFetchOrders(String platformCode, String channelId, String merchantId, JsonNode body) {
        try {
            String timeRange = body.get("timeRange").asText("last_5_minutes");
            ChannelAdapter adapter = getAdapter(platformCode);

            if (adapter == null) {
                log.error("No adapter found for platform: {}", platformCode);
                return;
            }

            // 根據 Mode 調用不同的處理邏輯
            if ("A".equals(adapter.getMode().getCode())) {
                // Mode A: 直接拉取完整訂單列表
                modeAOrderListHandler.handleModeAOrders(adapter, timeRange, merchantId, channelId);
                log.info("Mode A order list processing completed for {}", platformCode);
            } else {
                // Mode B: 拉取訂單 ID 列表，然後發送詳情查詢消息
                modeBOrderListHandler.handleModeBOrderList(merchantId, channelId, adapter, timeRange);
                log.info("Mode B order list processing completed for {}", platformCode);
            }

        } catch (Exception e) {
            log.error("Error handling FETCH_ORDERS for {}", platformCode, e);
        }
    }

    /**
     * 處理 FETCH_ORDER_DETAIL（detail topic）
     * 從 Adapter 拉取訂單詳情，然後發送 ORDER_UPSERT 消息
     */
    private void handleFetchOrderDetail(String platformCode, String channelId, String merchantId,
                                        String channelOrderId) {
        try {
            ChannelAdapter adapter = getAdapter(platformCode);

            if (adapter == null) {
                log.error("No adapter found for platform: {}", platformCode);
                return;
            }

            // Mode B: 拉取單筆訂單詳情
            modeBOrderDetailHandler.handleModeBOrderDetail(merchantId, channelId, channelOrderId, adapter);
            log.info("Mode B order detail processing completed for {}", channelOrderId);

        } catch (Exception e) {
            log.error("Error handling FETCH_ORDER_DETAIL for {}", channelOrderId, e);
        }
    }

    /**
     * 根據 platform code 獲取適配器
     */
    private ChannelAdapter getAdapter(String platformCode) {
        switch (platformCode.toLowerCase()) {
            case "shopify":
                return shopifyAdapter;
            case "easystore":
                return easystoreAdapter;
            case "shopee":
                return shopeeAdapter;
            case "cyberbiz":
                return cyberbizAdapter;
            default:
                log.warn("Unknown platform: {}", platformCode);
                return null;
        }
    }
}
