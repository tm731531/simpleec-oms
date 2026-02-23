package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.OrderStatusEnum;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.entity.Order;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.repository.ChannelRepository;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.core.repository.PlatformRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 用戶訂單管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/user/orders")
@RequiredArgsConstructor
public class UserOrderController {
    private final OrderRepository orderRepository;
    private final ChannelRepository channelRepository;
    private final PlatformRepository platformRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @GetMapping
    public ResponseEntity<UserPageResponse<Order>> listOrders(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String channelId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<Order> orders;
        if (status != null) {
            try {
                OrderStatusEnum statusEnum = OrderStatusEnum.fromCode(status);
                orders = orderRepository.findByMerchantIdAndOrderStatus(principal.getMerchantId(), statusEnum, pageable);
            } catch (IllegalArgumentException e) { orders = Page.empty(); }
        } else if (channelId != null) {
            orders = orderRepository.findByMerchantIdAndChannelId(principal.getMerchantId(), channelId, pageable);
        } else {
            orders = orderRepository.findByMerchantId(principal.getMerchantId(), pageable);
        }
        return ResponseEntity.ok(UserPageResponse.from(orders));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {
        Optional<Order> order = orderRepository.findById(id);
        if (order.isEmpty() || !principal.getMerchantId().equals(order.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(order.get());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Order> updateOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<Order> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty() || !principal.getMerchantId().equals(orderOpt.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        Order order = orderOpt.get();
        String action = (String) body.get("action");

        if ("ship".equals(action)) {
            order.setOrderStatus(OrderStatusEnum.SHIPPED);
            order.setShippedAt(LocalDateTime.now());
            order = orderRepository.save(order);
            publishOrderEvent(order, "SHIP_ORDER", body.get("trackingNumber"));
        } else if ("cancel".equals(action)) {
            order.setOrderStatus(OrderStatusEnum.CANCELLED);
            order = orderRepository.save(order);
            publishOrderEvent(order, "CANCEL_ORDER", body.get("reason"));
        } else {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(order);
    }

    private void publishOrderEvent(Order order, String taskType, Object metadata) {
        try {
            Optional<Channel> channelOpt = channelRepository.findById(order.getChannelId());
            channelOpt.ifPresent(channel -> {
                Optional<Platform> platformOpt = platformRepository.findById(channel.getPlatformId());
                platformOpt.ifPresent(platform -> {
                    String topic = TopicConstants.platformFastTopic(platform.getPlatformName().toLowerCase());
                    Map<String, Object> message = new HashMap<>();
                    message.put("taskType", taskType);
                    message.put("orderId", order.getId());
                    message.put("channelId", order.getChannelId());
                    message.put("merchantId", order.getMerchantId());
                    if (metadata != null) message.put("metadata", metadata);
                    kafkaTemplate.send(topic, order.getId(), message);
                    log.info("Published {} to {}", taskType, topic);
                });
            });
        } catch (Exception e) {
            log.warn("Failed to publish Kafka event for order {}: {}", order.getId(), e.getMessage());
        }
    }
}
