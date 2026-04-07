package com.simpleec.channeljob.repository;

import com.simpleec.channeljob.entity.ChannelSyncLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChannelSyncLogRepository extends JpaRepository<ChannelSyncLog, String> {
    // 查詢特定通路的日誌
    Page<ChannelSyncLog> findByChannelId(String channelId, Pageable pageable);

    // 查詢特定平台的日誌
    Page<ChannelSyncLog> findByPlatformId(String platformId, Pageable pageable);

    // 查詢平台檢查日誌（channel_id 為 null）
    Page<ChannelSyncLog> findByChannelIdIsNullOrderByCreatedAtDesc(Pageable pageable);

    // 查詢所有日誌（限制 100 筆）
    List<ChannelSyncLog> findTop100ByOrderByCreatedAtDesc();
}
