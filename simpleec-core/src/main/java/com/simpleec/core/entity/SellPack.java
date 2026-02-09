package com.simpleec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sell_pack")
public class SellPack {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String merchantId;
    private String productId;
    private String channelId;
    private String sku;
    private String channelProductId;
    private String channelSpecId;
    private String channelProductName;
    private String channelSpecName;
    private String channelProductUrl;
    private String title;
    private BigDecimal sellingPrice;
    private Integer quantity;
    private String status;

    private LocalDateTime lastSyncAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
