package com.simpleec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sell_pack")
public class SellPack {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;
    private Long productId;
    private Long channelId;
    private String channelProductId;
    private String channelProductUrl;
    private String title;
    private BigDecimal sellingPrice;
    private Integer quantity;
    private String status; // draft, pending, active, inactive, failed

    private LocalDateTime lastSyncAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
