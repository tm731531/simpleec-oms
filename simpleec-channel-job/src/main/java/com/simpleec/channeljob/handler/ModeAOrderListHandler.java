package com.simpleec.channeljob.handler;

import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.common.enums.ModeEnum;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.util.RedisKeyUtil;
import com.simpleec.common.util.NanoIdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mode A 訂單列表處理器
 *
 * Mode A: 列表 API 已完整 → 直接組織 OMS → 計算 Hash → ORDER_UPSERT
 * 不需要詳情 API 調用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModeAOrderListHandler {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 處理 Mode A 訂單列表
     *
     * @param adapter 通路適配器
     * @param timeRange 時間範圍
     * @param merchantId 商家 ID
     * @param channelId 通路實例 ID
     */
    public void handleModeAOrders(ChannelAdapter adapter, String timeRange,
                                   String merchantId, String channelId) {
        try {
            log.info("Processing Mode A orders for {} ({})", adapter.getPlatformCode(), channelId);

            if (adapter.getMode() != ModeEnum.A) {
                throw new IllegalArgumentException("This handler only supports Mode A");
            }

            // 第一步：呼叫列表 API（已含完整資訊）
            List<Map<String, Object>> orders = adapter.fetchOrders(channelId, timeRange);

            log.info("Fetched {} orders from {} API", orders.size(), adapter.getPlatformCode());

            // 第二步：逐單處理
            for (Map<String, Object> channelOrder : orders) {
                try {
                    processOrder(channelOrder, adapter.getPlatformCode(), channelId, merchantId);
                } catch (Exception e) {
                    log.error("Error processing order from {}", adapter.getPlatformCode(), e);
                    // 繼續處理下一筆，不中斷整個流程
                }
            }

            log.info("Completed processing {} orders from {}", orders.size(), adapter.getPlatformCode());

        } catch (Exception e) {
            log.error("Error handling Mode A orders for {}", adapter.getPlatformCode(), e);
        }
    }

    /**
     * 處理單筆訂單
     */
    private void processOrder(Map<String, Object> channelOrder, String platformCode,
                              String channelId, String merchantId) throws Exception {

        // 第三步：組織成 OMS Order 結構
        String channelOrderId = channelOrder.get("order_id").toString();
        ObjectNode omsOrderData = buildOmsOrderData(channelOrder, platformCode);

        // 第四步：計算 Hash（基於完整資料）
        String orderHash = calculateOrderHash(omsOrderData);

        // 第五步：檢查 Redis（第一層去重）
        String redisKey = RedisKeyUtil.orderHashKey(merchantId, channelId, channelOrderId);
        String existingHash = redisTemplate.opsForValue().get(redisKey);

        if (orderHash.equals(existingHash)) {
            log.debug("Order unchanged (hash match): {}", channelOrderId);
            return;  // 跳過
        }

        // 第六步：發送 ORDER_UPSERT 到 order.process topic
        sendOrderUpsert(channelOrderId, omsOrderData, orderHash, channelId, merchantId);

        log.info("Sent ORDER_UPSERT for {} from {}", channelOrderId, platformCode);
    }

    /**
     * 構建 OMS Order 數據結構
     */
    private ObjectNode buildOmsOrderData(Map<String, Object> channelOrder, String platformCode) {
        ObjectNode omsData = objectMapper.createObjectNode();

        // 基本信息
        omsData.put("status", channelOrder.get("status").toString());
        omsData.put("totalAmount", Double.parseDouble(channelOrder.get("total_price").toString()));
        omsData.put("createdAt", channelOrder.get("created_at").toString());
        omsData.put("updatedAt", channelOrder.get("updated_at").toString());

        // Items（Mode A 已完整）
        if (channelOrder.containsKey("line_items")) {
            omsData.set("items", objectMapper.valueToTree(channelOrder.get("line_items")));
        }

        // Shipping Address
        if (channelOrder.containsKey("shipping_address")) {
            omsData.set("shippingInfo", objectMapper.valueToTree(channelOrder.get("shipping_address")));
        }

        // Customer/Buyer Info
        if (channelOrder.containsKey("customer")) {
            omsData.set("buyerInfo", objectMapper.valueToTree(channelOrder.get("customer")));
        }

        return omsData;
    }

    /**
     * 計算訂單 Hash（SHA-256）
     * 只包含會變動的業務欄位，避免時間戳等不變欄位造成假陽性
     */
    private String calculateOrderHash(ObjectNode omsData) {
        try {
            // 只提取會變動的業務欄位
            TreeMap<String, Object> sortedData = new TreeMap<>();

            if (omsData.has("status") && !omsData.get("status").isNull()) {
                sortedData.put("status", omsData.get("status").asText());
            }
            if (omsData.has("totalAmount") && !omsData.get("totalAmount").isNull()) {
                sortedData.put("totalAmount", omsData.get("totalAmount").asText());
            }
            if (omsData.has("items") && !omsData.get("items").isNull()) {
                sortedData.put("items", omsData.get("items").toString());
            }
            if (omsData.has("shippingInfo") && !omsData.get("shippingInfo").isNull()) {
                sortedData.put("shippingInfo", omsData.get("shippingInfo").toString());
            }
            if (omsData.has("buyerInfo") && !omsData.get("buyerInfo").isNull()) {
                sortedData.put("buyerInfo", omsData.get("buyerInfo").toString());
            }

            String json = objectMapper.writeValueAsString(sortedData);
            return org.apache.commons.codec.digest.DigestUtils.sha256Hex(json);
        } catch (Exception e) {
            log.error("Error calculating hash", e);
            return "";
        }
    }

    /**
     * 發送 ORDER_UPSERT 到 order.process topic
     */
    private void sendOrderUpsert(String channelOrderId, ObjectNode omsOrderData,
                                 String orderHash, String channelId, String merchantId) throws Exception {

        ObjectNode message = objectMapper.createObjectNode();

        // Header
        ObjectNode header = objectMapper.createObjectNode();
        String messageId = "msg_" + NanoIdUtil.generate();
        header.put("messageId", messageId);
        header.put("taskType", TaskTypeEnum.ORDER_UPSERT.getCode());
        header.put("channelId", channelId);
        header.put("merchantId", merchantId);
        header.put("timestamp", Instant.now().toString());
        header.put("version", "1.0");

        // Body
        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelOrderId", channelOrderId);
        body.put("orderHash", orderHash);
        body.set("orderData", omsOrderData);

        message.set("header", header);
        message.set("body", body);

        // 發送到 order.process topic
        kafkaTemplate.send(TopicConstants.ORDER_PROCESS, messageId, message);
    }
}
