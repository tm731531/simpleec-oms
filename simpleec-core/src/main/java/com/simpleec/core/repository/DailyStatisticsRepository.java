package com.simpleec.core.repository;

import com.simpleec.core.entity.DailyStatistics;
import com.simpleec.core.entity.DailyStatisticsId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyStatisticsRepository extends JpaRepository<DailyStatistics, DailyStatisticsId> {

    Optional<DailyStatistics> findByMerchantIdAndPlatformIdAndChannelIdAndStatDate(
            String merchantId, String platformId, String channelId, LocalDate statDate);

    List<DailyStatistics> findByMerchantIdAndStatDateBetweenOrderByStatDateDesc(
            String merchantId, LocalDate from, LocalDate to);

    List<DailyStatistics> findByMerchantIdAndStatDate(String merchantId, LocalDate statDate);
}
