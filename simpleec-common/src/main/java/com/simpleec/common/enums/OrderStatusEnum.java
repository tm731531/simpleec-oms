package com.simpleec.common.enums;

/**
 * OMS 統一訂單狀態——所有平台訂單的標準狀態機
 */
public enum OrderStatusEnum {
    PENDING("pending", "待確認"),
    CONFIRMED("confirmed", "已確認"),
    READY_TO_SHIP("ready_to_ship", "準備出貨"),
    SHIPPING("shipping", "出貨中"),
    SHIPPED("shipped", "已出貨"),
    COMPLETED("completed", "已完成"),
    CANCELLED("cancelled", "已取消"),
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
        if (code == null) return null;

        // 精確查詢（統一使用小寫）
        String normalizedCode = code.toLowerCase().trim();
        for (OrderStatusEnum e : OrderStatusEnum.values()) {
            if (e.code.equals(normalizedCode)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown OrderStatus: " + code);
    }
}
