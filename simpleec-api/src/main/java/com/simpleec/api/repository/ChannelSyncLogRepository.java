package com.simpleec.api.repository;

import com.simpleec.api.entity.ChannelSyncLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChannelSyncLogRepository extends JpaRepository<ChannelSyncLog, String> {

    Page<ChannelSyncLog> findByChannelIdOrderByCreatedAtDesc(String channelId, Pageable pageable);

    Page<ChannelSyncLog> findByPlatformIdOrderByCreatedAtDesc(String platformId, Pageable pageable);
}
