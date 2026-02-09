package com.simpleec.common.enums;

import lombok.Getter;

@Getter
public enum ActionType {
    // Product / SellPack
    NEW_SELL_PACK("NewSellPack", "上架新賣場"),
    MODIFY_CONTENT("ModifyContent", "修改商品內容"),
    MODIFY_PRICE("ModifyPrice", "修改價格"),
    MODIFY_QUANTITY("ModifyQuantity", "修改庫存數量"),
    START_SELLING("StartSelling", "開賣"),
    STOP_SELLING("StopSelling", "停售"),
    CHECK_LAUNCH_STATUS("CheckSellpackLaunchStatus", "檢查上架狀態"),
    GET_QUANTITY("GetQuantity", "取得庫存"),

    // Order
    FETCH_ORDERS("GetOrder", "拉取訂單"),
    FETCH_REFUND_ORDERS("GetRefund", "拉取退款單"),
    CHANGE_ORDER_STATUS("ChangeOrderStatus", "變更訂單狀態"),
    SHIPPING_CONFIRMED("ShippingConfirmed", "確認出貨"),
    ORDER_CANCELED("OrderCanceled", "取消訂單"),
    GET_SHIP_CODE("GetShipCode", "取得出貨編號"),
    DOWNLOAD_SHIP_DOCUMENT("DownloadShipDocument", "下載出貨單"),
    ACCEPT_BUYER_CANCELLATION("AcceptBuyerCancellation", "接受買家取消"),
    REJECT_BUYER_CANCELLATION("RejectBuyerCancellation", "拒絕買家取消"),

    // Info sync
    GET_STATIC_INFO("GetStaticInfo", "同步靜態資訊"),
    SYNC_CATEGORIES("SyncCategories", "同步分類"),
    SYNC_BRANDS("SyncBrands", "同步品牌"),

    // Statistics
    FETCH_STATISTICS("FetchStatistics", "拉取統計資料");

    private final String code;
    private final String description;

    ActionType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
