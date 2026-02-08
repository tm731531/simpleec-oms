package com.oneec.common.enums;

import lombok.Getter;

@Getter
public enum OrderStatus {
    PENDING("pending", "待處理"),
    CONFIRMED("confirmed", "已確認"),
    PROCESSING("processing", "處理中"),
    SHIPPED("shipped", "已出貨"),
    DELIVERED("delivered", "已送達"),
    COMPLETED("completed", "已完成"),
    CANCELLED("cancelled", "已取消"),
    REFUNDING("refunding", "退款中"),
    REFUNDED("refunded", "已退款");

    private final String code;
    private final String displayName;

    OrderStatus(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }
}
