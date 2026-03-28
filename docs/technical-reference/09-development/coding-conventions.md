# 編碼慣例與模式

本文件說明 SimpleEC OMS 中使用的各種模式。遵守這些慣例能確保一致性與正確性 — 其中許多慣例的存在是為了防止 PII 加密、Kafka 去重或資料庫約束方面的潛在 bug。

---

## 主鍵生成

所有資料表使用 `VARCHAR(20)` NanoID 作為主鍵，主鍵一律在應用程式層生成，絕不由資料庫生成。

```java
// 在 entity 類別中
@Id
@Column(name = "id", length = 20, nullable = false)
private String id;

// 建立新 entity 時 — 使用來自 simpleec-common 的 IdGenerator
entity.setId(IdGenerator.nextId());

// 針對訂單：使用複合 NanoID（嵌入商家前綴 + 時間戳）
order.setId(NanoIdUtil.generateComposite(merchantId));
```

所有外鍵欄位（merchantId、channelId、orderId 等）同樣是 `String` 型別，絕不使用 `Long` 或 `UUID`。

**絕對不要**使用 `@GeneratedValue` 或 `@TableId(type = IdType.AUTO)`，本系統不使用資料庫自動遞增。

---

## 時間戳欄位

由 Hibernate 管理的時間戳使用 `@CreationTimestamp` 和 `@UpdateTimestamp`，絕不手動設值。

```java
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private OffsetDateTime createdAt;

@UpdateTimestamp
@Column(name = "updated_at", nullable = false)
private OffsetDateTime updatedAt;
```

對於來自平台 API 的時間戳（例如訂單在 Shopee 上的建立時間），請使用 `channelCreatedAt` 欄位，並從 ISO-8601 解析：

```java
// 正確：從平台 API 解析 ISO-8601 字串
LocalDateTime channelCreatedAt = Instant.parse(apiResponse.get("created_time").asText())
    .atZone(ZoneId.of("UTC"))
    .toLocalDateTime();
order.setChannelCreatedAt(channelCreatedAt);

// 錯誤：當平台時間戳可用時，絕不使用 Instant.now()
order.setChannelCreatedAt(LocalDateTime.now()); // 禁止這樣做
```

---

## Kafka Consumer 模式

所有 Kafka consumer 遵循相同的結構。請完整複製此模式 — schema 驗證、MDC 設定、PII 上下文及錯誤路由的順序是刻意設計的。

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ExampleConsumer {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "order.process", groupId = "order-job-group", concurrency = "3")
    public void consume(@Payload String messageJson) {

        // 步驟 1：解析 JSON — 若無法解析，記錄日誌並丟棄（無法在解析前路由至 DLT）
        JsonNode json;
        try {
            json = objectMapper.readTree(messageJson);
        } catch (Exception e) {
            log.error("Failed to parse message as JSON: {}", e.getMessage());
            return;
        }

        // 步驟 2：驗證 schema 版本 — 不支援的版本直接送往 DLT
        try {
            SchemaVersionHandler.validate(json);
        } catch (UnsupportedSchemaVersionException e) {
            log.error("Unsupported schema version: {}", e.getMessage());
            kafkaTemplate.send(TopicConstants.TASK_DLT, "Consumer", json.toString());
            return;
        }

        // 步驟 3：設定 MDC 以支援結構化日誌與追蹤
        TaskMdcHelper.set(json);
        try {
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            // 步驟 4：守衛必要欄位 — 格式錯誤的訊息送往 DLT
            if (header == null || body == null) {
                log.error("Malformed message: missing header or body");
                kafkaTemplate.send(TopicConstants.TASK_DLT, "Consumer", json.toString());
                return;
            }

            String merchantId = header.path("merchantId").asText();
            String channelId = header.path("channelId").asText();
            boolean isRollback = header.path("isRollback").asBoolean(false);

            // 步驟 5：設定 PII 加密上下文（用於任何加密欄位的 DB 讀寫）
            EncryptionContext.setMerchantId(merchantId);
            try {
                // 步驟 6：業務邏輯
                doProcessing(merchantId, channelId, body, isRollback);
            } finally {
                EncryptionContext.clear();  // 必須清除 — 即使發生例外也不例外
            }

        } catch (Exception e) {
            log.error("Error processing message: {}", e.getMessage(), e);

            // 步驟 7：將可重試錯誤路由至 task.failed
            // retry-job 將在退避後重新發布訊息
            ObjectNode wrapped = buildFailedMessage(json, e);
            kafkaTemplate.send(TopicConstants.TASK_FAILED, "Consumer", wrapped.toString());

        } finally {
            // 步驟 8：必須清除 MDC — 即使發生例外也不例外
            TaskMdcHelper.clear();
        }
    }
}
```

### 並發數

- 所有 consumer 的預設並發數為 `"3"`
- 若 lag 持續累積，可暫時提高至 `"8"`，正常操作後再調回 `"3"`
- 並發數透過 `docker-compose.yml` 中各服務的 `JOB_CHANNEL_CONCURRENCY` 設定

---

## PII 加密上下文

以下四個欄位使用 AES-256-GCM 透過 `EncryptedFieldTypeHandler` 加密儲存：
- `buyer_name`
- `buyer_phone`
- `buyer_email`
- `shipping_address`

加密金鑰衍生自 `merchantId`。**任何讀寫這些欄位的程式碼都必須包在加密上下文中：**

```java
EncryptionContext.setMerchantId(merchantId);
try {
    // 在此可安全讀取加密欄位
    Order order = orderRepository.findById(orderId).orElseThrow();
    String buyerName = order.getBuyerName();  // 透明解密

    // 在此可安全寫入加密欄位
    order.setBuyerPhone("+886912345678");     // 透明加密
    orderRepository.save(order);
} finally {
    EncryptionContext.clear();  // 必須在 finally 區塊中清除
}
```

若未設定上下文，加密的密文會原樣回傳，在應用程式層將顯示為亂碼。

---

## OrderStatusEnum 與 ReturnStatusEnum 的使用

所有訂單狀態使用 `OrderStatusEnum` 列舉。`fromCode()` 方法不區分大小寫 — 平台 API 可用任意大小寫回傳狀態字串。

```java
// 正確：將平台特定狀態映射至 OMS 狀態
OrderStatusEnum status = OrderStatusMapper.mapCyberbizStatus(rawStatus);
order.setOrderStatus(status);

// 正確：從字串解析（不區分大小寫）
OrderStatusEnum status = OrderStatusEnum.fromCode("pending");   // → PENDING
OrderStatusEnum status = OrderStatusEnum.fromCode("CONFIRMED"); // → CONFIRMED

// OMS 統一狀態：
// PENDING, CONFIRMED, READY_TO_SHIP, SHIPPING, SHIPPED, COMPLETED, CANCELLED

// 退貨狀態（ReturnStatusEnum）：
// PENDING, APPROVED, REJECTED, COMPLETED, REFUNDED
```

本系統是**被動同步方** — 接受任何狀態轉換，不做驗證。請勿新增如「不能從 COMPLETED 跳回 PENDING」之類的守衛。平台訂單可能隨時以任何狀態出現。

---

## Repository 查詢慣例

簡單查詢使用 Spring Data JPA 方法命名：

```java
// 簡單查詢的優先選擇
List<Order> findByMerchantIdAndOrderStatus(String merchantId, OrderStatusEnum status);
Optional<Order> findByChannelIdAndChannelOrderId(String channelId, String channelOrderId);
Page<Order> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

// 含日期範圍的複雜查詢 — 務必使用範圍謂詞，不要使用 DATE() 函數
// 錯誤（會阻礙索引使用）：
@Query("SELECT o FROM Order o WHERE DATE(o.createdAt) = :date")

// 正確（索引友善的範圍查詢）：
@Query("SELECT o FROM Order o WHERE o.channelCreatedAt >= :startOfDay AND o.channelCreatedAt < :endOfDay")
List<Order> findByMerchantIdAndDateRange(
    @Param("merchantId") String merchantId,
    @Param("startOfDay") LocalDateTime startOfDay,
    @Param("endOfDay") LocalDateTime endOfDay
);
```

---

## 錯誤處理與路由

### 錯誤分類

| 錯誤類型 | 路由 | 是否重試 |
|-----------|---------|---------|
| 暫時性錯誤（DB 逾時、網路抖動） | `task.failed` | 是 — retry-job 在退避後重新發布 |
| 平台 API 5xx | `task.failed` | 是 |
| 平台 API 4xx（錯誤請求） | `task.dlt` | 否 — 需修正訊息，非暫時性錯誤 |
| Schema 版本不支援 | `task.dlt` | 否 — 版本不符需修改程式碼 |
| JSON 格式錯誤 | 丟棄（記錄錯誤） | 否 — 無法在解析前進行路由 |

### 建立失敗訊息封包

```java
// 將原始訊息包裝錯誤元資料，供 retry-job 使用
ObjectNode wrapped = originalJson.deepCopy();
ObjectNode errorInfo = objectMapper.createObjectNode();
errorInfo.put("errorType", "SERVER_ERROR_5XX");
errorInfo.put("errorMessage", exception.getMessage());
errorInfo.put("retryCount", 0);
((ObjectNode) wrapped.get("body")).set("errorInfo", errorInfo);
kafkaTemplate.send(TopicConstants.TASK_FAILED, "MyConsumer", wrapped.toString());
```

---

## 日誌慣例

所有 Java 服務使用 SLF4J 搭配 Lombok 的 `@Slf4j`。在 Docker 環境中以 JSON 格式輸出，本機開發則使用人類可讀的格式。

```java
@Slf4j  // Lombok — 自動生成 `private static final Logger log = ...`
public class MyConsumer {

    void process(String orderId, String merchantId) {
        // INFO：業務里程碑（每個有意義的步驟記錄一次）
        log.info("Processing ORDER_UPSERT: {} from merchant {}", orderId, merchantId);

        // DEBUG：詳細資料（只在 log level 為 DEBUG 時輸出）
        log.debug("Full order payload: {}", jsonPayload);

        // WARN：功能降級但仍可運作（Redis 無法連線、選填欄位缺失）
        log.warn("Redis dedup check failed for order {}, proceeding with DB check", orderId);

        // ERROR：最後一個參數務必傳入 exception 物件以輸出 stack trace
        try {
            riskyOperation();
        } catch (Exception e) {
            log.error("Failed to process order {}: {}", orderId, e.getMessage(), e);
        }
    }
}
```

**絕對不要記錄：** JWT token、明文密碼、完整 PII 欄位（buyer_phone、buyer_email）。請記錄 NanoID 或 channelOrderId 來識別訂單，同時避免暴露個人資料。

---

## isRollback 旗標

所有代表從過去時間窗口抓取的訂單（回補操作）的訊息，其 header 中必須設定 `isRollback=true`。Consumer 使用此旗標來：

1. 將訂單的統計資料歸屬至原始的 `channelCreatedAt` 日期，而非今日
2. 將原始日期標記至 Redis 髒集合，讓 `STATS_RECALC` 能夠取得

```java
// 在 Channel Job 中 — 建立 ORDER_UPSERT 訊息時
boolean isRollback = isBackfillWindow(fetchWindowStart);  // 你的判斷邏輯
header.put("isRollback", isRollback);

// 在 OrderUpsertConsumer 中 — stats 髒標記尊重 isRollback 旗標
LocalDate statDate = isRollback && order.getChannelCreatedAt() != null
    ? order.getChannelCreatedAt().toLocalDate()
    : LocalDate.now();
```

---

## DailyStatisticsService

統計資料從兩個角度計算，並儲存於 `daily_statistics` 資料表中：

| 角度 | 欄位 | 說明 |
|------------|--------|-------------|
| 業務視角 | `new_order_count`、`new_order_amount` | 當日建立的訂單 |
| 財務視角 | `received_count`、`received_amount`、`refund_count`、`refund_amount`、`net_amount` | 現金流：收款減去退款 |
| 物流視角 | `shipped_count`、`completed_count`、`cancelled_count` | 履約 KPI |
| 商品視角 | `item_sold_count` | SKU 銷售件數 |

此服務透過 `STATS_RECALC` 任務類型被呼叫，由 scheduler 每 5 分鐘派送一次。它從 Redis 髒集合讀取，以確認哪些（商家、平台、通路、日期）組合需要更新 — 只重新計算那些組合，而非整張表。

若某個組合找不到任何訂單，則刪除現有的該筆記錄（而非保留過時的計數）。

---

## 模組依賴規則

```
simpleec-common        ← 無內部依賴
simpleec-core          ← 依賴：simpleec-common
simpleec-channel       ← 依賴：simpleec-common、simpleec-core
simpleec-api           ← 依賴：simpleec-common、simpleec-core
simpleec-gateway       ← 依賴：simpleec-common
simpleec-*-job         ← 依賴：simpleec-common、simpleec-core
```

各服務不得引入循環依賴。`simpleec-common` 必須保持零依賴（不含 Spring、不含 DB）。

---

## TaskType 參考

所有業務操作以 `TaskTypeEnum` 值表示。完整列舉位於 `simpleec-common/.../enums/TaskTypeEnum.java`。

| 類別 | TaskType | 主題 | 由 Scheduler 驅動 |
|---------|---------|-------|-----------------|
| 通路抓取 | `FETCH_ORDERS` | `{platform}.slow` | 是（每 5 分鐘） |
| 通路抓取 | `FETCH_ORDER_DETAIL` | `{platform}.slow` | 否（由 channel job 觸發） |
| 通路抓取 | `FETCH_RETURNS` | `{platform}.slow` | 是（每 5 分鐘） |
| 通路抓取 | `FETCH_RETURN_DETAIL` | `{platform}.slow` | 否 |
| 通路動作 | `SHIP_ORDER` | `{platform}.fast` | 否 |
| 通路動作 | `UPDATE_INVENTORY` | `{platform}.fast` | 否 |
| 通路動作 | `UPDATE_PRICE` | `{platform}.fast` | 否 |
| 通路動作 | `APPROVE_RETURN` | `{platform}.fast` | 否 |
| 通路動作 | `CANCEL_ORDER` | `{platform}.fast` | 否 |
| 通路同步 | `SYNC_PACK` | `{platform}.slow` | 否 |
| 訂單處理 | `ORDER_UPSERT` | `order.process` | 否 |
| 退貨處理 | `RETURN_UPSERT` | `return.process` | 否 |
| 後台 | `STATS_RECALC` | `task.backend` | 是（每 5 分鐘） |
| 後台 | `SYNC_PRODUCT` | `task.backend` | 否 |
| 後台 | `ORDER_REPORT` | `task.backend` | 是 |
| 後台 | `SALES_REPORT` | `task.backend` | 是 |
| 後台 | `INVENTORY_REPORT` | `task.backend` | 是 |
| 後台 | `RETURN_REPORT` | `task.backend` | 是 |
| 後台 | `DAILY_REPORT` | `task.backend` | 是（每小時） |
| 系統 | `HEARTBEAT` | `scheduler.heartbeat` | 是（每秒） |
