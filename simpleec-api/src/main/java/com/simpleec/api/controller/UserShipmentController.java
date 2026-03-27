package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.core.entity.Order;
import com.simpleec.core.entity.OrderShipment;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.core.repository.OrderShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * REST controller for shipment management.
 *
 * All endpoints are JWT-protected via the global security filter chain.
 * merchantId is always read from the JWT principal — never from the request body.
 */
@Slf4j
@RestController
@RequestMapping("/api/user/shipments")
@RequiredArgsConstructor
public class UserShipmentController {

    private final OrderShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;

    // -------------------------------------------------------------------------
    // Inner record types — Request / Response DTOs
    // -------------------------------------------------------------------------

    /**
     * Request body for creating a new shipment record.
     */
    record CreateShipmentRequest(
            String orderId,
            String trackingNumber,
            String carrier          // maps to logistics_company
    ) {}

    /**
     * Request body for updating a tracking number.
     */
    record UpdateTrackingRequest(
            String trackingNumber
    ) {}

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    /**
     * GET /api/user/shipments
     * Paginated list of all shipments for the authenticated merchant.
     */
    @GetMapping
    public ResponseEntity<UserPageResponse<OrderShipment>> listShipments(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {

        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<OrderShipment> result = shipmentRepository.findByMerchantIdOrderByCreatedAtDesc(
                principal.getMerchantId(), pageable);
        return ResponseEntity.ok(UserPageResponse.from(result));
    }

    /**
     * GET /api/user/shipments/{id}
     * Fetch one shipment by ID; verifies it belongs to the calling merchant.
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderShipment> getShipment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {

        Optional<OrderShipment> shipmentOpt = shipmentRepository.findById(id);
        if (shipmentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        OrderShipment shipment = shipmentOpt.get();

        // Ownership check: confirm the parent order belongs to this merchant
        if (!isOwnedByMerchant(shipment.getOrderId(), principal.getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(shipment);
    }

    /**
     * POST /api/user/shipments
     * Create a new shipment record for an existing order.
     */
    @PostMapping
    public ResponseEntity<OrderShipment> createShipment(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CreateShipmentRequest req) {

        if (req.orderId() == null || req.orderId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Verify the order belongs to this merchant
        if (!isOwnedByMerchant(req.orderId(), principal.getMerchantId())) {
            return ResponseEntity.notFound().build();
        }

        OrderShipment shipment = OrderShipment.builder()
                .id(NanoIdUtil.generate())
                .orderId(req.orderId())
                .trackingNumber(req.trackingNumber())
                .logisticsCompany(req.carrier())
                .shippingStatus("pending")
                .shippedAt(req.trackingNumber() != null ? LocalDateTime.now() : null)
                .build();

        OrderShipment saved = shipmentRepository.save(shipment);
        log.info("Created shipment {} for order {}", saved.getId(), req.orderId());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * PATCH /api/user/shipments/{id}
     * Update the tracking number of an existing shipment.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<OrderShipment> updateTracking(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestBody UpdateTrackingRequest req) {

        Optional<OrderShipment> shipmentOpt = shipmentRepository.findById(id);
        if (shipmentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        OrderShipment shipment = shipmentOpt.get();

        if (!isOwnedByMerchant(shipment.getOrderId(), principal.getMerchantId())) {
            return ResponseEntity.notFound().build();
        }

        shipment.setTrackingNumber(req.trackingNumber());
        OrderShipment saved = shipmentRepository.save(shipment);
        log.info("Updated tracking number for shipment {}", id);
        return ResponseEntity.ok(saved);
    }

    /**
     * GET /api/user/orders/{orderId}/shipments
     * List all shipments for a specific order.
     *
     * Note: this path is nested under /orders but defined here for cohesion.
     * We use a separate @GetMapping so Spring does not conflict with
     * the /shipments prefix on the class-level @RequestMapping.
     */
    @GetMapping("/by-order/{orderId}")
    public ResponseEntity<List<OrderShipment>> listShipmentsByOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String orderId) {

        if (!isOwnedByMerchant(orderId, principal.getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        List<OrderShipment> shipments = shipmentRepository.findByOrderId(orderId);
        return ResponseEntity.ok(shipments);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if the order with the given id exists and belongs to merchantId.
     */
    private boolean isOwnedByMerchant(String orderId, String merchantId) {
        Optional<Order> orderOpt = orderRepository.findById(orderId);
        return orderOpt.isPresent() && merchantId.equals(orderOpt.get().getMerchantId());
    }
}
