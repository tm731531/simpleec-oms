package com.simpleec.channeljob.consumer;

import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channel.adapter.CyberbizAdapter;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.handler.ApproveReturnHandler;
import com.simpleec.channeljob.handler.FetchReturnsHandler;
import com.simpleec.channeljob.handler.ModeAOrderListHandler;
import com.simpleec.channeljob.handler.ModeBOrderListHandler;
import com.simpleec.channeljob.handler.ModeBOrderDetailHandler;
import com.simpleec.channeljob.handler.RejectReturnHandler;
import com.simpleec.channeljob.handler.ShipOrderHandler;
import com.simpleec.channeljob.handler.UpdateInventoryHandler;
import com.simpleec.channeljob.handler.UpdatePriceHandler;
import com.simpleec.channeljob.service.ChannelService;
import com.simpleec.channeljob.service.HealthCheckService;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.kafka.SchemaVersionHandler;
import com.simpleec.common.kafka.TaskMdcHelper;
import com.simpleec.common.kafka.UnsupportedSchemaVersionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
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

    private final ApproveReturnHandler approveReturnHandler;
    private final FetchReturnsHandler fetchReturnsHandler;
    private final ModeAOrderListHandler modeAOrderListHandler;
    private final ModeBOrderListHandler modeBOrderListHandler;
    private final ModeBOrderDetailHandler modeBOrderDetailHandler;
    private final RejectReturnHandler rejectReturnHandler;
    private final ShipOrderHandler shipOrderHandler;
    private final UpdateInventoryHandler updateInventoryHandler;
    private final UpdatePriceHandler updatePriceHandler;
    private final ObjectMapper objectMapper;
    private final ChannelService channelService;
    private final HealthCheckService healthCheckService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

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
            // Get the consume method (accepts String, will be parsed to JsonNode)
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
     *
     * 重要：merchantId 從數據庫 channel 表查詢，而不是從消息頭讀取
     * Scheduler 只需發送 channelId，ChannelJob 負責查詢對應的 merchantId
     */
    public void consumeChannelMessage(String messageJson) {
        try {
            // Parse JSON string to JsonNode
            JsonNode json = objectMapper.readTree(messageJson);

            try {
                SchemaVersionHandler.validate(json);
            } catch (UnsupportedSchemaVersionException e) {
                log.error("Unsupported schema version in channel message: {}", e.getMessage());
                kafkaTemplate.send(TopicConstants.TASK_DLT, "ChannelJob", messageJson);
                return;
            }

            TaskMdcHelper.set(json);
            try {

            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            if (header == null || body == null) {
                log.error("Malformed channel message: missing header or body — routing to DLT");
                kafkaTemplate.send(TopicConstants.TASK_DLT, "ChannelJob", messageJson);
                return;
            }

            String taskType = header.path("taskType").asText();
            String platformCode = extractPlatformFromGroupId();

            log.debug("Processing {} message for {}", taskType, platformCode);

            // CHECK_HEALTH_PLATFORM 不需要 channelId，直接處理
            if ("CHECK_HEALTH_PLATFORM".equals(taskType)) {
                healthCheckService.performPlatformHealthCheck(platformCode);
                log.info("Platform health check completed for {}", platformCode);
                return;
            }

            // 其他 taskType 需要 channelId 和 merchantId
            String channelId = header.path("channelId").asText("");
            if (channelId.isBlank()) {
                log.error("Channel message missing channelId for taskType={} — routing to DLT", taskType);
                kafkaTemplate.send(TopicConstants.TASK_DLT, "ChannelJob", messageJson);
                return;
            }

            // 從數據庫查詢 Channel，獲得真實的 merchantId
            Channel channel = channelService.getChannel(channelId);
            if (channel == null) {
                log.error("Channel not found: {}", channelId);
                return;
            }
            String merchantId = channel.getMerchantId();

            log.debug("Processing {} message for {} (channel: {}, merchant: {})",
                taskType, platformCode, channelId, merchantId);

            // 提取消息的時間戳（心跳時間）
            String timestamp = header.path("timestamp").asText();
            long baseTimestamp = parseTimestamp(timestamp);

            // 根據 taskType 路由
            if ("FETCH_ORDERS".equals(taskType)) {
                handleFetchOrders(platformCode, channelId, merchantId, body, baseTimestamp);
            } else if ("FETCH_ORDER_DETAIL".equals(taskType)) {
                String channelOrderId = body.path("channelOrderId").asText();
                handleFetchOrderDetail(platformCode, channelId, merchantId, channelOrderId);
            } else if ("FETCH_RETURNS".equals(taskType)) {
                fetchReturnsHandler.handleFetchReturns(platformCode, channelId, merchantId, baseTimestamp);
            } else if ("CHECK_HEALTH".equals(taskType)) {
                // 檢查特定通路的 token 健康狀況
                healthCheckService.performChannelHealthCheck(channelId);
                log.info("Health check completed for channel {}", channelId);
            } else if ("SYNC_PACK".equals(taskType)) {
                log.debug("SYNC_PACK not implemented yet");
            } else if ("SHIP_ORDER".equals(taskType)) {
                shipOrderHandler.handleShipOrder(platformCode, channelId, merchantId, body);
            } else if ("UPDATE_INVENTORY".equals(taskType)) {
                updateInventoryHandler.handleUpdateInventory(platformCode, channelId, merchantId, body);
            } else if ("UPDATE_PRICE".equals(taskType)) {
                updatePriceHandler.handleUpdatePrice(platformCode, channelId, merchantId, body);
            } else if ("APPROVE_RETURN".equals(taskType)) {
                approveReturnHandler.handleApproveReturn(platformCode, channelId, merchantId, body);
            } else if ("REJECT_RETURN".equals(taskType)) {
                rejectReturnHandler.handleRejectReturn(platformCode, channelId, merchantId, body);
            } else {
                log.warn("Unknown taskType: {}", taskType);
            }

            } finally {
                TaskMdcHelper.clear();
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
    private void handleFetchOrders(String platformCode, String channelId, String merchantId, JsonNode body, long baseTimestamp) {
        try {
            // Use path() instead of get() to handle missing fields safely
            String timeRange = body.path("timeRange").asText("last_5_minutes");
            ChannelAdapter adapter = getAdapter(platformCode);

            if (adapter == null) {
                log.error("No adapter found for platform: {}", platformCode);
                return;
            }

            // 若是 CyberbizAdapter，設置 token 和 token2
            if (adapter instanceof CyberbizAdapter) {
                com.simpleec.channeljob.entity.Channel channel = channelService.getChannel(channelId);
                if (channel == null) {
                    throw new IllegalArgumentException("Channel not found for: " + channelId);
                }
                String token = channel.getToken();
                String token2 = channel.getToken2();
                if (token == null || token.isEmpty() || token2 == null || token2.isEmpty()) {
                    throw new IllegalArgumentException("Channel credentials not fully configured for: " + channelId);
                }
                ((CyberbizAdapter) adapter).setCredentials(token, token2);
            }

            // 根據 Mode 調用不同的處理邏輯
            if ("A".equals(adapter.getMode().getCode())) {
                // Mode A: 直接拉取完整訂單列表（使用 baseTimestamp 計算時間窗口）
                // 調用兩次 API：新建（7天） + 更新（1天），然後合併去重
                modeAOrderListHandler.handleModeAOrders(adapter, baseTimestamp, merchantId, channelId);
                log.info("Mode A order list processing completed for {}", platformCode);
            } else {
                // Mode B: 拉取訂單 ID 列表，然後發送詳情查詢消息
                modeBOrderListHandler.handleModeBOrderList(merchantId, channelId, adapter, baseTimestamp);
                log.info("Mode B order list processing completed for {}", platformCode);
            }

        } catch (Exception e) {
            log.error("Error handling FETCH_ORDERS for {}", platformCode, e);
        }
    }

    /**
     * 解析 ISO-8601 格式的時間戳為 Unix 時間戳（秒）
     */
    private long parseTimestamp(String isoTimestamp) {
        try {
            return java.time.Instant.parse(isoTimestamp).getEpochSecond();
        } catch (Exception e) {
            log.warn("Failed to parse timestamp: {}, using current time", isoTimestamp);
            return java.time.Instant.now().getEpochSecond();
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
