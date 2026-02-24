package com.simpleec.orderjob.consumer;

import com.simpleec.core.entity.Order;
import com.simpleec.core.service.OrderService;
import com.simpleec.common.enums.OrderStatusEnum;
import com.simpleec.common.util.RedisKeyUtil;
import com.simpleec.common.util.NanoIdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * OrderUpsert 消費者 — 訂單入庫的核心業務邏輯
 *
 * 消費 order.process topic 中的 ORDER_UPSERT 消息
 * 執行兩層去重：
 *   1. Redis（快速）
 *   2. 數據庫（並發安全）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderUpsertConsumer {

    private final OrderService orderService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 消費 order.process topic
     */
    @KafkaListener(topics = "order.process", groupId = "order-job-group", concurrency = "3")
    @Transactional
    public void consumeOrderUpsert(@Payload JsonNode json,
                                   @Header(name = "kafka_receivedPartitionId") int partition,
                                   Acknowledgment acknowledgment) {
        try {
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            String taskType = header.get("taskType").asText();

            if (!taskType.equals("ORDER_UPSERT")) {
                log.warn("Unexpected taskType: {} in OrderUpsertConsumer", taskType);
                acknowledgment.acknowledge();
                return;
            }

            // 解析訊息
            String merchantId = header.get("merchantId").asText();
            String channelId = header.get("channelId").asText();
            String channelOrderId = body.get("channelOrderId").asText();
            String orderHash = body.get("orderHash").asText();
            JsonNode orderDataJson = body.get("orderData");
            boolean isRollback = header.has("isRollback") ? header.get("isRollback").asBoolean() : false;

            log.info("Processing ORDER_UPSERT: {} from {} (hash: {}, isRollback: {})",
                channelOrderId, channelId, orderHash.substring(0, 8) + "...", isRollback);

            // 執行訂單入庫邏輯
            handleOrderUpsert(merchantId, channelId, channelOrderId, orderHash, orderDataJson, isRollback);

            // 手動提交 offset（確保訂單已入庫）
            acknowledgment.acknowledge();

            log.info("Successfully processed ORDER_UPSERT: {}", channelOrderId);

        } catch (Exception e) {
            log.error("Error processing ORDER_UPSERT: {}", e.getMessage(), e);
            try {
                // 發送到失敗隊列供人工處理或異步重試
                kafkaTemplate.send("task.failed", "OrderUpsert", json);
                log.info("Message sent to task.failed topic");
            } catch (Exception sendError) {
                log.error("Failed to send message to task.failed", sendError);
            }
            // 確認消息 - 避免無限重複
            acknowledgment.acknowledge();
        }
    }

    /**
     * 處理訂單 UPSERT（INSERT 或 UPDATE）
     *
     * 步驟：
     * 1. Redis 去重檢查（快速判定是否已處理）
     * 2. 數據庫查詢（檢查是否存在）
     * 3. INSERT 或 UPDATE
     * 4. 更新 Redis hash 快取
     */
    private void handleOrderUpsert(String merchantId, String channelId, String channelOrderId,
                                    String orderHash, JsonNode orderDataJson, boolean isRollback) throws Exception {

        // 第 0 步：構建 Redis Key
        String redisKey = RedisKeyUtil.orderHashKey(merchantId, channelId, channelOrderId);

        // 第 1 步：再次檢查 Redis（避免並發重複） — 容錯模式
        try {
            String existingHashInRedis = redisTemplate.opsForValue().get(redisKey);
            if (orderHash.equals(existingHashInRedis)) {
                log.info("Order already processed (Redis hash match): {}", channelOrderId);
                return;
            }
        } catch (Exception e) {
            // Redis 連接失敗時，記錄警告但繼續處理
            log.warn("Redis dedup check failed for order {}, proceeding with database check", channelOrderId, e);
        }

        // 第 2 步：檢查資料庫中是否已存在
        Optional<Order> existingOrder = orderService.findByChannelOrderId(channelId, channelOrderId);

        Order order;
        if (existingOrder.isPresent()) {
            // UPDATE 現有訂單
            order = existingOrder.get();

            // 計算 DB 中現有訂單的 hash（內存，不是從 DB 欄位讀）
            String dbOrderHash = calculateOrderHash(order, orderDataJson);

            if (!orderHash.equals(dbOrderHash)) {
                // Hash 不同 → 有實質變化 → 執行 UPDATE
                order = updateOrderFromData(order, orderDataJson, isRollback);
                log.info("Updated order: {} from channel {} (hash changed)",
                    order.getId(), channelId);
            } else {
                // Hash 相同 → 沒有變化 → 跳過
                log.debug("Order content unchanged: {}", channelOrderId);
                // 但仍要更新 Redis（刷新 TTL）
                try {
                    redisTemplate.opsForValue().set(redisKey, orderHash, Duration.ofDays(7));
                } catch (Exception e) {
                    log.warn("Failed to update Redis cache for order {}", channelOrderId, e);
                }
                return;
            }
        } else {
            // INSERT 新訂單
            order = createOrderFromData(merchantId, channelId, channelOrderId, orderDataJson, isRollback);
            log.info("Created new order: {} from channel {}", order.getId(), channelId);
        }

        // 第 3 步：儲存訂單到資料庫
        Order savedOrder = orderService.updateOrder(order);

        // 第 4 步：更新 Redis hash 快取 — 容錯模式
        try {
            redisTemplate.opsForValue().set(redisKey, orderHash, Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("Failed to update Redis cache for order {}", channelOrderId, e);
        }

        // 第 5 步：觸發後續流程（可選）
        triggerFollowUpTasks(savedOrder);

        log.info("Completed ORDER_UPSERT for: {}", savedOrder.getId());
    }

    /**
     * 從 API 數據創建 Order 實體
     */
    private Order createOrderFromData(String merchantId, String channelId, String channelOrderId,
                                      JsonNode orderDataJson, boolean isRollback) throws Exception {

        Order order = new Order();
        // 生成 Composite NanoID: merchant_first_4_digits + yyyymmddhhmmss + random_code(2)
        order.setId(NanoIdUtil.generateComposite(merchantId));
        order.setMerchantId(merchantId);
        order.setChannelId(channelId);
        order.setChannelOrderId(channelOrderId);

        // 填充訂單數據
        populateOrderFromData(order, orderDataJson, isRollback);

        return order;
    }

    /**
     * 更新現有 Order 實體
     */
    private Order updateOrderFromData(Order order, JsonNode orderDataJson, boolean isRollback) throws Exception {
        populateOrderFromData(order, orderDataJson, isRollback);
        return order;
    }

    /**
     * 從 API 數據填充 Order 實體
     */
    private void populateOrderFromData(Order order, JsonNode orderDataJson, boolean isRollback) throws Exception {
        // 訂單狀態
        if (orderDataJson.has("orderStatus")) {
            String status = orderDataJson.get("orderStatus").asText();
            order.setOrderStatus(OrderStatusEnum.fromCode(status));
        }

        // 總金額
        if (orderDataJson.has("totalAmount")) {
            order.setTotalAmount(
                new java.math.BigDecimal(orderDataJson.get("totalAmount").asText())
            );
        }

        // 運費
        if (orderDataJson.has("shippingFee")) {
            order.setShippingFee(
                new java.math.BigDecimal(orderDataJson.get("shippingFee").asText())
            );
        }

        // 折扣金額
        if (orderDataJson.has("discountAmount")) {
            order.setDiscountAmount(
                new java.math.BigDecimal(orderDataJson.get("discountAmount").asText())
            );
        }

        // 商品清單（JSONB 格式 — 確保為有效的 JSON 字符串）
        if (orderDataJson.has("items")) {
            try {
                JsonNode itemsNode = orderDataJson.get("items");
                String itemsJson = objectMapper.writeValueAsString(itemsNode);
                order.setItems(itemsJson);
            } catch (Exception e) {
                log.warn("Failed to serialize items: {}", e.getMessage());
            }
        }

        // 買家資訊（JSONB 格式 — 確保為有效的 JSON 字符串）
        if (orderDataJson.has("buyerInfo")) {
            try {
                JsonNode buyerNode = orderDataJson.get("buyerInfo");
                String buyerJson = objectMapper.writeValueAsString(buyerNode);
                order.setBuyerInfo(buyerJson);
            } catch (Exception e) {
                log.warn("Failed to serialize buyerInfo: {}", e.getMessage());
            }
        }

        // 配送資訊（JSONB 格式 — 確保為有效的 JSON 字符串）
        if (orderDataJson.has("shippingInfo")) {
            try {
                JsonNode shippingNode = orderDataJson.get("shippingInfo");
                String shippingJson = objectMapper.writeValueAsString(shippingNode);
                order.setShippingInfo(shippingJson);
            } catch (Exception e) {
                log.warn("Failed to serialize shippingInfo: {}", e.getMessage());
            }
        }

        // 通路訂單建立時間（ISO-8601）
        if (orderDataJson.has("channelCreatedAt")) {
            String createdAtStr = orderDataJson.get("channelCreatedAt").asText();
            try {
                LocalDateTime createdAt = Instant.parse(createdAtStr)
                    .atZone(ZoneId.of("UTC"))
                    .toLocalDateTime();
                order.setChannelCreatedAt(createdAt);
            } catch (Exception e) {
                log.warn("Failed to parse channelCreatedAt (ISO-8601): {}", createdAtStr, e);
            }
        }

        // 支付時間（ISO-8601）
        if (orderDataJson.has("paidAt")) {
            String paidAtStr = orderDataJson.get("paidAt").asText();
            try {
                LocalDateTime paidAt = Instant.parse(paidAtStr)
                    .atZone(ZoneId.of("UTC"))
                    .toLocalDateTime();
                order.setPaidAt(paidAt);
            } catch (Exception e) {
                log.warn("Failed to parse paidAt (ISO-8601): {}", paidAtStr, e);
            }
        }

        // 配送時間（ISO-8601）
        if (orderDataJson.has("shippedAt")) {
            String shippedAtStr = orderDataJson.get("shippedAt").asText();
            try {
                LocalDateTime shippedAt = Instant.parse(shippedAtStr)
                    .atZone(ZoneId.of("UTC"))
                    .toLocalDateTime();
                order.setShippedAt(shippedAt);
            } catch (Exception e) {
                log.warn("Failed to parse shippedAt (ISO-8601): {}", shippedAtStr, e);
            }
        }

        // 提取買家標量字段
        if (orderDataJson.has("buyerName")) {
            order.setBuyerName(orderDataJson.get("buyerName").asText());
        }

        if (orderDataJson.has("buyerPhone")) {
            order.setBuyerPhone(orderDataJson.get("buyerPhone").asText());
        }

        if (orderDataJson.has("buyerEmail")) {
            order.setBuyerEmail(orderDataJson.get("buyerEmail").asText());
        }

        // 配送相關標量字段
        if (orderDataJson.has("shippingAddress")) {
            order.setShippingAddress(orderDataJson.get("shippingAddress").asText());
        }

        if (orderDataJson.has("paymentMethod")) {
            order.setPaymentMethod(orderDataJson.get("paymentMethod").asText());
        }

        if (orderDataJson.has("shippingMethod")) {
            order.setShippingMethod(orderDataJson.get("shippingMethod").asText());
        }

        // 設置回補訂單標籤（從 header 讀取）
        order.setIsRollback(isRollback);
    }

    /**
     * 計算訂單 Hash（比對用）
     * 只包含會變動的業務欄位，與 Handler 邏輯保持一致
     */
    private String calculateOrderHash(Order order, JsonNode orderDataJson) {
        try {
            // 只包含會變動的業務欄位
            var sortedData = new java.util.TreeMap<String, Object>();

            if (order.getOrderStatus() != null) {
                sortedData.put("status", order.getOrderStatus().getCode());
            }
            if (order.getTotalAmount() != null) {
                sortedData.put("totalAmount", order.getTotalAmount());
            }
            if (order.getItems() != null) {
                sortedData.put("items", order.getItems());
            }
            if (order.getBuyerInfo() != null) {
                sortedData.put("buyerInfo", order.getBuyerInfo());
            }
            if (order.getShippingInfo() != null) {
                sortedData.put("shippingInfo", order.getShippingInfo());
            }

            String json = objectMapper.writeValueAsString(sortedData);
            return org.apache.commons.codec.digest.DigestUtils.sha256Hex(json);
        } catch (Exception e) {
            log.error("Error calculating order hash", e);
            return "";
        }
    }

    /**
     * 觸發後續流程（如果需要）
     */
    private void triggerFollowUpTasks(Order order) {
        // 可能的後續流程：
        // - 同步商品到 SYNC_PRODUCT
        // - 同步上架配置到 SYNC_PACK
        // - 發送到後端報表系統
        // TODO: 根據業務需求實現
        log.debug("Triggering follow-up tasks for order: {}", order.getId());
    }
}
