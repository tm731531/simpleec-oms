package com.simpleec.core.service;

import com.simpleec.core.entity.ReturnOrder;
import com.simpleec.core.repository.ReturnOrderRepository;
import com.simpleec.common.enums.ReturnStatusEnum;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

/**
 * 退貨服務
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnOrderService {

    private final ReturnOrderRepository returnOrderRepository;

    /**
     * 查詢單筆退貨
     */
    public Optional<ReturnOrder> findById(String returnId) {
        return returnOrderRepository.findById(returnId);
    }

    /**
     * 根據通路 ID + 通路退貨 ID 查詢（DB-C4: scoped dedup）
     */
    public Optional<ReturnOrder> findByChannelIdAndChannelRefundId(String channelId, String channelRefundId) {
        return returnOrderRepository.findByChannelIdAndChannelRefundId(channelId, channelRefundId);
    }

    /**
     * 查詢訂單的退貨
     */
    public List<ReturnOrder> findByOrderId(String orderId) {
        return returnOrderRepository.findByOrderId(orderId);
    }

    /**
     * 查詢商家的退貨（分頁）
     */
    public Page<ReturnOrder> findByMerchantId(String merchantId, Pageable pageable) {
        return returnOrderRepository.findByMerchantId(merchantId, pageable);
    }

    /**
     * 查詢特定狀態的退貨
     */
    public Page<ReturnOrder> findByStatus(String merchantId, ReturnStatusEnum status, Pageable pageable) {
        return returnOrderRepository.findByMerchantIdAndReturnStatus(merchantId, status, pageable);
    }

    /**
     * 建立退貨
     */
    @Transactional
    public ReturnOrder createReturn(ReturnOrder returnOrder) {
        if (returnOrder.getId() == null) {
            returnOrder.setId(NanoIdUtil.generateWithPrefix("RET_"));
        }

        log.info("Creating return: {} for order {}", returnOrder.getId(), returnOrder.getOrderId());
        return returnOrderRepository.save(returnOrder);
    }

    /**
     * 更新退貨
     */
    @Transactional
    public ReturnOrder updateReturn(ReturnOrder returnOrder) {
        log.info("Updating return: {}", returnOrder.getId());
        return returnOrderRepository.save(returnOrder);
    }

    /**
     * 統計待處理退貨
     */
    public long countPending(String merchantId) {
        return returnOrderRepository.countByMerchantIdAndReturnStatus(merchantId, ReturnStatusEnum.PENDING);
    }
}
