package com.simpleec.backendjob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.core.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 庫存報表生成器
 *
 * 職責：
 * - 統計庫存數量、價值、周轉率
 * - 識別庫存不足、滯銷品
 * - 按倉庫、SKU 統計
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReportHandler {

    private final ProductService productService;
    private final ObjectMapper objectMapper;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 生成庫存報表
     *
     * Body 格式：
     * {
     *   "warehouseId": "WH-001",
     *   "merchantId": "M001"
     * }
     */
    public void generateInventoryReport(String merchantId, JsonNode body) throws Exception {
        try {
            String warehouseId = body.get("warehouseId").asText(null);

            log.info("Generating inventory report for merchant {} (warehouse: {})",
                merchantId, warehouseId);

            // 統計數據
            InventoryStatistics stats = collectInventoryStatistics(merchantId, warehouseId);

            // 生成報表
            String report = buildInventoryReport(stats);
            log.info("Inventory report generated:\n{}", report);

            // TODO: 存儲報表
            // saveReport(merchantId, "INVENTORY_REPORT", "REALTIME", report);

            log.info("Inventory report completed for {}", merchantId);

        } catch (Exception e) {
            log.error("Error generating inventory report for {}", merchantId, e);
            throw e;
        }
    }

    /**
     * 收集庫存統計數據
     */
    private InventoryStatistics collectInventoryStatistics(String merchantId, String warehouseId) {
        InventoryStatistics stats = new InventoryStatistics();
        stats.merchantId = merchantId;
        stats.warehouseId = warehouseId != null ? warehouseId : "ALL";
        stats.generatedAt = LocalDateTime.now().format(FORMATTER);

        // TODO: 從 ProductService 查詢實際數據
        // List<Product> products = productService.findByMerchant(merchantId);

        // 模擬數據
        stats.totalSkus = 45;
        stats.totalQuantity = 5000;
        stats.totalValue = "500000.00";  // TWD
        stats.lowStockSkus = 8;
        stats.deadStockSkus = 3;
        stats.avgTurnover = 2.5;

        // SKU 狀態分布
        stats.activeSkus = 40;
        stats.slowMovingSkus = 5;

        // 價值分布（ABC 分析）
        stats.highValueSkus = 12;      // A 級 (80%)
        stats.mediumValueSkus = 18;    // B 級 (15%)
        stats.lowValueSkus = 15;       // C 級 (5%)

        return stats;
    }

    /**
     * 構建報表文本
     */
    private String buildInventoryReport(InventoryStatistics stats) {
        StringBuilder report = new StringBuilder();

        report.append("====================================\n");
        report.append("庫存報表\n");
        report.append("====================================\n");
        report.append("商戶ID: ").append(stats.merchantId).append("\n");
        report.append("倉庫: ").append(stats.warehouseId).append("\n");
        report.append("生成時間: ").append(stats.generatedAt).append("\n");
        report.append("\n");

        report.append("--- 庫存概況 ---\n");
        report.append("總SKU數: ").append(stats.totalSkus).append("\n");
        report.append("總庫存數: ").append(stats.totalQuantity).append(" 件\n");
        report.append("庫存總價值: ").append(stats.totalValue).append(" TWD\n");
        report.append("平均周轉率: ").append(stats.avgTurnover).append(" 次/月\n");
        report.append("\n");

        report.append("--- SKU 狀態 ---\n");
        report.append("活躍SKU: ").append(stats.activeSkus).append(" (")
            .append(percentage(stats.activeSkus, stats.totalSkus)).append("%)\n");
        report.append("滯銷SKU: ").append(stats.slowMovingSkus).append(" (")
            .append(percentage(stats.slowMovingSkus, stats.totalSkus)).append("%)\n");
        report.append("\n");

        report.append("--- 庫存警告 ---\n");
        report.append("庫存不足SKU: ").append(stats.lowStockSkus).append(" ⚠️\n");
        report.append("滯銷品SKU: ").append(stats.deadStockSkus).append(" 🔴\n");
        report.append("\n");

        report.append("--- ABC 分析 (帕累托法則) ---\n");
        report.append("A級 (高價值): ").append(stats.highValueSkus).append(" SKU (約80%價值)\n");
        report.append("B級 (中價值): ").append(stats.mediumValueSkus).append(" SKU (約15%價值)\n");
        report.append("C級 (低價值): ").append(stats.lowValueSkus).append(" SKU (約5%價值)\n");
        report.append("\n");

        report.append("--- 建議 ---\n");
        if (stats.lowStockSkus > 0) {
            report.append("1. 緊急補貨以下SKU: ").append(stats.lowStockSkus).append(" 項\n");
        }
        if (stats.deadStockSkus > 0) {
            report.append("2. 考慮清倉以下滯銷品: ").append(stats.deadStockSkus).append(" 項\n");
        }
        report.append("3. 重點管理 A 級SKU，確保高周轉\n");
        report.append("4. 定期檢查 C 級SKU 的銷售潛力\n");
        report.append("\n");
        report.append("====================================\n");

        return report.toString();
    }

    private String percentage(int part, int total) {
        if (total == 0) return "0";
        return String.format("%.1f", (part * 100.0) / total);
    }

    // 內部數據類
    static class InventoryStatistics {
        String merchantId;
        String warehouseId;
        String generatedAt;

        int totalSkus;
        int totalQuantity;
        String totalValue;
        double avgTurnover;

        int activeSkus;
        int slowMovingSkus;
        int lowStockSkus;
        int deadStockSkus;

        int highValueSkus;    // A 級
        int mediumValueSkus;  // B 級
        int lowValueSkus;     // C 級
    }
}
