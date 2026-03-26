package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import com.simpleec.core.repository.SellPackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Inventory report handler — runs at minute % 5 == 2.
 *
 * Queries the sell_pack table for the merchant and logs a summary of total
 * packs and how many are low-stock (quantity < LOW_STOCK_THRESHOLD).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReportHandler extends AbstractEventHandler {

    /** Packs with quantity below this value are considered low-stock. */
    private static final int LOW_STOCK_THRESHOLD = 10;

    private final SellPackRepository sellPackRepository;

    @Override
    public String getTaskType() {
        return "INVENTORY_REPORT";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        if (merchantId == null) {
            log.warn("No merchantId provided for INVENTORY_REPORT");
            return;
        }

        LocalDate reportDate;
        try {
            reportDate = OffsetDateTime.parse(timestamp).toLocalDate();
        } catch (Exception e) {
            log.warn("Could not parse timestamp '{}', falling back to LocalDate.now()", timestamp);
            reportDate = LocalDate.now();
        }

        long totalPacks = sellPackRepository.countByMerchantId(merchantId);
        long lowStockPacks = sellPackRepository.countByMerchantIdAndQuantityLessThan(merchantId, LOW_STOCK_THRESHOLD);

        if (totalPacks == 0) {
            log.info("INVENTORY_REPORT [{}] date={} — no packs found", merchantId, reportDate);
            return;
        }

        log.info("INVENTORY_REPORT [{}] date={} totalPacks={} lowStock={} (qty<{})",
                merchantId, reportDate, totalPacks, lowStockPacks, LOW_STOCK_THRESHOLD);

        if (lowStockPacks > 0) {
            log.warn("INVENTORY_REPORT [{}] date={} — {} pack(s) are below low-stock threshold (qty<{})",
                    merchantId, reportDate, lowStockPacks, LOW_STOCK_THRESHOLD);
        }
    }
}
