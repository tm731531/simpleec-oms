package com.simpleec.channeljob.util;

import lombok.extern.slf4j.Slf4j;

/**
 * 訂單狀態映射工具 - 基於各平台 API 文檔
 * 負責將各平台的狀態轉換為 OMS 統一狀態
 *
 * OMS 統一狀態（小寫）：
 * - pending: 訂單成立/待確認
 * - ready_to_ship: 待出貨
 * - shipping: 出貨中
 * - shipped: 出貨完成
 * - completed: 訂單完成
 * - cancelled: 訂單取消
 *
 * 注意：退貨通過 return_id + return_flag 單獨處理，不在 order_status 中
 */
@Slf4j
public class OrderStatusMapper {

    /**
     * 根據平台代碼和原始狀態轉換為 OMS 統一狀態
     */
    public static String mapToOmsStatus(String platformCode, String platformStatus) {
        if (platformStatus == null) {
            log.warn("Platform status is null for {}, defaulting to pending", platformCode);
            return "pending";
        }

        String normalizedStatus = platformStatus.toLowerCase().trim();

        return switch (platformCode.toLowerCase()) {
            case "cyberbiz" -> mapCyberbizStatus(normalizedStatus);
            case "shopee" -> mapShopeeStatus(normalizedStatus);
            case "momo" -> mapMomoStatus(normalizedStatus);
            case "yahoo" -> mapYahooStatus(normalizedStatus);
            case "pchome" -> mapPchomeStatus(normalizedStatus);
            case "easystore" -> mapEasystoreStatus(normalizedStatus);
            case "shopify" -> mapShopifyStatus(normalizedStatus);
            case "rakuten" -> mapRakutenStatus(normalizedStatus);
            case "iopen" -> mapIOpenStatus(normalizedStatus);
            case "ruten" -> mapRutenStatus(normalizedStatus);
            case "91app" -> map91AppStatus(normalizedStatus);
            case "shopline" -> mapShoplineStatus(normalizedStatus);
            case "yahoo_mall" -> mapYahooMallStatus(normalizedStatus);
            case "coupang" -> mapCoupangStatus(normalizedStatus);
            case "friday" -> mapFridayStatus(normalizedStatus);
            default -> {
                log.warn("Unknown platform: {}, using normalized status: {}", platformCode, normalizedStatus);
                yield normalizedStatus;
            }
        };
    }

    // ========== Cyberbiz ==========
    private static String mapCyberbizStatus(String status) {
        // 根據 fulfillment_statuses 和 financial_statuses 判斷
        return switch (status) {
            case "pending" -> "pending";              // financial_statuses: pending
            case "paid", "cod" -> "ready_to_ship";    // financial_statuses: paid, cod (待出貨)
            case "preparing", "partial" -> "shipping"; // fulfillment: preparing/partial (出貨中)
            case "fulfilled" -> "shipped";             // fulfillment: fulfilled (出貨完成)
            case "arrived" -> "shipped";               // fulfillment: arrived (到達)
            case "received" -> "completed";            // fulfillment: received (已完成)
            case "problem" -> "shipped";               // fulfillment: problem (問題訂單當作已出貨)
            case "cancelled" -> "cancelled";           // statuses: cancelled
            default -> {
                log.warn("Unmapped Cyberbiz status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== Shopee ==========
    private static String mapShopeeStatus(String status) {
        return switch (status) {
            case "unpaid" -> "pending";                    // 未付款
            case "ready_to_ship", "in_cancel" -> "ready_to_ship"; // 待出貨
            case "processed" -> "shipping";                // 已審核(出貨中)
            case "shipped" -> "shipped";                   // 已出貨
            case "to_confirm_receive", "completed", "invoice_pending" -> "completed"; // 已完成
            case "cancelled" -> "cancelled";               // 已取消
            default -> {
                log.warn("Unmapped Shopee status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== Momo ==========
    private static String mapMomoStatus(String status) {
        return switch (status) {
            case "pending" -> "pending";                   // 未出貨訂單
            case "confirmed" -> "ready_to_ship";           // 出貨確認
            case "preparing" -> "shipping";                // 貨源確認/出貨中
            case "shipped" -> "shipped";                   // 出庫(出貨完成)
            case "delivered" -> "completed";               // 已交付/已完成
            case "cancelled" -> "cancelled";               // 已取消
            default -> {
                log.warn("Unmapped Momo status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== Yahoo ==========
    private static String mapYahooStatus(String status) {
        return switch (status) {
            case "wait_payment" -> "pending";              // 待付款/待出貨確認
            case "wait_ship" -> "ready_to_ship";           // 待出貨
            case "processing" -> "shipping";               // 新版包裝確認(出貨中)
            case "shipped" -> "shipped";                   // 出貨確認/已出貨
            case "cannot_ship" -> "ready_to_ship";         // 無法出貨(降級為待出貨)
            case "cancelled" -> "cancelled";               // 已取消
            case "confirmed_receive" -> "completed";       // 已取消/確認接收(已完成)
            default -> {
                log.warn("Unmapped Yahoo status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== PChome ==========
    private static String mapPchomeStatus(String status) {
        return switch (status) {
            case "checking", "error" -> "ready_to_ship";   // 單號確認/錯誤(待出貨)
            case "shipped" -> "shipped";                   // 已出貨
            case "stored" -> "completed";                  // 寄倉訂單(已完成)
            case "cancelled" -> "cancelled";               // 已取消
            default -> {
                log.warn("Unmapped PChome status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== Easystore ==========
    private static String mapEasystoreStatus(String status) {
        return switch (status) {
            case "unpaid" -> "pending";                    // 待付款
            case "pending" -> "ready_to_ship";             // 待出貨
            case "processing" -> "shipping";               // 處理中(出貨中)
            case "shipped" -> "shipped";                   // 已出貨
            case "completed" -> "completed";               // 已完成
            case "cancelled" -> "cancelled";               // 已取消
            default -> {
                log.warn("Unmapped Easystore status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== Shopify ==========
    private static String mapShopifyStatus(String status) {
        return switch (status) {
            case "authorized", "unpaid" -> "pending";                         // 未付款
            case "pending", "partially_paid" -> "ready_to_ship";              // 待出貨
            case "partial" -> "shipping";                                     // 部分履行(出貨中)
            case "fulfilled" -> "shipped";                                    // 已履行(出貨完成)
            case "complete" -> "completed";                                   // 已完成
            case "expired", "voided" -> "cancelled";                          // 已取消
            case "refunded", "partially_refunded" -> "completed";             // 已退款(已完成)
            default -> {
                log.warn("Unmapped Shopify status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== 台灣樂天 (Rakuten) ==========
    private static String mapRakutenStatus(String status) {
        return switch (status) {
            case "unfixed", "awaitingpayment" -> "pending";         // 待確認/待付款
            case "processingpayment" -> "ready_to_ship";             // 處理中(待出貨)
            case "notshipped" -> "ready_to_ship";                    // 待出貨
            case "awaitingcompletion" -> "shipped";                  // 待完成(已出貨)
            case "complete" -> "completed";                          // 已完成
            case "cancelled" -> "cancelled";                         // 已取消
            case "cancelledandrefund" -> "cancelled";                // 已取消並退款
            default -> {
                log.warn("Unmapped Rakuten status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== I Open Mall ==========
    private static String mapIOpenStatus(String status) {
        return switch (status) {
            case "pending" -> "pending";                    // 帳款確認中
            case "confirmed" -> "ready_to_ship";            // 待出貨
            case "package" -> "shipping";                   // 出貨中
            case "shipping" -> "shipped";                   // 已出貨
            case "shipped" -> "shipped";                    // 已送達
            case "in_store" -> "shipped";                   // 貨到門市
            case "cancel" -> "cancelled";                   // 訂單取消
            case "complete" -> "completed";                 // 已完成
            default -> {
                log.warn("Unmapped IOpen status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== Ruten ==========
    private static String mapRutenStatus(String status) {
        return switch (status) {
            case "unpaid" -> "pending";                     // 未付款
            case "tobeconfirmed" -> "ready_to_ship";        // 待確認(待出貨)
            case "readytoship" -> "ready_to_ship";          // 準備出貨
            case "incancel" -> "ready_to_ship";             // 取消中(降級為待出貨)
            case "shipped" -> "shipped";                    // 已出貨
            case "cancelled" -> "cancelled";                // 已取消
            default -> {
                log.warn("Unmapped Ruten status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== 91app ==========
    private static String map91AppStatus(String status) {
        return switch (status) {
            case "pending" -> "pending";                    // 待處理
            case "confirmed" -> "ready_to_ship";            // 待出貨
            case "shipped" -> "shipped";                    // 已出貨
            case "completed" -> "completed";                // 已完成
            case "cancelled" -> "cancelled";                // 已取消
            default -> {
                log.warn("Unmapped 91app status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== Shopline ==========
    private static String mapShoplineStatus(String status) {
        // Shopline 同時有 order 和 delivery 兩種狀態
        return switch (status) {
            case "temp" -> "pending";                       // 臨時訂單
            case "pending" -> "ready_to_ship";              // 待確認/待出貨
            case "confirmed" -> "ready_to_ship";            // 已確認(待出貨)
            case "completed" -> "completed";                // 已完成
            case "cancelled", "removed" -> "cancelled";     // 已取消
            // delivery_status 優先級
            case "shipping" -> "shipping";                  // 配送中(出貨中)
            case "shipped" -> "shipped";                    // 已出貨
            case "collected" -> "completed";                // 已取貨(已完成)
            case "arrived" -> "shipped";                    // 已到達(出貨完成)
            case "returning" -> "shipped";                  // 退貨中(當作出貨)
            case "returned" -> "completed";                 // 已退貨(已完成，退貨另行處理)
            default -> {
                log.warn("Unmapped Shopline status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== Yahoo 商城 ==========
    private static String mapYahooMallStatus(String status) {
        return switch (status) {
            case "new" -> "pending";                        // 新訂單/待付款
            case "shipped" -> "shipped";                    // 已出貨
            case "buyerconfirmdate" -> "completed";         // 買家確認日期(已完成)
            case "cancel" -> "cancelled";                   // 已取消
            default -> {
                log.warn("Unmapped Yahoo Mall status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== 韓國 Coupang ==========
    private static String mapCoupangStatus(String status) {
        return switch (status) {
            case "accept" -> "pending";                     // 接受訂單
            case "instruct" -> "ready_to_ship";             // 指示(待出貨)
            case "departure" -> "shipping";                 // 出發(出貨中)
            case "delivering" -> "shipping";                // 配送中(出貨中)
            case "final_delivery" -> "completed";           // 最終交付(已完成)
            case "none_tracking" -> "shipped";              // 無追蹤(出貨完成)
            default -> {
                log.warn("Unmapped Coupang status: {}", status);
                yield "pending";
            }
        };
    }

    // ========== Friday ==========
    private static String mapFridayStatus(String status) {
        return switch (status) {
            case "1000", "1100" -> "pending";                      // 待確認
            case "1111" -> "shipping";                             // 出貨中
            case "1110" -> "shipping";                             // 配送中(出貨中)
            case "1111_2" -> "completed";                          // 已送達(已完成)
            default -> {
                log.warn("Unmapped Friday status: {}", status);
                yield "pending";
            }
        };
    }
}
