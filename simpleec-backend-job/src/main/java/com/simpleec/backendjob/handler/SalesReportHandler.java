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
 * 銷售報表生成器
 *
 * 職責：
 * - 統計銷售額、客單價、轉化率
 * - 按平台、時間段分析
 * - 識別暢銷品和低銷品
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesReportHandler {

    private final OrderService orderService;
    private final ObjectMapper objectMapper;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 生成銷售報表
     */
    public void generateSalesReport(String merchantId, JsonNode body) throws Exception {
        try {
            String startDate = body.get("startDate").asText();
            String endDate = body.get("endDate").asText();

            log.info("Generating sales report for merchant {} ({}~{})",
                merchantId, startDate, endDate);

            // 統計數據
            SalesStatistics stats = collectSalesStatistics(merchantId, startDate, endDate);

            // 生成報表
            String report = buildSalesReport(stats);
            log.info("Sales report generated:\n{}", report);

            log.info("Sales report completed for {}", merchantId);

        } catch (Exception e) {
            log.error("Error generating sales report for {}", merchantId, e);
            throw e;
        }
    }

    /**
     * 收集銷售統計數據
     */
    private SalesStatistics collectSalesStatistics(String merchantId, String startDate, String endDate) {
        SalesStatistics stats = new SalesStatistics();
        stats.merchantId = merchantId;
        stats.startDate = startDate;
        stats.endDate = endDate;
        stats.generatedAt = LocalDateTime.now().format(FORMATTER);

        // 模擬數據
        stats.totalRevenue = new BigDecimal("150000.00");
        stats.totalOrders = 500;
        stats.customerCount = 320;
        stats.repeatCustomerRate = 35.0;
        stats.conversionRate = 3.2;
        stats.avgOrderValue = stats.totalRevenue.divide(
            new BigDecimal(stats.totalOrders), 2, BigDecimal.ROUND_HALF_UP);

        // 平台銷售
        stats.shopifySales = new BigDecimal("60000.00");
        stats.shopeeSales = new BigDecimal("50000.00");
        stats.easystoreSales = new BigDecimal("40000.00");

        // 產品銷售
        stats.topProductCount = 3;
        stats.topProductRevenue = new BigDecimal("45000.00");

        return stats;
    }

    /**
     * 構建報表文本
     */
    private String buildSalesReport(SalesStatistics stats) {
        StringBuilder report = new StringBuilder();

        report.append("====================================\n");
        report.append("銷售報表\n");
        report.append("====================================\n");
        report.append("商戶ID: ").append(stats.merchantId).append("\n");
        report.append("日期範圍: ").append(stats.startDate).append(" ~ ").append(stats.endDate).append("\n");
        report.append("生成時間: ").append(stats.generatedAt).append("\n");
        report.append("\n");

        report.append("--- 銷售概況 ---\n");
        report.append("總銷售額: ").append(stats.totalRevenue).append(" TWD\n");
        report.append("訂單數: ").append(stats.totalOrders).append("\n");
        report.append("客户數: ").append(stats.customerCount).append("\n");
        report.append("客單價: ").append(stats.avgOrderValue).append(" TWD\n");
        report.append("\n");

        report.append("--- 關鍵指標 ---\n");
        report.append("轉化率: ").append(stats.conversionRate).append("%\n");
        report.append("復購客戶率: ").append(stats.repeatCustomerRate).append("%\n");
        report.append("\n");

        report.append("--- 平台銷售分析 ---\n");
        report.append("Shopify: ").append(stats.shopifySales).append(" TWD (")
            .append(percentage(stats.shopifySales, stats.totalRevenue)).append("%)\n");
        report.append("Shopee: ").append(stats.shopeeSales).append(" TWD (")
            .append(percentage(stats.shopeeSales, stats.totalRevenue)).append("%)\n");
        report.append("Easystore: ").append(stats.easystoreSales).append(" TWD (")
            .append(percentage(stats.easystoreSales, stats.totalRevenue)).append("%)\n");
        report.append("\n");

        report.append("--- 商品銷售 ---\n");
        report.append("Top 3 商品銷售額: ").append(stats.topProductRevenue).append(" TWD (")
            .append(percentage(stats.topProductRevenue, stats.totalRevenue)).append(")\n");
        report.append("\n");

        report.append("--- 商業建議 ---\n");
        if (stats.repeatCustomerRate < 30) {
            report.append("• 復購率較低，考慮實施會員獎勵計畫\n");
        }
        if (stats.conversionRate < 2) {
            report.append("• 轉化率較低，優化商品頁面和結帳流程\n");
        }
        report.append("• 持續推廣 Top 3 暢銷品\n");
        report.append("• 開發新商品以提升客戶多樣性需求\n");
        report.append("\n");
        report.append("====================================\n");

        return report.toString();
    }

    private String percentage(BigDecimal part, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) == 0) return "0";
        return part.divide(total, 4, BigDecimal.ROUND_HALF_UP)
            .multiply(new BigDecimal(100))
            .setScale(1, BigDecimal.ROUND_HALF_UP).toString();
    }

    // 內部數據類
    static class SalesStatistics {
        String merchantId;
        String startDate;
        String endDate;
        String generatedAt;

        BigDecimal totalRevenue;
        int totalOrders;
        int customerCount;
        double repeatCustomerRate;
        double conversionRate;
        BigDecimal avgOrderValue;

        BigDecimal shopifySales;
        BigDecimal shopeeSales;
        BigDecimal easystoreSales;

        int topProductCount;
        BigDecimal topProductRevenue;
    }
}
