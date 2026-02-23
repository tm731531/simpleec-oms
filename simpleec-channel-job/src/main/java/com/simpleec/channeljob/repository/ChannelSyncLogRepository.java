package com.simpleec.channeljob.repository;

import com.simpleec.channeljob.entity.ChannelSyncLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChannelSyncLogRepository extends JpaRepository<ChannelSyncLog, String> {
    Page<ChannelSyncLog> findByChannelId(String channelId, Pageable pageable);

    Page<ChannelSyncLog> findByChannelIdIsNullOrderByCreatedAtDesc(Pageable pageable);

    List<ChannelSyncLog> findTop100ByOrderByCreatedAtDesc();
}
