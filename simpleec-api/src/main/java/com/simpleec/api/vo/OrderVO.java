package com.simpleec.api.vo;

import com.simpleec.common.util.PiiMasker;
import com.simpleec.core.entity.Order;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderVO {

    private String id;
    private String merchantId;
    private String channelId;
    private String channelOrderId;
    private String orderStatus;
    private String buyerName;
    private String buyerPhone;
    private String buyerEmail;
    private String shippingAddress;
    private String shippingMethod;
    private String paymentMethod;
    private BigDecimal totalAmount;
    private BigDecimal shippingFee;
    private BigDecimal discountAmount;
    private String items;
    private LocalDateTime channelCreatedAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static OrderVO fromMasked(Order order) {
        OrderVO vo = fromPlain(order);
        vo.setBuyerName(PiiMasker.maskName(order.getBuyerName()));
        vo.setBuyerPhone(PiiMasker.maskPhone(order.getBuyerPhone()));
        vo.setBuyerEmail(PiiMasker.maskEmail(order.getBuyerEmail()));
        vo.setShippingAddress(PiiMasker.maskAddress(order.getShippingAddress()));
        return vo;
    }

    public static OrderVO fromPlain(Order order) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setMerchantId(order.getMerchantId());
        vo.setChannelId(order.getChannelId());
        vo.setChannelOrderId(order.getChannelOrderId());
        vo.setOrderStatus(order.getOrderStatus());
        vo.setBuyerName(order.getBuyerName());
        vo.setBuyerPhone(order.getBuyerPhone());
        vo.setBuyerEmail(order.getBuyerEmail());
        vo.setShippingAddress(order.getShippingAddress());
        vo.setShippingMethod(order.getShippingMethod());
        vo.setPaymentMethod(order.getPaymentMethod());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setShippingFee(order.getShippingFee());
        vo.setDiscountAmount(order.getDiscountAmount());
        vo.setItems(order.getItems());
        vo.setChannelCreatedAt(order.getChannelCreatedAt());
        vo.setPaidAt(order.getPaidAt());
        vo.setShippedAt(order.getShippedAt());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());
        return vo;
    }
}
