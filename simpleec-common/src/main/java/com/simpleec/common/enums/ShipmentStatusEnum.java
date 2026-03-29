package com.simpleec.common.enums;

/**
 * 出貨單狀態——出貨流程中各個階段的狀態
 *
 * 注：code 值為大寫，與 Hibernate @Enumerated(EnumType.STRING) 的行為一致
 * 數據庫中存儲的是枚舉名（PICKING_LIST, PICKING 等），而不是 code
 * 但為了統一性，code 也使用大寫，便於比較和轉換
 *
 * label：簡短的中文標籤（UI 展示用）
 * description：詳細的狀態說明
 */
public enum ShipmentStatusEnum {
    PICKING_LIST("PICKING_LIST", "整理清單", "正在整理出貨清單"),
    PICKING("PICKING", "找貨中", "倉庫人員正在找貨"),
    PACKING("PACKING", "裝箱中", "正在進行商品裝箱"),
    LABELING("LABELING", "貼標中", "正在貼運單標籤"),
    AWAITING_PICKUP("AWAITING_PICKUP", "等待取件", "出貨單已準備完成，等待物流商取件"),
    DISPATCHED("DISPATCHED", "已取件", "物流商已取件出發"),
    ON_HOLD("ON_HOLD", "暫停處理", "出貨單已暫停，待人工介入處理"),
    CANCELLED("CANCELLED", "已取消", "出貨單已取消"),
    ;

    private final String code;
    private final String label;
    private final String description;

    ShipmentStatusEnum(String code, String label, String description) {
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

    public static ShipmentStatusEnum fromCode(String code) {
        if (code == null) return null;

        // 不區分大小寫的查詢——將輸入轉換為大寫進行比較
        String normalizedCode = code.toUpperCase().trim();
        for (ShipmentStatusEnum e : ShipmentStatusEnum.values()) {
            if (e.code.equals(normalizedCode)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown ShipmentStatus: " + code);
    }

    /** Returns true if this is a terminal state (no further transitions). */
    public boolean isTerminal() {
        return this == DISPATCHED || this == CANCELLED;
    }
}
