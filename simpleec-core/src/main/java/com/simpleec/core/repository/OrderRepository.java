package com.simpleec.core.repository;

import com.simpleec.core.entity.Order;
import com.simpleec.common.enums.OrderStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
