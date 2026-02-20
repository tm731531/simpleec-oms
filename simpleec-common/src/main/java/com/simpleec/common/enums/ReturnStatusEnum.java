package com.simpleec.common.enums;

/**
 * OMS 統一退貨狀態
 */
public enum ReturnStatusEnum {
    PENDING("PENDING", "待處理"),
    APPROVED("APPROVED", "已批准"),
    REJECTED("REJECTED", "已拒絕"),
    COMPLETED("COMPLETED", "已完成"),
    REFUNDED("REFUNDED", "已退款"),
    ;

    private final String code;
    private final String description;

    ReturnStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ReturnStatusEnum fromCode(String code) {
        for (ReturnStatusEnum e : ReturnStatusEnum.values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown ReturnStatus: " + code);
    }
}
