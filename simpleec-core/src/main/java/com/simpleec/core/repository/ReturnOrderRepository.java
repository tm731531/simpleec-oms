package com.simpleec.core.repository;

import com.simpleec.core.entity.ReturnOrder;
import com.simpleec.common.enums.ReturnStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
