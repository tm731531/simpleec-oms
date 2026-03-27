package com.simpleec.core.repository;

import com.simpleec.core.entity.OrderShipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for order shipment records.
 */
@Repository
public interface OrderShipmentRepository extends JpaRepository<OrderShipment, String> {

    /**
     * Find all shipments for a given order, oldest first.
     */
    List<OrderShipment> findByOrderId(String orderId);

    /**
     * Find all shipments belonging to orders of a specific merchant, newest first.
     *
     * Joins through the orders table so we can filter by merchant_id without
     * storing merchant_id redundantly on the shipment row.
     */
    @Query("SELECT s FROM OrderShipment s " +
           "JOIN Order o ON o.id = s.orderId " +
           "WHERE o.merchantId = :merchantId " +
           "ORDER BY s.createdAt DESC")
    Page<OrderShipment> findByMerchantIdOrderByCreatedAtDesc(
            @Param("merchantId") String merchantId,
            Pageable pageable);
}
