package com.simpleec.api.repository;

import com.simpleec.api.entity.ChannelSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for ChannelSyncLog entity
 */
@Repository
public interface ChannelSyncLogRepository extends JpaRepository<ChannelSyncLog, String> {
}
