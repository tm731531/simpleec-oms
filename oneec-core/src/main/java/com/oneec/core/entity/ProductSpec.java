package com.oneec.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product_spec")
public class ProductSpec {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;
    private String skuCode;
    private String specName;
    private String specValue;
    private BigDecimal price;
    private Integer quantity;
    private String barcode;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
