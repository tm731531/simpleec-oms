package com.simpleec.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simpleec.common.model.PageResult;
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
        Page<Order> result = orderMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getMerchantId, merchantId)
                        .orderByDesc(Order::getCreatedAt)
        );
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    public Order getByChannelOrderId(String channelId, String channelOrderId) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getChannelId, channelId)
                        .eq(Order::getChannelOrderId, channelOrderId)
        );
    }

    @Transactional
    public void saveOrder(Order order) {
        if (order.getId() == null) {
            orderMapper.insert(order);
        } else {
            orderMapper.updateById(order);
        }
    }

    @Transactional
    public void updateStatus(String orderId, String fromStatus, String toStatus,
                             String operator, String remark) {
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
    }
}
