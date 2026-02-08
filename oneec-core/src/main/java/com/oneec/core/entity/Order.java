package com.oneec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;
    private Long channelId;
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

    private LocalDateTime channelCreatedAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
