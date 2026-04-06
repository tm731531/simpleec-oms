package com.simpleec.channeljob.util;

import lombok.extern.slf4j.Slf4j;

/**
 * 訂單狀態映射工具 - 基於各平台 API 文檔
 * 負責將各平台的狀態轉換為 OMS 統一狀態
 *
 * OMS 統一狀態（大寫——與 Hibernate @Enumerated(EnumType.STRING) 一致）：
 * - PENDING: 訂單成立/待確認
 * - READY_TO_SHIP: 待出貨
 * - SHIPPING: 出貨中
 * - SHIPPED: 出貨完成
 * - COMPLETED: 訂單完成
 * - CANCELLED: 訂單取消
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
            log.warn("Platform status is null for {}, defaulting to PENDING", platformCode);
            return "PENDING";
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
                yield normalizedStatus.toUpperCase();
            }
        };
    }

    // ========== Cyberbiz ==========
    private static String mapCyberbizStatus(String status) {
        return switch (status) {
            case "pending" -> "PENDING";
            case "paid", "cod", "unshipped" -> "READY_TO_SHIP";  // unshipped = paid but not yet shipped
            case "preparing", "partial" -> "SHIPPING";
            case "fulfilled" -> "SHIPPED";
            case "arrived" -> "SHIPPED";
            case "received" -> "COMPLETED";
            case "problem" -> "SHIPPED";
            case "cancelled" -> "CANCELLED";
            default -> {
                log.warn("Unmapped Cyberbiz status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== Shopee ==========
    private static String mapShopeeStatus(String status) {
        return switch (status) {
            case "unpaid" -> "PENDING";
            case "ready_to_ship", "in_cancel" -> "READY_TO_SHIP";
            case "processed" -> "SHIPPING";
            case "shipped" -> "SHIPPED";
            case "to_confirm_receive", "completed", "invoice_pending" -> "COMPLETED";
            case "cancelled" -> "CANCELLED";
            default -> {
                log.warn("Unmapped Shopee status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== Momo ==========
    private static String mapMomoStatus(String status) {
        return switch (status) {
            case "pending" -> "PENDING";
            case "confirmed" -> "READY_TO_SHIP";
            case "preparing" -> "SHIPPING";
            case "shipped" -> "SHIPPED";
            case "delivered" -> "COMPLETED";
            case "cancelled" -> "CANCELLED";
            default -> {
                log.warn("Unmapped Momo status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== Yahoo ==========
    private static String mapYahooStatus(String status) {
        return switch (status) {
            case "wait_payment" -> "PENDING";
            case "wait_ship" -> "READY_TO_SHIP";
            case "processing" -> "SHIPPING";
            case "shipped" -> "SHIPPED";
            case "cannot_ship" -> "READY_TO_SHIP";
            case "cancelled" -> "CANCELLED";
            case "confirmed_receive" -> "COMPLETED";
            default -> {
                log.warn("Unmapped Yahoo status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== PChome ==========
    private static String mapPchomeStatus(String status) {
        return switch (status) {
            case "checking", "error" -> "READY_TO_SHIP";
            case "shipped" -> "SHIPPED";
            case "stored" -> "COMPLETED";
            case "cancelled" -> "CANCELLED";
            default -> {
                log.warn("Unmapped PChome status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== Easystore ==========
    private static String mapEasystoreStatus(String status) {
        return switch (status) {
            case "unpaid" -> "PENDING";
            case "pending" -> "READY_TO_SHIP";
            case "processing" -> "SHIPPING";
            case "shipped" -> "SHIPPED";
            case "completed" -> "COMPLETED";
            case "cancelled" -> "CANCELLED";
            default -> {
                log.warn("Unmapped Easystore status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== Shopify ==========
    private static String mapShopifyStatus(String status) {
        return switch (status) {
            case "authorized", "unpaid" -> "PENDING";
            case "pending", "partially_paid" -> "READY_TO_SHIP";
            case "partial" -> "SHIPPING";
            case "fulfilled" -> "SHIPPED";
            case "complete" -> "COMPLETED";
            case "expired", "voided" -> "CANCELLED";
            case "refunded", "partially_refunded" -> "COMPLETED";
            default -> {
                log.warn("Unmapped Shopify status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== 台灣樂天 (Rakuten) ==========
    private static String mapRakutenStatus(String status) {
        return switch (status) {
            case "unfixed", "awaitingpayment" -> "PENDING";
            case "processingpayment" -> "READY_TO_SHIP";
            case "notshipped" -> "READY_TO_SHIP";
            case "awaitingcompletion" -> "SHIPPED";
            case "complete" -> "COMPLETED";
            case "cancelled" -> "CANCELLED";
            case "cancelledandrefund" -> "CANCELLED";
            default -> {
                log.warn("Unmapped Rakuten status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== I Open Mall ==========
    private static String mapIOpenStatus(String status) {
        return switch (status) {
            case "pending" -> "PENDING";
            case "confirmed" -> "READY_TO_SHIP";
            case "package" -> "SHIPPING";
            case "shipping" -> "SHIPPED";
            case "shipped" -> "SHIPPED";
            case "in_store" -> "SHIPPED";
            case "cancel" -> "CANCELLED";
            case "complete" -> "COMPLETED";
            default -> {
                log.warn("Unmapped IOpen status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== Ruten ==========
    private static String mapRutenStatus(String status) {
        return switch (status) {
            case "unpaid" -> "PENDING";
            case "tobeconfirmed" -> "READY_TO_SHIP";
            case "readytoship" -> "READY_TO_SHIP";
            case "incancel" -> "READY_TO_SHIP";
            case "shipped" -> "SHIPPED";
            case "cancelled" -> "CANCELLED";
            default -> {
                log.warn("Unmapped Ruten status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== 91app ==========
    private static String map91AppStatus(String status) {
        return switch (status) {
            case "pending" -> "PENDING";
            case "confirmed" -> "READY_TO_SHIP";
            case "shipped" -> "SHIPPED";
            case "completed" -> "COMPLETED";
            case "cancelled" -> "CANCELLED";
            default -> {
                log.warn("Unmapped 91app status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== Shopline ==========
    private static String mapShoplineStatus(String status) {
        return switch (status) {
            case "temp" -> "PENDING";
            case "pending" -> "READY_TO_SHIP";
            case "confirmed" -> "READY_TO_SHIP";
            case "completed" -> "COMPLETED";
            case "cancelled", "removed" -> "CANCELLED";
            case "shipping" -> "SHIPPING";
            case "shipped" -> "SHIPPED";
            case "collected" -> "COMPLETED";
            case "arrived" -> "SHIPPED";
            case "returning" -> "SHIPPED";
            case "returned" -> "COMPLETED";
            default -> {
                log.warn("Unmapped Shopline status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== Yahoo 商城 ==========
    private static String mapYahooMallStatus(String status) {
        return switch (status) {
            case "new" -> "PENDING";
            case "shipped" -> "SHIPPED";
            case "buyerconfirmdate" -> "COMPLETED";
            case "cancel" -> "CANCELLED";
            default -> {
                log.warn("Unmapped Yahoo Mall status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== 韓國 Coupang ==========
    private static String mapCoupangStatus(String status) {
        return switch (status) {
            case "accept" -> "PENDING";
            case "instruct" -> "READY_TO_SHIP";
            case "departure" -> "SHIPPING";
            case "delivering" -> "SHIPPING";
            case "final_delivery" -> "COMPLETED";
            case "none_tracking" -> "SHIPPED";
            default -> {
                log.warn("Unmapped Coupang status: {}", status);
                yield "PENDING";
            }
        };
    }

    // ========== Friday ==========
    private static String mapFridayStatus(String status) {
        return switch (status) {
            case "1000", "1100" -> "PENDING";
            case "1111" -> "SHIPPING";
            case "1110" -> "SHIPPING";
            case "1111_2" -> "COMPLETED";
            default -> {
                log.warn("Unmapped Friday status: {}", status);
                yield "PENDING";
            }
        };
    }
}
