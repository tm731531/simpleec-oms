# Return (Refund) Order Processing Flow

## Overview

ReturnUpsertConsumer handles return/refund order synchronization from e-commerce platforms.
It follows the same pattern as OrderUpsertConsumer with two-layer deduplication for idempotency.

---

## Architecture

```
Platform API (FETCH_RETURNS)
         ↓
Adapter.fetchReturns()
         ↓
RETURN_UPSERT Message (return.process topic)
         ↓
ReturnUpsertConsumer.consumeReturnUpsert()
         ↓
ReturnUpsertHandler.handleReturnUpsert()
         ├─ Redis Layer (fast path)
         │  └─ Hash match → skip (already processed)
         │
         └─ Database Layer (safe path)
            ├─ findByChannelRefundId()
            │  ├─ EXISTS → Check hash
            │  │  ├─ Hash same → no change, skip
            │  │  └─ Hash different → UPDATE (return data changed)
            │  │
            │  └─ NOT FOUND → INSERT new return record
            │
            └─ Update Redis with new hash
                   ↓
            ReturnOrder persisted in refund_orders table
```

---

## Message Format

### Header (Standard)
```json
{
  "header": {
    "messageId": "msg_abc123",
    "taskType": "RETURN_UPSERT",
    "merchantId": "M001",
    "channelId": "shopee",
    "timestamp": "2024-02-21T10:00:00Z",
    "source": "channel_job",
    "version": "1.0"
  }
}
```

### Body (Return-Specific)
```json
{
  "body": {
    "channelRefundId": "REF-202402-001",
    "returnHash": "sha256hashofreturndata...",
    "returnData": {
      "status": "PENDING",
      "reason": "Product defect",
      "returnDate": "2024-02-21T08:00:00Z",
      "items": [
        {
          "sku": "SKU123",
          "qty": 1,
          "refundAmount": "500.00"
        }
      ],
      "totalRefundAmount": "500.00",
      "createdAt": "2024-02-21T08:00:00Z"
    }
  }
}
```

---

## Deduplication Strategy

### Two-Layer Approach

#### 1. Redis Layer (Primary, Fast Path)
- **Key Format**: `return:hash:{merchantId}:{channelId}:{channelRefundId}`
- **Value**: SHA256 hash of return data
- **TTL**: 7 days
- **Operation**: Quick-lookup to skip already-processed returns
- **Performance**: O(1) Redis lookup

#### 2. Database Layer (Secondary, Safe Path)
- **Query**: Find return by `channelRefundId` in `refund_orders` table
- **Decision Logic**:
  - If return NOT FOUND → INSERT new record
  - If return FOUND → Compare hash:
    - Hash SAME → Data unchanged, skip database update
    - Hash DIFFERENT → Data changed, UPDATE return record
- **Performance**: Index on `channel_refund_id` for fast lookup

### Hash Calculation

Hash includes **only mutable fields** (fields that can change):
- `status` (PENDING → APPROVED → COMPLETED)
- `reason` (customer provided reason)
- `items` and `qty` (items included in return)
- `totalRefundAmount` (refund amount)

Hash **excludes** unchanging fields:
- `id` (primary key)
- `channelRefundId` (platform identifier)
- `createdAt` (never changes after creation)
- `merchantId`, `channelId` (business keys)

### Hash Pseudocode

```java
String calculateHash(ReturnData data) {
    String hashInput = data.status + "|" +
                       data.reason + "|" +
                       data.items.toString() + "|" +
                       data.totalRefundAmount;
    return SHA256.hash(hashInput);
}
```

---

## Return Statuses

| Status | Code | 中文 | Meaning |
|--------|------|------|---------|
| PENDING | PENDING | 待處理 | Awaiting merchant review/approval |
| APPROVED | APPROVED | 已批准 | Merchant approved the return |
| REJECTED | REJECTED | 已拒絕 | Merchant rejected the return |
| COMPLETED | COMPLETED | 已完成 | Return process fully completed |
| REFUNDED | REFUNDED | 已退款 | Refund has been issued to customer |

---

## Processing Workflow

### Step 1: Message Arrives at return.process Topic
Consumer receives RETURN_UPSERT message with:
- `channelRefundId`: Unique identifier from platform
- `returnHash`: Pre-calculated hash from platform adapter
- `returnData`: Complete return information

### Step 2: Redis Quick-Path Check
```java
String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelRefundId);
String cachedHash = redisCache.get(redisKey);

if (cachedHash != null && cachedHash.equals(returnHash)) {
    // Already processed, skip
    logger.info("Return already processed: {}", channelRefundId);
    return;
}
```

**Result**: If hash matches, skip to end (cost: 1 Redis lookup)

### Step 3: Database Safe-Path Check
```java
ReturnOrder existing = returnOrderService.findByChannelRefundId(channelRefundId);

if (existing == null) {
    // New return, create it
    ReturnOrder newReturn = ReturnOrder.builder()
        .id(IdGenerator.nextId())
        .merchantId(merchantId)
        .channelId(channelId)
        .channelRefundId(channelRefundId)
        .status(returnData.status)
        .reason(returnData.reason)
        .items(returnData.items)
        .totalRefundAmount(returnData.totalRefundAmount)
        .createdAt(now())
        .updatedAt(now())
        .build();

    returnOrderService.save(newReturn);
    logger.info("Created new return: {}", channelRefundId);
} else {
    // Existing return, check if changed
    String existingHash = calculateHash(existing);

    if (!existingHash.equals(returnHash)) {
        // Data changed, update it
        existing.setStatus(returnData.status);
        existing.setReason(returnData.reason);
        existing.setItems(returnData.items);
        existing.setTotalRefundAmount(returnData.totalRefundAmount);
        existing.setUpdatedAt(now());

        returnOrderService.update(existing);
        logger.info("Updated existing return: {}", channelRefundId);
    } else {
        // No change detected
        logger.debug("No changes detected for return: {}", channelRefundId);
    }
}
```

### Step 4: Cache Update
```java
String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelRefundId);
redisCache.setex(redisKey, 7 * 24 * 3600, returnHash);  // 7-day TTL
```

---

## Database Schema

### refund_orders Table

```sql
CREATE TABLE refund_orders (
    id VARCHAR(20) PRIMARY KEY,                    -- NanoID PK
    merchant_id VARCHAR(20) NOT NULL,              -- FK to merchant
    channel_id VARCHAR(50) NOT NULL,               -- Platform ID (shopee, momo, etc.)
    channel_refund_id VARCHAR(100) NOT NULL,       -- Platform refund ID (unique per platform)
    status VARCHAR(20) NOT NULL,                   -- PENDING, APPROVED, REJECTED, COMPLETED, REFUNDED
    reason TEXT,                                   -- Customer reason
    items JSONB NOT NULL,                          -- Array of returned items
    total_refund_amount NUMERIC(10,2),             -- Total refund amount
    created_at TIMESTAMP NOT NULL,                 -- Creation time
    updated_at TIMESTAMP NOT NULL,                 -- Last update time

    UNIQUE(merchant_id, channel_id, channel_refund_id),  -- Ensure unique per merchant+platform
    INDEX idx_channel_refund_id (channel_refund_id),
    INDEX idx_merchant_channel (merchant_id, channel_id),
    INDEX idx_created_at (created_at)
);
```

---

## Entity Definition

```java
@Data
@Builder
@Entity
@Table(name = "refund_orders")
public class ReturnOrder {

    @TableId(type = IdType.ASSIGN_UUID)
    @Column(name = "id")
    private String id;

    @Column(name = "merchant_id")
    private String merchantId;

    @Column(name = "channel_id")
    private String channelId;

    @Column(name = "channel_refund_id")
    private String channelRefundId;

    @Column(name = "status")
    private String status;  // PENDING, APPROVED, REJECTED, COMPLETED, REFUNDED

    @Column(name = "reason")
    private String reason;

    @Column(name = "items")
    @TableField(typeHandler = JsonTypeHandler.class)
    private List<ReturnItem> items;

    @Column(name = "total_refund_amount")
    private BigDecimal totalRefundAmount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

@Data
@Builder
public class ReturnItem {
    private String sku;
    private Integer qty;
    private BigDecimal refundAmount;
}
```

---

## Service Layer

```java
public interface ReturnOrderService {

    /**
     * Find return by channel refund ID
     */
    ReturnOrder findByChannelRefundId(String channelRefundId);

    /**
     * Create new return order
     */
    ReturnOrder save(ReturnOrder returnOrder);

    /**
     * Update existing return order
     */
    ReturnOrder update(ReturnOrder returnOrder);

    /**
     * Calculate hash of return data for deduplication
     */
    String calculateReturnHash(ReturnData returnData);
}
```

---

## Consumer Implementation

```java
@Component
public class ReturnUpsertConsumer {

    @KafkaListener(topics = "return.process", groupId = "return-upsert-group")
    public void consumeReturnUpsert(@Payload ReturnUpsertMessage message) {
        try {
            // Set MDC for logging
            TaskMdcHelper.set(message.getHeader());

            // Delegate to handler
            returnUpsertHandler.handleReturnUpsert(message);

        } finally {
            TaskMdcHelper.clear();
        }
    }
}
```

---

## Handler Implementation

```java
@Component
public class ReturnUpsertHandler {

    public void handleReturnUpsert(ReturnUpsertMessage message) {
        ReturnUpsertHeader header = message.getHeader();
        ReturnUpsertBody body = message.getBody();

        String merchantId = header.getMerchantId();
        String channelId = header.getChannelId();
        String channelRefundId = body.getChannelRefundId();
        String returnHash = body.getReturnHash();
        ReturnData returnData = body.getReturnData();

        // Step 1: Quick-path check (Redis)
        String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelRefundId);
        String cachedHash = redisCache.get(redisKey);

        if (cachedHash != null && cachedHash.equals(returnHash)) {
            logger.info("Return already processed via Redis cache: {}", channelRefundId);
            return;
        }

        // Step 2: Safe-path check (Database)
        ReturnOrder existing = returnOrderService.findByChannelRefundId(channelRefundId);

        if (existing == null) {
            // Create new return
            ReturnOrder newReturn = ReturnOrder.builder()
                .id(IdGenerator.nextId())
                .merchantId(merchantId)
                .channelId(channelId)
                .channelRefundId(channelRefundId)
                .status(returnData.getStatus())
                .reason(returnData.getReason())
                .items(returnData.getItems())
                .totalRefundAmount(returnData.getTotalRefundAmount())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

            returnOrderService.save(newReturn);
            logger.info("Created new return order: {}", channelRefundId);
        } else {
            // Check if data changed
            String existingHash = returnOrderService.calculateReturnHash(
                ReturnData.from(existing)
            );

            if (!existingHash.equals(returnHash)) {
                existing.setStatus(returnData.getStatus());
                existing.setReason(returnData.getReason());
                existing.setItems(returnData.getItems());
                existing.setTotalRefundAmount(returnData.getTotalRefundAmount());
                existing.setUpdatedAt(LocalDateTime.now());

                returnOrderService.update(existing);
                logger.info("Updated existing return order: {}", channelRefundId);
            } else {
                logger.debug("No changes detected for return order: {}", channelRefundId);
            }
        }

        // Step 3: Update Redis cache
        redisCache.setex(redisKey, 7 * 24 * 3600, returnHash);
    }
}
```

---

## Error Handling

### Retry Strategy

- **Transient Errors** (network timeout, temporary DB connection):
  - Retry up to 3 times with exponential backoff
  - On final failure → send to `task.failed` topic
- **Permanent Errors** (invalid data format, DB constraint violation):
  - No retry
  - Send directly to `task.dlt` (Dead Letter Topic)

### Logging

All operations logged with MDC context:
```
2024-02-21 10:05:23.456 [return-upsert-group] [msg_abc123] [M001] [shopee] INFO
  Created new return order: REF-202402-001
```

---

## Monitoring & Metrics

### Key Metrics

1. **Consumer Lag**
   - Monitor offset lag for `return-upsert-group`
   - Alert if lag > 1000 messages

2. **Processing Rate**
   - Returns processed per minute
   - Expected: 100-1000 per minute

3. **Redis Hit Rate**
   - % of messages skipped via Redis cache
   - Expected: 50-70% (many returns unchanged)

4. **Database Hit Rate**
   - % of messages requiring DB update
   - Expected: 30-50%

5. **Error Rate**
   - % of messages sent to error topics
   - Alert if error rate > 1%

---

## Implementation Checklist

- [x] ReturnUpsertConsumer implemented with Kafka listener
- [x] ReturnUpsertHandler implemented with two-layer deduplication
- [x] ReturnOrderService with hash calculation
- [x] ReturnOrder entity with JSONB items field
- [x] Database refund_orders table created
- [x] Redis key utility method (RedisKeyUtil.returnHashKey)
- [x] Unit tests (5+ test cases)
- [ ] Integration tests with embedded Kafka
- [ ] Platform adapters' fetchReturns() methods (Phase 2)
- [ ] Failure retry to task.failed topic (Future)
- [ ] Monitoring & alerting rules in Prometheus

---

## Related Files

| File | Purpose |
|------|---------|
| `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/ReturnUpsertConsumer.java` | Kafka consumer entry point |
| `simpleec-order-job/src/main/java/com/simpleec/orderjob/handler/ReturnUpsertHandler.java` | Business logic handler |
| `simpleec-core/src/main/java/com/simpleec/core/service/ReturnOrderService.java` | Service layer |
| `simpleec-core/src/main/java/com/simpleec/core/entity/ReturnOrder.java` | JPA entity |
| `simpleec-core/src/main/java/com/simpleec/core/repository/ReturnOrderRepository.java` | DB access layer |
| `simpleec-common/src/main/java/com/simpleec/common/util/RedisKeyUtil.java` | Redis key utilities |
| `simpleec-common/src/main/java/com/simpleec/common/model/ReturnUpsertMessage.java` | Message DTO |

---

## Future Enhancements

1. **Failure Recovery**
   - Implement retry mechanism to `task.failed` topic
   - Auto-reprocess failed returns after N minutes

2. **Webhook Notifications**
   - Send return status change events to merchant platforms
   - Implement callback handlers

3. **Analytics Integration**
   - Track return rates by platform, product, reason
   - Generate daily return reports

4. **Return Reversal**
   - Handle rare case where customer cancels return after approval
   - Add REVERSAL status handling

5. **Batch Processing**
   - Aggregate small refunds for batch processing
   - Implement scheduled batch refund job
