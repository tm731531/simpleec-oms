package com.simpleec.core.service;

import com.simpleec.core.entity.DailyStatistics;
import com.simpleec.core.repository.DailyStatisticsRepository;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.core.repository.ReturnOrderRepository;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyStatisticsService {

    private final DailyStatisticsRepository statsRepository;
    private final OrderRepository orderRepository;
    private final ReturnOrderRepository returnOrderRepository;

    @Transactional
    public void recalculate(String merchantId, String platformId, String channelId, LocalDate statDate) {
        log.debug("Recalculating stats: merchantId={}, platformId={}, channelId={}, date={}",
                merchantId, platformId, channelId, statDate);

        LocalDateTime startOfDay = statDate.atStartOfDay();
        LocalDateTime endOfDay = statDate.plusDays(1).atStartOfDay();

        var stats = orderRepository.aggregateStatsByChannelAndDate(merchantId, channelId, startOfDay, endOfDay);

        // Aggregate queries always return a row; check the actual count instead of null
        if (stats.getNewOrderCount() == null || stats.getNewOrderCount() == 0) {
            log.debug("No orders found for stats recalc: {}/{}/{}/{} — deleting stale row if present",
                    merchantId, platformId, channelId, statDate);
            statsRepository.findByMerchantIdAndPlatformIdAndChannelIdAndStatDate(merchantId, platformId, channelId, statDate)
                    .ifPresent(statsRepository::delete);
            return;
        }

        DailyStatistics existing = statsRepository
                .findByMerchantIdAndPlatformIdAndChannelIdAndStatDate(merchantId, platformId, channelId, statDate)
                .orElse(null);

        if (existing == null) {
            existing = DailyStatistics.builder()
                    .id(NanoIdUtil.generate(20))
                    .merchantId(merchantId)
                    .platformId(platformId)
                    .channelId(channelId)
                    .statDate(statDate)
                    .build();
        }

        long refundCount = returnOrderRepository.countByMerchantIdAndChannelIdAndStatDate(merchantId, channelId, startOfDay, endOfDay);
        BigDecimal refundAmount = returnOrderRepository.sumRefundAmountByMerchantIdAndChannelIdAndStatDate(merchantId, channelId, startOfDay, endOfDay);
        if (refundAmount == null) refundAmount = BigDecimal.ZERO;

        BigDecimal receivedAmount = stats.getReceivedAmount() != null ? stats.getReceivedAmount() : BigDecimal.ZERO;
        BigDecimal netAmount = receivedAmount.subtract(refundAmount);

        // 業務視角
        existing.setNewOrderCount(stats.getNewOrderCount());
        existing.setNewOrderAmount(stats.getNewOrderAmount());
        // 老闆視角
        existing.setGrossOrderCount(stats.getGrossOrderCount());
        existing.setGrossAmount(stats.getGrossAmount());
        // 財務視角
        existing.setReceivedCount(stats.getReceivedCount());
        existing.setReceivedAmount(receivedAmount);
        existing.setRefundCount((int) refundCount);
        existing.setRefundAmount(refundAmount);
        existing.setNetAmount(netAmount);
        // 物流視角
        existing.setShippedCount(stats.getShippedCount());
        existing.setCompletedCount(stats.getCompletedCount());
        existing.setCancelledCount(stats.getCancelledCount());
        // 商品統計
        existing.setItemSoldCount(stats.getItemSoldCount());

        statsRepository.save(existing);

        log.info("Stats upserted: merchantId={}, channelId={}, date={}, newOrders={}, grossAmt={}, netAmt={}",
                merchantId, channelId, statDate,
                stats.getNewOrderCount(), stats.getGrossAmount(), netAmount);
    }
}
