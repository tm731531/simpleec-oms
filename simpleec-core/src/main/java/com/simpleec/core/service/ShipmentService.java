package com.simpleec.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.enums.OrderStatusEnum;
import com.simpleec.common.enums.ShipmentExceptionTypeEnum;
import com.simpleec.common.enums.ShipmentStatusEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.core.entity.*;
import com.simpleec.core.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentItemRepository shipmentItemRepository;
    private final ShipmentBatchRepository shipmentBatchRepository;
    private final ShipmentStatusLogRepository shipmentStatusLogRepository;
    private final OrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // Status advance order — ends at AWAITING_PICKUP.
    // DISPATCHED is intentionally excluded: the only path to DISPATCHED is
    // dispatch(), which performs a two-phase commit (DB + Kafka SHIP_ORDER).
    // advanceStatus() at AWAITING_PICKUP will throw "No next status" to force
    // the operator to call the explicit dispatch endpoint.
    private static final List<ShipmentStatusEnum> STATUS_SEQUENCE = List.of(
        ShipmentStatusEnum.PICKING_LIST,
        ShipmentStatusEnum.PICKING,
        ShipmentStatusEnum.PACKING,
        ShipmentStatusEnum.LABELING,
        ShipmentStatusEnum.AWAITING_PICKUP
    );

    /**
     * Phase 1: Create one shipment per order from the selected order IDs.
     * channelId is derived from the order — not passed as a parameter.
     * Populates shipment_items from orders.items JSONB.
     * Returns the created Shipment list.
     */
    @Transactional
    public List<Shipment> createShipments(List<String> orderIds, String merchantId) {
        List<Shipment> result = new ArrayList<>();
        String datePrefix = "SHP-" + LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));

        for (String orderId : orderIds) {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            if (!order.getMerchantId().equals(merchantId)) {
                throw new IllegalArgumentException("Order " + orderId + " does not belong to merchant");
            }

            String shipmentId = NanoIdUtil.generate();
            // Use NanoID suffix to avoid collision when concurrent createShipments calls happen on the same day
            String shipmentNo = datePrefix + "-" + shipmentId.substring(0, 6).toUpperCase();

            Shipment shipment = Shipment.builder()
                .id(shipmentId)
                .merchantId(merchantId)
                .channelId(order.getChannelId())   // derive from order, not request
                .shipmentNo(shipmentNo)
                .status(ShipmentStatusEnum.PICKING_LIST)
                .build();

            shipmentRepository.save(shipment);
            logStatusChange(shipmentId, null, ShipmentStatusEnum.PICKING_LIST, "system", "Created");

            // Populate shipment_items from order.items JSONB
            ShipmentItem item = ShipmentItem.builder()
                .id(NanoIdUtil.generate())
                .shipmentId(shipmentId)
                .merchantId(merchantId)
                .orderId(orderId)
                .channelOrderId(order.getChannelOrderId())
                .items(buildInitialItemsJson(order.getItems()))
                .build();

            shipmentItemRepository.save(item);
            result.add(shipment);
        }
        return result;
    }

    /**
     * Advance shipment to next status in sequence.
     * Cannot advance DISPATCHED, CANCELLED, or ON_HOLD (resolve exception first).
     */
    @Transactional
    public Shipment advanceStatus(String shipmentId, String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);

        if (shipment.isHasException()) {
            throw new IllegalStateException("Resolve exception before advancing status: " + shipmentId);
        }
        if (shipment.getStatus().isTerminal()) {
            throw new IllegalStateException("Cannot advance terminal status: " + shipment.getStatus());
        }
        if (shipment.getStatus() == ShipmentStatusEnum.ON_HOLD) {
            throw new IllegalStateException("Cannot advance ON_HOLD shipment. Resolve exception first.");
        }

        int currentIdx = STATUS_SEQUENCE.indexOf(shipment.getStatus());
        if (currentIdx < 0 || currentIdx >= STATUS_SEQUENCE.size() - 1) {
            throw new IllegalStateException("No next status for: " + shipment.getStatus());
        }

        ShipmentStatusEnum next = STATUS_SEQUENCE.get(currentIdx + 1);
        ShipmentStatusEnum prev = shipment.getStatus();

        // LABELING reached → check if batch should auto-transition to READY
        shipment.setStatus(next);
        shipmentRepository.save(shipment);
        logStatusChange(shipmentId, prev, next, operatorId, null);

        if (next == ShipmentStatusEnum.LABELING && shipment.getBatchId() != null) {
            checkAndAutoReadyBatch(shipment.getBatchId());
        }

        return shipment;
    }

    /**
     * Cancel a shipment. Releases items back to READY_TO_SHIP for their orders
     * if no other active shipments remain for that order.
     */
    @Transactional
    public Shipment cancelShipment(String shipmentId, String reason, String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);
        if (shipment.getStatus() == ShipmentStatusEnum.DISPATCHED) {
            throw new IllegalStateException("Cannot cancel a dispatched shipment");
        }

        ShipmentStatusEnum prev = shipment.getStatus();
        shipment.setStatus(ShipmentStatusEnum.CANCELLED);
        shipment.setCancelledAt(LocalDateTime.now());
        shipment.setCancelReason(reason);
        shipmentRepository.save(shipment);
        logStatusChange(shipmentId, prev, ShipmentStatusEnum.CANCELLED, operatorId, reason);

        // Check if all items for affected orders are now un-allocated
        List<ShipmentItem> items = shipmentItemRepository.findByShipmentId(shipmentId);
        for (ShipmentItem si : items) {
            List<ShipmentItem> remaining = shipmentItemRepository.findActiveByOrderId(si.getOrderId());
            if (remaining.isEmpty()) {
                // No active shipments — revert order to READY_TO_SHIP
                orderRepository.findById(si.getOrderId()).ifPresent(order -> {
                    order.setOrderStatus(OrderStatusEnum.READY_TO_SHIP);
                    orderRepository.save(order);
                });
            }
        }
        return shipment;
    }

    /**
     * Raise an exception on a shipment (缺貨, 破損品, etc.). Sets ON_HOLD.
     */
    @Transactional
    public Shipment raiseException(String shipmentId, ShipmentExceptionTypeEnum type,
                                   String note, String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);
        ShipmentStatusEnum prev = shipment.getStatus();

        shipment.setHasException(true);
        shipment.setExceptionType(type);
        shipment.setExceptionNote(note);
        shipment.setStatus(ShipmentStatusEnum.ON_HOLD);
        shipmentRepository.save(shipment);
        logStatusChange(shipmentId, prev, ShipmentStatusEnum.ON_HOLD, operatorId,
            type.getLabel() + ": " + note);
        return shipment;
    }

    /**
     * Resolve an exception and resume the shipment (returns to previous status).
     */
    @Transactional
    public Shipment resolveException(String shipmentId, ShipmentStatusEnum resumeStatus,
                                     String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);
        if (!shipment.isHasException()) {
            throw new IllegalStateException("No exception to resolve on: " + shipmentId);
        }

        shipment.setHasException(false);
        shipment.setExceptionType(null);
        shipment.setExceptionNote(null);
        shipment.setStatus(resumeStatus);
        shipmentRepository.save(shipment);
        logStatusChange(shipmentId, ShipmentStatusEnum.ON_HOLD, resumeStatus, operatorId, "Exception resolved");
        return shipment;
    }

    // -----------------------------------------------------------------------
    // Read operations
    // -----------------------------------------------------------------------

    public Optional<Shipment> findById(String id) {
        return shipmentRepository.findById(id);
    }

    public Page<Shipment> findByMerchant(String merchantId, Pageable pageable) {
        return shipmentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
    }

    public List<ShipmentItem> findItemsByShipmentId(String shipmentId) {
        return shipmentItemRepository.findByShipmentId(shipmentId);
    }

    public List<ShipmentStatusLog> findStatusLogs(String shipmentId) {
        return shipmentStatusLogRepository.findByShipmentIdOrderByCreatedAtDesc(shipmentId);
    }

    /**
     * Update tracking number and carrier on a LABELING-or-later shipment.
     */
    @Transactional
    public Shipment setTracking(String shipmentId, String trackingNumber,
                                String carrier, String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);
        String oldTracking = shipment.getTrackingNumber();
        shipment.setTrackingNumber(trackingNumber);
        shipment.setCarrier(carrier);
        shipmentRepository.save(shipment);
        if (oldTracking != null && !oldTracking.equals(trackingNumber)) {
            logStatusChange(shipmentId, shipment.getStatus(), shipment.getStatus(),
                operatorId, "Tracking updated: " + oldTracking + " → " + trackingNumber);
        }
        return shipment;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Shipment getAndVerify(String shipmentId) {
        return shipmentRepository.findById(shipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Shipment not found: " + shipmentId));
    }

    void logStatusChange(String shipmentId, ShipmentStatusEnum from,
                                  ShipmentStatusEnum to, String operatorId, String remark) {
        ShipmentStatusLog log = ShipmentStatusLog.builder()
            .id(NanoIdUtil.generate())
            .shipmentId(shipmentId)
            .fromStatus(from != null ? from.name() : null)
            .toStatus(to.name())
            .operatorId(operatorId)
            .remark(remark)
            .build();
        shipmentStatusLogRepository.save(log);
    }

    private void checkAndAutoReadyBatch(String batchId) {
        long notLabeled = shipmentRepository.countBatchShipmentsNotLabeled(batchId);
        if (notLabeled == 0) {
            shipmentBatchRepository.findById(batchId).ifPresent(batch -> {
                if ("PREPARING".equals(batch.getStatus())) {
                    batch.setStatus("READY");
                    shipmentBatchRepository.save(batch);
                    log.info("Batch {} auto-transitioned to READY", batchId);
                }
            });
        }
    }

    /**
     * Build initial items JSON from orders.items JSONB.
     * orders.items format: [{channel_item_id, sku, name, quantity, ...}]
     * We copy relevant fields and add picked=false, warehouse_location=null, barcode=null.
     */
    private String buildInitialItemsJson(String orderItemsJson) {
        try {
            JsonNode orderItems = objectMapper.readTree(orderItemsJson);
            ArrayNode result = objectMapper.createArrayNode();
            for (JsonNode item : orderItems) {
                ObjectNode si = objectMapper.createObjectNode();
                si.put("channel_item_id", item.path("channelItemId").asText(
                    item.path("channel_item_id").asText("")));
                si.put("sku",      item.path("sku").asText(""));
                // orders.items stores the display name as "productName" (not "name")
                si.put("name",     item.path("productName").asText(item.path("name").asText("")));
                si.put("quantity", item.path("quantity").asInt(1));
                si.put("picked",   false);
                si.putNull("warehouse_location");
                si.putNull("barcode");
                result.add(si);
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("Failed to parse order items JSON, defaulting to []", e);
            return "[]";
        }
    }
}
