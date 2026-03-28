package com.simpleec.core.repository;

import com.simpleec.core.entity.FailedTaskLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FailedTaskLogRepository extends JpaRepository<FailedTaskLog, String> {
}
