package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.Shipment;
import com.simpleec.core.entity.ShipmentBatch;
import com.simpleec.core.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/shipment-batches")
@RequiredArgsConstructor
public class UserShipmentBatchController {

    private final ShipmentService shipmentService;

    record CreateBatchRequest(String carrier, LocalDateTime scheduledPickupAt, String notes) {}
    record ForceReadyRequest(String note) {}
    record ConfirmPickupRequest(String carrierDriverId, int boxCount, BigDecimal logisticsCost) {}

    @PostMapping
    public ResponseEntity<ShipmentBatch> createBatch(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody CreateBatchRequest req) {
        ShipmentBatch batch = shipmentService.createBatch(
            p.getMerchantId(), req.carrier(), req.scheduledPickupAt(), req.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(batch);
    }

    @GetMapping
    public ResponseEntity<UserPageResponse<ShipmentBatch>> listBatches(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<ShipmentBatch> result = shipmentService.findBatchesByMerchant(
            p.getMerchantId(), PageRequest.of(page - 1, pageSize));
        return ResponseEntity.ok(UserPageResponse.from(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getBatch(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        return shipmentService.findBatchById(id)
            .filter(b -> b.getMerchantId().equals(p.getMerchantId()))
            .map(b -> {
                List<Shipment> shipments = shipmentService.findShipmentsByBatch(id);
                return ResponseEntity.ok(Map.of("batch", (Object) b, "shipments", shipments));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/force-ready")
    public ResponseEntity<ShipmentBatch> forceReady(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody ForceReadyRequest req) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.forceReadyBatch(id, req.note()));
    }

    @PutMapping("/{id}/confirm-pickup")
    public ResponseEntity<ShipmentBatch> confirmPickup(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody ConfirmPickupRequest req) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.confirmPickup(
            id, req.carrierDriverId(), req.boxCount(), req.logisticsCost()));
    }

    @GetMapping("/{id}/manifest")
    public ResponseEntity<Map<String, Object>> manifest(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.generateManifest(id));
    }

    private void verifyOwnership(String batchId, String merchantId) {
        shipmentService.findBatchById(batchId).ifPresentOrElse(b -> {
            if (!b.getMerchantId().equals(merchantId)) {
                throw new org.springframework.security.access.AccessDeniedException("Not your batch");
            }
        }, () -> { throw new IllegalArgumentException("Batch not found: " + batchId); });
    }
}
