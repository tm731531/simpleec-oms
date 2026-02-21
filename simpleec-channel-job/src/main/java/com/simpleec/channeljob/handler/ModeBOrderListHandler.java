package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.common.constants.TopicConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Mode B 訂單列表處理器 — 第一層：拉取訂單 ID 列表
 *
 * 流程：
 * 1. 消費 {platform}.slow topic 的 FETCH_ORDERS 消息
 * 2. 調用 Adapter.fetchOrderList() 獲取訂單 ID 列表（無詳情）
 * 3. 為每個訂單 ID 發送 FETCH_ORDER_DETAIL 消息到同一個 {platform}.slow topic
 * 4. 詳情處理由 ModeBOrderDetailHandler 負責（ChannelJobConsumer 根據 tasktype 路由）
 *
 * 速率控制：
 * - 每個訂單 ID 獨立一條消息（便於並發詳情查詢）
 * - Consumer 可配置 concurrency=8 提高吞吐
 * - 詳情查詢會分散到多個 consumer 實例
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModeBOrderListHandler {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 處理 Mode B 訂單列表
     *
     * @param merchantId 商戶 ID
     * @param channelId  通路 ID (e.g., "shopee")
     * @param adapter    通路適配器 (Mode B)
     * @param timeRange  時間範圍 (e.g., "last_30_minutes")
     */
    public void handleModeBOrderList(String merchantId, String channelId, ChannelAdapter adapter,
                                     String timeRange) throws Exception {

        log.info("Processing Mode B order list for {} from {}", merchantId, channelId);

        try {
            // 第 1 步：從 API 拉取訂單 ID 列表（不含詳情）
            List<String> orderIds = adapter.fetchOrderList(timeRange);
            log.info("Fetched {} order IDs from {} for {}", orderIds.size(), channelId, merchantId);

            if (orderIds.isEmpty()) {
                log.debug("No orders found for {} in {}", merchantId, channelId);
                return;
            }

            // 第 2 步：為每個訂單 ID 發送 FETCH_ORDER_DETAIL 消息
            for (String channelOrderId : orderIds) {
                try {
                    sendFetchDetailMessage(merchantId, channelId, channelOrderId);
                } catch (Exception e) {
                    log.error("Failed to send detail fetch message for order {}", channelOrderId, e);
                    // 繼續處理其他訂單（不中斷整個列表）
                }
            }

            log.info("Sent {} FETCH_ORDER_DETAIL messages for {}", orderIds.size(), channelId);

        } catch (Exception e) {
            log.error("Error handling Mode B order list for {} from {}", merchantId, channelId, e);
            throw e;
        }
    }

    /**
     * 發送 FETCH_ORDER_DETAIL 消息到 {platform}.slow topic（同一個 channel topic）
     *
     * 消息格式：
     * {
     *   "header": {
     *     "messageId": "msg_xxx",
     *     "taskType": "FETCH_ORDER_DETAIL",
     *     "channelId": "shopee",
     *     "merchantId": "M001",
     *     "timestamp": "2024-02-20T10:35:00Z",
     *     "version": "1.0"
     *   },
     *   "body": {
     *     "channelOrderId": "SHP-202402-00001"
     *   }
     * }
     */
    private void sendFetchDetailMessage(String merchantId, String channelId, String channelOrderId)
            throws Exception {

        ObjectNode message = objectMapper.createObjectNode();

        // 構建 header
        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_" + NanoIdUtil.generate());
        header.put("taskType", TaskTypeEnum.FETCH_ORDER_DETAIL.getCode());
        header.put("channelId", channelId);
        header.put("merchantId", merchantId);
        header.put("timestamp", Instant.now().toString());
        header.put("version", "1.0");
        message.set("header", header);

        // 構建 body（只需要訂單 ID）
        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelOrderId", channelOrderId);
        message.set("body", body);

        // 發送到 {platform}.slow topic（同一個 channel topic，只是 tasktype 不同）
        String slowTopic = TopicConstants.platformSlowTopic(channelId);
        String messageStr = objectMapper.writeValueAsString(message);

        kafkaTemplate.send(slowTopic, channelOrderId, messageStr);
        log.debug("Sent FETCH_ORDER_DETAIL for {} to topic {}", channelOrderId, slowTopic);
    }
}
