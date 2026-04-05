package com.simpleec.api.controller;

import com.simpleec.core.crypto.EncryptionContext;
import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.api.vo.OrderVO;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.OrderStatusEnum;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.entity.Order;
import com.simpleec.core.entity.OrderStatusLog;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.entity.ShipmentItem;
import com.simpleec.core.repository.ChannelRepository;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.core.repository.OrderStatusLogRepository;
import com.simpleec.core.repository.ShipmentItemRepository;
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
    private final OrderStatusLogRepository statusLogRepository;
    private final ShipmentItemRepository shipmentItemRepository;

    /**
     * POST /api/user/orders
     * 接收訂單並發布 ORDER_UPSERT 到 order.process，走完整事件流：
     * Kafka → OrderUpsertConsumer → DB → stats dirty marker → DailyStatisticsService
     */
    @PostMapping
    public ResponseEntity<?> receiveOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> requestBody) {

        String channelId = (String) requestBody.get("channelId");
        String channelOrderId = (String) requestBody.get("channelOrderId");

        if (channelId == null || channelOrderId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "channelId and channelOrderId are required"));
        }

        Optional<Channel> channelOpt = channelRepository.findById(channelId);
        if (channelOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Channel not found: " + channelId));
        }
        Channel channel = channelOpt.get();
        if (!principal.getMerchantId().equals(channel.getMerchantId())) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Channel does not belong to your merchant"));
        }

        Optional<Platform> platformOpt = platformRepository.findById(channel.getPlatformId());
        String platformId = platformOpt.map(p -> p.getPlatformName().toLowerCase()).orElse("unknown");

        // Build orderData (strip routing fields)
        Map<String, Object> orderData = new HashMap<>(requestBody);
        orderData.remove("channelId");
        orderData.remove("channelOrderId");
        String channelOrderNumber = (String) orderData.remove("channelOrderNumber");

        // Deterministic hash for dedup: status + amount change triggers re-process
        String hashInput = channelOrderId + ":"
                + orderData.getOrDefault("orderStatus", "") + ":"
                + orderData.getOrDefault("totalAmount", "");
        String orderHash = org.apache.commons.codec.digest.DigestUtils.sha256Hex(hashInput);

        Map<String, Object> header = new HashMap<>();
        header.put("taskType", "ORDER_UPSERT");
        header.put("merchantId", principal.getMerchantId());
        header.put("channelId", channelId);
        header.put("platformId", platformId);
        header.put("messageId", UUID.randomUUID().toString());
        header.put("version", 1);
        header.put("isRollback", false);
        header.put("timestamp", Instant.now().toString());
        header.put("source", "api");

        Map<String, Object> body = new HashMap<>();
        body.put("channelOrderId", channelOrderId);
        if (channelOrderNumber != null) body.put("channelOrderNumber", channelOrderNumber);
        body.put("orderHash", orderHash);
        body.put("orderData", orderData);

        Map<String, Object> message = new HashMap<>();
        message.put("header", header);
        message.put("body", body);

        kafkaTemplate.send(TopicConstants.ORDER_PROCESS, channelOrderId, message);
        log.info("Published ORDER_UPSERT for {} from {} (merchant {})",
                channelOrderId, channelId, principal.getMerchantId());

        return ResponseEntity.accepted().body(Map.of(
                "channelOrderId", channelOrderId,
                "status", "accepted"
        ));
    }

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
                publishShipOrderEvent(order,
                        (String) body.get("trackingNumber"),
                        (String) body.get("carrier"));
            } else if ("cancel".equals(action)) {
                order.setOrderStatus(OrderStatusEnum.CANCELLED);
                order = orderRepository.save(order);
                publishCancelOrderEvent(order, body.get("reason"));
            } else {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(order);
        } finally {
            EncryptionContext.clear();
        }
    }

    /**
     * GET /api/user/orders/{orderId}/shipments
     * Returns all shipment items for a specific order (uses new shipments tables).
     */
    @GetMapping("/{orderId}/shipments")
    public ResponseEntity<List<ShipmentItem>> listShipmentsForOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String orderId) {

        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty() || !principal.getMerchantId().equals(orderOpt.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(shipmentItemRepository.findByOrderId(orderId));
    }

    /**
     * GET /api/user/orders/{id}/status-logs
     * Returns the full status change history for an order, oldest-first.
     * Use this to render a timeline in the UI.
     */
    @GetMapping("/{id}/status-logs")
    public ResponseEntity<List<OrderStatusLog>> getStatusLogs(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {

        Optional<Order> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty() || !principal.getMerchantId().equals(orderOpt.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        List<OrderStatusLog> logs = statusLogRepository.findByOrderIdOrderByCreatedAtAsc(id);
        return ResponseEntity.ok(logs);
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

    /**
     * Publishes a SHIP_ORDER event to the platform fast topic.
     * Body includes channelOrderId, trackingNumber, and carrier so the channel handler
     * can call the platform shipment confirmation API.
     */
    private void publishShipOrderEvent(Order order, String trackingNumber, String carrier) {
        try {
            Optional<Channel> channelOpt = channelRepository.findById(order.getChannelId());
            channelOpt.ifPresent(channel -> {
                Optional<Platform> platformOpt = platformRepository.findById(channel.getPlatformId());
                platformOpt.ifPresent(platform -> {
                    String topic = TopicConstants.platformFastTopic(platform.getPlatformName().toLowerCase());

                    Map<String, Object> header = new HashMap<>();
                    header.put("taskType", "SHIP_ORDER");
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
                    if (trackingNumber != null) body.put("trackingNumber", trackingNumber);
                    if (carrier != null) body.put("carrier", carrier);

                    Map<String, Object> message = new HashMap<>();
                    message.put("header", header);
                    message.put("body", body);

                    kafkaTemplate.send(topic, order.getId(), message);
                    log.info("Published SHIP_ORDER to {}", topic);
                });
            });
        } catch (Exception e) {
            log.warn("Failed to publish SHIP_ORDER for order {}: {}", order.getId(), e.getMessage());
        }
    }

    /**
     * Publishes a CANCEL_ORDER_INTERNAL event to order.process topic so it is handled
     * by the order state machine (OrderUpsertConsumer / CancelOrderHandler) instead of
     * being routed to a platform fast topic.
     */
    private void publishCancelOrderEvent(Order order, Object reason) {
        try {
            Optional<Channel> channelOpt = channelRepository.findById(order.getChannelId());
            channelOpt.ifPresent(channel -> {
                Optional<Platform> platformOpt = platformRepository.findById(channel.getPlatformId());
                platformOpt.ifPresent(platform -> {
                    Map<String, Object> header = new HashMap<>();
                    header.put("taskType", "CANCEL_ORDER_INTERNAL");
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
                    if (reason != null) body.put("reason", reason);

                    Map<String, Object> message = new HashMap<>();
                    message.put("header", header);
                    message.put("body", body);

                    kafkaTemplate.send(TopicConstants.ORDER_PROCESS, order.getId(), message);
                    log.info("Published CANCEL_ORDER_INTERNAL to {}", TopicConstants.ORDER_PROCESS);
                });
            });
        } catch (Exception e) {
            log.warn("Failed to publish cancel event for order {}: {}", order.getId(), e.getMessage());
        }
    }
}
