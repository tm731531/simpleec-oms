package com.simpleec.schedulerjob.repository;

import com.simpleec.schedulerjob.entity.Platform;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformRepository extends JpaRepository<Platform, String> {
    @Query("SELECT p FROM Platform p WHERE p.actived = true")
    List<Platform> findAllActive();
}
