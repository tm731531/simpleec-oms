package com.simpleec.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.ReturnStatusEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.core.entity.Channel;
import com.simpleec.core.entity.Order;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.entity.ReturnOrder;
import com.simpleec.core.repository.ChannelRepository;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.core.repository.PlatformRepository;
import com.simpleec.core.service.ReturnOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 退貨 API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/returns")
@RequiredArgsConstructor
public class ReturnController {

    private final ReturnOrderService returnOrderService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;
    private final ChannelRepository channelRepository;
    private final PlatformRepository platformRepository;

    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 查詢退貨列表（分頁）
     * GET /api/returns?page=0&size=10
     */
    @GetMapping
    public ResponseEntity<Page<ReturnOrder>> listReturns(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {

        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > MAX_PAGE_SIZE) size = MAX_PAGE_SIZE;

        String merchantId = principal.getMerchantId();
        Pageable pageable = PageRequest.of(page, size);

        Page<ReturnOrder> returns;
        if (status != null) {
            try {
                ReturnStatusEnum statusEnum = ReturnStatusEnum.fromCode(status);
                returns = returnOrderService.findByStatus(merchantId, statusEnum, pageable);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            returns = returnOrderService.findByMerchantId(merchantId, pageable);
        }

        log.info("Listed {} returns for merchant {}", returns.getTotalElements(), merchantId);
        return ResponseEntity.ok(returns);
    }

    /**
     * 查詢單筆退貨
     * GET /api/returns/{returnId}
     */
    @GetMapping("/{returnId}")
    public ResponseEntity<ReturnOrder> getReturn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String returnId) {
        Optional<ReturnOrder> returnOrder = returnOrderService.findById(returnId);

        if (returnOrder.isPresent() && principal.getMerchantId().equals(returnOrder.get().getMerchantId())) {
            log.info("Retrieved return: {}", returnId);
            return ResponseEntity.ok(returnOrder.get());
        } else {
            log.warn("Return not found or access denied: {}", returnId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 查詢訂單的退貨
     * GET /api/returns/order/{orderId}
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<ReturnOrder>> getReturnsByOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String orderId) {
        List<ReturnOrder> returns = returnOrderService.findByOrderId(orderId);
        returns = returns.stream()
            .filter(r -> principal.getMerchantId().equals(r.getMerchantId()))
            .toList();

        log.info("Retrieved {} returns for order {}", returns.size(), orderId);
        return ResponseEntity.ok(returns);
    }

    /**
     * 建立退貨
     * POST /api/returns
     */
    @PostMapping
    public ResponseEntity<ReturnOrder> createReturn(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ReturnOrder returnOrder) {
        try {
            returnOrder.setMerchantId(principal.getMerchantId());
            ReturnOrder created = returnOrderService.createReturn(returnOrder);
            log.info("Created return: {} for order {}", created.getId(), created.getOrderId());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (Exception e) {
            log.error("Error creating return", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 更新退貨狀態
     * PATCH /api/returns/{returnId}
     */
    @PatchMapping("/{returnId}")
    public ResponseEntity<ReturnOrder> updateReturn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String returnId,
            @RequestBody ReturnOrder returnUpdate) {

        Optional<ReturnOrder> existing = returnOrderService.findById(returnId);

        if (existing.isEmpty() || !principal.getMerchantId().equals(existing.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }

        ReturnOrder returnOrder = existing.get();

        if (returnUpdate.getReturnStatus() != null) {
            returnOrder.setReturnStatus(returnUpdate.getReturnStatus());
        }
        if (returnUpdate.getReason() != null) {
            returnOrder.setReason(returnUpdate.getReason());
        }

        ReturnOrder updated = returnOrderService.updateReturn(returnOrder);
        log.info("Updated return: {}", returnId);
        return ResponseEntity.ok(updated);
    }

    /**
     * 批准退貨
     * POST /api/returns/{returnId}/approve
     */
    @PostMapping("/{returnId}/approve")
    public ResponseEntity<ReturnOrder> approveReturn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String returnId) {
        Optional<ReturnOrder> existing = returnOrderService.findById(returnId);

        if (existing.isEmpty() || !principal.getMerchantId().equals(existing.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }

        ReturnOrder returnOrder = existing.get();
        returnOrder.setReturnStatus(ReturnStatusEnum.APPROVED);
        ReturnOrder updated = returnOrderService.updateReturn(returnOrder);

        sendReturnActionEvent("APPROVE_RETURN", returnId, updated, principal);

        log.info("Approved return: {}", returnId);
        return ResponseEntity.ok(updated);
    }

    /**
     * 拒絕退貨
     * POST /api/returns/{returnId}/reject
     */
    @PostMapping("/{returnId}/reject")
    public ResponseEntity<ReturnOrder> rejectReturn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String returnId) {
        Optional<ReturnOrder> existing = returnOrderService.findById(returnId);

        if (existing.isEmpty() || !principal.getMerchantId().equals(existing.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }

        ReturnOrder returnOrder = existing.get();
        returnOrder.setReturnStatus(ReturnStatusEnum.REJECTED);
        ReturnOrder updated = returnOrderService.updateReturn(returnOrder);

        sendReturnActionEvent("REJECT_RETURN", returnId, updated, principal);

        log.info("Rejected return: {}", returnId);
        return ResponseEntity.ok(updated);
    }

    /**
     * Sends APPROVE_RETURN or REJECT_RETURN Kafka event to the platform's fast topic.
     * Resolves channelId and platformName via: returnOrder.orderId → order → channel → platform.
     */
    private void sendReturnActionEvent(String taskType, String returnId,
                                        ReturnOrder returnOrder, UserPrincipal principal) {
        try {
            Optional<Order> orderOpt = orderRepository.findById(returnOrder.getOrderId());
            if (orderOpt.isEmpty()) {
                log.error("{}: order not found for return {}", taskType, returnId);
                return;
            }
            String channelId = orderOpt.get().getChannelId();

            Optional<Channel> channelOpt = channelRepository.findById(channelId);
            if (channelOpt.isEmpty()) {
                log.error("{}: channel not found: {}", taskType, channelId);
                return;
            }
            String platformId = channelOpt.get().getPlatformId();

            Optional<Platform> platformOpt = platformRepository.findById(platformId);
            if (platformOpt.isEmpty()) {
                log.error("{}: platform not found: {}", taskType, platformId);
                return;
            }
            String platformName = platformOpt.get().getPlatformName();

            ObjectNode header = objectMapper.createObjectNode();
            header.put("taskType", taskType);
            header.put("merchantId", principal.getMerchantId());
            header.put("platformId", platformId);
            header.put("channelId", channelId);
            header.put("requestId", NanoIdUtil.generate());
            header.put("timestamp", java.time.Instant.now().toString());
            header.put("source", "api");
            header.put("version", 1);
            header.put("isRollback", false);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("returnId", returnId);

            ObjectNode message = objectMapper.createObjectNode();
            message.set("header", header);
            message.set("body", body);

            String topic = TopicConstants.platformFastTopic(platformName);
            kafkaTemplate.send(topic, channelId, message);  // partition by channelId per contract §4.7
            log.info("{}: sent to topic {} for return {}", taskType, topic, returnId);
        } catch (Exception e) {
            log.error("{}: failed to send Kafka event for return {}", taskType, returnId, e);
        }
    }
}
