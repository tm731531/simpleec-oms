# 新增 TaskType Handler

本指南說明在 SimpleEC OMS 中新增業務任務類型所需的每個步驟，以 `BATCH_CANCEL_ORDERS` 作為完整的實作範例。相同步驟適用於任何新的任務類型，無論它使用哪個主題。

---

## Handler 管道概覽

在撰寫程式碼之前，請先了解你的 handler 在整個流程中的位置：

```
API 請求
    ↓（HTTP POST）
simpleec-api  →  發布至 Kafka 主題
    ↓（Kafka 訊息）
Job consumer  →  依 taskType 路由
    ↓
你的 handler  →  業務邏輯 + DB 寫入
```

任務也可以來自 scheduler（用於背景任務）、channel job（用於 ORDER_UPSERT），或其他 handler（用於鏈式工作流）。

---

## 步驟 1：定義 TaskType Enum 值

在 `simpleec-common` 的 `TaskTypeEnum` 中新增你的任務類型：

```java
// simpleec-common/src/main/java/com/simpleec/common/enums/TaskTypeEnum.java

// 在 enum 內部，對應的類別注解區塊中新增：
BATCH_CANCEL_ORDERS("BATCH_CANCEL_ORDERS", "批量取消訂單", false),
```

第三個建構子參數（`isSchedulerDriven`）：商家發起的操作設為 `false`，由 scheduler 心跳派送的任務設為 `true`。

新增 enum 值後，重新建置 `simpleec-common`：

```bash
./gradlew :simpleec-common:build -x test
```

---

## 步驟 2：定義 Kafka 訊息契約

在撰寫任何程式碼之前，請先記錄並確認訊息結構。在 `docs/3-EVENT-FLOW/EVENT_SAMPLES.md` 中新增樣本。

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

**檢查清單：**
- `header.taskType` 必須完全與 enum 值相符（區分大小寫）
- `header.version` 必須是 `1`（目前的 schema 版本）
- `body` 欄位使用 camelCase
- `orderIds` 參照 OMS 內部的 NanoID，不是 `channelOrderId`

---

## 步驟 3：建立 Handler 類別

在適當的 job 模組中建立 handler。商家發起的操作請使用 `simpleec-frontend-job`；背景/系統任務請使用 `simpleec-backend-job`。

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
     * 在單次操作中取消多筆訂單。
     *
     * 設計說明：我們是被動同步方。無論當前訂單狀態為何，
     * 都接受取消操作 — 不做狀態守衛驗證。
     *
     * @param merchantId  要取消訂單的商家
     * @param channelId   這些訂單所屬的通路
     * @param body        Kafka 訊息中已解析的 JSON body
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

                // 驗證訂單屬於此商家（安全守衛）
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

**Handler 類別的關鍵規則：**
- 使用 `@Component` — 由 Spring 管理生命週期
- 使用 `@RequiredArgsConstructor` 進行建構子注入
- 不在 handler 內部設定 `EncryptionContext` — consumer 會在呼叫 handler 前完成設定
- 接受任何狀態轉換 — 不加狀態機守衛
- 業務里程碑記錄 INFO，單筆明細記錄 DEBUG，單筆失敗記錄 ERROR

---

## 步驟 4：在 Consumer 中注冊

在對應 consumer 的 `switch` 或 `if` 鏈中新增路由 case。

```java
// simpleec-frontend-job/src/main/java/com/simpleec/frontendjob/consumer/FrontendJobConsumer.java

@KafkaListener(topics = "task.frontend", groupId = "frontend-job-group", concurrency = "3")
public void consume(@Payload String messageJson) {
    // ... JSON 解析、schema 驗證、MDC 設定（現有程式碼）...

    String taskType = header.path("taskType").asText();
    String merchantId = header.path("merchantId").asText();
    String channelId = header.path("channelId").asText();

    EncryptionContext.setMerchantId(merchantId);
    try {
        switch (taskType) {
            case "EXISTING_TASK_TYPE" -> existingHandler.handle(merchantId, channelId, body);

            // 在此新增你的 case：
            case "BATCH_CANCEL_ORDERS" -> batchCancelOrdersHandler.handle(merchantId, channelId, body);

            default -> log.warn("Unhandled taskType: {} in FrontendJobConsumer", taskType);
        }
    } finally {
        EncryptionContext.clear();
    }
}
```

`batchCancelOrdersHandler` 欄位透過建構子注入（新增至 `@RequiredArgsConstructor` 的欄位列表）：

```java
private final BatchCancelOrdersHandler batchCancelOrdersHandler;
```

---

## 步驟 5：新增 API 端點（若由商家發起）

若商家透過 UI 觸發此任務，請在 `simpleec-api` 中新增 REST 端點。

```java
// simpleec-api/src/main/java/com/simpleec/api/controller/UserOrderController.java

@PostMapping("/orders/batch-cancel")
public ResponseEntity<BatchCancelResponse> batchCancel(
    @RequestBody @Valid BatchCancelRequest request,
    @AuthenticationPrincipal UserPrincipal principal
) {
    String merchantId = principal.getMerchantId();
    String channelId = request.getChannelId();

    // 建立 Kafka 訊息
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

請求 DTO：

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

回應 DTO：

```java
@Data
@AllArgsConstructor
public class BatchCancelResponse {
    private String status;      // "queued"
    private int orderCount;
}
```

注意回應使用 `202 Accepted` 而非 `200 OK` — 操作是非同步的。API 將訊息發布至 Kafka；實際處理在 frontend-job 容器中進行。

---

## 步驟 6：更新 HANDLER_REGISTRY.md

在 `docs/3-EVENT-FLOW/HANDLER_REGISTRY.md` 中新增項目，讓團隊成員能找到你的 handler：

```markdown
## BATCH_CANCEL_ORDERS

| 欄位 | 值 |
|-------|-------|
| 主題 | `task.frontend` |
| Consumer | `FrontendJobConsumer` |
| Handler 類別 | `BatchCancelOrdersHandler` |
| 來源 | API 端點：`POST /api/orders/batch-cancel` |
| 由 Scheduler 驅動 | 否 |
| isRollback 適用 | 否 |

**Body 欄位：**
- `orderIds`（陣列，必填）— OMS 內部 NanoID
- `cancelReason`（字串，選填）
- `notifyBuyer`（布林值，選填，預設：false）
```

---

## 步驟 7：撰寫測試

為 handler 新增單元測試，並為 consumer 路由新增整合測試。

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
        // Arrange：訂單屬於不同商家
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

        // Assert：跨商家的訂單不應呼叫 save
        verify(orderRepository, never()).save(any());
    }

    @Test
    void handle_shouldHandleEmptyOrderIds() throws Exception {
        String bodyJson = """{"orderIds": []}""";
        JsonNode body = objectMapper.readTree(bodyJson);

        // 應直接返回，不拋出例外
        handler.handle("a00000", "SHOPEE_001", body);

        verify(orderRepository, never()).findById(any());
    }
}
```

---

## 完整檢查清單

- [ ] `TaskTypeEnum` — 已新增值，並設定正確的 `isSchedulerDriven` 旗標
- [ ] 訊息契約已記錄於 `docs/3-EVENT-FLOW/EVENT_SAMPLES.md`
- [ ] Handler 類別已在正確的 job 模組中建立，並標注 `@Component`
- [ ] Consumer 路由已更新（新增 `switch` case，注入欄位）
- [ ] API 端點已在 `simpleec-api` 中新增（若適用）
- [ ] 請求/回應 DTO 已定義並附加 `@Valid` 約束
- [ ] `docs/3-EVENT-FLOW/HANDLER_REGISTRY.md` 已更新
- [ ] Handler 的單元測試已撰寫
- [ ] `./gradlew clean build -x test` 通過
- [ ] `./quick-redeploy.sh simpleec-frontend-job simpleec-api` 已部署，日誌顯示路由正確

---

## 常見錯誤

**忘記清除 EncryptionContext** — 這由 consumer 負責，不是 handler。若在 handler 內呼叫 `EncryptionContext.setMerchantId()`，你可能讓上下文洩漏至同一執行緒後續處理的訊息。

**使用 `json.get()` 而非 `json.path()`** — `get()` 對不存在的欄位回傳 `null`；`path()` 回傳一個 `MissingNode`，能安全地提供預設值。選填欄位請用 `path()`，只有在立即進行 null 檢查時才使用 `get()`。

**對非同步操作回傳同步的 200 OK** — 發布至 Kafka 的任務是非同步處理的，API 端點應回傳 `202 Accepted`。

**新增狀態機守衛** — OMS 接受任何狀態轉換，平台同步可能以非預期的順序到達。絕不要新增如「出貨前訂單必須是 CONFIRMED 狀態」之類的驗證。

**忘記 merchantId 擁有權檢查** — 修改訂單前務必確認 `order.getMerchantId().equals(merchantId)`。JWT 認證驗證的是呼叫者身分，但不驗證他們被允許存取哪些訂單。
