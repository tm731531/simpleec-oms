package com.simpleec.core.repository;

import com.simpleec.core.entity.Order;
import com.simpleec.core.dto.OrderStatsResult;
import com.simpleec.common.enums.OrderStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

/**
 * 訂單倉庫
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    /**
     * 根據通路訂單 ID 查詢
     */
    Optional<Order> findByChannelIdAndChannelOrderId(String channelId, String channelOrderId);

    /**
     * 查詢商家的訂單（分頁）
     */
    Page<Order> findByMerchantId(String merchantId, Pageable pageable);

    /**
     * 查詢特定狀態的訂單（分頁）
     */
    Page<Order> findByMerchantIdAndOrderStatus(String merchantId, OrderStatusEnum status, Pageable pageable);

    /**
     * 查詢特定通路的訂單（分頁）
     */
    Page<Order> findByMerchantIdAndChannelId(String merchantId, String channelId, Pageable pageable);

    /**
     * 查詢時間範圍內的訂單
     */
    List<Order> findByMerchantIdAndCreatedAtBetween(String merchantId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 統計訂單數（按狀態）
     */
    long countByMerchantIdAndOrderStatus(String merchantId, OrderStatusEnum status);

    /**
     * 統計訂單數（按通路）
     */
    long countByMerchantIdAndChannelId(String merchantId, String channelId);

    @Query(value = """
        SELECT
            COUNT(*)                                                                            AS newOrderCount,
            COALESCE(SUM(total_amount), 0)                                                      AS newOrderAmount,
            SUM(CASE WHEN order_status != 'CANCELLED' THEN 1 ELSE 0 END)                       AS grossOrderCount,
            COALESCE(SUM(CASE WHEN order_status != 'CANCELLED' THEN total_amount ELSE 0 END), 0) AS grossAmount,
            SUM(CASE WHEN order_status IN ('CONFIRMED','READY_TO_SHIP','SHIPPING','PARTIALLY_SHIPPED','SHIPPED','COMPLETED') THEN 1 ELSE 0 END) AS receivedCount,
            COALESCE(SUM(CASE WHEN order_status IN ('CONFIRMED','READY_TO_SHIP','SHIPPING','PARTIALLY_SHIPPED','SHIPPED','COMPLETED') THEN total_amount ELSE 0 END), 0) AS receivedAmount,
            SUM(CASE WHEN order_status IN ('PARTIALLY_SHIPPED', 'SHIPPED', 'COMPLETED') THEN 1 ELSE 0 END) AS shippedCount,
            SUM(CASE WHEN order_status = 'COMPLETED' THEN 1 ELSE 0 END)                        AS completedCount,
            SUM(CASE WHEN order_status = 'CANCELLED' THEN 1 ELSE 0 END)                        AS cancelledCount,
            COALESCE(SUM(
                (SELECT COALESCE(SUM((item->>'quantity')::integer), 0)
                 FROM jsonb_array_elements(COALESCE(items, '[]'::jsonb)) AS item)
            ), 0)                                                                               AS itemSoldCount
        FROM orders
        WHERE merchant_id = :merchantId
          AND channel_id = :channelId
          AND COALESCE(channel_created_at, created_at) >= :startOfDay
          AND COALESCE(channel_created_at, created_at) < :endOfDay
        """, nativeQuery = true)
    @org.springframework.data.jpa.repository.QueryHints(
        @jakarta.persistence.QueryHint(name = "org.hibernate.readOnly", value = "true")
    )
    OrderStatsResult aggregateStatsByChannelAndDate(
        @Param("merchantId") String merchantId,
        @Param("channelId") String channelId,
        @Param("startOfDay") LocalDateTime startOfDay,
        @Param("endOfDay") LocalDateTime endOfDay
    );
}
