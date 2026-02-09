package com.simpleec.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simpleec.common.model.PageResult;
import com.simpleec.core.crypto.EncryptionContext;
import com.simpleec.core.entity.Order;
import com.simpleec.core.entity.OrderStatusLog;
import com.simpleec.core.mapper.OrderMapper;
import com.simpleec.core.mapper.OrderStatusLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;

    public PageResult<Order> list(String merchantId, int page, int size) {
        EncryptionContext.setMerchantId(merchantId);
        try {
            Page<Order> result = orderMapper.selectPage(
                    new Page<>(page, size),
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getMerchantId, merchantId)
                            .orderByDesc(Order::getCreatedAt)
            );
            return PageResult.of(result.getRecords(), result.getTotal(), page, size);
        } finally {
            EncryptionContext.clear();
        }
    }

    public Order getByChannelOrderId(String merchantId, String channelId, String channelOrderId) {
        EncryptionContext.setMerchantId(merchantId);
        try {
            return orderMapper.selectOne(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getChannelId, channelId)
                            .eq(Order::getChannelOrderId, channelOrderId)
            );
        } finally {
            EncryptionContext.clear();
        }
    }

    @Transactional
    public void saveOrder(Order order) {
        EncryptionContext.setMerchantId(order.getMerchantId());
        try {
            if (order.getId() == null) {
                orderMapper.insert(order);
            } else {
                orderMapper.updateById(order);
            }
        } finally {
            EncryptionContext.clear();
        }
    }

    @Transactional
    public void updateStatus(String merchantId, String orderId, String fromStatus,
                             String toStatus, String operator, String remark) {
        EncryptionContext.setMerchantId(merchantId);
        try {
            Order order = orderMapper.selectById(orderId);
            if (order == null) {
                throw new IllegalArgumentException("Order not found: " + orderId);
            }
            order.setOrderStatus(toStatus);
            order.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(order);

            OrderStatusLog log = new OrderStatusLog();
            log.setOrderId(orderId);
            log.setFromStatus(fromStatus);
            log.setToStatus(toStatus);
            log.setOperator(operator);
            log.setRemark(remark);
            orderStatusLogMapper.insert(log);
        } finally {
            EncryptionContext.clear();
        }
    }
}
