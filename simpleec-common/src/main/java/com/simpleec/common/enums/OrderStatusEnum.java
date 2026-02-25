package com.simpleec.common.enums;

/**
 * OMS 統一訂單狀態——所有平台訂單的標準狀態機
 *
 * 注：code 值為大寫，與 Hibernate @Enumerated(EnumType.STRING) 的行為一致
 * 數據庫中存儲的是枚舉名（PENDING, CONFIRMED 等），而不是 code
 * 但為了統一性，code 也使用大寫，便於比較和轉換
 */
public enum OrderStatusEnum {
    PENDING("PENDING", "待確認"),
    CONFIRMED("CONFIRMED", "已確認"),
    READY_TO_SHIP("READY_TO_SHIP", "準備出貨"),
    SHIPPING("SHIPPING", "出貨中"),
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
        if (code == null) return null;

        // 不區分大小寫的查詢——將輸入轉換為大寫進行比較
        String normalizedCode = code.toUpperCase().trim();
        for (OrderStatusEnum e : OrderStatusEnum.values()) {
            if (e.code.equals(normalizedCode)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown OrderStatus: " + code);
    }
}
