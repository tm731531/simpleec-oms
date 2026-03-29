package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.Order;
import com.simpleec.core.entity.Shipment;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.core.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * REST controller for shipment management.
 *
 * NOTE: This is a transitional stub — will be fully replaced in Task 8
 * (REST API — Replace UserShipmentController) with full CRUD + batch support.
 *
 * All endpoints are JWT-protected via the global security filter chain.
 * merchantId is always read from the JWT principal — never from the request body.
 */
@Slf4j
@RestController
@RequestMapping("/api/user/shipments")
@RequiredArgsConstructor
public class UserShipmentController {

    private final ShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;

    /**
     * GET /api/user/shipments
     * Paginated list of all shipments for the authenticated merchant.
     */
    @GetMapping
    public ResponseEntity<UserPageResponse<Shipment>> listShipments(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {

        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<Shipment> result = shipmentRepository.findByMerchantIdOrderByCreatedAtDesc(
                principal.getMerchantId(), pageable);
        return ResponseEntity.ok(UserPageResponse.from(result));
    }

    /**
     * GET /api/user/shipments/{id}
     * Fetch one shipment by ID; verifies it belongs to the calling merchant.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Shipment> getShipment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {

        Optional<Shipment> shipmentOpt = shipmentRepository.findById(id);
        if (shipmentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Shipment shipment = shipmentOpt.get();
        if (!principal.getMerchantId().equals(shipment.getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(shipment);
    }

    /**
     * GET /api/user/shipments/by-order/{orderId}
     * List all shipments for a specific order.
     */
    @GetMapping("/by-order/{orderId}")
    public ResponseEntity<List<Shipment>> listShipmentsByOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String orderId) {

        if (!isOwnedByMerchant(orderId, principal.getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        List<Shipment> shipments = shipmentRepository.findByBatchId(orderId);
        return ResponseEntity.ok(shipments);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isOwnedByMerchant(String orderId, String merchantId) {
        Optional<Order> orderOpt = orderRepository.findById(orderId);
        return orderOpt.isPresent() && merchantId.equals(orderOpt.get().getMerchantId());
    }
}
