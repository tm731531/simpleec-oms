package com.oneec.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.oneec.common.model.PageResult;
import com.oneec.core.entity.Order;
import com.oneec.core.entity.OrderItem;
import com.oneec.core.entity.OrderStatusLog;
import com.oneec.core.mapper.OrderItemMapper;
import com.oneec.core.mapper.OrderMapper;
import com.oneec.core.mapper.OrderStatusLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;

    public PageResult<Order> list(Long merchantId, int page, int size) {
        Page<Order> result = orderMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getMerchantId, merchantId)
                        .orderByDesc(Order::getCreatedAt)
        );
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    public Order getByChannelOrderId(Long channelId, String channelOrderId) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getChannelId, channelId)
                        .eq(Order::getChannelOrderId, channelOrderId)
        );
    }

    @Transactional
    public void saveOrder(Order order, List<OrderItem> items) {
        if (order.getId() == null) {
            orderMapper.insert(order);
        } else {
            orderMapper.updateById(order);
        }
        for (OrderItem item : items) {
            item.setOrderId(order.getId());
            if (item.getId() == null) {
                orderItemMapper.insert(item);
            } else {
                orderItemMapper.updateById(item);
            }
        }
    }

    @Transactional
    public void updateStatus(Long orderId, String fromStatus, String toStatus, String operator, String remark) {
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

    public List<OrderItem> getOrderItems(Long orderId) {
        return orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId)
        );
    }
}
