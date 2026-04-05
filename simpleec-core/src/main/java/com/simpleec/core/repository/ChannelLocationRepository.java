package com.simpleec.core.repository;

import com.simpleec.core.entity.ChannelLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChannelLocationRepository extends JpaRepository<ChannelLocation, String> {

    List<ChannelLocation> findByChannelId(String channelId);

    Optional<ChannelLocation> findByChannelIdAndIsSyncTargetTrue(String channelId);
}
