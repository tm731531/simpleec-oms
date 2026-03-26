package com.simpleec.orderjob.consumer;

import com.simpleec.core.crypto.EncryptionContext;
import com.simpleec.core.entity.Order;
import com.simpleec.core.service.OrderService;
import com.simpleec.common.enums.OrderStatusEnum;
import com.simpleec.common.kafka.SchemaVersionHandler;
import com.simpleec.common.kafka.TaskMdcHelper;
import com.simpleec.common.kafka.UnsupportedSchemaVersionException;
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
            SchemaVersionHandler.validate(json);
        } catch (UnsupportedSchemaVersionException e) {
            log.error("Unsupported schema version in ORDER_UPSERT message: {}", e.getMessage());
            kafkaTemplate.send("task.dlt", "OrderUpsert", json);
            acknowledgment.acknowledge();
            return;
        }

        TaskMdcHelper.set(json);
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
            String platformId = header.has("platformId") ? header.get("platformId").asText() : "unknown";
            String channelOrderId = body.get("channelOrderId").asText();
            String channelOrderNumber = body.has("channelOrderNumber")
                ? body.get("channelOrderNumber").asText() : null;
            String orderHash = body.get("orderHash").asText();
            JsonNode orderDataJson = body.get("orderData");
            boolean isRollback = header.has("isRollback") ? header.get("isRollback").asBoolean() : false;

            log.info("Processing ORDER_UPSERT: {} from {} (hash: {}, isRollback: {})",
                channelOrderId, channelId, orderHash.substring(0, 8) + "...", isRollback);

            // Set encryption context for PII field encryption/decryption
            EncryptionContext.setMerchantId(merchantId);
            try {
                // 執行訂單入庫邏輯
                handleOrderUpsert(merchantId, platformId, channelId, channelOrderId, channelOrderNumber, orderHash, orderDataJson, isRollback);
            } finally {
                EncryptionContext.clear();
            }

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
        } finally {
            TaskMdcHelper.clear();
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
    private void handleOrderUpsert(String merchantId, String platformId, String channelId, String channelOrderId, String channelOrderNumber,
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
                // 設定 channelOrderNumber（若提供）
                if (channelOrderNumber != null && !channelOrderNumber.isBlank()) {
                    order.setChannelOrderNumber(channelOrderNumber);
                }
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
            order = createOrderFromData(merchantId, channelId, channelOrderId, channelOrderNumber, orderDataJson, isRollback);
            log.info("Created new order: {} from channel {}", order.getId(), channelId);
        }

        // 第 3 步：儲存訂單到資料庫
        log.info("Before saving order - ID: {}, Status: {}, Amount: {}",
            order.getId(), order.getOrderStatus(), order.getTotalAmount());
        Order savedOrder = orderService.updateOrder(order);
        log.info("After updateOrder() returned - ID: {}, Status: {}, Amount: {}",
            savedOrder.getId(), savedOrder.getOrderStatus(), savedOrder.getTotalAmount());

        // ★ 立即驗證訂單是否真的被保存到資料庫
        Optional<Order> verifyOrder = orderService.findById(savedOrder.getId());
        if (verifyOrder.isPresent()) {
            log.info("✓ VERIFIED: Order {} successfully persisted to database", savedOrder.getId());
        } else {
            log.error("✗ FAILED: Order {} was NOT persisted to database after updateOrder()!", savedOrder.getId());
        }

        // 第 4 步：更新 Redis hash 快取 — 容錯模式
        try {
            redisTemplate.opsForValue().set(redisKey, orderHash, Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("Failed to update Redis cache for order {}", channelOrderId, e);
        }

        // Step 5: Mark stats dirty for this channel's date
        try {
            java.time.LocalDate statDate = isRollback && order.getChannelCreatedAt() != null
                ? order.getChannelCreatedAt().toLocalDate()
                : java.time.LocalDate.now();
            String member = com.simpleec.common.util.RedisKeyUtil.statsDirtyMember(merchantId, platformId, channelId, statDate.toString());
            redisTemplate.opsForZSet().add(
                com.simpleec.common.util.RedisKeyUtil.STATS_DIRTY_KEY,
                member,
                System.currentTimeMillis()
            );
            log.debug("Marked stats dirty: {}", member);
        } catch (Exception e) {
            log.warn("Failed to write stats dirty marker for order {}", channelOrderId, e);
        }

        log.info("Completed ORDER_UPSERT for: {}", savedOrder.getId());
    }

    /**
     * 從 API 數據創建 Order 實體
     */
    private Order createOrderFromData(String merchantId, String channelId, String channelOrderId, String channelOrderNumber,
                                      JsonNode orderDataJson, boolean isRollback) throws Exception {

        Order order = new Order();
        // 生成 Composite NanoID: merchant_first_4_digits + yyyymmddhhmmss + random_code(2)
        order.setId(NanoIdUtil.generateComposite(merchantId));
        order.setMerchantId(merchantId);
        order.setChannelId(channelId);
        order.setChannelOrderId(channelOrderId);
        order.setChannelOrderNumber(channelOrderNumber);

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

        // 總金額（可選；數據庫約束: NOT NULL DEFAULT 0，Hibernate @ColumnDefault 會使用 DB DEFAULT）
        if (orderDataJson.has("totalAmount")) {
            order.setTotalAmount(
                new java.math.BigDecimal(orderDataJson.get("totalAmount").asText())
            );
        }
        // 不提供時，保持 null — Hibernate 會使用 @ColumnDefault("0")

        // 運費（可選；數據庫約束: NOT NULL DEFAULT 0）
        if (orderDataJson.has("shippingFee")) {
            order.setShippingFee(
                new java.math.BigDecimal(orderDataJson.get("shippingFee").asText())
            );
        }
        // 不提供時，保持 null — Hibernate 會使用 @ColumnDefault("0")

        // 折扣金額（可選；數據庫約束: NOT NULL DEFAULT 0）
        if (orderDataJson.has("discountAmount")) {
            order.setDiscountAmount(
                new java.math.BigDecimal(orderDataJson.get("discountAmount").asText())
            );
        }
        // 不提供時，保持 null — Hibernate 會使用 @ColumnDefault("0")

        // 商品清單（JSONB 格式 — 可選；數據庫約束: NOT NULL DEFAULT '[]'::jsonb）
        if (orderDataJson.has("items")) {
            try {
                JsonNode itemsNode = orderDataJson.get("items");
                String itemsJson = objectMapper.writeValueAsString(itemsNode);
                order.setItems(itemsJson);
            } catch (Exception e) {
                log.warn("Failed to serialize items: {}", e.getMessage());
                // 序列化失敗時，保持 null — Hibernate 會使用 @ColumnDefault("'[]'::jsonb")
            }
        }
        // 不提供時，保持 null — Hibernate 會使用 @ColumnDefault("'[]'::jsonb")

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


}
