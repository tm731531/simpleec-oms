package com.simpleec.api.controller;

import com.simpleec.core.crypto.EncryptionContext;
import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.api.vo.OrderVO;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    public ResponseEntity<UserPageResponse<OrderVO>> listOrders(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String channelId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        // Set encryption context for decrypting PII fields in Order entities
        EncryptionContext.setMerchantId(principal.getMerchantId());
        try {
            // 轉換 1-indexed 頁碼到 0-indexed (Spring Data 期望的格式)
            PageRequest pageable = PageRequest.of(page - 1, pageSize);
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

            // 轉換 Order 到 OrderVO，並填充 platform 信息
            List<OrderVO> orderVOs = orders.stream().map(order -> {
                String platformName = getPlatformName(order.getChannelId());
                return OrderVO.from(order, platformName);
            }).collect(Collectors.toList());

            // 建立新的 Page 物件，保留分頁信息
            Page<OrderVO> orderVOPage = new PageImpl<>(orderVOs, orders.getPageable(), orders.getTotalElements());
            return ResponseEntity.ok(UserPageResponse.from(orderVOPage));
        } finally {
            EncryptionContext.clear();
        }
    }

    /**
     * 根據 channelId 獲取平台名稱
     */
    private String getPlatformName(String channelId) {
        try {
            Optional<Channel> channelOpt = channelRepository.findById(channelId);
            if (channelOpt.isPresent()) {
                Optional<Platform> platformOpt = platformRepository.findById(channelOpt.get().getPlatformId());
                if (platformOpt.isPresent()) {
                    return platformOpt.get().getPlatformName();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get platform name for channel {}", channelId, e);
        }
        return "Unknown";  // 默認值
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {
        EncryptionContext.setMerchantId(principal.getMerchantId());
        try {
            Optional<Order> order = orderRepository.findById(id);
            if (order.isEmpty() || !principal.getMerchantId().equals(order.get().getMerchantId())) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(order.get());
        } finally {
            EncryptionContext.clear();
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Order> updateOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        EncryptionContext.setMerchantId(principal.getMerchantId());
        try {
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
        } finally {
            EncryptionContext.clear();
        }
    }

    private void publishOrderEvent(Order order, String taskType, Object metadata) {
        try {
            Optional<Channel> channelOpt = channelRepository.findById(order.getChannelId());
            channelOpt.ifPresent(channel -> {
                Optional<Platform> platformOpt = platformRepository.findById(channel.getPlatformId());
                platformOpt.ifPresent(platform -> {
                    String topic = TopicConstants.platformFastTopic(platform.getPlatformName().toLowerCase());

                    Map<String, Object> header = new HashMap<>();
                    header.put("taskType", taskType);
                    header.put("merchantId", order.getMerchantId());
                    header.put("platformId", platform.getPlatformName().toLowerCase());
                    header.put("channelId", order.getChannelId());
                    header.put("requestId", UUID.randomUUID().toString());
                    header.put("timestamp", Instant.now().toString());
                    header.put("source", "api");
                    header.put("version", 1);
                    header.put("isRollback", false);

                    Map<String, Object> body = new HashMap<>();
                    body.put("orderId", order.getId());
                    if (metadata != null) body.put("metadata", metadata);

                    Map<String, Object> message = new HashMap<>();
                    message.put("header", header);
                    message.put("body", body);

                    kafkaTemplate.send(topic, order.getId(), message);
                    log.info("Published {} to {}", taskType, topic);
                });
            });
        } catch (Exception e) {
            log.warn("Failed to publish Kafka event for order {}: {}", order.getId(), e.getMessage());
        }
    }
}
