package com.simpleec.api.controller;

import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.DailyStatistics;
import com.simpleec.core.entity.Product;
import com.simpleec.core.repository.DailyStatisticsRepository;
import com.simpleec.core.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/user/reports")
@RequiredArgsConstructor
public class UserReportController {

    private final DailyStatisticsRepository statsRepository;
    private final ProductRepository productRepository;

    /**
     * GET /api/user/reports/sales
     * Returns sales summary, by-date breakdown, and by-platform breakdown.
     * Query params: from (default 7 days ago), to (default today), channelId (optional)
     */
    @GetMapping("/sales")
    public ResponseEntity<?> getSalesReport(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String channelId) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String merchantId = principal.getMerchantId();
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(6);

        List<DailyStatistics> stats = statsRepository
                .findByMerchantIdAndStatDateBetweenOrderByStatDateDesc(merchantId, startDate, endDate);

        // Optional channel filter
        if (channelId != null && !channelId.isBlank()) {
            stats = stats.stream()
                    .filter(s -> channelId.equals(s.getChannelId()))
                    .toList();
        }

        // --- Summary aggregation ---
        int totalOrders = stats.stream()
                .mapToInt(s -> s.getOrderCount() != null ? s.getOrderCount() : 0).sum();
        BigDecimal totalAmount = stats.stream()
                .map(s -> s.getTotalAmount() != null ? s.getTotalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalShipped = stats.stream()
                .mapToInt(s -> s.getShippedCount() != null ? s.getShippedCount() : 0).sum();
        int totalCompleted = stats.stream()
                .mapToInt(s -> s.getCompletedCount() != null ? s.getCompletedCount() : 0).sum();
        int totalCancelled = stats.stream()
                .mapToInt(s -> s.getCancelledCount() != null ? s.getCancelledCount() : 0).sum();
        int totalRefund = stats.stream()
                .mapToInt(s -> s.getRefundCount() != null ? s.getRefundCount() : 0).sum();

        double returnRate = totalOrders > 0
                ? BigDecimal.valueOf((double) totalRefund / totalOrders * 100)
                        .setScale(2, RoundingMode.HALF_UP).doubleValue()
                : 0.0;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalOrders", totalOrders);
        summary.put("totalAmount", totalAmount);
        summary.put("totalShipped", totalShipped);
        summary.put("totalCompleted", totalCompleted);
        summary.put("totalCancelled", totalCancelled);
        summary.put("returnRate", returnRate);

        // --- By Date (aggregate across channels per day, ascending for chart display) ---
        Map<LocalDate, Integer> byDateOrders = new TreeMap<>();
        Map<LocalDate, BigDecimal> byDateAmount = new TreeMap<>();
        for (DailyStatistics s : stats) {
            LocalDate d = s.getStatDate();
            byDateOrders.merge(d, s.getOrderCount() != null ? s.getOrderCount() : 0, Integer::sum);
            byDateAmount.merge(d,
                    s.getTotalAmount() != null ? s.getTotalAmount() : BigDecimal.ZERO,
                    BigDecimal::add);
        }

        List<Map<String, Object>> byDate = new ArrayList<>();
        for (LocalDate d : byDateOrders.keySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", d.toString());
            entry.put("orderCount", byDateOrders.get(d));
            entry.put("amount", byDateAmount.getOrDefault(d, BigDecimal.ZERO));
            byDate.add(entry);
        }

        // --- By Platform (aggregate by platformId) ---
        Map<String, Integer> byPlatformOrders = new LinkedHashMap<>();
        Map<String, BigDecimal> byPlatformAmount = new LinkedHashMap<>();
        for (DailyStatistics s : stats) {
            String pid = s.getPlatformId();
            byPlatformOrders.merge(pid, s.getOrderCount() != null ? s.getOrderCount() : 0, Integer::sum);
            byPlatformAmount.merge(pid,
                    s.getTotalAmount() != null ? s.getTotalAmount() : BigDecimal.ZERO,
                    BigDecimal::add);
        }

        List<Map<String, Object>> byPlatform = new ArrayList<>();
        for (String pid : byPlatformOrders.keySet()) {
            int cnt = byPlatformOrders.get(pid);
            double pct = totalOrders > 0
                    ? BigDecimal.valueOf((double) cnt / totalOrders * 100)
                            .setScale(2, RoundingMode.HALF_UP).doubleValue()
                    : 0.0;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("platformId", pid);
            entry.put("orderCount", cnt);
            entry.put("amount", byPlatformAmount.getOrDefault(pid, BigDecimal.ZERO));
            entry.put("percentage", pct);
            byPlatform.add(entry);
        }
        byPlatform.sort((a, b) -> Integer.compare((int) b.get("orderCount"), (int) a.get("orderCount")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("summary", summary);
        response.put("byDate", byDate);
        response.put("byPlatform", byPlatform);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/user/reports/profit
     * Returns gross profit estimate based on daily_statistics (revenue) and product cost_price.
     * Since daily_statistics is aggregated at channel/date level (no productId FK),
     * totalCost is computed as sum of cost_price across all products as a reference figure.
     * Per-product revenue cannot be derived from daily_statistics; revenue/profit are null per product.
     * Query params: from (default 7 days ago), to (default today)
     */
    @GetMapping("/profit")
    public ResponseEntity<?> getProfitReport(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String merchantId = principal.getMerchantId();
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(6);

        // Total revenue from daily_statistics
        List<DailyStatistics> stats = statsRepository
                .findByMerchantIdAndStatDateBetweenOrderByStatDateDesc(merchantId, startDate, endDate);

        BigDecimal totalRevenue = stats.stream()
                .map(s -> s.getTotalAmount() != null ? s.getTotalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Load products for merchant — cost_price is the only cost data available
        List<Product> products = productRepository
                .findByMerchantId(merchantId, PageRequest.of(0, 500))
                .getContent();

        // Build per-product list; revenue/profit per product is null (not available from daily_statistics)
        List<Map<String, Object>> byProduct = new ArrayList<>();
        for (Product p : products) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("productId", p.getId());
            entry.put("productName", p.getProductName() != null ? p.getProductName() : p.getSku());
            entry.put("revenue", null);
            entry.put("cost", p.getCostPrice());  // null if not set
            entry.put("profit", null);
            byProduct.add(entry);
        }

        // Gross profit and margin cannot be computed without product-level sales data
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalRevenue", totalRevenue);
        response.put("totalCost", null);
        response.put("grossProfit", null);
        response.put("grossMargin", null);
        response.put("byProduct", byProduct);

        return ResponseEntity.ok(response);
    }
}
