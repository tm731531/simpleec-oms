package com.simpleec.schedulerjob.repository;

import com.simpleec.schedulerjob.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, String> {
    @Query("SELECT c FROM Channel c WHERE c.enableSync = true")
    List<Channel> findAllEnabled();
}
