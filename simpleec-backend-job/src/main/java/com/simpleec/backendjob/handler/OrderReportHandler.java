package com.simpleec.backendjob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.core.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 訂單報表生成器
 *
 * 職責：
 * - 統計訂單數量、金額、狀態分布
 * - 按平台、時間段統計
 * - 生成 CSV/JSON 報表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReportHandler {

    private final OrderService orderService;
    private final ObjectMapper objectMapper;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 生成訂單報表
     *
     * Body 格式：
     * {
     *   "reportType": "DAILY|WEEKLY|MONTHLY",
     *   "merchantId": "M001",
     *   "startDate": "2024-02-20",
     *   "endDate": "2024-02-21"
     * }
     */
    public void generateOrderReport(String merchantId, JsonNode body) throws Exception {
        try {
            String reportType = body.get("reportType").asText("DAILY");
            String startDate = body.get("startDate").asText();
            String endDate = body.get("endDate").asText();

            log.info("Generating {} order report for merchant {} ({}~{})",
                reportType, merchantId, startDate, endDate);

            // 統計數據
            OrderStatistics stats = collectOrderStatistics(merchantId, startDate, endDate);

            // 生成報表
            String report = buildOrderReport(stats, reportType);
            log.info("Order report generated:\n{}", report);

            // TODO: 存儲報表到數據庫或文件系統
            // saveReport(merchantId, "ORDER_REPORT", reportType, report);

            log.info("Order report completed for {}", merchantId);

        } catch (Exception e) {
            log.error("Error generating order report for {}", merchantId, e);
            throw e;
        }
    }

    /**
     * 收集訂單統計數據
     */
    private OrderStatistics collectOrderStatistics(String merchantId, String startDate, String endDate) {
        OrderStatistics stats = new OrderStatistics();
        stats.merchantId = merchantId;
        stats.startDate = startDate;
        stats.endDate = endDate;
        stats.generatedAt = LocalDateTime.now().format(FORMATTER);

        // TODO: 從 OrderService 查詢實際數據
        // stats.totalOrders = orderService.countByMerchant(merchantId, startDate, endDate);
        // stats.totalAmount = orderService.sumAmountByMerchant(merchantId, startDate, endDate);
        // stats.pendingOrders = orderService.countByStatus(merchantId, "PENDING", startDate, endDate);

        // 模擬數據
        stats.totalOrders = 150;
        stats.totalAmount = new BigDecimal("45000.00");
        stats.completedOrders = 120;
        stats.pendingOrders = 20;
        stats.cancelledOrders = 10;
        stats.avgOrderValue = new BigDecimal("300.00");

        // 平台分布
        stats.shopifyOrders = 50;
        stats.shopeeOrders = 60;
        stats.easystoreOrders = 40;

        return stats;
    }

    /**
     * 構建報表文本
     */
    private String buildOrderReport(OrderStatistics stats, String reportType) {
        StringBuilder report = new StringBuilder();

        report.append("====================================\n");
        report.append("訂單報表 (").append(reportType).append(")\n");
        report.append("====================================\n");
        report.append("商戶ID: ").append(stats.merchantId).append("\n");
        report.append("日期範圍: ").append(stats.startDate).append(" ~ ").append(stats.endDate).append("\n");
        report.append("生成時間: ").append(stats.generatedAt).append("\n");
        report.append("\n");

        report.append("--- 概況統計 ---\n");
        report.append("總訂單數: ").append(stats.totalOrders).append("\n");
        report.append("總金額: ").append(stats.totalAmount).append(" TWD\n");
        report.append("平均訂單金額: ").append(stats.avgOrderValue).append(" TWD\n");
        report.append("\n");

        report.append("--- 訂單狀態分布 ---\n");
        report.append("已完成: ").append(stats.completedOrders).append(" (")
            .append(percentage(stats.completedOrders, stats.totalOrders)).append("%)\n");
        report.append("待處理: ").append(stats.pendingOrders).append(" (")
            .append(percentage(stats.pendingOrders, stats.totalOrders)).append("%)\n");
        report.append("已取消: ").append(stats.cancelledOrders).append(" (")
            .append(percentage(stats.cancelledOrders, stats.totalOrders)).append("%)\n");
        report.append("\n");

        report.append("--- 平台分布 ---\n");
        report.append("Shopify: ").append(stats.shopifyOrders).append(" (")
            .append(percentage(stats.shopifyOrders, stats.totalOrders)).append("%)\n");
        report.append("Shopee: ").append(stats.shopeeOrders).append(" (")
            .append(percentage(stats.shopeeOrders, stats.totalOrders)).append("%)\n");
        report.append("Easystore: ").append(stats.easystoreOrders).append(" (")
            .append(percentage(stats.easystoreOrders, stats.totalOrders)).append("%)\n");
        report.append("\n");
        report.append("====================================\n");

        return report.toString();
    }

    private String percentage(int part, int total) {
        if (total == 0) return "0";
        return String.format("%.1f", (part * 100.0) / total);
    }

    // 內部數據類
    static class OrderStatistics {
        String merchantId;
        String startDate;
        String endDate;
        String generatedAt;

        int totalOrders;
        BigDecimal totalAmount;
        int completedOrders;
        int pendingOrders;
        int cancelledOrders;
        BigDecimal avgOrderValue;

        int shopifyOrders;
        int shopeeOrders;
        int easystoreOrders;
    }
}
