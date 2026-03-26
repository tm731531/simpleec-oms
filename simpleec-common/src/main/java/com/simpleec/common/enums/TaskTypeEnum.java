package com.simpleec.common.enums;

/**
 * TaskType 定義——所有系統操作的行為抽象
 *
 * 與通路無關，代表系統要做什麼事
 * 生成到 Kafka 消息的 header.taskType 欄位
 */
public enum TaskTypeEnum {

    // ========== Channel Job (通路同步) ==========
    FETCH_ORDERS("FETCH_ORDERS", "定時拉取訂單列表", true),
    FETCH_ORDER_DETAIL("FETCH_ORDER_DETAIL", "拉取單筆訂單詳情 (Mode B)", true),
    FETCH_RETURNS("FETCH_RETURNS", "定時拉取退貨列表", true),
    FETCH_RETURN_DETAIL("FETCH_RETURN_DETAIL", "拉取單筆退貨詳情 (Mode B)", true),

    // ========== Channel Actions (平台操作) ==========
    SYNC_PACK("SYNC_PACK", "同步上架配置", false),
    SHIP_ORDER("SHIP_ORDER", "執行出貨", false),
    UPDATE_INVENTORY("UPDATE_INVENTORY", "更新庫存", false),
    UPDATE_PRICE("UPDATE_PRICE", "更新價格", false),
    APPROVE_RETURN("APPROVE_RETURN", "同意退貨", false),
    CANCEL_ORDER("CANCEL_ORDER", "取消訂單", false),

    // ========== Order Processing (訂單入庫) ==========
    ORDER_UPSERT("ORDER_UPSERT", "新建或更新訂單", false),
    ORDER_STATUS_CHANGE("ORDER_STATUS_CHANGE", "訂單狀態變更", false),
    CANCEL_ORDER_INTERNAL("CANCEL_ORDER_INTERNAL", "內部取消訂單", false),

    // ========== Return Processing (退貨入庫) ==========
    RETURN_UPSERT("RETURN_UPSERT", "新建或更新退貨", false),
    APPROVE_RETURN_INTERNAL("APPROVE_RETURN_INTERNAL", "內部同意退貨", false),
    REJECT_RETURN("REJECT_RETURN", "拒絕退貨", false),

    // ========== Backend Tasks (後端任務) ==========
    SYNC_PRODUCT("SYNC_PRODUCT", "商品元數據同步", false),
    ORDER_REPORT("ORDER_REPORT", "訂單報表", false),
    STATS_RECALC("STATS_RECALC", "Statistics Recalculation", false),
    INVENTORY_REPORT("INVENTORY_REPORT", "庫存報表", false),
    SALES_REPORT("SALES_REPORT", "銷售報表", false),
    RETURN_REPORT("RETURN_REPORT", "退貨報表", false),
    KAFKA_HEALTH_CHECK("KAFKA_HEALTH_CHECK", "Kafka 健康檢查", false),
    DAILY_REPORT("DAILY_REPORT", "每日報表", false),

    // ========== System (系統) ==========
    HEARTBEAT("HEARTBEAT", "系統心臟脈搏", true),
    ;

    private final String code;
    private final String description;
    private final boolean isSchedulerDriven;  // 是否由 Scheduler 派發

    TaskTypeEnum(String code, String description, boolean isSchedulerDriven) {
        this.code = code;
        this.description = description;
        this.isSchedulerDriven = isSchedulerDriven;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSchedulerDriven() {
        return isSchedulerDriven;
    }

    public static TaskTypeEnum fromCode(String code) {
        for (TaskTypeEnum e : TaskTypeEnum.values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown TaskType: " + code);
    }
}
