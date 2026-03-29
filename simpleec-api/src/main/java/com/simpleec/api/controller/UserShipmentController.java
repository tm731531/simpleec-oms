package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.common.enums.ShipmentExceptionTypeEnum;
import com.simpleec.common.enums.ShipmentStatusEnum;
import com.simpleec.core.entity.Shipment;
import com.simpleec.core.entity.ShipmentItem;
import com.simpleec.core.entity.ShipmentStatusLog;
import com.simpleec.core.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/user/shipments")
@RequiredArgsConstructor
public class UserShipmentController {

    private final ShipmentService shipmentService;

    // ---- Request records ----
    // channelId removed — derived automatically from order.getChannelId() in ShipmentService
    record CreateShipmentsRequest(List<String> orderIds) {}
    record SetTrackingRequest(String trackingNumber, String carrier) {}
    record SplitRequest(List<String> channelItemIds) {}
    record MergeRequest(String shipmentIdB) {}
    record CancelRequest(String reason) {}
    record ExceptionRequest(String exceptionType, String note) {}
    record ResolveExceptionRequest(String resumeStatus) {}
    record PickListRequest(List<String> shipmentIds) {}

    @PostMapping
    public ResponseEntity<List<Shipment>> createShipments(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody CreateShipmentsRequest req) {
        List<Shipment> shipments = shipmentService.createShipments(
            req.orderIds(), p.getMerchantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(shipments);
    }

    @GetMapping
    public ResponseEntity<UserPageResponse<Shipment>> listShipments(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<Shipment> result = shipmentService.findByMerchant(
            p.getMerchantId(), PageRequest.of(page - 1, pageSize));
        return ResponseEntity.ok(UserPageResponse.from(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getShipment(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        Optional<Shipment> opt = shipmentService.findById(id);
        if (opt.isEmpty() || !opt.get().getMerchantId().equals(p.getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        Shipment s = opt.get();
        List<ShipmentItem> items = shipmentService.findItemsByShipmentId(id);
        List<ShipmentStatusLog> logs = shipmentService.findStatusLogs(id);
        return ResponseEntity.ok(Map.of("shipment", s, "items", items, "statusLogs", logs));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Shipment> advanceStatus(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.advanceStatus(id, p.getAccountId()));
    }

    @PutMapping("/{id}/tracking")
    public ResponseEntity<Shipment> setTracking(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody SetTrackingRequest req) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(
            shipmentService.setTracking(id, req.trackingNumber(), req.carrier(), p.getAccountId()));
    }

    @PostMapping("/{id}/split")
    public ResponseEntity<List<Shipment>> split(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody SplitRequest req) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.split(id, req.channelItemIds(), p.getAccountId()));
    }

    @PostMapping("/{id}/merge")
    public ResponseEntity<Shipment> mergeInto(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody MergeRequest req) {
        verifyOwnership(id, p.getMerchantId());
        verifyOwnership(req.shipmentIdB(), p.getMerchantId());
        return ResponseEntity.ok(shipmentService.merge(id, req.shipmentIdB(), p.getAccountId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Shipment> cancel(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody(required = false) CancelRequest req) {
        verifyOwnership(id, p.getMerchantId());
        String reason = req != null ? req.reason() : null;
        return ResponseEntity.ok(shipmentService.cancelShipment(id, reason, p.getAccountId()));
    }

    @PutMapping("/{id}/exception")
    public ResponseEntity<Shipment> raiseException(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody ExceptionRequest req) {
        verifyOwnership(id, p.getMerchantId());
        ShipmentExceptionTypeEnum type = ShipmentExceptionTypeEnum.valueOf(req.exceptionType());
        return ResponseEntity.ok(
            shipmentService.raiseException(id, type, req.note(), p.getAccountId()));
    }

    @PutMapping("/{id}/exception/resolve")
    public ResponseEntity<Shipment> resolveException(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody ResolveExceptionRequest req) {
        verifyOwnership(id, p.getMerchantId());
        ShipmentStatusEnum resume = ShipmentStatusEnum.fromCode(req.resumeStatus());
        return ResponseEntity.ok(shipmentService.resolveException(id, resume, p.getAccountId()));
    }

    @PutMapping("/{id}/dispatch")
    public ResponseEntity<Shipment> dispatch(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.dispatch(id, p.getAccountId()));
    }

    @PostMapping("/pick-list")
    public ResponseEntity<List<ShipmentService.PickLineItem>> pickList(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody PickListRequest req) {
        return ResponseEntity.ok(shipmentService.generatePickList(req.shipmentIds()));
    }

    @PostMapping("/sort-list")
    public ResponseEntity<List<ShipmentService.SortLineItem>> sortList(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody PickListRequest req) {
        return ResponseEntity.ok(shipmentService.generateSortList(req.shipmentIds()));
    }

    private void verifyOwnership(String shipmentId, String merchantId) {
        shipmentService.findById(shipmentId).ifPresentOrElse(s -> {
            if (!s.getMerchantId().equals(merchantId)) {
                throw new org.springframework.security.access.AccessDeniedException("Not your shipment");
            }
        }, () -> { throw new IllegalArgumentException("Shipment not found: " + shipmentId); });
    }
}
