package com.simpleec.core.repository;

import com.simpleec.core.entity.ReturnOrder;
import com.simpleec.common.enums.ReturnStatusEnum;
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
 * 退貨倉庫
 */
@Repository
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, String> {

    /**
     * 根據通路退貨 ID 查詢
     */
    Optional<ReturnOrder> findByChannelRefundId(String channelRefundId);

    /**
     * 查詢訂單的退貨
     */
    List<ReturnOrder> findByOrderId(String orderId);

    /**
     * 查詢商家的退貨（分頁）
     */
    Page<ReturnOrder> findByMerchantId(String merchantId, Pageable pageable);

    /**
     * 查詢特定狀態的退貨（分頁）
     */
    Page<ReturnOrder> findByMerchantIdAndReturnStatus(String merchantId, ReturnStatusEnum status, Pageable pageable);

    /**
     * 統計退貨數
     */
    long countByMerchantIdAndReturnStatus(String merchantId, ReturnStatusEnum status);

    /**
     * Count returns grouped by status in a single query.
     * Returns Object[]{ReturnStatusEnum status, Long count} per row.
     */
    @Query("SELECT r.returnStatus, COUNT(r) FROM ReturnOrder r WHERE r.merchantId = :merchantId GROUP BY r.returnStatus")
    List<Object[]> countByMerchantIdGroupByStatus(@Param("merchantId") String merchantId);

    /**
     * Count refund orders for a merchant/channel on a specific stat date.
     * Joins refund_orders with orders to filter by channelId and creation date.
     */
    @Query(value = """
        SELECT COUNT(ro.id)
        FROM refund_orders ro
        JOIN orders o ON ro.order_id = o.id
        WHERE ro.merchant_id = :merchantId
          AND o.channel_id = :channelId
          AND ro.requested_at >= :startOfDay
          AND ro.requested_at < :endOfDay
        """, nativeQuery = true)
    long countByMerchantIdAndChannelIdAndStatDate(
        @Param("merchantId") String merchantId,
        @Param("channelId") String channelId,
        @Param("startOfDay") LocalDateTime startOfDay,
        @Param("endOfDay") LocalDateTime endOfDay
    );

    @Query(value = """
        SELECT COALESCE(SUM(ro.refund_amount), 0)
        FROM refund_orders ro
        JOIN orders o ON ro.order_id = o.id
        WHERE ro.merchant_id = :merchantId
          AND o.channel_id = :channelId
          AND ro.requested_at >= :startOfDay
          AND ro.requested_at < :endOfDay
        """, nativeQuery = true)
    java.math.BigDecimal sumRefundAmountByMerchantIdAndChannelIdAndStatDate(
        @Param("merchantId") String merchantId,
        @Param("channelId") String channelId,
        @Param("startOfDay") LocalDateTime startOfDay,
        @Param("endOfDay") LocalDateTime endOfDay
    );
}
