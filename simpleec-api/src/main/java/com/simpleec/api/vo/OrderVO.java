package com.simpleec.api.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.core.entity.Order;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 訂單視圖對象 - 前端期望的格式
 *
 * 將 Order 實體轉換為前端期望的字段結構
 */
@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderVO {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private String id;
    private String merchantId;

    // 前端期望的字段
    private String orderNumber;              // 訂單編號（使用 channelOrderNumber 或 fallback 到 channelOrderId）
    private String platform;                 // 通路（從 channelId 提取或映射）
    private String status;                   // 訂單狀態（從 orderStatus 轉換）
    private BigDecimal totalAmount;          // 金額
    private LocalDateTime createdAt;         // 建立時間
    private LocalDateTime updatedAt;         // 更新時間
    private Map<String, Object> syncStatus;  // 同步狀態（暫時為 null）

    // 額外的有用字段
    private String channelId;
    private String channelOrderId;
    private String channelOrderNumber;       // 平台訂單號碼（給用戶看的）
    private String orderStatus;              // 原始訂單狀態
    private BigDecimal shippingFee;
    private BigDecimal discountAmount;
    private String buyerName;
    private String buyerPhone;
    private String buyerEmail;
    private Object items;
    private Object buyerInfo;
    private Object shippingInfo;
    private String paymentMethod;
    private String shippingMethod;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;
    private LocalDateTime channelCreatedAt;
    private Boolean isRollback;

    /**
     * 從 Order 實體轉換為 OrderVO
     */
    public static OrderVO from(Order order, String platformName) {
        OrderVO vo = new OrderVO();

        vo.setId(order.getId());
        vo.setMerchantId(order.getMerchantId());
        vo.setChannelId(order.getChannelId());
        vo.setChannelOrderId(order.getChannelOrderId());
        vo.setChannelOrderNumber(order.getChannelOrderNumber());

        // 前端期望的字段
        // 優先顯示人可讀的訂單號碼，fallback 到 channelOrderId
        String displayNumber = order.getChannelOrderNumber() != null && !order.getChannelOrderNumber().isEmpty()
            ? order.getChannelOrderNumber()
            : order.getChannelOrderId();
        vo.setOrderNumber(displayNumber);
        vo.setPlatform(platformName);                  // 通路名稱
        vo.setStatus(order.getOrderStatus().getCode());  // 統一使用小寫
        vo.setTotalAmount(order.getTotalAmount());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());
        vo.setSyncStatus(null);  // 暫時不提供同步狀態，前端會顯示 "-"

        // 額外字段
        vo.setOrderStatus(order.getOrderStatus().getCode());
        vo.setShippingFee(order.getShippingFee());
        vo.setDiscountAmount(order.getDiscountAmount());
        vo.setBuyerName(order.getBuyerName());
        vo.setBuyerPhone(order.getBuyerPhone());
        vo.setBuyerEmail(order.getBuyerEmail());
        vo.setItems(parseJson(order.getItems()));
        vo.setBuyerInfo(order.getBuyerInfo());
        vo.setShippingInfo(order.getShippingInfo());
        vo.setPaymentMethod(order.getPaymentMethod());
        vo.setShippingMethod(order.getShippingMethod());
        vo.setPaidAt(order.getPaidAt());
        vo.setShippedAt(order.getShippedAt());
        vo.setChannelCreatedAt(order.getChannelCreatedAt());
        vo.setIsRollback(order.isRollback());

        return vo;
    }

    /**
     * Parse JSON string to object so frontend receives a proper array/object, not a string.
     * Returns the original string if parsing fails.
     */
    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("Failed to parse items JSON: {}", e.getMessage());
            return json;
        }
    }
}
