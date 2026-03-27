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
 * Daily report handler — runs at minute == 0 or 30 (twice per hour).
 *
 * Queries daily_statistics for the current date and logs a summary of
 * total orders and total amount across all channels for the merchant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportHandler extends AbstractEventHandler {

    private final DailyStatisticsRepository dailyStatisticsRepository;

    @Override
    public String getTaskType() {
        return "DAILY_REPORT";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        if (merchantId == null) {
            log.warn("No merchantId provided for DAILY_REPORT");
            return;
        }

        // Parse today's date from the ISO-8601 timestamp in the event header
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
            log.info("DAILY_REPORT [{}] date={} — no statistics records found", merchantId, today);
            return;
        }

        int totalOrders = records.stream()
                .mapToInt(r -> r.getNewOrderCount() != null ? r.getNewOrderCount() : 0)
                .sum();

        BigDecimal totalAmount = records.stream()
                .map(r -> r.getNewOrderAmount() != null ? r.getNewOrderAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.info("DAILY_REPORT [{}] date={} channels={} totalOrders={} totalAmount={}",
                merchantId, today, records.size(), totalOrders, totalAmount);
    }
}
