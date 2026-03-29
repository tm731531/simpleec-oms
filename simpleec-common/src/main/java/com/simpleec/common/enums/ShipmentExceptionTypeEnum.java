package com.simpleec.common.enums;

/**
 * 出貨異常類型——出貨過程中可能遇到的異常分類
 *
 * 注：code 值為大寫，與 Hibernate @Enumerated(EnumType.STRING) 的行為一致
 * 數據庫中存儲的是枚舉名（OUT_OF_STOCK, DAMAGED 等），而不是 code
 * 但為了統一性，code 也使用大寫，便於比較和轉換
 *
 * label：簡短的中文標籤（UI 展示用）
 */
public enum ShipmentExceptionTypeEnum {
    OUT_OF_STOCK("OUT_OF_STOCK", "缺貨"),
    DAMAGED("DAMAGED", "破損品"),
    ADDRESS_ERROR("ADDRESS_ERROR", "地址錯誤"),
    OTHER("OTHER", "其他"),
    ;

    private final String code;
    private final String label;

    ShipmentExceptionTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static ShipmentExceptionTypeEnum fromCode(String code) {
        if (code == null) return null;

        // 不區分大小寫的查詢——將輸入轉換為大寫進行比較
        String normalizedCode = code.toUpperCase().trim();
        for (ShipmentExceptionTypeEnum e : ShipmentExceptionTypeEnum.values()) {
            if (e.code.equals(normalizedCode)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown ShipmentExceptionType: " + code);
    }
}
