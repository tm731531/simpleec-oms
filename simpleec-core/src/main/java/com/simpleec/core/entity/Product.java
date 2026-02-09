package com.simpleec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    @TableField("item_number")
    private String itemNumber;

    private String name;
    private String description;
    private String brand;
    private String mainImageUrl;
    private BigDecimal costPrice;
    private BigDecimal suggestPrice;
    private Integer totalQuantity;
    private Integer safetyQuantity;
    private String status; // active, inactive, deleted

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
