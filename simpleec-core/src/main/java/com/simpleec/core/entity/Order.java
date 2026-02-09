package com.simpleec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.simpleec.core.crypto.EncryptedFieldTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName(value = "orders", autoResultMap = true)
public class Order {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String merchantId;
    private String channelId;
    private String channelOrderId;
    private String orderStatus;

    @TableField(typeHandler = EncryptedFieldTypeHandler.class)
    private String buyerName;

    @TableField(typeHandler = EncryptedFieldTypeHandler.class)
    private String buyerPhone;

    @TableField(typeHandler = EncryptedFieldTypeHandler.class)
    private String buyerEmail;

    @TableField(typeHandler = EncryptedFieldTypeHandler.class)
    private String shippingAddress;

    private String shippingMethod;
    private String paymentMethod;

    private BigDecimal totalAmount;
    private BigDecimal shippingFee;
    private BigDecimal discountAmount;

    /** JSONB — 訂單明細 */
    private String items;

    private LocalDateTime channelCreatedAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
