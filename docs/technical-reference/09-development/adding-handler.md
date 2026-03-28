# Adding a New TaskType Handler

This guide walks through every step required to add a new business task type to SimpleEC OMS, using `BATCH_CANCEL_ORDERS` as a complete worked example. The same steps apply to any new task type, regardless of which topic it uses.

---

## Overview of the Handler Pipeline

Before writing code, understand where your handler fits:

```
API request
    ↓ (HTTP POST)
simpleec-api  →  publishes to Kafka topic
    ↓ (Kafka message)
Job consumer  →  routes by taskType
    ↓
Your handler  →  business logic + DB write
```

Alternatively, a task can originate from the scheduler (for background tasks), from a channel job (for ORDER_UPSERT), or from another handler (for chained workflows).

---

## Step 1: Define the TaskType Enum Value

Add your new task type to `TaskTypeEnum` in `simpleec-common`:

```java
// simpleec-common/src/main/java/com/simpleec/common/enums/TaskTypeEnum.java

// Add inside the enum, in the appropriate category comment block:
BATCH_CANCEL_ORDERS("BATCH_CANCEL_ORDERS", "批量取消訂單", false),
```

The third constructor parameter (`isSchedulerDriven`) is `false` for merchant-initiated actions and `true` for tasks dispatched by the scheduler heartbeat.

After adding the enum value, rebuild `simpleec-common`:

```bash
./gradlew :simpleec-common:build -x test
```

---

## Step 2: Define the Kafka Message Contract

Document and agree on the message structure before writing any code. Add a sample to `docs/3-EVENT-FLOW/EVENT_SAMPLES.md`.

```json
{
  "header": {
    "taskType": "BATCH_CANCEL_ORDERS",
    "merchantId": "a00000",
    "platformId": "shopee",
    "channelId": "SHOPEE_001",
    "requestId": "req-aBcDe1234",
    "timestamp": "2026-03-28T10:00:00Z",
    "source": "api",
    "version": 1,
    "isRollback": false
  },
  "body": {
    "orderIds": ["NANO_ID_001", "NANO_ID_002", "NANO_ID_003"],
    "cancelReason": "out_of_stock",
    "notifyBuyer": true
  }
}
```

**Checklist:**
- `header.taskType` must match the enum value exactly (case-sensitive)
- `header.version` must be `1` (current schema version)
- `body` fields use camelCase
- `orderIds` references OMS internal NanoIDs, not `channelOrderId` values

---

## Step 3: Create the Handler Class

Create the handler in the appropriate job module. For merchant-initiated actions, use `simpleec-frontend-job`. For background/system tasks, use `simpleec-backend-job`.

```java
// simpleec-frontend-job/src/main/java/com/simpleec/frontendjob/handler/BatchCancelOrdersHandler.java

package com.simpleec.frontendjob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.core.entity.Order;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.common.enums.OrderStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchCancelOrdersHandler {

    private final OrderRepository orderRepository;

    /**
     * Cancels multiple orders in a single operation.
     *
     * Design note: we are a passive sync party. We accept the cancellation
     * regardless of the current order status — no state guard validation.
     *
     * @param merchantId  the merchant whose orders to cancel
     * @param channelId   the channel these orders belong to
     * @param body        parsed JSON body from the Kafka message
     */
    public void handle(String merchantId, String channelId, JsonNode body) {
        JsonNode orderIdsNode = body.get("orderIds");
        if (orderIdsNode == null || !orderIdsNode.isArray() || orderIdsNode.isEmpty()) {
            log.warn("BATCH_CANCEL_ORDERS received empty orderIds for merchant {}", merchantId);
            return;
        }

        String cancelReason = body.path("cancelReason").asText("unspecified");
        boolean notifyBuyer = body.path("notifyBuyer").asBoolean(false);

        List<String> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (JsonNode orderIdNode : orderIdsNode) {
            String orderId = orderIdNode.asText();
            try {
                Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

                // Verify the order belongs to this merchant (security guard)
                if (!merchantId.equals(order.getMerchantId())) {
                    log.error("BATCH_CANCEL_ORDERS: order {} does not belong to merchant {}",
                        orderId, merchantId);
                    failed.add(orderId);
                    continue;
                }

                order.setOrderStatus(OrderStatusEnum.CANCELLED);
                orderRepository.save(order);

                succeeded.add(orderId);
                log.debug("Cancelled order {} (reason: {})", orderId, cancelReason);

            } catch (Exception e) {
                log.error("Failed to cancel order {}: {}", orderId, e.getMessage(), e);
                failed.add(orderId);
            }
        }

        log.info("BATCH_CANCEL_ORDERS complete: {} succeeded, {} failed (merchant: {}, reason: {})",
            succeeded.size(), failed.size(), merchantId, cancelReason);

        if (!failed.isEmpty()) {
            log.warn("Failed to cancel orders: {}", failed);
        }
    }
}
```

**Key rules for handler classes:**
- Use `@Component` — Spring manages the lifecycle
- Use `@RequiredArgsConstructor` for constructor injection
- Never set `EncryptionContext` inside the handler — the consumer does this before calling the handler
- Accept any status transition — no state machine guards
- Log at INFO level for business milestones, DEBUG for per-item detail, ERROR for per-item failures

---

## Step 4: Register in the Consumer

Add a routing case in the appropriate consumer's `switch` or `if` chain.

```java
// simpleec-frontend-job/src/main/java/com/simpleec/frontendjob/consumer/FrontendJobConsumer.java

@KafkaListener(topics = "task.frontend", groupId = "frontend-job-group", concurrency = "3")
public void consume(@Payload String messageJson) {
    // ... JSON parse, schema validation, MDC setup (existing code) ...

    String taskType = header.path("taskType").asText();
    String merchantId = header.path("merchantId").asText();
    String channelId = header.path("channelId").asText();

    EncryptionContext.setMerchantId(merchantId);
    try {
        switch (taskType) {
            case "EXISTING_TASK_TYPE" -> existingHandler.handle(merchantId, channelId, body);

            // Add your new case here:
            case "BATCH_CANCEL_ORDERS" -> batchCancelOrdersHandler.handle(merchantId, channelId, body);

            default -> log.warn("Unhandled taskType: {} in FrontendJobConsumer", taskType);
        }
    } finally {
        EncryptionContext.clear();
    }
}
```

The `batchCancelOrdersHandler` field is injected via constructor injection (add to the `@RequiredArgsConstructor` field list):

```java
private final BatchCancelOrdersHandler batchCancelOrdersHandler;
```

---

## Step 5: Add the API Endpoint (if merchant-initiated)

If merchants trigger this task through the UI, add a REST endpoint in `simpleec-api`.

```java
// simpleec-api/src/main/java/com/simpleec/api/controller/UserOrderController.java

@PostMapping("/orders/batch-cancel")
public ResponseEntity<BatchCancelResponse> batchCancel(
    @RequestBody @Valid BatchCancelRequest request,
    @AuthenticationPrincipal UserPrincipal principal
) {
    String merchantId = principal.getMerchantId();
    String channelId = request.getChannelId();

    // Build Kafka message
    ObjectNode header = objectMapper.createObjectNode();
    header.put("taskType", "BATCH_CANCEL_ORDERS");
    header.put("merchantId", merchantId);
    header.put("platformId", request.getPlatformId());
    header.put("channelId", channelId);
    header.put("requestId", NanoIdUtil.generate());
    header.put("timestamp", Instant.now().toString());
    header.put("source", "api");
    header.put("version", 1);
    header.put("isRollback", false);

    ObjectNode body = objectMapper.createObjectNode();
    body.set("orderIds", objectMapper.valueToTree(request.getOrderIds()));
    body.put("cancelReason", request.getCancelReason());
    body.put("notifyBuyer", request.isNotifyBuyer());

    ObjectNode message = objectMapper.createObjectNode();
    message.set("header", header);
    message.set("body", body);

    kafkaTemplate.send(TopicConstants.TASK_FRONTEND, merchantId, message.toString());

    log.info("BATCH_CANCEL_ORDERS queued: {} orders for merchant {}",
        request.getOrderIds().size(), merchantId);

    return ResponseEntity.accepted()
        .body(new BatchCancelResponse("queued", request.getOrderIds().size()));
}
```

The request DTO:

```java
// simpleec-api/src/main/java/com/simpleec/api/dto/BatchCancelRequest.java

@Data
public class BatchCancelRequest {
    @NotBlank
    private String platformId;

    @NotBlank
    private String channelId;

    @NotEmpty
    @Size(max = 100, message = "Cannot cancel more than 100 orders at once")
    private List<String> orderIds;

    private String cancelReason;

    private boolean notifyBuyer = false;
}
```

The response DTO:

```java
@Data
@AllArgsConstructor
public class BatchCancelResponse {
    private String status;      // "queued"
    private int orderCount;
}
```

Note the response is `202 Accepted`, not `200 OK` — the operation is async. The API published to Kafka; actual processing happens in the frontend-job container.

---

## Step 6: Update HANDLER_REGISTRY.md

Add an entry to `docs/3-EVENT-FLOW/HANDLER_REGISTRY.md` so the team can find your handler:

```markdown
## BATCH_CANCEL_ORDERS

| Field | Value |
|-------|-------|
| Topic | `task.frontend` |
| Consumer | `FrontendJobConsumer` |
| Handler class | `BatchCancelOrdersHandler` |
| Source | API endpoint: `POST /api/orders/batch-cancel` |
| Scheduler-driven | No |
| isRollback applicable | No |

**Body fields:**
- `orderIds` (array, required) — OMS internal NanoIDs
- `cancelReason` (string, optional)
- `notifyBuyer` (boolean, optional, default: false)
```

---

## Step 7: Write Tests

Add unit tests for the handler and integration tests for the consumer routing.

```java
// simpleec-frontend-job/src/test/java/com/simpleec/frontendjob/handler/BatchCancelOrdersHandlerTest.java

@ExtendWith(MockitoExtension.class)
class BatchCancelOrdersHandlerTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private BatchCancelOrdersHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_shouldCancelAllRequestedOrders() throws Exception {
        // Arrange
        String merchantId = "a00000";
        String channelId = "SHOPEE_001";

        Order order1 = new Order();
        order1.setId("NANO_ID_001");
        order1.setMerchantId(merchantId);
        order1.setOrderStatus(OrderStatusEnum.CONFIRMED);

        Order order2 = new Order();
        order2.setId("NANO_ID_002");
        order2.setMerchantId(merchantId);
        order2.setOrderStatus(OrderStatusEnum.READY_TO_SHIP);

        when(orderRepository.findById("NANO_ID_001")).thenReturn(Optional.of(order1));
        when(orderRepository.findById("NANO_ID_002")).thenReturn(Optional.of(order2));

        String bodyJson = """
            {
              "orderIds": ["NANO_ID_001", "NANO_ID_002"],
              "cancelReason": "out_of_stock",
              "notifyBuyer": false
            }
            """;
        JsonNode body = objectMapper.readTree(bodyJson);

        // Act
        handler.handle(merchantId, channelId, body);

        // Assert
        verify(orderRepository, times(1)).save(argThat(o ->
            o.getId().equals("NANO_ID_001") &&
            o.getOrderStatus() == OrderStatusEnum.CANCELLED
        ));
        verify(orderRepository, times(1)).save(argThat(o ->
            o.getId().equals("NANO_ID_002") &&
            o.getOrderStatus() == OrderStatusEnum.CANCELLED
        ));
    }

    @Test
    void handle_shouldSkipOrdersBelongingToOtherMerchant() throws Exception {
        // Arrange: order belongs to a different merchant
        Order order = new Order();
        order.setId("NANO_ID_999");
        order.setMerchantId("other-merchant");
        order.setOrderStatus(OrderStatusEnum.CONFIRMED);

        when(orderRepository.findById("NANO_ID_999")).thenReturn(Optional.of(order));

        String bodyJson = """
            {"orderIds": ["NANO_ID_999"], "cancelReason": "test"}
            """;
        JsonNode body = objectMapper.readTree(bodyJson);

        // Act
        handler.handle("a00000", "SHOPEE_001", body);

        // Assert: save never called for cross-merchant order
        verify(orderRepository, never()).save(any());
    }

    @Test
    void handle_shouldHandleEmptyOrderIds() throws Exception {
        String bodyJson = """{"orderIds": []}""";
        JsonNode body = objectMapper.readTree(bodyJson);

        // Should return early without throwing
        handler.handle("a00000", "SHOPEE_001", body);

        verify(orderRepository, never()).findById(any());
    }
}
```

---

## Complete Checklist

- [ ] `TaskTypeEnum` — new value added with correct `isSchedulerDriven` flag
- [ ] Message contract documented in `docs/3-EVENT-FLOW/EVENT_SAMPLES.md`
- [ ] Handler class created in correct job module with `@Component`
- [ ] Consumer routing updated (`switch` case added, field injected)
- [ ] API endpoint added in `simpleec-api` (if applicable)
- [ ] Request/response DTOs defined with `@Valid` constraints
- [ ] `docs/3-EVENT-FLOW/HANDLER_REGISTRY.md` updated
- [ ] Unit tests written for the handler
- [ ] `./gradlew clean build -x test` passes
- [ ] `./quick-redeploy.sh simpleec-frontend-job simpleec-api` deployed and logs show correct routing

---

## Common Mistakes to Avoid

**Forgetting to clear EncryptionContext** — The consumer handles this, not the handler. If you call `EncryptionContext.setMerchantId()` inside a handler, you risk leaking the context to subsequent messages on the same thread.

**Using `json.get()` instead of `json.path()`** — `get()` returns `null` for missing fields; `path()` returns a `MissingNode` that safely returns defaults. Use `path()` for optional fields, `get()` only when you immediately null-check.

**Sending a synchronous 200 OK for an async operation** — Tasks published to Kafka are processed asynchronously. Return `202 Accepted` from the API endpoint.

**Adding state machine guards** — OMS accepts any status transition. Platform sync can arrive in unexpected order. Never add validation like "order must be CONFIRMED before SHIPPING".

**Forgetting merchantId ownership check** — Always verify that `order.getMerchantId().equals(merchantId)` before modifying an order. JWT authentication verifies the caller but not which orders they are allowed to touch.
