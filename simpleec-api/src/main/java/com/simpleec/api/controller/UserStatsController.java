package com.simpleec.api.controller;

import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.DailyStatistics;
import com.simpleec.core.repository.DailyStatisticsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/stats")
@RequiredArgsConstructor
public class UserStatsController {

    private final DailyStatisticsRepository statsRepository;

    /**
     * Get daily stats for a date range (default: last 7 days)
     * GET /api/user/stats/daily?channelId=xxx&from=2026-03-20&to=2026-03-26
     */
    @GetMapping("/daily")
    public ResponseEntity<?> getDailyStats(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String channelId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String merchantId = principal.getMerchantId();
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(6);

        // Use Spring Data JPA to find stats for this merchant in the date range
        List<DailyStatistics> stats = statsRepository
                .findByMerchantIdAndStatDateBetweenOrderByStatDateDesc(merchantId, startDate, endDate);

        // If channelId filter provided, filter in memory (small result set)
        if (channelId != null && !channelId.isBlank()) {
            stats = stats.stream()
                    .filter(s -> channelId.equals(s.getChannelId()))
                    .toList();
        }

        return ResponseEntity.ok(Map.of("data", stats, "success", true));
    }

    /**
     * Get today's summary across all channels for this merchant
     * GET /api/user/stats/today
     */
    @GetMapping("/today")
    public ResponseEntity<?> getTodayStats(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String merchantId = principal.getMerchantId();
        LocalDate today = LocalDate.now();

        List<DailyStatistics> todayStats = statsRepository
                .findByMerchantIdAndStatDate(merchantId, today);

        // Aggregate across channels
        int totalOrders = todayStats.stream().mapToInt(s -> s.getOrderCount() != null ? s.getOrderCount() : 0).sum();
        java.math.BigDecimal totalAmount = todayStats.stream()
                .map(s -> s.getTotalAmount() != null ? s.getTotalAmount() : java.math.BigDecimal.ZERO)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        return ResponseEntity.ok(Map.of(
                "data", Map.of(
                        "date", today.toString(),
                        "totalOrders", totalOrders,
                        "totalAmount", totalAmount,
                        "channels", todayStats
                ),
                "success", true
        ));
    }
}
