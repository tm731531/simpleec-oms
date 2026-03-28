# Coding Conventions & Patterns

This document describes the patterns used throughout SimpleEC OMS. Following these conventions ensures consistency and correctness — many of them exist to prevent subtle bugs with PII encryption, Kafka deduplication, or database constraints.

---

## Primary Key Generation

All tables use `VARCHAR(20)` NanoID primary keys. PKs are always generated at the application layer — never by the database.

```java
// In entity classes
@Id
@Column(name = "id", length = 20, nullable = false)
private String id;

// When creating a new entity — use IdGenerator from simpleec-common
entity.setId(IdGenerator.nextId());

// For orders: use the composite NanoID (embeds merchant prefix + timestamp)
order.setId(NanoIdUtil.generateComposite(merchantId));
```

All foreign key columns (merchantId, channelId, orderId, etc.) are also `String` — never `Long` or `UUID`.

**Never** use `@GeneratedValue` or `@TableId(type = IdType.AUTO)`. Database auto-increment is not used anywhere in this codebase.

---

## Timestamp Fields

Timestamps managed by Hibernate use `@CreationTimestamp` and `@UpdateTimestamp`. Never set these manually.

```java
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private OffsetDateTime createdAt;

@UpdateTimestamp
@Column(name = "updated_at", nullable = false)
private OffsetDateTime updatedAt;
```

For timestamps received from platform APIs (e.g., when an order was created on Shopee), use the `channelCreatedAt` field and parse from ISO-8601:

```java
// Correct: parse ISO-8601 string from platform API
LocalDateTime channelCreatedAt = Instant.parse(apiResponse.get("created_time").asText())
    .atZone(ZoneId.of("UTC"))
    .toLocalDateTime();
order.setChannelCreatedAt(channelCreatedAt);

// Wrong: never use Instant.now() when the platform timestamp is available
order.setChannelCreatedAt(LocalDateTime.now()); // DO NOT DO THIS
```

---

## Kafka Consumer Pattern

All Kafka consumers follow the same structure. Copy this pattern exactly — the ordering of schema validation, MDC setup, PII context, and error routing is intentional.

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ExampleConsumer {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "order.process", groupId = "order-job-group", concurrency = "3")
    public void consume(@Payload String messageJson) {

        // Step 1: Parse JSON — if unparseable, log and drop (can't route to DLT without parsing)
        JsonNode json;
        try {
            json = objectMapper.readTree(messageJson);
        } catch (Exception e) {
            log.error("Failed to parse message as JSON: {}", e.getMessage());
            return;
        }

        // Step 2: Validate schema version — unsupported versions go directly to DLT
        try {
            SchemaVersionHandler.validate(json);
        } catch (UnsupportedSchemaVersionException e) {
            log.error("Unsupported schema version: {}", e.getMessage());
            kafkaTemplate.send(TopicConstants.TASK_DLT, "Consumer", json.toString());
            return;
        }

        // Step 3: Set MDC for structured logging and tracing
        TaskMdcHelper.set(json);
        try {
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            // Step 4: Guard required fields — malformed messages go to DLT
            if (header == null || body == null) {
                log.error("Malformed message: missing header or body");
                kafkaTemplate.send(TopicConstants.TASK_DLT, "Consumer", json.toString());
                return;
            }

            String merchantId = header.path("merchantId").asText();
            String channelId = header.path("channelId").asText();
            boolean isRollback = header.path("isRollback").asBoolean(false);

            // Step 5: Set PII encryption context (for any DB read/write of encrypted fields)
            EncryptionContext.setMerchantId(merchantId);
            try {
                // Step 6: Business logic
                doProcessing(merchantId, channelId, body, isRollback);
            } finally {
                EncryptionContext.clear();  // Always clear — even on exception
            }

        } catch (Exception e) {
            log.error("Error processing message: {}", e.getMessage(), e);

            // Step 7: Route retryable errors to task.failed
            // The retry-job will re-publish after a backoff period
            ObjectNode wrapped = buildFailedMessage(json, e);
            kafkaTemplate.send(TopicConstants.TASK_FAILED, "Consumer", wrapped.toString());

        } finally {
            // Step 8: Always clear MDC — even on exception
            TaskMdcHelper.clear();
        }
    }
}
```

### Concurrency

- `concurrency = "3"` is the default for all consumers
- Increase to `"8"` temporarily if lag is building, then revert to `"3"` for normal operations
- The concurrency value is configured per-service via `JOB_CHANNEL_CONCURRENCY` in `docker-compose.yml`

---

## PII Encryption Context

Four fields are encrypted at rest using AES-256-GCM via `EncryptedFieldTypeHandler`:
- `buyer_name`
- `buyer_phone`
- `buyer_email`
- `shipping_address`

The encryption key is derived from the `merchantId`. **Any code that reads or writes these fields must wrap the operation in an encryption context:**

```java
EncryptionContext.setMerchantId(merchantId);
try {
    // Safe to read encrypted fields here
    Order order = orderRepository.findById(orderId).orElseThrow();
    String buyerName = order.getBuyerName();  // decrypted transparently

    // Safe to write encrypted fields here
    order.setBuyerPhone("+886912345678");     // encrypted transparently
    orderRepository.save(order);
} finally {
    EncryptionContext.clear();  // MUST be in finally block
}
```

Failing to set the context results in encrypted ciphertext being returned as-is, which will look like garbled data in the application layer.

---

## OrderStatusEnum and ReturnStatusEnum Usage

All order statuses use the `OrderStatusEnum` enum. The `fromCode()` method is case-insensitive — platform APIs can return status strings in any case.

```java
// Correct: map platform-specific status to OMS status
OrderStatusEnum status = OrderStatusMapper.mapCyberbizStatus(rawStatus);
order.setOrderStatus(status);

// Correct: parse from a string (case-insensitive)
OrderStatusEnum status = OrderStatusEnum.fromCode("pending");   // → PENDING
OrderStatusEnum status = OrderStatusEnum.fromCode("CONFIRMED"); // → CONFIRMED

// OMS unified statuses:
// PENDING, CONFIRMED, READY_TO_SHIP, SHIPPING, SHIPPED, COMPLETED, CANCELLED

// Return statuses (ReturnStatusEnum):
// PENDING, APPROVED, REJECTED, COMPLETED, REFUNDED
```

This system is a **passive sync party** — it accepts any status transition without validation. Do not add guards like "cannot go from COMPLETED to PENDING". Platform orders can appear in any state at any time.

---

## Repository Query Conventions

Use Spring Data JPA method naming for simple queries:

```java
// Preferred for simple lookups
List<Order> findByMerchantIdAndOrderStatus(String merchantId, OrderStatusEnum status);
Optional<Order> findByChannelIdAndChannelOrderId(String channelId, String channelOrderId);
Page<Order> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

// For complex queries with date ranges — always use range predicates, NOT DATE() functions
// Wrong (prevents index use):
@Query("SELECT o FROM Order o WHERE DATE(o.createdAt) = :date")

// Correct (index-friendly range query):
@Query("SELECT o FROM Order o WHERE o.channelCreatedAt >= :startOfDay AND o.channelCreatedAt < :endOfDay")
List<Order> findByMerchantIdAndDateRange(
    @Param("merchantId") String merchantId,
    @Param("startOfDay") LocalDateTime startOfDay,
    @Param("endOfDay") LocalDateTime endOfDay
);
```

---

## Error Handling and Routing

### Error classification

| Error type | Routing | Retried? |
|-----------|---------|---------|
| Transient errors (DB timeout, network blip) | `task.failed` | Yes — retry-job re-publishes after backoff |
| Platform API 5xx | `task.failed` | Yes |
| Platform API 4xx (bad request) | `task.dlt` | No — fix the message, not a transient error |
| Schema version unsupported | `task.dlt` | No — version mismatch needs code fix |
| Malformed JSON | Drop (log error) | No — cannot route without parsing |

### Building a failed message envelope

```java
// Wrap the original message with error metadata for the retry-job
ObjectNode wrapped = originalJson.deepCopy();
ObjectNode errorInfo = objectMapper.createObjectNode();
errorInfo.put("errorType", "SERVER_ERROR_5XX");
errorInfo.put("errorMessage", exception.getMessage());
errorInfo.put("retryCount", 0);
((ObjectNode) wrapped.get("body")).set("errorInfo", errorInfo);
kafkaTemplate.send(TopicConstants.TASK_FAILED, "MyConsumer", wrapped.toString());
```

---

## Logging Conventions

All Java services use SLF4J with Lombok `@Slf4j`. Logs are structured JSON in Docker and human-readable pattern on local.

```java
@Slf4j  // Lombok — generates `private static final Logger log = ...`
public class MyConsumer {

    void process(String orderId, String merchantId) {
        // INFO: business milestones (one per meaningful step)
        log.info("Processing ORDER_UPSERT: {} from merchant {}", orderId, merchantId);

        // DEBUG: verbose data (only emitted when log level is DEBUG)
        log.debug("Full order payload: {}", jsonPayload);

        // WARN: degraded but still functioning (Redis unavailable, optional field missing)
        log.warn("Redis dedup check failed for order {}, proceeding with DB check", orderId);

        // ERROR: always include the exception object as the final argument for stack trace
        try {
            riskyOperation();
        } catch (Exception e) {
            log.error("Failed to process order {}: {}", orderId, e.getMessage(), e);
        }
    }
}
```

**Never log:** JWT tokens, plaintext passwords, full PII fields (buyer_phone, buyer_email). Log the NanoID or channelOrderId instead to identify orders without exposing personal data.

---

## isRollback Flag

All messages that represent orders fetched from a time window in the past (backfill operations) must have `isRollback=true` in the header. Consumers use this flag to:

1. Attribute the order's statistics to the original `channelCreatedAt` date, not today
2. Mark the stats dirty set for the original date so `STATS_RECALC` picks it up

```java
// In Channel Job — when building an ORDER_UPSERT message
boolean isRollback = isBackfillWindow(fetchWindowStart);  // your logic
header.put("isRollback", isRollback);

// In OrderUpsertConsumer — stats dirty marker respects isRollback
LocalDate statDate = isRollback && order.getChannelCreatedAt() != null
    ? order.getChannelCreatedAt().toLocalDate()
    : LocalDate.now();
```

---

## DailyStatisticsService

Statistics are computed from two perspectives and stored in the `daily_statistics` table:

| Perspective | Fields | Description |
|------------|--------|-------------|
| Business view | `new_order_count`, `new_order_amount` | Orders created on this date |
| Finance view | `received_count`, `received_amount`, `refund_count`, `refund_amount`, `net_amount` | Cash flow: received minus refunds |
| Logistics view | `shipped_count`, `completed_count`, `cancelled_count` | Fulfillment KPIs |
| Product view | `item_sold_count` | SKU units sold |

The service is invoked via the `STATS_RECALC` task type, dispatched by the scheduler every 5 minutes. It reads from the Redis dirty set to know which (merchant, platform, channel, date) combinations need updating — only those combinations are recalculated, not the entire table.

If no orders are found for a combination, the existing row is deleted (rather than left with stale counts).

---

## Module Dependency Rules

```
simpleec-common        ← no internal dependencies
simpleec-core          ← depends on: simpleec-common
simpleec-channel       ← depends on: simpleec-common, simpleec-core
simpleec-api           ← depends on: simpleec-common, simpleec-core
simpleec-gateway       ← depends on: simpleec-common
simpleec-*-job         ← depends on: simpleec-common, simpleec-core
```

Services must not introduce circular dependencies. `simpleec-common` must remain dependency-free (no Spring, no DB).

---

## TaskType Reference

All business operations are expressed as `TaskTypeEnum` values. The full enum is in `simpleec-common/.../enums/TaskTypeEnum.java`.

| Category | TaskType | Topic | Scheduler-driven |
|---------|---------|-------|-----------------|
| Channel fetch | `FETCH_ORDERS` | `{platform}.slow` | Yes (every 5 min) |
| Channel fetch | `FETCH_ORDER_DETAIL` | `{platform}.slow` | No (triggered by channel job) |
| Channel fetch | `FETCH_RETURNS` | `{platform}.slow` | Yes (every 5 min) |
| Channel fetch | `FETCH_RETURN_DETAIL` | `{platform}.slow` | No |
| Channel action | `SHIP_ORDER` | `{platform}.fast` | No |
| Channel action | `UPDATE_INVENTORY` | `{platform}.fast` | No |
| Channel action | `UPDATE_PRICE` | `{platform}.fast` | No |
| Channel action | `APPROVE_RETURN` | `{platform}.fast` | No |
| Channel action | `CANCEL_ORDER` | `{platform}.fast` | No |
| Channel sync | `SYNC_PACK` | `{platform}.slow` | No |
| Order processing | `ORDER_UPSERT` | `order.process` | No |
| Return processing | `RETURN_UPSERT` | `return.process` | No |
| Backend | `STATS_RECALC` | `task.backend` | Yes (every 5 min) |
| Backend | `SYNC_PRODUCT` | `task.backend` | No |
| Backend | `ORDER_REPORT` | `task.backend` | Yes |
| Backend | `SALES_REPORT` | `task.backend` | Yes |
| Backend | `INVENTORY_REPORT` | `task.backend` | Yes |
| Backend | `RETURN_REPORT` | `task.backend` | Yes |
| Backend | `DAILY_REPORT` | `task.backend` | Yes (hourly) |
| System | `HEARTBEAT` | `scheduler.heartbeat` | Yes (every second) |
