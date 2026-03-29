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
        try {
            List<Shipment> shipments = shipmentService.createShipments(
                req.orderIds(), p.getMerchantId());
            return ResponseEntity.status(HttpStatus.CREATED).body(shipments);
        } catch (IllegalArgumentException e) {
            log.warn("createShipments: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("createShipments business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
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
        try {
            verifyOwnership(id, p.getMerchantId());
            return ResponseEntity.ok(shipmentService.advanceStatus(id, p.getAccountId()));
        } catch (IllegalArgumentException e) {
            log.warn("advanceStatus not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("advanceStatus business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @PutMapping("/{id}/tracking")
    public ResponseEntity<Shipment> setTracking(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody SetTrackingRequest req) {
        try {
            verifyOwnership(id, p.getMerchantId());
            return ResponseEntity.ok(
                shipmentService.setTracking(id, req.trackingNumber(), req.carrier(), p.getAccountId()));
        } catch (IllegalArgumentException e) {
            log.warn("setTracking not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("setTracking business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @PostMapping("/{id}/split")
    public ResponseEntity<List<Shipment>> split(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody SplitRequest req) {
        try {
            verifyOwnership(id, p.getMerchantId());
            return ResponseEntity.ok(shipmentService.split(id, req.channelItemIds(), p.getAccountId()));
        } catch (IllegalArgumentException e) {
            log.warn("split not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("split business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @PostMapping("/{id}/merge")
    public ResponseEntity<Shipment> mergeInto(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody MergeRequest req) {
        try {
            verifyOwnership(id, p.getMerchantId());
            verifyOwnership(req.shipmentIdB(), p.getMerchantId());
            return ResponseEntity.ok(shipmentService.merge(id, req.shipmentIdB(), p.getAccountId()));
        } catch (IllegalArgumentException e) {
            log.warn("merge not found or cross-merchant: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("merge business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Shipment> cancel(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody(required = false) CancelRequest req) {
        try {
            verifyOwnership(id, p.getMerchantId());
            String reason = req != null ? req.reason() : null;
            return ResponseEntity.ok(shipmentService.cancelShipment(id, reason, p.getAccountId()));
        } catch (IllegalArgumentException e) {
            log.warn("cancel not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("cancel business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @PutMapping("/{id}/exception")
    public ResponseEntity<Shipment> raiseException(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody ExceptionRequest req) {
        try {
            verifyOwnership(id, p.getMerchantId());
            ShipmentExceptionTypeEnum type = ShipmentExceptionTypeEnum.valueOf(req.exceptionType());
            return ResponseEntity.ok(
                shipmentService.raiseException(id, type, req.note(), p.getAccountId()));
        } catch (IllegalArgumentException e) {
            log.warn("raiseException bad request or not found: {}", e.getMessage());
            // valueOf() throws IAE for unknown enum names; verifyOwnership throws IAE for not-found
            // Distinguish: if it mentions "Shipment not found" return 404, else 400
            if (e.getMessage() != null && e.getMessage().startsWith("Shipment not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            log.warn("raiseException business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @PutMapping("/{id}/exception/resolve")
    public ResponseEntity<Shipment> resolveException(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody ResolveExceptionRequest req) {
        try {
            verifyOwnership(id, p.getMerchantId());
            ShipmentStatusEnum resume = ShipmentStatusEnum.fromCode(req.resumeStatus());
            return ResponseEntity.ok(shipmentService.resolveException(id, resume, p.getAccountId()));
        } catch (IllegalArgumentException e) {
            log.warn("resolveException bad request or not found: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("Shipment not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            log.warn("resolveException business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @PutMapping("/{id}/dispatch")
    public ResponseEntity<Shipment> dispatch(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        try {
            verifyOwnership(id, p.getMerchantId());
            return ResponseEntity.ok(shipmentService.dispatch(id, p.getAccountId()));
        } catch (IllegalArgumentException e) {
            log.warn("dispatch not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("dispatch business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @PostMapping("/pick-list")
    public ResponseEntity<List<ShipmentService.PickLineItem>> pickList(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody PickListRequest req) {
        try {
            for (String sid : req.shipmentIds()) {
                verifyOwnership(sid, p.getMerchantId());
            }
            return ResponseEntity.ok(shipmentService.generatePickList(req.shipmentIds()));
        } catch (IllegalArgumentException e) {
            log.warn("pickList not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("pickList business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @PostMapping("/sort-list")
    public ResponseEntity<List<ShipmentService.SortLineItem>> sortList(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody PickListRequest req) {
        try {
            for (String sid : req.shipmentIds()) {
                verifyOwnership(sid, p.getMerchantId());
            }
            return ResponseEntity.ok(shipmentService.generateSortList(req.shipmentIds()));
        } catch (IllegalArgumentException e) {
            log.warn("sortList not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("sortList business rule: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    private void verifyOwnership(String shipmentId, String merchantId) {
        shipmentService.findById(shipmentId).ifPresentOrElse(s -> {
            if (!s.getMerchantId().equals(merchantId)) {
                throw new org.springframework.security.access.AccessDeniedException("Not your shipment");
            }
        }, () -> { throw new IllegalArgumentException("Shipment not found: " + shipmentId); });
    }
}
