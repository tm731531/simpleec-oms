package com.simpleec.core.repository;

import com.simpleec.core.entity.ShipmentStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShipmentStatusLogRepository extends JpaRepository<ShipmentStatusLog, String> {
    List<ShipmentStatusLog> findByShipmentIdOrderByCreatedAtDesc(String shipmentId);
}
