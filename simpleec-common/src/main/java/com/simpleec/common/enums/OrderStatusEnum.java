package com.simpleec.common.enums;

/**
 * OMS 統一訂單狀態——所有平台訂單的標準狀態機
 */
public enum OrderStatusEnum {
    PENDING("PENDING", "待確認"),
    CONFIRMED("CONFIRMED", "已確認"),
    READY_TO_SHIP("READY_TO_SHIP", "準備出貨"),
    SHIPPED("SHIPPED", "已出貨"),
    COMPLETED("COMPLETED", "已完成"),
    CANCELLED("CANCELLED", "已取消"),
    ;

    private final String code;
    private final String description;

    OrderStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static OrderStatusEnum fromCode(String code) {
        for (OrderStatusEnum e : OrderStatusEnum.values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown OrderStatus: " + code);
    }
}
