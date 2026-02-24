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
        // 不同平台使用不同的 ID 字段名（Cyberbiz: id, 其他: order_id）
        String channelOrderId = null;
        if (channelOrder.containsKey("id")) {
            channelOrderId = channelOrder.get("id").toString();
        } else if (channelOrder.containsKey("order_id")) {
            channelOrderId = channelOrder.get("order_id").toString();
        } else {
            log.warn("No order ID found in order data for platform: {}", platformCode);
            return;
        }

        ObjectNode omsOrderData = buildOmsOrderData(channelOrder, platformCode);

        // 第四步：計算 Hash（基於完整資料）
        String orderHash = calculateOrderHash(omsOrderData);

        // 第五步：檢查 Redis（第一層去重） — 容錯模式
        try {
            String redisKey = RedisKeyUtil.orderHashKey(merchantId, channelId, channelOrderId);
            String existingHash = redisTemplate.opsForValue().get(redisKey);

            if (orderHash.equals(existingHash)) {
                log.debug("Order unchanged (hash match): {}", channelOrderId);
                return;  // 跳過
            }
        } catch (Exception e) {
            // Redis 連接失敗時，記錄警告但繼續發送消息
            log.warn("Redis dedup check failed for order {}, proceeding with ORDER_UPSERT", channelOrderId, e);
        }

        // 第六步：發送 ORDER_UPSERT 到 order.process topic
        sendOrderUpsert(channelOrderId, omsOrderData, orderHash, channelId, merchantId, platformCode);

        log.info("Sent ORDER_UPSERT for {} from {}", channelOrderId, platformCode);
    }

    /**
     * 構建 OMS Order 數據結構
     * 支援多個平台的字段名差異
     */
    private ObjectNode buildOmsOrderData(Map<String, Object> channelOrder, String platformCode) {
        ObjectNode omsData = objectMapper.createObjectNode();

        // 基本信息（支援不同的字段名）
        // Status
        Object statusObj = channelOrder.get("status");
        if (statusObj != null) {
            omsData.put("orderStatus", statusObj.toString());
        } else {
            omsData.put("orderStatus", "PENDING");  // 預設
        }

        // Total Amount（支援 total_price, total_amount, amount 等）
        Object amountObj = channelOrder.get("total_price");
        if (amountObj == null) amountObj = channelOrder.get("total_amount");
        if (amountObj == null) amountObj = channelOrder.get("amount");
        if (amountObj != null) {
            try {
                omsData.put("totalAmount", Double.parseDouble(amountObj.toString()));
            } catch (Exception e) {
                omsData.put("totalAmount", 0.0);
            }
        } else {
            omsData.put("totalAmount", 0.0);
        }

        // Shipping Fee
        Object shippingFeeObj = channelOrder.get("shipping_fee");
        if (shippingFeeObj != null) {
            try {
                omsData.put("shippingFee", Double.parseDouble(shippingFeeObj.toString()));
            } catch (Exception e) {
                log.warn("Failed to parse shipping_fee for platform: {}", platformCode);
            }
        }

        // Discount Amount
        Object discountObj = channelOrder.get("discount_amount");
        if (discountObj != null) {
            try {
                omsData.put("discountAmount", Double.parseDouble(discountObj.toString()));
            } catch (Exception e) {
                log.warn("Failed to parse discount_amount for platform: {}", platformCode);
            }
        }

        // Created At (通路訂單建立時間)
        Object createdObj = channelOrder.get("created_at");
        if (createdObj != null) {
            omsData.put("channelCreatedAt", createdObj.toString());
        }

        // Paid At
        Object paidObj = channelOrder.get("paid_at");
        if (paidObj != null) {
            omsData.put("paidAt", paidObj.toString());
        }

        // Shipped At
        Object shippedObj = channelOrder.get("shipped_at");
        if (shippedObj != null) {
            omsData.put("shippedAt", shippedObj.toString());
        }

        // Updated At
        Object updatedObj = channelOrder.get("updated_at");
        if (updatedObj != null) {
            omsData.put("updatedAt", updatedObj.toString());
        }

        // Items（支援 line_items, items 等）
        Object itemsObj = channelOrder.get("line_items");
        if (itemsObj == null) itemsObj = channelOrder.get("items");
        if (itemsObj != null) {
            try {
                omsData.set("items", objectMapper.valueToTree(itemsObj));
            } catch (Exception e) {
                log.warn("Failed to set items for platform: {}", platformCode);
            }
        }

        // Shipping Address（支援 shipping_address, shipping_info, shipping 等）
        Object shippingObj = channelOrder.get("shipping_address");
        if (shippingObj == null) shippingObj = channelOrder.get("shipping_info");
        if (shippingObj == null) shippingObj = channelOrder.get("shipping");
        if (shippingObj != null) {
            try {
                omsData.set("shippingInfo", objectMapper.valueToTree(shippingObj));
            } catch (Exception e) {
                log.warn("Failed to set shipping info for platform: {}", platformCode);
            }
        }

        // Customer/Buyer Info（支援 customer, buyer_info, buyer 等）
        Object buyerObj = channelOrder.get("customer");
        if (buyerObj == null) buyerObj = channelOrder.get("buyer_info");
        if (buyerObj == null) buyerObj = channelOrder.get("buyer");
        if (buyerObj != null) {
            try {
                omsData.set("buyerInfo", objectMapper.valueToTree(buyerObj));

                // 提取買家標量字段
                if (buyerObj instanceof Map) {
                    Map<String, Object> buyer = (Map<String, Object>) buyerObj;
                    Object nameObj = buyer.get("name");
                    if (nameObj != null) {
                        omsData.put("buyerName", nameObj.toString());
                    }
                    Object phoneObj = buyer.get("mobile") != null ? buyer.get("mobile") : buyer.get("phone");
                    if (phoneObj != null) {
                        omsData.put("buyerPhone", phoneObj.toString());
                    }
                    Object emailObj = buyer.get("email");
                    if (emailObj != null) {
                        omsData.put("buyerEmail", emailObj.toString());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to set buyer info for platform: {}", platformCode);
            }
        }

        // 提取配送地址標量字段（從 shippingInfo 對象中）
        if (shippingObj instanceof Map) {
            Map<String, Object> shipping = (Map<String, Object>) shippingObj;
            Object addressObj = shipping.get("address");
            if (addressObj != null) {
                omsData.put("shippingAddress", addressObj.toString());
            }
        }

        // 支付方式（支援 payment_method, payment_type 等）
        Object paymentObj = channelOrder.get("payment_method");
        if (paymentObj == null) paymentObj = channelOrder.get("payment_type");
        if (paymentObj != null) {
            omsData.put("paymentMethod", paymentObj.toString());
        }

        // 配送方式
        Object shippingMethodObj = channelOrder.get("shipping_method");
        if (shippingMethodObj == null) shippingMethodObj = channelOrder.get("delivery_method");
        if (shippingMethodObj != null) {
            omsData.put("shippingMethod", shippingMethodObj.toString());
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

            if (omsData.has("orderStatus") && !omsData.get("orderStatus").isNull()) {
                sortedData.put("status", omsData.get("orderStatus").asText());
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
                                 String orderHash, String channelId, String merchantId, String platformCode) throws Exception {

        ObjectNode message = objectMapper.createObjectNode();

        // Header
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

        // Body
        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelOrderId", channelOrderId);
        body.put("orderHash", orderHash);
        body.set("orderData", omsOrderData);

        message.set("header", header);
        message.set("body", body);

        // 發送到 order.process topic
        // 重要：使用 channelOrderId 作為 partition key，確保同一訂單的消息排隊到同一 partition
        kafkaTemplate.send(TopicConstants.ORDER_PROCESS, channelOrderId, message);
    }
}
