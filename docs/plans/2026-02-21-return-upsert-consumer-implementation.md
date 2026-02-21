# ReturnUpsertConsumer Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Implement ReturnUpsertConsumer to process RETURN_UPSERT messages from the return.process Kafka topic, enabling automated refund/return order synchronization with two-layer deduplication.

**Architecture:** ReturnUpsertConsumer mirrors OrderUpsertConsumer's pattern—consuming RETURN_UPSERT messages, performing hash-based deduplication via Redis (fast) and database (safe), then persisting via ReturnOrderService. Return data is mapped from platform-specific formats to OMS schema.

**Tech Stack:** Spring Boot, Spring Kafka, Redis, PostgreSQL, Jackson, JUnit 5, Mockito

---

## Task 1: Create ReturnUpsertHandler (Business Logic)

**Files:**
- Create: `simpleec-order-job/src/main/java/com/simpleec/orderjob/handler/ReturnUpsertHandler.java`
- Modify: None
- Test: `simpleec-order-job/src/test/java/com/simpleec/orderjob/handler/ReturnUpsertHandlerTest.java`

**Step 1: Write unit test for hash deduplication (Redis hit)**

```java
package com.simpleec.orderjob.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.core.entity.ReturnOrder;
import com.simpleec.core.service.ReturnOrderService;
import com.simpleec.common.enums.ReturnStatusEnum;
import com.simpleec.common.util.RedisKeyUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReturnUpsertHandlerTest {

    @Mock
    private ReturnOrderService returnOrderService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private ReturnUpsertHandler handler;

    private ObjectMapper objectMapper;
    private String merchantId = "MERCHANT_001";
    private String channelId = "shopee";
    private String channelRefundId = "REF-2024-001";
    private String returnHash = "abc123def456";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new ReturnUpsertHandler(returnOrderService, redisTemplate, objectMapper);
    }

    @Test
    void testHandleReturnUpsert_RedisCacheHit_SkipsProcessing() throws Exception {
        // Given: Redis cache has matching hash (return already processed)
        String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelRefundId);
        when(redisTemplate.opsForValue().get(redisKey)).thenReturn(returnHash);

        ObjectNode returnDataJson = objectMapper.createObjectNode();
        returnDataJson.put("status", "COMPLETED");
        returnDataJson.put("refund_amount", "1500.00");

        // When: Handler processes return with matching hash
        handler.handleReturnUpsert(merchantId, channelId, channelRefundId, returnHash, returnDataJson);

        // Then: Service should NOT be called (skip via Redis hit)
        verify(returnOrderService, never()).findByChannelRefundId(anyString());
        verify(returnOrderService, never()).createReturn(any());
    }

    @Test
    void testHandleReturnUpsert_DatabaseInsert_CreatesNewReturn() throws Exception {
        // Given: Redis miss and database miss (new return)
        String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelRefundId);
        when(redisTemplate.opsForValue().get(redisKey)).thenReturn(null);
        when(returnOrderService.findByChannelRefundId(channelRefundId)).thenReturn(java.util.Optional.empty());

        ObjectNode returnDataJson = objectMapper.createObjectNode();
        returnDataJson.put("status", "PENDING");
        returnDataJson.put("reason", "Product defect");
        returnDataJson.put("refund_amount", "1500.00");

        ReturnOrder mockSavedReturn = new ReturnOrder();
        mockSavedReturn.setId("RET_12345");
        mockSavedReturn.setChannelRefundId(channelRefundId);

        when(returnOrderService.createReturn(any(ReturnOrder.class))).thenReturn(mockSavedReturn);

        // When: Handler processes new return
        handler.handleReturnUpsert(merchantId, channelId, channelRefundId, returnHash, returnDataJson);

        // Then: Service should create return and update Redis
        verify(returnOrderService, times(1)).createReturn(any(ReturnOrder.class));
        verify(redisTemplate.opsForValue(), times(1)).set(eq(redisKey), eq(returnHash), any());
    }

    @Test
    void testHandleReturnUpsert_DatabaseUpdate_UpdatesExistingReturn() throws Exception {
        // Given: Database hit with different hash (return changed)
        String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelRefundId);
        when(redisTemplate.opsForValue().get(redisKey)).thenReturn(null);

        ReturnOrder existingReturn = new ReturnOrder();
        existingReturn.setId("RET_12345");
        existingReturn.setChannelRefundId(channelRefundId);
        existingReturn.setReturnStatus(ReturnStatusEnum.PENDING);

        when(returnOrderService.findByChannelRefundId(channelRefundId))
            .thenReturn(java.util.Optional.of(existingReturn));

        ObjectNode returnDataJson = objectMapper.createObjectNode();
        returnDataJson.put("status", "APPROVED");  // Status changed
        returnDataJson.put("refund_amount", "1500.00");

        when(returnOrderService.updateReturn(any(ReturnOrder.class))).thenReturn(existingReturn);

        // When: Handler processes return with status change
        handler.handleReturnUpsert(merchantId, channelId, channelRefundId, returnHash, returnDataJson);

        // Then: Service should update return and refresh Redis
        verify(returnOrderService, times(1)).updateReturn(any(ReturnOrder.class));
        verify(redisTemplate.opsForValue(), times(1)).set(eq(redisKey), eq(returnHash), any());
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :simpleec-order-job:test -k ReturnUpsertHandlerTest
```

Expected: FAIL with "cannot find symbol: class ReturnUpsertHandler"

**Step 3: Implement ReturnUpsertHandler**

```java
package com.simpleec.orderjob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.core.entity.ReturnOrder;
import com.simpleec.core.service.ReturnOrderService;
import com.simpleec.common.enums.ReturnStatusEnum;
import com.simpleec.common.util.RedisKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.TreeMap;
import java.util.Optional;

/**
 * Return Upsert Handler — 退貨入庫的核心業務邏輯
 *
 * 流程：
 * 1. 消費 return.process topic 的 RETURN_UPSERT 消息
 * 2. 兩層去重：Redis（快速）→ 資料庫（安全）
 * 3. INSERT 或 UPDATE 退貨記錄
 * 4. 更新 Redis hash 快取
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnUpsertHandler {

    private final ReturnOrderService returnOrderService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 處理退貨 UPSERT（INSERT 或 UPDATE）
     *
     * @param merchantId       商家 ID
     * @param channelId        通路 ID (e.g., "shopee")
     * @param channelRefundId  通路退貨 ID
     * @param returnHash       退貨數據 hash（用於變更檢測）
     * @param returnDataJson   退貨數據（JSON 格式）
     */
    public void handleReturnUpsert(String merchantId, String channelId, String channelRefundId,
                                   String returnHash, JsonNode returnDataJson) throws Exception {

        // 第 0 步：構建 Redis Key
        String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelRefundId);

        // 第 1 步：檢查 Redis（避免並發重複）
        String existingHashInRedis = redisTemplate.opsForValue().get(redisKey);
        if (returnHash.equals(existingHashInRedis)) {
            log.info("Return already processed (Redis hash match): {}", channelRefundId);
            return;
        }

        // 第 2 步：檢查資料庫中是否已存在
        Optional<ReturnOrder> existingReturn = returnOrderService.findByChannelRefundId(channelRefundId);

        ReturnOrder returnOrder;
        if (existingReturn.isPresent()) {
            // UPDATE 現有退貨
            returnOrder = existingReturn.get();

            // 計算 DB 中現有退貨的 hash
            String dbReturnHash = calculateReturnHash(returnOrder, returnDataJson);

            if (!returnHash.equals(dbReturnHash)) {
                // Hash 不同 → 有實質變化 → 執行 UPDATE
                returnOrder = updateReturnFromData(returnOrder, returnDataJson);
                log.info("Updated return: {} from channel {}", returnOrder.getId(), channelId);
            } else {
                // Hash 相同 → 沒有變化 → 跳過，但刷新 Redis TTL
                log.debug("Return content unchanged: {}", channelRefundId);
                redisTemplate.opsForValue().set(redisKey, returnHash, Duration.ofDays(7));
                return;
            }
        } else {
            // INSERT 新退貨
            returnOrder = createReturnFromData(merchantId, channelId, channelRefundId, returnDataJson);
            log.info("Created new return: {} from channel {}", returnOrder.getId(), channelId);
        }

        // 第 3 步：儲存退貨到資料庫
        ReturnOrder savedReturn = returnOrderService.updateReturn(returnOrder);

        // 第 4 步：更新 Redis hash 快取
        redisTemplate.opsForValue().set(redisKey, returnHash, Duration.ofDays(7));

        log.info("Completed RETURN_UPSERT for: {}", savedReturn.getId());
    }

    /**
     * 從 API 數據創建 ReturnOrder 實體
     */
    private ReturnOrder createReturnFromData(String merchantId, String channelId,
                                             String channelRefundId, JsonNode returnDataJson) throws Exception {

        ReturnOrder returnOrder = new ReturnOrder();
        returnOrder.setMerchantId(merchantId);
        returnOrder.setChannelRefundId(channelRefundId);

        // 需要關聯到訂單，但在此處可能不知道 orderId
        // TODO: 從 returnDataJson 或外部服務獲取 orderId

        populateReturnFromData(returnOrder, returnDataJson);
        return returnOrder;
    }

    /**
     * 更新現有 ReturnOrder 實體
     */
    private ReturnOrder updateReturnFromData(ReturnOrder returnOrder, JsonNode returnDataJson) throws Exception {
        populateReturnFromData(returnOrder, returnDataJson);
        return returnOrder;
    }

    /**
     * 從 API 數據填充 ReturnOrder 實體
     */
    private void populateReturnFromData(ReturnOrder returnOrder, JsonNode returnDataJson) throws Exception {
        if (returnDataJson.has("status")) {
            String status = returnDataJson.get("status").asText();
            returnOrder.setReturnStatus(ReturnStatusEnum.fromCode(status));
        }

        if (returnDataJson.has("reason")) {
            returnOrder.setReason(returnDataJson.get("reason").asText());
        }

        if (returnDataJson.has("items")) {
            returnOrder.setItems(objectMapper.writeValueAsString(returnDataJson.get("items")));
        }

        if (returnDataJson.has("refund_amount")) {
            returnOrder.setRefundAmount(
                new BigDecimal(returnDataJson.get("refund_amount").asText())
            );
        }

        if (returnDataJson.has("created_at")) {
            returnOrder.setCreatedAt(
                java.time.LocalDateTime.parse(returnDataJson.get("created_at").asText())
            );
        }
    }

    /**
     * 計算退貨 Hash（比對用）
     */
    private String calculateReturnHash(ReturnOrder returnOrder, JsonNode returnDataJson) {
        try {
            var sortedData = new TreeMap<String, Object>();

            if (returnOrder.getReturnStatus() != null) {
                sortedData.put("status", returnOrder.getReturnStatus().getCode());
            }
            if (returnOrder.getReason() != null) {
                sortedData.put("reason", returnOrder.getReason());
            }
            if (returnOrder.getItems() != null) {
                sortedData.put("items", returnOrder.getItems());
            }
            if (returnOrder.getRefundAmount() != null) {
                sortedData.put("refund_amount", returnOrder.getRefundAmount());
            }

            String json = objectMapper.writeValueAsString(sortedData);
            return DigestUtils.sha256Hex(json);
        } catch (Exception e) {
            log.error("Error calculating return hash", e);
            return "";
        }
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :simpleec-order-job:test -k ReturnUpsertHandlerTest
```

Expected: PASS (3/3 tests pass)

**Step 5: Commit**

```bash
git add simpleec-order-job/src/main/java/com/simpleec/orderjob/handler/ReturnUpsertHandler.java \
        simpleec-order-job/src/test/java/com/simpleec/orderjob/handler/ReturnUpsertHandlerTest.java
git commit -m "feat: implement ReturnUpsertHandler with two-layer deduplication

Add business logic for processing RETURN_UPSERT messages with Redis-based
fast path and database-based safe path. Supports hash-based change detection
and INSERT/UPDATE operations for return orders.

Tests: 3 test cases covering Redis hit, DB insert, and DB update scenarios"
```

---

## Task 2: Create ReturnUpsertConsumer (Kafka Listener)

**Files:**
- Create: `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/ReturnUpsertConsumer.java`
- Test: `simpleec-order-job/src/test/java/com/simpleec/orderjob/consumer/ReturnUpsertConsumerTest.java`

**Step 1: Write unit test for Kafka message consumption**

```java
package com.simpleec.orderjob.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.orderjob.handler.ReturnUpsertHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReturnUpsertConsumerTest {

    @Mock
    private ReturnUpsertHandler returnUpsertHandler;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private ReturnUpsertConsumer consumer;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new ReturnUpsertConsumer(returnUpsertHandler, objectMapper);
    }

    @Test
    void testConsumeReturnUpsert_ValidMessage_CallsHandler() throws Exception {
        // Given: Valid RETURN_UPSERT message
        ObjectNode message = createValidReturnMessage();
        String messageStr = objectMapper.writeValueAsString(message);

        // When: Consumer processes message
        consumer.consumeReturnUpsert(messageStr, 0, acknowledgment);

        // Then: Handler should be called and offset acknowledged
        verify(returnUpsertHandler, times(1)).handleReturnUpsert(
            eq("MERCHANT_001"),
            eq("shopee"),
            eq("REF-2024-001"),
            anyString(),
            any()
        );
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    void testConsumeReturnUpsert_InvalidTaskType_SkipsProcessing() throws Exception {
        // Given: Message with wrong taskType
        ObjectNode message = objectMapper.createObjectNode();
        ObjectNode header = objectMapper.createObjectNode();
        header.put("taskType", "INVALID_TYPE");
        header.put("merchantId", "MERCHANT_001");
        message.set("header", header);

        String messageStr = objectMapper.writeValueAsString(message);

        // When: Consumer processes message
        consumer.consumeReturnUpsert(messageStr, 0, acknowledgment);

        // Then: Handler should NOT be called
        verify(returnUpsertHandler, never()).handleReturnUpsert(any(), any(), any(), any(), any());
        verify(acknowledgment, times(1)).acknowledge();
    }

    private ObjectNode createValidReturnMessage() {
        ObjectNode message = objectMapper.createObjectNode();

        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_12345");
        header.put("taskType", "RETURN_UPSERT");
        header.put("merchantId", "MERCHANT_001");
        header.put("channelId", "shopee");
        header.put("timestamp", java.time.Instant.now().toString());
        header.put("version", "1.0");
        message.set("header", header);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelRefundId", "REF-2024-001");
        body.put("returnHash", "abc123def456");
        ObjectNode returnData = objectMapper.createObjectNode();
        returnData.put("status", "PENDING");
        returnData.put("reason", "Product defect");
        returnData.put("refund_amount", "1500.00");
        body.set("returnData", returnData);
        message.set("body", body);

        return message;
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :simpleec-order-job:test -k ReturnUpsertConsumerTest
```

Expected: FAIL with "cannot find symbol: class ReturnUpsertConsumer"

**Step 3: Implement ReturnUpsertConsumer**

```java
package com.simpleec.orderjob.consumer;

import com.simpleec.orderjob.handler.ReturnUpsertHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReturnUpsert Consumer — 退貨入庫的 Kafka 監聽器
 *
 * 消費 return.process topic 中的 RETURN_UPSERT 消息
 * 執行兩層去重（Redis + 資料庫），然後入庫
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnUpsertConsumer {

    private final ReturnUpsertHandler returnUpsertHandler;
    private final ObjectMapper objectMapper;

    /**
     * 消費 return.process topic
     */
    @KafkaListener(topics = "return.process", groupId = "return-job-group", concurrency = "4")
    @Transactional
    public void consumeReturnUpsert(@Payload String message,
                                    @Header(name = "kafka_receivedPartitionId") int partition,
                                    Acknowledgment acknowledgment) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            String taskType = header.get("taskType").asText();

            if (!taskType.equals("RETURN_UPSERT")) {
                log.warn("Unexpected taskType: {} in ReturnUpsertConsumer", taskType);
                acknowledgment.acknowledge();
                return;
            }

            // 解析訊息
            String merchantId = header.get("merchantId").asText();
            String channelId = header.get("channelId").asText();
            String channelRefundId = body.get("channelRefundId").asText();
            String returnHash = body.get("returnHash").asText();
            JsonNode returnDataJson = body.get("returnData");

            log.info("Processing RETURN_UPSERT: {} from {} (hash: {})",
                channelRefundId, channelId, returnHash.substring(0, 8) + "...");

            // 執行退貨入庫邏輯
            returnUpsertHandler.handleReturnUpsert(merchantId, channelId, channelRefundId, returnHash, returnDataJson);

            // 手動提交 offset（確保退貨已入庫）
            acknowledgment.acknowledge();

            log.info("Successfully processed RETURN_UPSERT: {}", channelRefundId);

        } catch (Exception e) {
            log.error("Error processing RETURN_UPSERT", e);
            // TODO: 發送到 task.failed 重試隊列
        }
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :simpleec-order-job:test -k ReturnUpsertConsumerTest
```

Expected: PASS (2/2 tests pass)

**Step 5: Commit**

```bash
git add simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/ReturnUpsertConsumer.java \
        simpleec-order-job/src/test/java/com/simpleec/orderjob/consumer/ReturnUpsertConsumerTest.java
git commit -m "feat: implement ReturnUpsertConsumer Kafka listener

Add listener for return.process topic with RETURN_UPSERT message handling.
Routes to ReturnUpsertHandler for business logic processing with manual
offset management and transactional semantics.

Tests: 2 test cases covering valid message processing and invalid taskType"
```

---

## Task 3: Add Redis Helper Method for Return Hashing

**Files:**
- Modify: `simpleec-common/src/main/java/com/simpleec/common/util/RedisKeyUtil.java`

**Step 1: Write test for Redis key generation**

Create or update test in `simpleec-common/src/test/java/com/simpleec/common/util/RedisKeyUtilTest.java`:

```java
@Test
void testReturnHashKey_GeneratesCorrectFormat() {
    // Given: Return identifiers
    String merchantId = "M001";
    String channelId = "shopee";
    String channelRefundId = "REF-2024-001";

    // When: Generating return hash key
    String key = RedisKeyUtil.returnHashKey(merchantId, channelId, channelRefundId);

    // Then: Key should follow pattern
    assertEquals("return:hash:M001:shopee:REF-2024-001", key);
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :simpleec-common:test -k RedisKeyUtilTest
```

Expected: FAIL with "method returnHashKey not found"

**Step 3: Add method to RedisKeyUtil**

Append to `RedisKeyUtil.java`:

```java
/**
 * 退貨 Hash 快取 Key
 * 格式：return:hash:{merchantId}:{channelId}:{channelRefundId}
 */
public static String returnHashKey(String merchantId, String channelId, String channelRefundId) {
    return String.format("return:hash:%s:%s:%s", merchantId, channelId, channelRefundId);
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :simpleec-common:test -k RedisKeyUtilTest
```

Expected: PASS

**Step 5: Commit**

```bash
git add simpleec-common/src/main/java/com/simpleec/common/util/RedisKeyUtil.java \
        simpleec-common/src/test/java/com/simpleec/common/util/RedisKeyUtilTest.java
git commit -m "feat: add Redis key helper for return hash deduplication

Add returnHashKey() method to generate consistent Redis keys for return
order hash caching with format: return:hash:{merchantId}:{channelId}:{channelRefundId}"
```

---

## Task 4: Update Documentation

**Files:**
- Modify: `docs/README.md` (add ReturnUpsertConsumer section)
- Create: `docs/RETURN_UPSERT_FLOW.md` (detailed flow documentation)

**Step 1: Read current README.md**

```bash
head -100 docs/README.md
```

**Step 2: Add ReturnUpsertConsumer section to README.md**

After the "Cyberbiz Integration" section, add:

```markdown
### Return (Refund) Order Processing — ReturnUpsertConsumer

**Kafka Topic:** `return.process`

**Message Type:** `RETURN_UPSERT`

**Flow:**
1. Platform adapters detect returns/refunds via API polling
2. Send RETURN_UPSERT message to return.process topic with:
   - `channelRefundId`: Platform-specific refund ID
   - `returnHash`: SHA256 hash of return data for deduplication
   - `returnData`: Return details (status, reason, items, amount)
3. ReturnUpsertConsumer processes message with two-layer deduplication
4. Redis quick-path: Hash match = already processed
5. Database safe-path: Create new or update existing return
6. Return data stored in `refund_orders` table

**Files:**
- Consumer: `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/ReturnUpsertConsumer.java`
- Handler: `simpleec-order-job/src/main/java/com/simpleec/orderjob/handler/ReturnUpsertHandler.java`
- Service: `simpleec-core/src/main/java/com/simpleec/core/service/ReturnOrderService.java`
- Entity: `simpleec-core/src/main/java/com/simpleec/core/entity/ReturnOrder.java`

**Status Mapping:**
Platform return statuses are mapped to OMS statuses:
- `PENDING` → pending (default)
- `APPROVED` → approved
- `REJECTED` → rejected
- `COMPLETED` → completed
- `REFUNDED` → refunded
```

**Step 3: Create RETURN_UPSERT_FLOW.md**

```markdown
# Return (Refund) Order Processing Flow

## Overview

ReturnUpsertConsumer handles return/refund order synchronization from e-commerce platforms.
It follows the same pattern as OrderUpsertConsumer with two-layer deduplication.

## Architecture

```
Platform API
    ↓
Adapter.fetchReturns()
    ↓
RETURN_UPSERT Message (return.process topic)
    ↓
ReturnUpsertConsumer.consumeReturnUpsert()
    ↓
ReturnUpsertHandler.handleReturnUpsert()
    ├─ Redis Layer (fast path)
    │  └─ Hash match → skip
    │
    └─ Database Layer (safe path)
       ├─ findByChannelRefundId()
       │  ├─ EXISTS → UPDATE if hash changed
       │  └─ NOT FOUND → INSERT new
       │
       └─ Update Redis with new hash
              ↓
         ReturnOrder persisted
```

## Message Format

```json
{
  "header": {
    "messageId": "msg_abc123",
    "taskType": "RETURN_UPSERT",
    "merchantId": "M001",
    "channelId": "shopee",
    "timestamp": "2024-02-21T10:00:00Z",
    "version": "1.0"
  },
  "body": {
    "channelRefundId": "REF-202402-001",
    "returnHash": "sha256hash...",
    "returnData": {
      "status": "PENDING",
      "reason": "Product defect",
      "items": [{"sku": "SKU123", "qty": 1}],
      "refund_amount": "1500.00",
      "created_at": "2024-02-21T08:00:00Z"
    }
  }
}
```

## Deduplication Strategy

### Two-Layer Approach

1. **Redis Layer (Primary, Fast)**
   - Key: `return:hash:{merchantId}:{channelId}:{channelRefundId}`
   - Value: SHA256 hash of return data
   - TTL: 7 days
   - Use: Quick skip if hash matches

2. **Database Layer (Secondary, Safe)**
   - Query: Find by channelRefundId
   - Compare: Calculate hash of DB record vs incoming
   - Decision:
     - Hash same → No change, skip
     - Hash different → Change detected, UPDATE
     - Not found → New record, INSERT

### Hash Calculation

Hash includes only mutable fields:
- `status`
- `reason`
- `items`
- `refund_amount`

Excludes unchanging fields:
- `id`, `channelRefundId`, `created_at`

## Return Statuses

| Status | Code | Meaning |
|--------|------|---------|
| PENDING | PENDING | 待處理 |
| APPROVED | APPROVED | 已批准 |
| REJECTED | REJECTED | 已拒絕 |
| COMPLETED | COMPLETED | 已完成 |
| REFUNDED | REFUNDED | 已退款 |

## Implementation Status

- ✅ ReturnUpsertConsumer implemented
- ✅ ReturnUpsertHandler implemented
- ✅ Two-layer deduplication (Redis + DB)
- ✅ Unit tests (5 test cases)
- ⏳ Platform adapters' fetchReturns() methods (Phase 2)
- ⏳ Failure retry to task.failed topic (Future)
```

**Step 4: Commit**

```bash
git add docs/README.md docs/RETURN_UPSERT_FLOW.md
git commit -m "docs: add ReturnUpsertConsumer documentation

Add detailed documentation for RETURN_UPSERT message processing including
message format, deduplication strategy, status mapping, and architectural flow.

Updates README with Return processing overview and links to implementation files."
```

---

## Summary

**Deliverables:**
- ✅ ReturnUpsertHandler (business logic with 3 test cases)
- ✅ ReturnUpsertConsumer (Kafka listener with 2 test cases)
- ✅ Redis helper method (returnHashKey)
- ✅ Unit tests (5 tests total, all passing)
- ✅ Documentation (README update + detailed flow doc)

**Total Commits:** 4
**Test Coverage:** 5 test cases
**Time Estimate:** 3-4 hours for implementation + testing

**Next Steps After Implementation:**
1. Implement platform adapters' `fetchReturns()` methods (Phase 2)
2. Add DLT retry logic to error handling
3. Implement Return reporting in BackendJobConsumer
4. Add integration tests with Kafka testcontainers
