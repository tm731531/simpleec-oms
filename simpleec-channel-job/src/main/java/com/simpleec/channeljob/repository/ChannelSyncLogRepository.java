package com.simpleec.channeljob.repository;

import com.simpleec.channeljob.entity.ChannelSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChannelSyncLogRepository extends JpaRepository<ChannelSyncLog, String> {
}
