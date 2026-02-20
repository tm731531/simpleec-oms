package com.simpleec.common.enums;

/**
 * 電商平台列舉
 */
public enum PlatformEnum {
    SHOPIFY("SHOPIFY", "Shopify", ModeEnum.A),
    SHOPEE("SHOPEE", "蝦皮", ModeEnum.B),
    MOMO("MOMO", "Momo 購物", ModeEnum.B),
    YAHOO("YAHOO", "Yahoo", ModeEnum.B),
    PCHOME("PCHOME", "PChome", ModeEnum.B),
    CYBERBIZ("CYBERBIZ", "Cyberbiz", ModeEnum.B),
    EASYSTORE("EASYSTORE", "Easystore", ModeEnum.A),
    ;

    private final String code;
    private final String name;
    private final ModeEnum mode;

    PlatformEnum(String code, String name, ModeEnum mode) {
        this.code = code;
        this.name = name;
        this.mode = mode;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public ModeEnum getMode() {
        return mode;
    }

    public static PlatformEnum fromCode(String code) {
        for (PlatformEnum e : PlatformEnum.values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown Platform: " + code);
    }
}
