package com.simpleec.common.enums;

/**
 * OMS 統一訂單狀態——所有平台訂單的標準狀態機
 *
 * 注：code 值為大寫，與 Hibernate @Enumerated(EnumType.STRING) 的行為一致
 * 數據庫中存儲的是枚舉名（PENDING, CONFIRMED 等），而不是 code
 * 但為了統一性，code 也使用大寫，便於比較和轉換
 *
 * label：簡短的中文標籤（UI 展示用）
 * description：詳細的狀態說明
 */
public enum OrderStatusEnum {
    PENDING("PENDING", "待支付", "等待買家支付款項"),
    CONFIRMED("CONFIRMED", "已確認", "訂單已確認，待出貨"),
    READY_TO_SHIP("READY_TO_SHIP", "待出貨", "準備出貨中"),
    PARTIALLY_SHIPPED("PARTIALLY_SHIPPED", "部分出貨", "訂單已部分出貨，尚有商品待出"),
    SHIPPING("SHIPPING", "出貨中", "訂單已出貨，運送中"),
    SHIPPED("SHIPPED", "已出貨", "訂單已出貨"),
    COMPLETED("COMPLETED", "已完成", "訂單交易完成"),
    CANCELLED("CANCELLED", "已取消", "訂單已取消"),
    ;

    private final String code;
    private final String label;
    private final String description;

    OrderStatusEnum(String code, String label, String description) {
        this.code = code;
        this.label = label;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
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
