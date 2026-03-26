package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import com.simpleec.common.enums.ReturnStatusEnum;
import com.simpleec.core.repository.ReturnOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Return report handler — runs at minute % 5 == 4.
 *
 * Queries the refund_orders table and logs a status-breakdown summary
 * (PENDING, APPROVED, REJECTED, COMPLETED, REFUNDED) for the merchant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnReportHandler extends AbstractEventHandler {

    private final ReturnOrderRepository returnOrderRepository;

    @Override
    public String getTaskType() {
        return "RETURN_REPORT";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        if (merchantId == null) {
            log.warn("No merchantId provided for RETURN_REPORT");
            return;
        }

        LocalDate reportDate;
        try {
            reportDate = OffsetDateTime.parse(timestamp).toLocalDate();
        } catch (Exception e) {
            log.warn("Could not parse timestamp '{}', falling back to LocalDate.now()", timestamp);
            reportDate = LocalDate.now();
        }

        long pending   = returnOrderRepository.countByMerchantIdAndReturnStatus(merchantId, ReturnStatusEnum.PENDING);
        long approved  = returnOrderRepository.countByMerchantIdAndReturnStatus(merchantId, ReturnStatusEnum.APPROVED);
        long rejected  = returnOrderRepository.countByMerchantIdAndReturnStatus(merchantId, ReturnStatusEnum.REJECTED);
        long completed = returnOrderRepository.countByMerchantIdAndReturnStatus(merchantId, ReturnStatusEnum.COMPLETED);
        long refunded  = returnOrderRepository.countByMerchantIdAndReturnStatus(merchantId, ReturnStatusEnum.REFUNDED);

        long total = pending + approved + rejected + completed + refunded;

        if (total == 0) {
            log.info("RETURN_REPORT [{}] date={} — no return records found", merchantId, reportDate);
            return;
        }

        log.info("RETURN_REPORT [{}] date={} total={} pending={} approved={} rejected={} completed={} refunded={}",
                merchantId, reportDate, total,
                pending, approved, rejected, completed, refunded);
    }
}
