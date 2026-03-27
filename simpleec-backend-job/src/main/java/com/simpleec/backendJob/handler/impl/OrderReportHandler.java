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

/**
 * Order report handler — runs at minute % 5 == 1.
 *
 * Queries daily_statistics for today and logs an aggregate summary across all
 * channels: total orders, total amount, shipped count, completed count, and
 * cancelled count for the merchant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReportHandler extends AbstractEventHandler {

    private final DailyStatisticsRepository dailyStatisticsRepository;

    @Override
    public String getTaskType() {
        return "ORDER_REPORT";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        if (merchantId == null) {
            log.warn("No merchantId provided for ORDER_REPORT");
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
            log.info("ORDER_REPORT [{}] date={} — no statistics records found", merchantId, today);
            return;
        }

        int totalOrders = records.stream()
                .mapToInt(r -> r.getNewOrderCount() != null ? r.getNewOrderCount() : 0)
                .sum();

        BigDecimal totalAmount = records.stream()
                .map(r -> r.getNewOrderAmount() != null ? r.getNewOrderAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int shippedCount = records.stream()
                .mapToInt(r -> r.getShippedCount() != null ? r.getShippedCount() : 0)
                .sum();

        int completedCount = records.stream()
                .mapToInt(r -> r.getCompletedCount() != null ? r.getCompletedCount() : 0)
                .sum();

        int cancelledCount = records.stream()
                .mapToInt(r -> r.getCancelledCount() != null ? r.getCancelledCount() : 0)
                .sum();

        log.info("ORDER_REPORT [{}] date={} channels={} totalOrders={} totalAmount={} shipped={} completed={} cancelled={}",
                merchantId, today, records.size(),
                totalOrders, totalAmount,
                shippedCount, completedCount, cancelledCount);
    }
}
