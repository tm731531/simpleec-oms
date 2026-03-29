package com.simpleec.core.repository;

import com.simpleec.core.entity.ShipmentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ShipmentItemRepository extends JpaRepository<ShipmentItem, String> {
    List<ShipmentItem> findByShipmentId(String shipmentId);
    List<ShipmentItem> findByOrderId(String orderId);
    List<ShipmentItem> findByShipmentIdIn(List<String> shipmentIds);

    /**
     * Find all active (non-cancelled) shipment items for an order.
     * Uses native SQL because ShipmentItem.shipmentId is a plain String FK (no @ManyToOne),
     * so JPQL cross-entity JOIN would fail.
     */
    @Query(value = "SELECT si.* FROM shipment_items si " +
                   "JOIN shipments s ON s.id = si.shipment_id " +
                   "WHERE si.order_id = :orderId AND s.status <> 'CANCELLED'",
           nativeQuery = true)
    List<ShipmentItem> findActiveByOrderId(@org.springframework.data.repository.query.Param("orderId") String orderId);
}
