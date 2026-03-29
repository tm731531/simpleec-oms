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

    /**
     * Split: move specified channel_item_ids from shipmentId into a new shipment.
     * Uses Redis distributed lock to prevent concurrent modification.
     * Returns [originalShipment, newShipment].
     */
    @Transactional
    public List<Shipment> split(String shipmentId, List<String> channelItemIdsToMove,
                                 String operatorId) {
        String lockKey = "shipment:" + shipmentId + ":lock";
        // Use unique value so we only delete our own lock (avoids deleting another thread's lock after TTL expiry)
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, 30, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            throw new IllegalStateException("Shipment is being modified by another operator: " + shipmentId);
        }
        try {
            Shipment original = getAndVerify(shipmentId);
            if (original.getStatus().isTerminal() || original.getStatus() == ShipmentStatusEnum.ON_HOLD) {
                throw new IllegalStateException("Cannot split shipment in status: " + original.getStatus());
            }

            List<ShipmentItem> allItems = shipmentItemRepository.findByShipmentId(shipmentId);

            // Create new shipment
            String newShipmentId = NanoIdUtil.generate();
            Shipment newShipment = Shipment.builder()
                .id(newShipmentId)
                .merchantId(original.getMerchantId())
                .channelId(original.getChannelId())
                .batchId(original.getBatchId())
                .shipmentNo(original.getShipmentNo() + "-B")
                .status(original.getStatus())
                .build();
            shipmentRepository.save(newShipment);
            logStatusChange(newShipmentId, null, newShipment.getStatus(), operatorId, "Split from " + shipmentId);

            Set<String> toMove = new HashSet<>(channelItemIdsToMove);

            for (ShipmentItem si : allItems) {
                try {
                    JsonNode items = objectMapper.readTree(si.getItems());
                    ArrayNode remaining = objectMapper.createArrayNode();
                    ArrayNode moved = objectMapper.createArrayNode();

                    for (JsonNode item : items) {
                        String cid = item.path("channel_item_id").asText();
                        if (toMove.contains(cid)) {
                            moved.add(item);
                        } else {
                            remaining.add(item);
                        }
                    }

                    if (!moved.isEmpty()) {
                        // Create new item row on new shipment
                        ShipmentItem newItem = ShipmentItem.builder()
                            .id(NanoIdUtil.generate())
                            .shipmentId(newShipmentId)
                            .merchantId(si.getMerchantId())
                            .orderId(si.getOrderId())
                            .channelOrderId(si.getChannelOrderId())
                            .items(objectMapper.writeValueAsString(moved))
                            .build();
                        shipmentItemRepository.save(newItem);
                    }
                    if (!remaining.isEmpty()) {
                        si.setItems(objectMapper.writeValueAsString(remaining));
                        shipmentItemRepository.save(si);
                    } else {
                        // All items moved — remove the original row
                        shipmentItemRepository.delete(si);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Error processing shipment items during split", e);
                }
            }
            return List.of(original, newShipment);
        } finally {
            // Only delete the lock if we still own it (TTL may have expired and another thread may have re-acquired)
            if (lockValue.equals(redisTemplate.opsForValue().get(lockKey))) {
                redisTemplate.delete(lockKey);
            }
        }
    }

    /**
     * Merge shipmentIdB into shipmentIdA.
     * Both must belong to the same merchant and channel.
     * shipmentIdB is CANCELLED after merge.
     */
    @Transactional
    public Shipment merge(String shipmentIdA, String shipmentIdB, String operatorId) {
        String lockKeyA = "shipment:" + shipmentIdA + ":lock";
        String lockKeyB = "shipment:" + shipmentIdB + ":lock";
        // Use unique value so we only release locks we actually acquired
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean lockedA = redisTemplate.opsForValue().setIfAbsent(lockKeyA, lockValue, 30, TimeUnit.SECONDS);
        Boolean lockedB = redisTemplate.opsForValue().setIfAbsent(lockKeyB, lockValue, 30, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(lockedA) || !Boolean.TRUE.equals(lockedB)) {
            // Only release locks we actually acquired — don't delete another thread's lock
            if (Boolean.TRUE.equals(lockedA)) redisTemplate.delete(lockKeyA);
            if (Boolean.TRUE.equals(lockedB)) redisTemplate.delete(lockKeyB);
            throw new IllegalStateException("One or both shipments are being modified concurrently");
        }
        try {
            Shipment a = getAndVerify(shipmentIdA);
            Shipment b = getAndVerify(shipmentIdB);

            if (!a.getMerchantId().equals(b.getMerchantId())) {
                throw new IllegalArgumentException("Cannot merge shipments from different merchants");
            }
            if (!a.getChannelId().equals(b.getChannelId())) {
                throw new IllegalArgumentException("Cannot merge shipments from different channels");
            }
            if (a.getStatus().isTerminal() || b.getStatus().isTerminal()) {
                throw new IllegalStateException("Cannot merge terminal shipments");
            }

            // Move all shipment_items from B → A
            List<ShipmentItem> bItems = shipmentItemRepository.findByShipmentId(shipmentIdB);
            for (ShipmentItem si : bItems) {
                si.setShipmentId(shipmentIdA);
                shipmentItemRepository.save(si);
            }

            // Cancel B — capture previous status BEFORE mutation (fixes audit log from_status)
            ShipmentStatusEnum prevB = b.getStatus();
            b.setStatus(ShipmentStatusEnum.CANCELLED);
            b.setCancelledAt(LocalDateTime.now());
            b.setCancelReason("Merged into " + shipmentIdA);
            shipmentRepository.save(b);
            logStatusChange(shipmentIdB, prevB, ShipmentStatusEnum.CANCELLED, operatorId,
                "Merged into " + shipmentIdA);

            return a;
        } finally {
            // Only release locks we still own (TTL-safe)
            if (lockValue.equals(redisTemplate.opsForValue().get(lockKeyA))) redisTemplate.delete(lockKeyA);
            if (lockValue.equals(redisTemplate.opsForValue().get(lockKeyB))) redisTemplate.delete(lockKeyB);
        }
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
