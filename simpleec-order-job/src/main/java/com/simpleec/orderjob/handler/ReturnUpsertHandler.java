package com.simpleec.orderjob.handler;

import com.simpleec.core.entity.Order;
import com.simpleec.core.entity.ReturnOrder;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.core.service.ReturnOrderService;
import com.simpleec.common.enums.ReturnStatusEnum;
import com.simpleec.common.util.RedisKeyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ReturnUpsert 業務處理器 — 退貨入庫的核心業務邏輯
 *
 * 執行兩層去重：
 *   1. Redis（快速）
 *   2. 資料庫（並發安全）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnUpsertHandler {

    private final ReturnOrderService returnOrderService;
    private final OrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 處理退貨 UPSERT（INSERT 或 UPDATE）
     *
     * 步驟：
     * 1. Redis 去重檢查（快速判定是否已處理）
     * 2. 資料庫查詢（檢查是否存在）
     * 3. INSERT 或 UPDATE
     * 4. 更新 Redis hash 快取
     */
    public void handleReturnUpsert(String merchantId, String channelId, String channelRefundId,
                                   String returnHash, JsonNode returnDataJson) throws Exception {

        // 第 0 步：構建 Redis Key
        String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelRefundId);

        // 第 1 步：再次檢查 Redis（避免並發重複；Redis 不可用時降級到 DB 去重）
        try {
            String existingHashInRedis = redisTemplate.opsForValue().get(redisKey);
            if (returnHash.equals(existingHashInRedis)) {
                log.info("Return already processed (Redis hash match): {}", channelRefundId);
                return;
            }
        } catch (Exception e) {
            log.warn("Redis dedup check failed for return {}, proceeding with DB check", channelRefundId, e);
        }

        // 第 2 步：檢查資料庫中是否已存在
        Optional<ReturnOrder> existingReturn = returnOrderService.findByChannelRefundId(channelRefundId);

        ReturnOrder returnOrder;
        if (existingReturn.isPresent()) {
            // UPDATE 現有退貨
            returnOrder = existingReturn.get();

            // 計算 DB 中現有退貨的 hash（內存，不是從 DB 欄位讀）
            String dbReturnHash = calculateReturnHash(returnOrder, returnDataJson);

            if (!returnHash.equals(dbReturnHash)) {
                // Hash 不同 → 有實質變化 → 執行 UPDATE
                returnOrder = updateReturnFromData(returnOrder, channelId, returnDataJson);
                log.info("Updated return: {} from channel {} (hash changed)",
                    returnOrder.getId(), channelId);
            } else {
                // Hash 相同 → 沒有變化 → 跳過
                log.debug("Return content unchanged: {}", channelRefundId);
                // 但仍要更新 Redis（刷新 TTL）
                redisTemplate.opsForValue().set(redisKey, returnHash, Duration.ofDays(7));
                return;
            }
        } else {
            // INSERT 新退貨
            returnOrder = createReturnFromData(merchantId, channelId, channelRefundId, returnDataJson);
            log.info("Created new return: {} from channel {}", returnOrder.getId(), channelId);
        }

        // 第 3 步：儲存退貨到資料庫
        ReturnOrder savedReturn = returnOrderService.updateReturn(returnOrder);

        // 第 4 步：更新 Redis hash 快取
        redisTemplate.opsForValue().set(redisKey, returnHash, Duration.ofDays(7));

        log.info("Completed RETURN_UPSERT for: {}", savedReturn.getId());
    }

    /**
     * 從 API 數據創建 ReturnOrder 實體
     */
    private ReturnOrder createReturnFromData(String merchantId, String channelId, String channelRefundId,
                                             JsonNode returnDataJson) throws Exception {

        ReturnOrder returnOrder = new ReturnOrder();
        returnOrder.setMerchantId(merchantId);
        returnOrder.setChannelRefundId(channelRefundId);

        // 填充退貨數據
        populateReturnFromData(returnOrder, channelId, returnDataJson);

        return returnOrder;
    }

    /**
     * 更新現有 ReturnOrder 實體
     */
    private ReturnOrder updateReturnFromData(ReturnOrder returnOrder, String channelId, JsonNode returnDataJson) throws Exception {
        populateReturnFromData(returnOrder, channelId, returnDataJson);
        return returnOrder;
    }

    /**
     * 從 API 數據填充 ReturnOrder 實體
     *
     * orderId 解析優先順序：
     *  1. returnData.orderId（OMS 內部 ID，直接使用）
     *  2. returnData.channelOrderId（通路訂單 ID，查詢 orders 表取 OMS ID）
     */
    private void populateReturnFromData(ReturnOrder returnOrder, String channelId, JsonNode returnDataJson) throws Exception {
        if (returnDataJson.has("orderId") && !returnDataJson.get("orderId").isNull()) {
            returnOrder.setOrderId(returnDataJson.get("orderId").asText());
        } else if (returnDataJson.has("channelOrderId") && !returnDataJson.get("channelOrderId").isNull()) {
            String channelOrderId = returnDataJson.get("channelOrderId").asText();
            Optional<Order> order = orderRepository.findByChannelIdAndChannelOrderId(channelId, channelOrderId);
            if (order.isPresent()) {
                returnOrder.setOrderId(order.get().getId());
            } else {
                log.warn("populateReturnFromData: order not found for channelId={} channelOrderId={}",
                        channelId, channelOrderId);
            }
        }

        if (returnDataJson.has("status")) {
            String status = returnDataJson.get("status").asText();
            returnOrder.setReturnStatus(ReturnStatusEnum.fromCode(status));
        }

        if (returnDataJson.has("refundAmount")) {
            returnOrder.setRefundAmount(
                new BigDecimal(returnDataJson.get("refundAmount").asText())
            );
        }

        if (returnDataJson.has("reason")) {
            returnOrder.setReason(returnDataJson.get("reason").asText());
        }

        if (returnDataJson.has("items")) {
            returnOrder.setItems(objectMapper.writeValueAsString(returnDataJson.get("items")));
        }

        if (returnDataJson.has("requestedAt")) {
            returnOrder.setRequestedAt(
                LocalDateTime.parse(returnDataJson.get("requestedAt").asText())
            );
        }
    }

    /**
     * 計算退貨 Hash（比對用）
     */
    private String calculateReturnHash(ReturnOrder returnOrder, JsonNode returnDataJson) {
        try {
            // 只包含會變動的業務欄位
            var sortedData = new java.util.TreeMap<String, Object>();

            if (returnOrder.getReturnStatus() != null) {
                sortedData.put("status", returnOrder.getReturnStatus().getCode());
            }
            if (returnOrder.getRefundAmount() != null) {
                sortedData.put("refundAmount", returnOrder.getRefundAmount());
            }
            if (returnOrder.getReason() != null) {
                sortedData.put("reason", returnOrder.getReason());
            }
            if (returnOrder.getItems() != null) {
                sortedData.put("items", returnOrder.getItems());
            }

            String json = objectMapper.writeValueAsString(sortedData);
            return org.apache.commons.codec.digest.DigestUtils.sha256Hex(json);
        } catch (Exception e) {
            log.error("Error calculating return hash", e);
            return "";
        }
    }
}
