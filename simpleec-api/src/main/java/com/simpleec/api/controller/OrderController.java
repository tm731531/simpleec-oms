package com.simpleec.api.controller;

import com.simpleec.core.crypto.EncryptionContext;
import com.simpleec.core.entity.Order;
import com.simpleec.core.service.OrderService;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.common.enums.OrderStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 訂單 API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 查詢訂單列表（分頁）
     * GET /api/orders?page=0&size=10&status=PENDING
     */
    @GetMapping
    public ResponseEntity<Page<Order>> listOrders(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {

        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > MAX_PAGE_SIZE) size = MAX_PAGE_SIZE;

        String merchantId = principal.getMerchantId();
        Pageable pageable = PageRequest.of(page, size);

        EncryptionContext.setMerchantId(merchantId);
        try {
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
        } finally {
            EncryptionContext.clear();
        }
    }

    /**
     * 查詢單筆訂單
     * GET /api/orders/{orderId}
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String orderId) {

        String merchantId = principal.getMerchantId();
        EncryptionContext.setMerchantId(merchantId);
        try {
            Optional<Order> order = orderService.findById(orderId);

            if (order.isPresent() && merchantId.equals(order.get().getMerchantId())) {
                log.info("Retrieved order: {}", orderId);
                return ResponseEntity.ok(order.get());
            } else {
                log.warn("Order not found or access denied: {}", orderId);
                return ResponseEntity.notFound().build();
            }
        } finally {
            EncryptionContext.clear();
        }
    }

    /**
     * 查詢通路訂單
     * GET /api/orders/channel/{channelId}/{channelOrderId}
     */
    @GetMapping("/channel/{channelId}/{channelOrderId}")
    public ResponseEntity<Order> getByChannelOrderId(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String channelId,
            @PathVariable String channelOrderId) {

        String merchantId = principal.getMerchantId();
        EncryptionContext.setMerchantId(merchantId);
        try {
            Optional<Order> order = orderService.findByChannelOrderId(channelId, channelOrderId);

            if (order.isPresent() && merchantId.equals(order.get().getMerchantId())) {
                return ResponseEntity.ok(order.get());
            } else {
                return ResponseEntity.notFound().build();
            }
        } finally {
            EncryptionContext.clear();
        }
    }

    /**
     * 建立訂單
     * POST /api/orders
     */
    @PostMapping
    public ResponseEntity<Order> createOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Order order) {
        try {
            order.setMerchantId(principal.getMerchantId());
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
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String orderId,
            @RequestBody Order orderUpdate) {

        String merchantId = principal.getMerchantId();
        EncryptionContext.setMerchantId(merchantId);
        try {
            Optional<Order> existing = orderService.findById(orderId);

            if (existing.isEmpty() || !merchantId.equals(existing.get().getMerchantId())) {
                return ResponseEntity.notFound().build();
            }

            Order order = existing.get();

            if (orderUpdate.getOrderStatus() != null) {
                order.setOrderStatus(orderUpdate.getOrderStatus());
            }
            if (orderUpdate.getItems() != null) {
                order.setItems(orderUpdate.getItems());
            }

            Order updated = orderService.updateOrder(order);
            log.info("Updated order: {}", orderId);
            return ResponseEntity.ok(updated);
        } finally {
            EncryptionContext.clear();
        }
    }

    /**
     * 查詢訂單統計
     * GET /api/orders/stats/summary
     */
    @GetMapping("/stats/summary")
    public ResponseEntity<Object> getOrderStats(@AuthenticationPrincipal UserPrincipal principal) {
        String merchantId = principal.getMerchantId();
        var stats = new java.util.HashMap<String, Object>();
        stats.put("merchantId", merchantId);
        stats.put("pendingOrders", orderService.countByStatus(merchantId, OrderStatusEnum.PENDING));
        stats.put("confirmedOrders", orderService.countByStatus(merchantId, OrderStatusEnum.CONFIRMED));
        stats.put("shippedOrders", orderService.countByStatus(merchantId, OrderStatusEnum.SHIPPED));
        stats.put("completedOrders", orderService.countByStatus(merchantId, OrderStatusEnum.COMPLETED));

        return ResponseEntity.ok(stats);
    }
}
