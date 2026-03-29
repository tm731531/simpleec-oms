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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ChannelRepository channelRepository;

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

    // -----------------------------------------------------------------------
    // Pick list generation
    // -----------------------------------------------------------------------

    public record PickLineItem(
        String warehouseLocation,
        String sku,
        String name,
        int totalQuantity,
        List<String> orderIds   // which orders need this item
    ) {}

    public record SortLineItem(
        String orderId,
        String channelOrderId,
        List<PickItem> items
    ) {}

    public record PickItem(
        String channelItemId,
        String sku,
        String name,
        int quantity,
        boolean picked
    ) {}

    /**
     * 拿貨清單 — consolidated by warehouse_location × sku, sorted by location then sku.
     * Picker walks the warehouse once and picks all needed quantity for each SKU.
     */
    public List<PickLineItem> generatePickList(List<String> shipmentIds) {
        List<ShipmentItem> allItems = shipmentItemRepository.findByShipmentIdIn(shipmentIds);

        // location × sku → (totalQty, orderIds)
        Map<String, int[]> qtyMap   = new LinkedHashMap<>();
        Map<String, List<String>> orderMap = new LinkedHashMap<>();
        Map<String, String> skuName = new HashMap<>();

        for (ShipmentItem si : allItems) {
            try {
                JsonNode items = objectMapper.readTree(si.getItems());
                for (JsonNode item : items) {
                    String location = item.path("warehouse_location").isNull()
                        ? "UNKNOWN" : item.path("warehouse_location").asText("UNKNOWN");
                    String sku = item.path("sku").asText("");
                    String key = location + "\t" + sku;
                    int qty = item.path("quantity").asInt(1);

                    qtyMap.computeIfAbsent(key, k -> new int[]{0})[0] += qty;
                    orderMap.computeIfAbsent(key, k -> new ArrayList<>()).add(si.getOrderId());
                    skuName.putIfAbsent(key, item.path("name").asText(""));
                }
            } catch (Exception e) {
                log.warn("Failed to parse items for shipment_item {}", si.getId(), e);
            }
        }

        return qtyMap.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> {
                String[] parts = e.getKey().split("\t", 2);
                return new PickLineItem(parts[0], parts[1],
                    skuName.getOrDefault(e.getKey(), ""),
                    e.getValue()[0],
                    orderMap.getOrDefault(e.getKey(), List.of()));
            })
            .collect(Collectors.toList());
    }

    /**
     * 分貨清單 — per-order breakdown for the packing station.
     * Shows what to put in each order's box after picking.
     */
    public List<SortLineItem> generateSortList(List<String> shipmentIds) {
        List<ShipmentItem> allItems = shipmentItemRepository.findByShipmentIdIn(shipmentIds);

        return allItems.stream().map(si -> {
            List<PickItem> items = new ArrayList<>();
            try {
                JsonNode itemsNode = objectMapper.readTree(si.getItems());
                for (JsonNode item : itemsNode) {
                    items.add(new PickItem(
                        item.path("channel_item_id").asText(""),
                        item.path("sku").asText(""),
                        item.path("name").asText(""),
                        item.path("quantity").asInt(1),
                        item.path("picked").asBoolean(false)
                    ));
                }
            } catch (Exception e) {
                log.warn("Failed to parse items for sort list: {}", si.getId(), e);
            }
            return new SortLineItem(si.getOrderId(), si.getChannelOrderId(), items);
        }).collect(Collectors.toList());
    }

    /**
     * Dispatch a shipment (Phase 6).
     *
     * Phase 1 — atomic DB (via TransactionTemplate):
     *   1. status → DISPATCHED, record dispatched_at
     *   2. Update affected orders (SHIPPED or PARTIALLY_SHIPPED)
     *
     * Phase 2 — Kafka (outside transaction, after DB commits):
     *   3. Publish SHIP_ORDER v2 per affected order
     *   4. Write platform_notified_at on success; leave null for retry on failure
     *
     * NOTE: We use TransactionTemplate (not @Transactional on dispatchDb) because
     * Spring AOP @Transactional does NOT work on internal self-invocation — the proxy
     * is bypassed and no transaction would start.
     */
    public Shipment dispatch(String shipmentId, String operatorId) {
        // Phase 1: atomic DB — TransactionTemplate ensures the proxy boundary is respected
        DispatchResult result = transactionTemplate.execute(status -> dispatchDb(shipmentId, operatorId));

        // Phase 2: Kafka (outside transaction — DB has already committed)
        for (OrderDispatchInfo info : result.orderInfos()) {
            publishShipOrderEvent(result.shipment(), info);
        }

        return result.shipment();
    }

    // No @Transactional here — transaction is managed by TransactionTemplate in dispatch()
    private DispatchResult dispatchDb(String shipmentId, String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);

        if (shipment.getStatus() != ShipmentStatusEnum.AWAITING_PICKUP) {
            throw new IllegalStateException(
                "Can only dispatch from AWAITING_PICKUP, current: " + shipment.getStatus());
        }
        if (shipment.isHasException()) {
            throw new IllegalStateException("Resolve exception before dispatching: " + shipmentId);
        }

        shipment.setStatus(ShipmentStatusEnum.DISPATCHED);
        shipment.setDispatchedAt(LocalDateTime.now());
        shipmentRepository.save(shipment);
        logStatusChange(shipmentId, ShipmentStatusEnum.AWAITING_PICKUP,
            ShipmentStatusEnum.DISPATCHED, operatorId, null);

        // Determine affected orders and their shipment completeness.
        // Deduplicate by orderId: a split-then-merge can leave two ShipmentItem rows
        // for the same order on the same shipment. Without deduplication, SHIP_ORDER
        // would be published twice for that order.
        List<ShipmentItem> items = shipmentItemRepository.findByShipmentId(shipmentId);
        List<OrderDispatchInfo> orderInfos = new ArrayList<>();
        Set<String> processedOrderIds = new HashSet<>();

        for (ShipmentItem si : items) {
            if (!processedOrderIds.add(si.getOrderId())) continue; // skip duplicate
            Order order = orderRepository.findById(si.getOrderId()).orElse(null);
            if (order == null) continue;

            List<ShipmentItem> activeForOrder = shipmentItemRepository.findActiveByOrderId(si.getOrderId());
            boolean fullyShipped = activeForOrder.stream()
                .allMatch(a -> {
                    Shipment s = shipmentRepository.findById(a.getShipmentId()).orElse(null);
                    return s != null && s.getStatus() == ShipmentStatusEnum.DISPATCHED;
                });

            OrderStatusEnum newStatus = fullyShipped
                ? OrderStatusEnum.SHIPPED : OrderStatusEnum.PARTIALLY_SHIPPED;
            order.setOrderStatus(newStatus);
            if (fullyShipped) order.setShippedAt(LocalDateTime.now());
            orderRepository.save(order);

            orderInfos.add(new OrderDispatchInfo(
                order.getId(), order.getChannelOrderId(), order.getMerchantId(),
                order.getChannelId(), si.getItems()
            ));
        }

        return new DispatchResult(shipment, orderInfos);
    }

    private void publishShipOrderEvent(Shipment shipment, OrderDispatchInfo info) {
        try {
            // Resolve platform code via channel lookup.
            // channel.getPlatformId() IS the platform code ("cyberbiz", "shopee", etc.) — same pattern
            // as SchedulerEventHandler.dispatchFetchOrders(). No Platform table lookup needed.
            Channel channel = channelRepository.findById(info.channelId())
                .orElseThrow(() -> new IllegalStateException("Channel not found: " + info.channelId()));
            String platformCode = channel.getPlatformId().toLowerCase();
            String topic = com.simpleec.common.constants.TopicConstants.platformFastTopic(platformCode);

            ObjectNode message = objectMapper.createObjectNode();

            ObjectNode header = objectMapper.createObjectNode();
            header.put("messageId",  "msg_" + NanoIdUtil.generate());
            header.put("requestId",  "req_" + NanoIdUtil.generate());
            header.put("taskType",   "SHIP_ORDER");
            header.put("platformId", platformCode);
            header.put("channelId",  info.channelId());
            header.put("merchantId", info.merchantId());
            header.put("timestamp",  java.time.Instant.now().toString());
            header.put("source",     "shipment_service");
            header.put("version",    2);
            header.put("isRollback", false);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("orderId",         info.orderId());
            body.put("channelOrderId",  info.channelOrderId());
            body.put("trackingNumber",  shipment.getTrackingNumber());
            body.put("carrier",         shipment.getCarrier());
            body.set("lineItems",       objectMapper.readTree(info.itemsJson()));

            message.set("header", header);
            message.set("body", body);

            kafkaTemplate.send(topic, info.channelId(), message)
                .whenComplete((r, ex) -> {
                    if (ex == null) {
                        // Update platform_notified_at — must use TransactionTemplate since this runs
                        // on the Kafka producer thread with no Spring transaction context
                        transactionTemplate.executeWithoutResult(s ->
                            shipmentRepository.findById(shipment.getId()).ifPresent(found -> {
                                found.setPlatformNotifiedAt(LocalDateTime.now());
                                shipmentRepository.save(found);
                            })
                        );
                    } else {
                        log.error("SHIP_ORDER publish failed for order={} shipment={}",
                            info.orderId(), shipment.getId(), ex);
                        // platform_notified_at stays null — reconciliation job will retry
                    }
                });
        } catch (Exception e) {
            log.error("Failed to build/send SHIP_ORDER event for order={}", info.orderId(), e);
        }
    }

    // DTOs for internal dispatch coordination
    private record DispatchResult(Shipment shipment, List<OrderDispatchInfo> orderInfos) {}
    private record OrderDispatchInfo(String orderId, String channelOrderId,
                                      String merchantId, String channelId, String itemsJson) {}

    // -----------------------------------------------------------------------
    // Batch operations
    // -----------------------------------------------------------------------

    @Transactional
    public ShipmentBatch createBatch(String merchantId, String carrier,
                                      LocalDateTime scheduledPickupAt, String notes) {
        String batchId = NanoIdUtil.generate();
        ShipmentBatch batch = ShipmentBatch.builder()
            .id(batchId)
            .merchantId(merchantId)
            .batchNo("BATCH-" + LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "-" + batchId.substring(0, 6))
            .carrier(carrier)
            .scheduledPickupAt(scheduledPickupAt)
            .notes(notes)
            .status("PREPARING")
            .build();
        return shipmentBatchRepository.save(batch);
    }

    @Transactional
    public ShipmentBatch forceReadyBatch(String batchId, String note) {
        ShipmentBatch batch = shipmentBatchRepository.findById(batchId)
            .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));
        batch.setStatus("READY");
        batch.setForceReadyNote(note);
        return shipmentBatchRepository.save(batch);
    }

    @Transactional
    public ShipmentBatch confirmPickup(String batchId, String carrierDriverId,
                                        int boxCount, java.math.BigDecimal cost) {
        ShipmentBatch batch = shipmentBatchRepository.findById(batchId)
            .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));
        batch.setStatus("PICKED_UP");
        batch.setActualPickupAt(LocalDateTime.now());
        batch.setCarrierDriverId(carrierDriverId);
        batch.setHandoffBoxCount(boxCount);
        if (cost != null) batch.setLogisticsCost(cost);
        return shipmentBatchRepository.save(batch);
    }

    public Page<ShipmentBatch> findBatchesByMerchant(String merchantId, Pageable pageable) {
        return shipmentBatchRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
    }

    public Optional<ShipmentBatch> findBatchById(String id) {
        return shipmentBatchRepository.findById(id);
    }

    public List<Shipment> findShipmentsByBatch(String batchId) {
        return shipmentRepository.findByBatchId(batchId);
    }

    /** Generate manifest (裝車清單) for a batch. */
    public Map<String, Object> generateManifest(String batchId) {
        ShipmentBatch batch = shipmentBatchRepository.findById(batchId)
            .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));
        List<Shipment> shipments = shipmentRepository.findByBatchId(batchId);
        long activeCount = shipments.stream()
            .filter(s -> s.getStatus() != ShipmentStatusEnum.CANCELLED).count();
        List<Map<String, String>> boxes = shipments.stream()
            .filter(s -> s.getStatus() != ShipmentStatusEnum.CANCELLED)
            .map(s -> Map.of(
                "shipmentNo", s.getShipmentNo() != null ? s.getShipmentNo() : s.getId(),
                "trackingNumber", s.getTrackingNumber() != null ? s.getTrackingNumber() : "",
                "status", s.getStatus().getLabel()
            ))
            .collect(java.util.stream.Collectors.toList());

        // Use HashMap (not Map.of) because Map.of throws NPE on null values
        Map<String, Object> manifest = new java.util.HashMap<>();
        manifest.put("batchNo",    batch.getBatchNo());
        manifest.put("carrier",    batch.getCarrier() != null ? batch.getCarrier() : "");
        manifest.put("boxCount",   activeCount);
        manifest.put("scheduledPickupAt", batch.getScheduledPickupAt() != null
            ? batch.getScheduledPickupAt().toString() : null);
        manifest.put("generatedAt", LocalDateTime.now().toString());
        manifest.put("boxes",       boxes);
        return manifest;
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
