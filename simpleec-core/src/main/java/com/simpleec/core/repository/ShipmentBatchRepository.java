package com.simpleec.core.repository;

import com.simpleec.core.entity.ShipmentBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentBatchRepository extends JpaRepository<ShipmentBatch, String> {
    Page<ShipmentBatch> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);
}
