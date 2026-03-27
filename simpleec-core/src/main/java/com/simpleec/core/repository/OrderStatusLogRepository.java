package com.simpleec.core.repository;

import com.simpleec.core.entity.OrderStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for order status change logs.
 */
@Repository
public interface OrderStatusLogRepository extends JpaRepository<OrderStatusLog, String> {

    /**
     * Fetch the full status history for an order in chronological order.
     * Use this to render a status timeline in the UI.
     */
    List<OrderStatusLog> findByOrderIdOrderByCreatedAtAsc(String orderId);
}
