package com.simpleec.api.controller;

import com.simpleec.core.entity.Order;
import com.simpleec.core.service.OrderService;
import com.simpleec.common.enums.OrderStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 訂單 API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 查詢訂單列表（分頁）
     * GET /api/orders?merchantId=M001&page=0&size=10&status=PENDING
     */
    @GetMapping
    public ResponseEntity<Page<Order>> listOrders(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {

        Pageable pageable = PageRequest.of(page, size);

        Page<Order> orders;
        if (status != null) {
            try {
                OrderStatusEnum statusEnum = OrderStatusEnum.fromCode(status);
                orders = orderService.findByStatus(merchantId, statusEnum, pageable);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            orders = orderService.findByMerchantId(merchantId, pageable);
        }

        log.info("Listed {} orders for merchant {}", orders.getTotalElements(), merchantId);
        return ResponseEntity.ok(orders);
    }

    /**
     * 查詢單筆訂單
     * GET /api/orders/{orderId}
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        Optional<Order> order = orderService.findById(orderId);

        if (order.isPresent()) {
            log.info("Retrieved order: {}", orderId);
            return ResponseEntity.ok(order.get());
        } else {
            log.warn("Order not found: {}", orderId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 查詢通路訂單
     * GET /api/orders/channel/{channelId}/{channelOrderId}
     */
    @GetMapping("/channel/{channelId}/{channelOrderId}")
    public ResponseEntity<Order> getByChannelOrderId(
            @PathVariable String channelId,
            @PathVariable String channelOrderId) {

        Optional<Order> order = orderService.findByChannelOrderId(channelId, channelOrderId);

        if (order.isPresent()) {
            return ResponseEntity.ok(order.get());
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 建立訂單
     * POST /api/orders
     */
    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody Order order) {
        try {
            Order created = orderService.createOrder(order);
            log.info("Created order: {}", created.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (Exception e) {
            log.error("Error creating order", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 更新訂單
     * PATCH /api/orders/{orderId}
     */
    @PatchMapping("/{orderId}")
    public ResponseEntity<Order> updateOrder(
            @PathVariable String orderId,
            @RequestBody Order orderUpdate) {

        Optional<Order> existing = orderService.findById(orderId);

        if (existing.isPresent()) {
            Order order = existing.get();

            // 更新允許修改的欄位
            if (orderUpdate.getOrderStatus() != null) {
                order.setOrderStatus(orderUpdate.getOrderStatus());
            }
            if (orderUpdate.getItems() != null) {
                order.setItems(orderUpdate.getItems());
            }

            Order updated = orderService.updateOrder(order);
            log.info("Updated order: {}", orderId);
            return ResponseEntity.ok(updated);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 查詢訂單統計
     * GET /api/orders/stats?merchantId=M001
     */
    @GetMapping("/stats/summary")
    public ResponseEntity<Object> getOrderStats(@RequestParam String merchantId) {
        var stats = new java.util.HashMap<String, Object>();
        stats.put("merchantId", merchantId);
        stats.put("totalOrders", orderService.countByStatus(merchantId, null)); // TODO: sum all
        stats.put("pendingOrders", orderService.countByStatus(merchantId, OrderStatusEnum.PENDING));
        stats.put("confirmedOrders", orderService.countByStatus(merchantId, OrderStatusEnum.CONFIRMED));
        stats.put("shippedOrders", orderService.countByStatus(merchantId, OrderStatusEnum.SHIPPED));
        stats.put("completedOrders", orderService.countByStatus(merchantId, OrderStatusEnum.COMPLETED));

        return ResponseEntity.ok(stats);
    }
}
