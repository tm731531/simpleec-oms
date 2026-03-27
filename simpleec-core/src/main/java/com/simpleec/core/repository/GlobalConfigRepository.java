package com.simpleec.core.repository;

import com.simpleec.core.entity.GlobalConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GlobalConfigRepository extends JpaRepository<GlobalConfig, String> {
    // findById(id) is inherited — use findById("encryption_master_key")
}
