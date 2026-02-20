package com.simpleec.core.service;

import com.simpleec.core.entity.Order;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.common.enums.OrderStatusEnum;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 訂單服務
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    /**
     * 查詢單筆訂單
     */
    public Optional<Order> findById(String orderId) {
        return orderRepository.findById(orderId);
    }

    /**
     * 根據通路訂單 ID 查詢
     */
    public Optional<Order> findByChannelOrderId(String channelId, String channelOrderId) {
        return orderRepository.findByChannelIdAndChannelOrderId(channelId, channelOrderId);
    }

    /**
     * 查詢商家的訂單（分頁）
     */
    public Page<Order> findByMerchantId(String merchantId, Pageable pageable) {
        return orderRepository.findByMerchantId(merchantId, pageable);
    }

    /**
     * 查詢特定狀態的訂單
     */
    public Page<Order> findByStatus(String merchantId, OrderStatusEnum status, Pageable pageable) {
        return orderRepository.findByMerchantIdAndOrderStatus(merchantId, status, pageable);
    }

    /**
     * 查詢特定通路的訂單
     */
    public Page<Order> findByChannel(String merchantId, String channelId, Pageable pageable) {
        return orderRepository.findByMerchantIdAndChannelId(merchantId, channelId, pageable);
    }

    /**
     * 建立訂單
     */
    @Transactional
    public Order createOrder(Order order) {
        // 生成 OMS 訂單 ID
        if (order.getOrderId() == null) {
            order.setOrderId(NanoIdUtil.generateWithPrefix("ORD_"));
        }

        log.info("Creating order: {} from channel {}", order.getOrderId(), order.getChannelId());
        return orderRepository.save(order);
    }

    /**
     * 更新訂單
     */
    @Transactional
    public Order updateOrder(Order order) {
        log.info("Updating order: {}", order.getOrderId());
        return orderRepository.save(order);
    }

    /**
     * 統計商家的訂單數（按狀態）
     */
    public long countByStatus(String merchantId, OrderStatusEnum status) {
        return orderRepository.countByMerchantIdAndOrderStatus(merchantId, status);
    }

    /**
     * 統計商家在特定通路的訂單數
     */
    public long countByChannel(String merchantId, String channelId) {
        return orderRepository.countByMerchantIdAndChannelId(merchantId, channelId);
    }
}
