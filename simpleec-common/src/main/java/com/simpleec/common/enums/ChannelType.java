package com.simpleec.common.enums;

import lombok.Getter;

@Getter
public enum ChannelType {
    MOMO("momo", "momo購物"),
    SHOPEE("shopee", "蝦皮購物"),
    YAHOO("yahoo", "Yahoo購物中心"),
    PCHOME("pchome", "PChome商店街");

    private final String code;
    private final String displayName;

    ChannelType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public static ChannelType fromCode(String code) {
        for (ChannelType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown channel code: " + code);
    }
}
