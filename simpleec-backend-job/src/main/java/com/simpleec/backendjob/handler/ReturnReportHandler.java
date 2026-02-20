package com.simpleec.backendjob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.core.service.ReturnOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 退貨報表生成器
 *
 * 職責：
 * - 統計退貨率、退款額、常見原因
 * - 分析退貨趨勢
 * - 質量監控
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnReportHandler {

    private final ReturnOrderService returnOrderService;
    private final ObjectMapper objectMapper;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 生成退貨報表
     */
    public void generateReturnReport(String merchantId, JsonNode body) throws Exception {
        try {
            String startDate = body.get("startDate").asText();
            String endDate = body.get("endDate").asText();

            log.info("Generating return report for merchant {} ({}~{})",
                merchantId, startDate, endDate);

            // 統計數據
            ReturnStatistics stats = collectReturnStatistics(merchantId, startDate, endDate);

            // 生成報表
            String report = buildReturnReport(stats);
            log.info("Return report generated:\n{}", report);

            log.info("Return report completed for {}", merchantId);

        } catch (Exception e) {
            log.error("Error generating return report for {}", merchantId, e);
            throw e;
        }
    }

    /**
     * 收集退貨統計數據
     */
    private ReturnStatistics collectReturnStatistics(String merchantId, String startDate, String endDate) {
        ReturnStatistics stats = new ReturnStatistics();
        stats.merchantId = merchantId;
        stats.startDate = startDate;
        stats.endDate = endDate;
        stats.generatedAt = LocalDateTime.now().format(FORMATTER);

        // 模擬數據
        stats.totalReturns = 45;
        stats.totalOrdersInPeriod = 500;
        stats.totalRefundAmount = new BigDecimal("22500.00");
        stats.returnRate = 9.0;
        stats.avgRefundAmount = stats.totalRefundAmount.divide(
            new BigDecimal(stats.totalReturns), 2, BigDecimal.ROUND_HALF_UP);

        // 退貨原因分布
        stats.qualityIssues = 15;           // 33%
        stats.damageInShipping = 12;        // 27%
        stats.sizeMismatch = 10;            // 22%
        stats.notAsDescribed = 5;           // 11%
        stats.customerDisatisfied = 3;      // 7%

        // 平台退貨率
        stats.shopifyReturnRate = 8.5;
        stats.shopeeReturnRate = 10.2;
        stats.easystoreReturnRate = 8.0;

        // 狀態分布
        stats.pendingReturns = 8;
        stats.approvedReturns = 25;
        stats.completedReturns = 12;

        return stats;
    }

    /**
     * 構建報表文本
     */
    private String buildReturnReport(ReturnStatistics stats) {
        StringBuilder report = new StringBuilder();

        report.append("====================================\n");
        report.append("退貨報表\n");
        report.append("====================================\n");
        report.append("商戶ID: ").append(stats.merchantId).append("\n");
        report.append("日期範圍: ").append(stats.startDate).append(" ~ ").append(stats.endDate).append("\n");
        report.append("生成時間: ").append(stats.generatedAt).append("\n");
        report.append("\n");

        report.append("--- 退貨概況 ---\n");
        report.append("總退貨數: ").append(stats.totalReturns).append("\n");
        report.append("期間訂單數: ").append(stats.totalOrdersInPeriod).append("\n");
        report.append("退貨率: ").append(stats.returnRate).append("%\n");
        report.append("總退款金額: ").append(stats.totalRefundAmount).append(" TWD\n");
        report.append("平均退款金額: ").append(stats.avgRefundAmount).append(" TWD\n");
        report.append("\n");

        report.append("--- 退貨原因分析 ---\n");
        report.append("質量問題: ").append(stats.qualityIssues).append(" (")
            .append(percentage(stats.qualityIssues, stats.totalReturns)).append("%) 🔴\n");
        report.append("運輸受損: ").append(stats.damageInShipping).append(" (")
            .append(percentage(stats.damageInShipping, stats.totalReturns)).append("%)\n");
        report.append("尺寸不符: ").append(stats.sizeMismatch).append(" (")
            .append(percentage(stats.sizeMismatch, stats.totalReturns)).append("%)\n");
        report.append("描述不符: ").append(stats.notAsDescribed).append(" (")
            .append(percentage(stats.notAsDescribed, stats.totalReturns)).append("%)\n");
        report.append("顧客不滿意: ").append(stats.customerDisatisfied).append(" (")
            .append(percentage(stats.customerDisatisfied, stats.totalReturns)).append("%)\n");
        report.append("\n");

        report.append("--- 平台退貨率 ---\n");
        report.append("Shopify: ").append(stats.shopifyReturnRate).append("%\n");
        report.append("Shopee: ").append(stats.shopeeReturnRate).append("% (較高)\n");
        report.append("Easystore: ").append(stats.easystoreReturnRate).append("%\n");
        report.append("\n");

        report.append("--- 退貨流程進度 ---\n");
        report.append("待處理: ").append(stats.pendingReturns).append("\n");
        report.append("已批准: ").append(stats.approvedReturns).append("\n");
        report.append("已完成: ").append(stats.completedReturns).append("\n");
        report.append("\n");

        report.append("--- 質量監控 & 建議 ---\n");
        if (stats.qualityIssues > 10) {
            report.append("⚠️ 質量問題偏高，請檢查：\n");
            report.append("  - 供應商產品質量\n");
            report.append("  - 出庫品質檢驗流程\n");
            report.append("  - 客戶評價和反饋\n");
        }
        if (stats.damageInShipping > 8) {
            report.append("⚠️ 運輸受損率偏高，優化包裝和物流\n");
        }
        if (stats.shopeeReturnRate > 9) {
            report.append("⚠️ Shopee 退貨率較高，分析原因\n");
        }
        report.append("\n");
        report.append("====================================\n");

        return report.toString();
    }

    private String percentage(int part, int total) {
        if (total == 0) return "0";
        return String.format("%.1f", (part * 100.0) / total);
    }

    // 內部數據類
    static class ReturnStatistics {
        String merchantId;
        String startDate;
        String endDate;
        String generatedAt;

        int totalReturns;
        int totalOrdersInPeriod;
        double returnRate;
        BigDecimal totalRefundAmount;
        BigDecimal avgRefundAmount;

        int qualityIssues;
        int damageInShipping;
        int sizeMismatch;
        int notAsDescribed;
        int customerDisatisfied;

        double shopifyReturnRate;
        double shopeeReturnRate;
        double easystoreReturnRate;

        int pendingReturns;
        int approvedReturns;
        int completedReturns;
    }
}
