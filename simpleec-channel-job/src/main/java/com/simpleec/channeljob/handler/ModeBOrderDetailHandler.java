package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channel.adapter.CyberbizAdapter;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.service.ChannelService;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.common.constants.TopicConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Mode B 訂單詳情處理器 — 第二層：拉取並處理訂單詳情
 *
 * 流程：
 * 1. 消費 {platform}.detail topic 的 FETCH_ORDER_DETAIL 消息
 * 2. 調用 Adapter.fetchOrderDetail(orderId) 獲取完整訂單資訊
 * 3. 轉換為 OMS 標準 schema
 * 4. 計算訂單 hash（用於去重）
 * 5. 發送 ORDER_UPSERT 消息到 order.process topic
 *
 * 特點：
 * - 相比 Mode A，多了一層 API 呼叫
 * - Hash 計算邏輯與 Mode A 相同
 * - 最終都是發送 ORDER_UPSERT 到 order.process
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModeBOrderDetailHandler {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ChannelService channelService;

    /**
     * 處理 Mode B 訂單詳情
     *
     * @param merchantId      商戶 ID
     * @param channelId       通路 ID (e.g., "shopee")
     * @param channelOrderId  通路訂單 ID
     * @param adapter         通路適配器 (Mode B)
     */
    public void handleModeBOrderDetail(String merchantId, String channelId, String channelOrderId,
                                       ChannelAdapter adapter) throws Exception {

        log.info("Processing Mode B order detail: {} from {}", channelOrderId, channelId);

        try {
            // 若是 CyberbizAdapter，設置 token 和 token2
            if (adapter instanceof CyberbizAdapter) {
                Channel channel = channelService.getChannel(channelId);
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

            // 第 1 步：從 API 拉取完整訂單詳情
            Map<String, Object> orderDetail = adapter.fetchOrderDetail(channelId, channelOrderId);
            log.info("Fetched order detail from {}: {}", channelId, channelOrderId);

            // 第 2 步：轉換為 OMS 標準 schema
            ObjectNode orderData = buildOmsOrderData(orderDetail);

            // 第 3 步：計算 Hash（用於去重）
            String orderHash = calculateOrderHash(orderData);
            log.debug("Calculated order hash: {}", orderHash.substring(0, 8) + "...");

            // 第 4 步：發送 ORDER_UPSERT 消息
            sendOrderUpsertMessage(merchantId, channelId, channelOrderId, orderHash, orderData, adapter.getPlatformCode());

            log.info("Successfully processed Mode B order detail: {}", channelOrderId);

        } catch (Exception e) {
            log.error("Error handling Mode B order detail for {}", channelOrderId, e);
            throw e;
        }
    }

    /**
     * 將通路訂單數據轉換為 OMS 標準 schema
     *
     * OMS Schema:
     * {
     *   "orderStatus": "PENDING|CONFIRMED|READY_TO_SHIP|SHIPPED|COMPLETED|CANCELLED",
     *   "totalAmount": 3200.0,
     *   "shippingFee": 60.0,
     *   "discountAmount": 0.0,
     *   "items": [...],
     *   "buyerInfo": {...},
     *   "buyerName": "王小明",
     *   "buyerPhone": "0912345678",
     *   "buyerEmail": "wang@example.com",
     *   "shippingInfo": {...},
     *   "shippingAddress": "台北市信義區",
     *   "shippingMethod": "HOME_DELIVERY",
     *   "paymentMethod": "CREDIT_CARD",
     *   "channelCreatedAt": "2024-02-20T10:30:00Z",
     *   "paidAt": "2024-02-20T10:31:00Z",
     *   "shippedAt": null
     * }
     */
    private ObjectNode buildOmsOrderData(Map<String, Object> channelData) {
        ObjectNode omsData = objectMapper.createObjectNode();

        // 1. 轉換訂單狀態
        String channelStatus = extractString(channelData, "status", "");
        String omsStatus = mapChannelStatusToOMS(channelStatus);
        omsData.put("orderStatus", omsStatus);

        // 2. 金額資訊（支援多層結構）
        Object amountObj = channelData.get("amount_info");
        if (amountObj instanceof Map) {
            Map<String, Object> amountMap = (Map<String, Object>) amountObj;
            Object totalObj = amountMap.get("total");
            if (totalObj != null) {
                omsData.put("totalAmount", totalObj.toString());
            }
        } else {
            omsData.put("totalAmount", channelData.get("total_amount") != null ?
                channelData.get("total_amount").toString() : "0");
        }

        // 2.5 Shipping Fee
        Object shippingFeeObj = channelData.get("shipping_fee");
        if (shippingFeeObj != null) {
            omsData.put("shippingFee", shippingFeeObj.toString());
        }

        // 2.7 Discount Amount
        Object discountObj = channelData.get("discount_amount");
        if (discountObj != null) {
            omsData.put("discountAmount", discountObj.toString());
        }

        // 3. 商品清單（使用 JSON 數組，不轉字符串，與 Mode A 一致）
        List<?> itemsList = (List<?>) channelData.get("items");
        if (itemsList != null) {
            try {
                omsData.set("items", objectMapper.valueToTree(itemsList));
            } catch (Exception e) {
                log.warn("Failed to set items", e);
            }
        }

        // 4. 買家資訊
        Map<String, Object> buyerInfo = (Map<String, Object>) channelData.get("buyer_info");
        if (buyerInfo != null) {
            try {
                omsData.set("buyerInfo", objectMapper.valueToTree(buyerInfo));

                // 提取買家標量字段
                Object nameObj = buyerInfo.get("name");
                if (nameObj != null) {
                    omsData.put("buyerName", nameObj.toString());
                }
                Object phoneObj = buyerInfo.get("mobile") != null ? buyerInfo.get("mobile") : buyerInfo.get("phone");
                if (phoneObj != null) {
                    omsData.put("buyerPhone", phoneObj.toString());
                }
                Object emailObj = buyerInfo.get("email");
                if (emailObj != null) {
                    omsData.put("buyerEmail", emailObj.toString());
                }
            } catch (Exception e) {
                log.warn("Failed to set buyer info", e);
            }
        }

        // 5. 配送資訊
        Map<String, Object> shippingInfo = (Map<String, Object>) channelData.get("shipping_info");
        if (shippingInfo != null) {
            try {
                omsData.set("shippingInfo", objectMapper.valueToTree(shippingInfo));

                // 提取配送標量字段
                Object addressObj = shippingInfo.get("address");
                if (addressObj != null) {
                    omsData.put("shippingAddress", addressObj.toString());
                }
            } catch (Exception e) {
                log.warn("Failed to set shipping info", e);
            }
        }

        // 5.5 Shipping Method
        Object shippingMethodObj = channelData.get("shipping_method");
        if (shippingMethodObj == null) shippingMethodObj = channelData.get("delivery_method");
        if (shippingMethodObj != null) {
            omsData.put("shippingMethod", shippingMethodObj.toString());
        }

        // 5.7 Payment Method
        Object paymentMethodObj = channelData.get("payment_method");
        if (paymentMethodObj == null) paymentMethodObj = channelData.get("payment_type");
        if (paymentMethodObj != null) {
            omsData.put("paymentMethod", paymentMethodObj.toString());
        }

        // 6. 建立時間（通路訂單建立時間，ISO-8601 格式）
        String createdAt = extractString(channelData, "created_at", Instant.now().toString());
        omsData.put("channelCreatedAt", createdAt);

        // 6.5 Paid At
        Object paidObj = channelData.get("paid_at");
        if (paidObj != null) {
            omsData.put("paidAt", paidObj.toString());
        }

        // 6.7 Shipped At
        Object shippedObj = channelData.get("shipped_at");
        if (shippedObj != null) {
            omsData.put("shippedAt", shippedObj.toString());
        }

        return omsData;
    }

    /**
     * 將通路狀態映射到 OMS 標準狀態
     *
     * 例如：
     * - Shopee: "READY_TO_SHIP" → "READY_TO_SHIP"
     * - Easystore: "pending" → "PENDING"
     */
    private String mapChannelStatusToOMS(String channelStatus) {
        if (channelStatus == null) {
            return "PENDING";
        }

        return channelStatus.toUpperCase()
            .replaceAll("-", "_")
            .replaceAll(" ", "_");
    }

    /**
     * 計算訂單 Hash（與 Mode A 相同邏輯）
     *
     * Hash 只包含會變動的業務欄位：
     * - orderStatus
     * - totalAmount
     * - items
     * - buyerInfo
     * - shippingInfo
     *
     * 不包含 ID、時間戳等不變的欄位
     */
    private String calculateOrderHash(ObjectNode omsData) {
        try {
            TreeMap<String, Object> sortedData = new TreeMap<>();

            if (omsData.has("orderStatus") && !omsData.get("orderStatus").isNull()) {
                sortedData.put("status", omsData.get("orderStatus").asText());
            }
            if (omsData.has("totalAmount") && !omsData.get("totalAmount").isNull()) {
                sortedData.put("totalAmount", omsData.get("totalAmount").asText());
            }
            if (omsData.has("items") && !omsData.get("items").isNull()) {
                sortedData.put("items", omsData.get("items").toString());
            }
            if (omsData.has("buyerInfo") && !omsData.get("buyerInfo").isNull()) {
                sortedData.put("buyerInfo", omsData.get("buyerInfo").toString());
            }
            if (omsData.has("shippingInfo") && !omsData.get("shippingInfo").isNull()) {
                sortedData.put("shippingInfo", omsData.get("shippingInfo").toString());
            }

            String json = objectMapper.writeValueAsString(sortedData);
            return DigestUtils.sha256Hex(json);
        } catch (Exception e) {
            log.error("Error calculating order hash", e);
            return "";
        }
    }

    /**
     * 發送 ORDER_UPSERT 消息到 order.process topic
     */
    private void sendOrderUpsertMessage(String merchantId, String channelId, String channelOrderId,
                                        String orderHash, ObjectNode orderData, String platformCode) throws Exception {

        ObjectNode message = objectMapper.createObjectNode();

        // 構建 header
        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_" + NanoIdUtil.generate());
        header.put("requestId", "req_" + NanoIdUtil.generate());
        header.put("taskType", TaskTypeEnum.ORDER_UPSERT.getCode());
        header.put("platformId", platformCode);
        header.put("channelId", channelId);
        header.put("merchantId", merchantId);
        header.put("timestamp", Instant.now().toString());
        header.put("source", "channel_job");
        header.put("version", 1);
        header.put("isRollback", false);
        message.set("header", header);

        // 構建 body
        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelOrderId", channelOrderId);
        body.put("orderHash", orderHash);
        body.set("orderData", orderData);
        message.set("body", body);

        // 發送到 order.process topic
        kafkaTemplate.send(TopicConstants.ORDER_PROCESS, channelOrderId, message);

        log.info("Sent ORDER_UPSERT to order.process for {}", channelOrderId);
    }

    /**
     * 帮助方法：提取字符串值
     */
    private String extractString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
