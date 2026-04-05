package com.simpleec.schedulerjob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.util.DateUtil;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.schedulerjob.entity.Channel;
import com.simpleec.schedulerjob.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 排程事件處理器 — 根據時間驅動派發各種任務
 *
 * 消費 heartbeat 消息，根據分鐘位做決策派發：
 *   :00, :05, :10... (% 5 == 0) → FETCH_ORDERS（查詢訂單）
 *   :01, :06, :11... (% 5 == 1) → ORDER_REPORT（訂單報表）, STATS_RECALC（統計重算）
 *   :02, :07, :12... (% 5 == 2) → INVENTORY_REPORT（庫存報表）
 *   :03, :08, :13... (% 5 == 3) → SALES_REPORT（銷售報表）
 *   :04, :09, :14... (% 5 == 4) → RETURN_REPORT（退貨報表）
 *   :05, :15, :25... (% 10 == 5) → KAFKA_HEALTH_CHECK（健康檢查）
 *   每小時 :00 和 :30 → DAILY_REPORT（日報表）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerEventHandler {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ChannelRepository channelRepository;

    /**
     * Processes a heartbeat signal and dispatches scheduled tasks.
     * Only dispatches at second=0 to avoid duplicate dispatches within the same minute.
     *
     * <p>Dispatch strategy:
     * <ul>
     *   <li>STATS_RECALC — single message with merchantId="SYSTEM" (reads from Redis dirty set)</li>
     *   <li>All other task types — one message per distinct active merchant</li>
     * </ul>
     */
    public void handleHeartbeat(JsonNode body, long timestamp) {
        try {
            int minuteOfHour = body.get("minuteOfHour").asInt();
            int secondOfMinute = body.get("secondOfMinute").asInt();
            int mod5 = minuteOfHour % 5;
            int mod10 = minuteOfHour % 10;

            // 只在秒數為 0 時才派發，避免同一分鐘內重複派發
            if (secondOfMinute != 0) {
                return;
            }

            // :00, :05, :10, :15 ...
            if (mod5 == 0) {
                dispatchFetchOrders(timestamp);
                dispatchFetchReturns(timestamp);
            }

            // :01, :06, :11 ...
            if (mod5 == 1) {
                dispatchTask(TaskTypeEnum.ORDER_REPORT, timestamp);
                dispatchTask(TaskTypeEnum.STATS_RECALC, timestamp);  // add this line
            }

            // :02, :07, :12 ...
            if (mod5 == 2) {
                dispatchTask(TaskTypeEnum.INVENTORY_REPORT, timestamp);
            }

            // :03, :08, :13 ...
            if (mod5 == 3) {
                dispatchTask(TaskTypeEnum.SALES_REPORT, timestamp);
            }

            // :04, :09, :14 ...
            if (mod5 == 4) {
                dispatchTask(TaskTypeEnum.RETURN_REPORT, timestamp);
            }

            // :05, :15, :25 ... 每 10 分鐘
            if (mod10 == 5) {
                dispatchTask(TaskTypeEnum.KAFKA_HEALTH_CHECK, timestamp);
            }

            // 每小時 :00 和 :30
            if (minuteOfHour == 0 || minuteOfHour == 30) {
                dispatchTask(TaskTypeEnum.DAILY_REPORT, timestamp);
            }

            // 每小時整點注入假訂單（僅測試用，受 global_config.test_seeder_enabled 控管）
            if (minuteOfHour == 0) {
                dispatchTask(TaskTypeEnum.SEED_TEST_ORDERS, timestamp);
            }

        } catch (Exception e) {
            log.error("Error handling heartbeat event", e);
        }
    }

    /**
     * 派發 FETCH_ORDERS 到已啟用通路的 slow topic
     * 根據 Channel 表查詢已啟用的通路，只派送給啟用的通路
     */
    private void dispatchFetchOrders(long timestamp) {
        try {
            log.info("Dispatching FETCH_ORDERS at {}", DateUtil.toIsoString(timestamp));

            // 從數據庫查詢已啟用的通路
            List<Channel> enabledChannels = channelRepository.findByActivedTrueAndEnableSyncTrue();

            if (enabledChannels.isEmpty()) {
                log.warn("No enabled channels found for FETCH_ORDERS dispatch");
                return;
            }

            for (Channel channel : enabledChannels) {
                try {
                    String platformId = channel.getPlatformId().toLowerCase();
                    String topic = TopicConstants.platformSlowTopic(platformId);
                    ObjectNode message = buildFetchOrdersMessage(TaskTypeEnum.FETCH_ORDERS, timestamp, channel);

                    kafkaTemplate.send(topic, message.get("header").get("requestId").asText(), message);
                    log.debug("Sent FETCH_ORDERS to {} topic for channel {}", topic, channel.getId());
                } catch (Exception e) {
                    log.error("Error dispatching FETCH_ORDERS for channel {}", channel.getId(), e);
                }
            }

        } catch (Exception e) {
            log.error("Error in dispatchFetchOrders", e);
        }
    }

    /**
     * 派發 FETCH_RETURNS 到已啟用通路的 slow topic
     * 與 FETCH_ORDERS 同時觸發（每 5 分鐘）
     */
    private void dispatchFetchReturns(long timestamp) {
        try {
            log.info("Dispatching FETCH_RETURNS at {}", DateUtil.toIsoString(timestamp));

            List<Channel> enabledChannels = channelRepository.findByActivedTrueAndEnableSyncTrue();

            if (enabledChannels.isEmpty()) {
                log.warn("No enabled channels found for FETCH_RETURNS dispatch");
                return;
            }

            for (Channel channel : enabledChannels) {
                try {
                    String platformId = channel.getPlatformId().toLowerCase();
                    String topic = TopicConstants.platformSlowTopic(platformId);
                    ObjectNode message = buildFetchOrdersMessage(TaskTypeEnum.FETCH_RETURNS, timestamp, channel);

                    kafkaTemplate.send(topic, message.get("header").get("requestId").asText(), message);
                    log.debug("Sent FETCH_RETURNS to {} topic for channel {}", topic, channel.getId());
                } catch (Exception e) {
                    log.error("Error dispatching FETCH_RETURNS for channel {}", channel.getId(), e);
                }
            }

        } catch (Exception e) {
            log.error("Error in dispatchFetchReturns", e);
        }
    }

    /**
     * Dispatches a backend task to the task.backend topic.
     *
     * <p>STATS_RECALC is dispatched once with merchantId="SYSTEM" because its handler
     * reads from the Redis dirty set and does not use merchantId for data scoping.
     * All other task types are dispatched once per distinct active merchant.
     */
    private void dispatchTask(TaskTypeEnum taskType, long timestamp) {
        try {
            log.info("Dispatching {} at {}", taskType.getCode(), DateUtil.toIsoString(timestamp));

            if (taskType == TaskTypeEnum.STATS_RECALC) {
                // STATS_RECALC reads from Redis dirty set — merchantId not used
                ObjectNode message = buildTaskMessage(taskType, timestamp, "SYSTEM");
                kafkaTemplate.send(TopicConstants.TASK_BACKEND,
                    message.get("header").get("messageId").asText(), message);
                return;
            }

            // All other tasks: dispatch per active merchant
            List<String> merchantIds = channelRepository.findDistinctMerchantIdsByActivedTrue();
            if (merchantIds.isEmpty()) {
                log.debug("No active merchants for {} dispatch", taskType.getCode());
                return;
            }
            for (String merchantId : merchantIds) {
                ObjectNode message = buildTaskMessage(taskType, timestamp, merchantId);
                kafkaTemplate.send(TopicConstants.TASK_BACKEND,
                    message.get("header").get("messageId").asText(), message);
            }
            log.debug("Dispatched {} to {} merchants", taskType.getCode(), merchantIds.size());

        } catch (Exception e) {
            log.error("Error dispatching {}", taskType.getCode(), e);
        }
    }

    /**
     * 構建 FETCH_ORDERS 消息
     *
     * 重要：此處只發送 channelId，不發送 merchantId
     * ChannelJob 會根據 channelId 從數據庫查詢對應的 merchantId
     * 這樣確保使用的總是最新的、真實的 merchant_id 值
     */
    private ObjectNode buildFetchOrdersMessage(TaskTypeEnum taskType, long timestamp, Channel channel) {
        ObjectNode message = objectMapper.createObjectNode();

        ObjectNode header = objectMapper.createObjectNode();
        header.put("taskType", taskType.getCode());
        header.put("merchantId", channel.getMerchantId());
        header.put("platformId", channel.getPlatformId());
        header.put("channelId", channel.getId());
        header.put("requestId", "sched-" + NanoIdUtil.generate());
        header.put("timestamp", DateUtil.toIsoString(timestamp));
        header.put("source", "scheduler");
        header.put("version", 1);
        header.put("isRollback", false);
        header.put("priority", "NORMAL");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("timestamp", DateUtil.toIsoString(timestamp));

        message.set("header", header);
        message.set("body", body);

        return message;
    }

    /**
     * Builds a task message for the task.backend topic.
     *
     * @param taskType  the type of task to dispatch
     * @param timestamp epoch millis of the triggering heartbeat
     * @param merchantId the target merchant, or "SYSTEM" for system-wide tasks
     */
    private ObjectNode buildTaskMessage(TaskTypeEnum taskType, long timestamp, String merchantId) {
        ObjectNode message = objectMapper.createObjectNode();

        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", NanoIdUtil.generate());
        header.put("requestId", "req_" + NanoIdUtil.generate());
        header.put("taskType", taskType.getCode());
        header.put("merchantId", merchantId);
        header.put("timestamp", DateUtil.toIsoString(timestamp));
        header.put("source", "scheduler");
        header.put("version", 1);
        header.put("isRollback", false);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("merchantId", merchantId);

        message.set("header", header);
        message.set("body", body);

        return message;
    }
}
