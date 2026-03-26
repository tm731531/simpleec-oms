package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import com.simpleec.core.entity.DailyStatistics;
import com.simpleec.core.repository.DailyStatisticsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sales report handler — runs at minute % 5 == 3.
 *
 * Queries daily_statistics for today and logs a per-platform (platformId)
 * breakdown of order count and revenue for the merchant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesReportHandler extends AbstractEventHandler {

    private final DailyStatisticsRepository dailyStatisticsRepository;

    @Override
    public String getTaskType() {
        return "SALES_REPORT";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        if (merchantId == null) {
            log.warn("No merchantId provided for SALES_REPORT");
            return;
        }

        LocalDate today;
        try {
            today = OffsetDateTime.parse(timestamp).toLocalDate();
        } catch (Exception e) {
            log.warn("Could not parse timestamp '{}', falling back to LocalDate.now()", timestamp);
            today = LocalDate.now();
        }

        List<DailyStatistics> records =
                dailyStatisticsRepository.findByMerchantIdAndStatDate(merchantId, today);

        if (records.isEmpty()) {
            log.info("SALES_REPORT [{}] date={} — no statistics records found", merchantId, today);
            return;
        }

        // Group by platformId and aggregate order count + revenue per platform
        Map<String, List<DailyStatistics>> byPlatform = records.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getPlatformId() != null ? r.getPlatformId() : "UNKNOWN"));

        int grandTotalOrders = 0;
        BigDecimal grandTotalRevenue = BigDecimal.ZERO;

        for (Map.Entry<String, List<DailyStatistics>> entry : byPlatform.entrySet()) {
            String platformId = entry.getKey();
            List<DailyStatistics> platformRecords = entry.getValue();

            int platformOrders = platformRecords.stream()
                    .mapToInt(r -> r.getOrderCount() != null ? r.getOrderCount() : 0)
                    .sum();

            BigDecimal platformRevenue = platformRecords.stream()
                    .map(r -> r.getTotalAmount() != null ? r.getTotalAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            log.info("SALES_REPORT [{}] date={} platform={} orders={} revenue={}",
                    merchantId, today, platformId, platformOrders, platformRevenue);

            grandTotalOrders += platformOrders;
            grandTotalRevenue = grandTotalRevenue.add(platformRevenue);
        }

        log.info("SALES_REPORT [{}] date={} platforms={} grandTotalOrders={} grandTotalRevenue={}",
                merchantId, today, byPlatform.size(), grandTotalOrders, grandTotalRevenue);
    }
}
