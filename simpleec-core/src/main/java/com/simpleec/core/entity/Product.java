package com.simpleec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String merchantId;
    private String productGroupId;
    private String sku;
    private String name;
    private String specSummary;
    private BigDecimal costPrice;
    private BigDecimal suggestPrice;
    private Integer quantity;
    private Integer safetyQuantity;
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
