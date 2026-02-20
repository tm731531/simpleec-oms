package com.simpleec.common.enums;

/**
 * 通路處理模式
 *
 * Mode A: 列表 API 已含完整資訊 → 單一 Handler 直接處理
 * Mode B: 列表 API 缺少詳情 → 需要兩個 Handler (List + Detail)
 */
public enum ModeEnum {
    A("A", "直接模式 - 列表 API 已完整"),
    B("B", "列表+詳情模式 - 需要二次 API 呼叫"),
    ;

    private final String code;
    private final String description;

    ModeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ModeEnum fromCode(String code) {
        for (ModeEnum e : ModeEnum.values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown Mode: " + code);
    }
}
