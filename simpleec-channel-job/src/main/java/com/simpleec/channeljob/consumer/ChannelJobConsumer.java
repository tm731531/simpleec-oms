package com.simpleec.channeljob.consumer;

import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channeljob.handler.ModeAOrderListHandler;
import com.simpleec.channeljob.handler.ModeBOrderListHandler;
import com.simpleec.channeljob.handler.ModeBOrderDetailHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.listener.KafkaListenerErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Channel Job Consumer with Dynamic Listener Registration
 *
 * 根據環境變數 JOB_CHANNEL_TOPICS 和 JOB_CHANNEL_GROUP_ID 動態註冊 Kafka Listener
 * 支援 {platform}.fast 和 {platform}.slow topics
 *
 * 環境變數：
 * - JOB_CHANNEL_TOPICS: 逗號分隔的 topics (例如: "momo.fast,momo.slow")
 * - JOB_CHANNEL_GROUP_ID: consumer group ID (例如: "channel-job-momo")
 * - JOB_CHANNEL_CONCURRENCY: 並發度 (預設: 8)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelJobConsumer {

    private final ModeAOrderListHandler modeAOrderListHandler;
    private final ModeBOrderListHandler modeBOrderListHandler;
    private final ModeBOrderDetailHandler modeBOrderDetailHandler;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired(required = false)
    private DefaultMessageHandlerMethodFactory messageHandlerMethodFactory;

    @Autowired(required = false)
    private org.springframework.kafka.listener.ContainerProperties.AckMode ackMode;

    @Autowired(required = false)
    private org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory;

    // Platform-specific adapters (must be registered as Spring beans)
    private final ChannelAdapter shopifyAdapter;
    private final ChannelAdapter easystoreAdapter;
    private final ChannelAdapter shopeeAdapter;
    private final ChannelAdapter cyberbizAdapter;

    @Value("${JOB_CHANNEL_TOPICS:}")
    private String topicsConfig;

    @Value("${JOB_CHANNEL_GROUP_ID:channel-job-group}")
    private String groupId;

    @Value("${JOB_CHANNEL_CONCURRENCY:8}")
    private int concurrency;

    @PostConstruct
    public void registerDynamicListeners() {
        System.out.println("=== REGISTER_DYNAMIC_LISTENERS CALLED ===");
        System.out.println("groupId=" + groupId + ", topicsConfig=" + topicsConfig + ", concurrency=" + concurrency);
        log.info("Initializing ChannelJobConsumer with groupId={}, topics={}, concurrency={}",
                groupId, topicsConfig, concurrency);

        if (topicsConfig == null || topicsConfig.trim().isEmpty()) {
            log.warn("JOB_CHANNEL_TOPICS is not configured, listener registration skipped");
            return;
        }

        if (kafkaListenerEndpointRegistry == null) {
            log.error("KafkaListenerEndpointRegistry not available, cannot register dynamic listener");
            return;
        }

        String[] topics = Arrays.stream(topicsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        if (topics.length == 0) {
            log.warn("No valid topics configured");
            return;
        }

        try {
            // Get the consume method
            Method consumeMethod = this.getClass().getDeclaredMethod("consumeChannelMessage", String.class);

            // Create endpoint
            MethodKafkaListenerEndpoint<String, String> endpoint = new MethodKafkaListenerEndpoint<>();
            endpoint.setId("dynamic-channel-listener-" + groupId);
            endpoint.setGroupId(groupId);
            endpoint.setTopics(topics);
            endpoint.setMethod(consumeMethod);
            endpoint.setBean(this);
            endpoint.setConcurrency(concurrency);

            // Set message handler factory - use existing or create new one
            if (messageHandlerMethodFactory != null) {
                endpoint.setMessageHandlerMethodFactory(messageHandlerMethodFactory);
            } else {
                // Create a default factory if not autowired
                org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory defaultFactory =
                    new org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory();
                try {
                    defaultFactory.afterPropertiesSet();
                } catch (Exception e) {
                    log.warn("Failed to initialize default MessageHandlerMethodFactory", e);
                }
                endpoint.setMessageHandlerMethodFactory(defaultFactory);
            }

            // Register the endpoint with factory
            if (kafkaListenerContainerFactory == null) {
                log.error("KafkaListenerContainerFactory not available");
                return;
            }
            kafkaListenerEndpointRegistry.registerListenerContainer(
                    endpoint,
                    kafkaListenerContainerFactory
            );

            log.info("✓ Dynamic listener registered: groupId={}, topics={}", groupId, Arrays.toString(topics));

        } catch (NoSuchMethodException e) {
            log.error("Failed to find consumeChannelMessage method", e);
        } catch (Exception e) {
            log.error("Failed to register dynamic listener", e);
        }
    }

    /**
     * 消費 Channel 消息 (動態註冊，支援所有配置的 topics)
     */
    public void consumeChannelMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            String taskType = header.get("taskType").asText();
            String channelId = header.get("channelId").asText();
            String merchantId = header.get("merchantId").asText();
            String platformCode = extractPlatformFromGroupId();

            log.debug("Processing {} message for {} (channel: {})", taskType, platformCode, channelId);

            // 根據 taskType 路由
            if ("FETCH_ORDERS".equals(taskType)) {
                handleFetchOrders(platformCode, channelId, merchantId, body);
            } else if ("FETCH_ORDER_DETAIL".equals(taskType)) {
                String channelOrderId = body.get("channelOrderId").asText();
                handleFetchOrderDetail(platformCode, channelId, merchantId, channelOrderId);
            } else if ("SYNC_PACK".equals(taskType)) {
                log.debug("SYNC_PACK not implemented yet");
            } else if ("SHIP_ORDER".equals(taskType) || "UPDATE_INVENTORY".equals(taskType) || "UPDATE_PRICE".equals(taskType)) {
                log.debug("{} not implemented yet", taskType);
            } else {
                log.warn("Unknown taskType: {}", taskType);
            }

        } catch (Exception e) {
            log.error("Error processing channel message", e);
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
     * 處理 FETCH_ORDER_DETAIL（slow topic）
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

    /**
     * 從 groupId 提取 platform code
     * 例如: channel-job-momo → momo
     */
    private String extractPlatformFromGroupId() {
        if (groupId.startsWith("channel-job-")) {
            return groupId.substring("channel-job-".length());
        }
        return "unknown";
    }
}
