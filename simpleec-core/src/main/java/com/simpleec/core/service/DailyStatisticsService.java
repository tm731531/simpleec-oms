package com.simpleec.core.service;

import com.simpleec.core.entity.DailyStatistics;
import com.simpleec.core.repository.DailyStatisticsRepository;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyStatisticsService {

    private final DailyStatisticsRepository statsRepository;
    private final OrderRepository orderRepository;

    @Transactional
    public void recalculate(String merchantId, String platformId, String channelId, LocalDate statDate) {
        log.debug("Recalculating stats: merchantId={}, platformId={}, channelId={}, date={}",
                merchantId, platformId, channelId, statDate);

        var stats = orderRepository.aggregateStatsByChannelAndDate(merchantId, channelId, statDate);

        if (stats == null) {
            log.debug("No orders found for stats recalc: {}/{}/{}/{}", merchantId, platformId, channelId, statDate);
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

        existing.setOrderCount(stats.getOrderCount());
        existing.setTotalAmount(stats.getTotalAmount());
        existing.setShippedCount(stats.getShippedCount());
        existing.setCompletedCount(stats.getCompletedCount());
        existing.setCancelledCount(stats.getCancelledCount());
        existing.setRefundCount(0);
        statsRepository.save(existing);

        log.info("Stats upserted: merchantId={}, channelId={}, date={}, orders={}, amount={}",
                merchantId, channelId, statDate, stats.getOrderCount(), stats.getTotalAmount());
    }
}
