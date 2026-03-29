package com.simpleec.core.repository;

import com.simpleec.common.enums.ShipmentStatusEnum;
import com.simpleec.core.entity.Shipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ShipmentRepository extends JpaRepository<Shipment, String> {
    Page<Shipment> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);
    List<Shipment> findByMerchantIdAndStatus(String merchantId, ShipmentStatusEnum status);
    List<Shipment> findByBatchId(String batchId);

    /** Count non-CANCELLED shipments in a batch that have not yet reached LABELING or beyond. */
    @Query("SELECT COUNT(s) FROM Shipment s WHERE s.batchId = :batchId " +
           "AND s.status NOT IN ('LABELING','AWAITING_PICKUP','DISPATCHED','CANCELLED')")
    long countBatchShipmentsNotLabeled(@org.springframework.data.repository.query.Param("batchId") String batchId);

    /** Count non-CANCELLED shipments in a batch total. */
    @Query("SELECT COUNT(s) FROM Shipment s WHERE s.batchId = :batchId AND s.status <> 'CANCELLED'")
    long countActiveBatchShipments(@org.springframework.data.repository.query.Param("batchId") String batchId);
}
