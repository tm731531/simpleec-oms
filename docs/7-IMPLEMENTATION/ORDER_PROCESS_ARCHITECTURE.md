# 訂單處理完整架構指南

**最後更新**: 2026-02-25
**基於**: Cyberbiz 訂單流整合經驗
**適用**: 所有電商平台的訂單處理

---

## 📋 目錄

1. [核心架構](#核心架構)
2. [數據流](#數據流)
3. [時間戳傳遞機制](#時間戳傳遞機制)
4. [狀態轉換](#狀態轉換)
5. [去重機制](#去重機制)
6. [錯誤處理](#錯誤處理)
7. [最佳實踐](#最佳實踐)

---

## 核心架構

### 三層職責分離

```
┌─────────────────────────────────────────────────────────────┐
│ 層級 1: Scheduler (時間決策層)                              │
│ ├─ 決策: 何時拉取訂單                                       │
│ └─ 輸出: 時間戳 (ISO-8601 格式)                             │
│         例: 2026-02-25T10:00:00Z                           │
└──────────────────────┬──────────────────────────────────────┘
                       │ Kafka scheduler topic
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ 層級 2: Channel Job (適配層)                               │
│ ├─ 決策: 怎麼拉取                                           │
│ ├─ 行為:                                                    │
│ │  • 計算時間窗口 (每個平台不同邏輯)                       │
│ │  • 呼叫平台 API (帶重試邏輯)                              │
│ │  • 格式轉換成 OMS 統一結構                               │
│ │  • 去重 (Redis + Hash)                                    │
│ └─ 輸出: ORDER_UPSERT 訊息到 order.process topic           │
└──────────────────────┬──────────────────────────────────────┘
                       │ Kafka order.process topic
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ 層級 3: Order Job (儲存層)                                 │
│ ├─ 決策: 怎麼存資料庫                                       │
│ ├─ 行為:                                                    │
│ │  • 驗證資料完整性                                        │
│ │  • 業務邏輯檢查 (庫存、積分等)                           │
│ │  • INSERT/UPDATE 資料庫                                   │
│ │  • 發送後續事件 (出貨通知等)                             │
│ └─ 輸出: 訂單進入 OMS 系統                                  │
└─────────────────────────────────────────────────────────────┘
```

### 責任邊界

| 層級 | 決策 | 執行 | 限制 |
|------|------|------|------|
| **Scheduler** | 時間點 | 發送消息 | ❌ 不知道平台細節 |
| **Channel Job** | 時間窗口、API 策略 | 呼叫 API、轉換 | ❌ 不操作數據庫 |
| **Order Job** | 儲存策略、業務規則 | 存取 DB | ❌ 不呼叫外部 API |

---

## 數據流

### 完整的訊息流

```json
// 1️⃣ Scheduler 發送
{
  "header": {
    "taskType": "FETCH_ORDERS",
    "timestamp": "2026-02-25T10:00:00Z",
    "source": "scheduler",
    "merchantId": "m_123",
    "platformId": "cyberbiz"
  },
  "body": {
    "channelId": "ch_456"
  }
}
    ↓ (Kafka scheduler topic)

// 2️⃣ Channel Job 處理並發送 ORDER_UPSERT
{
  "header": {
    "taskType": "ORDER_UPSERT",
    "timestamp": "2026-02-25T10:05:00Z",
    "source": "channel_job",
    "merchantId": "m_123",
    "platformId": "cyberbiz",
    "channelId": "ch_456",
    "messageId": "msg_xyz",
    "requestId": "req_abc"
  },
  "body": {
    "channelOrderId": "ord_cyberbiz_123",
    "orderHash": "sha256_hash_of_order",
    "orderData": {
      "orderId": "ord_oms_789",
      "orderStatus": "PENDING",
      "totalAmount": 1000,
      "items": [
        {
          "channelItemId": "item_123",
          "sku": "SKU-001",
          "quantity": 2,
          "price": 500
        }
      ],
      "buyerInfo": {
        "name": "張三",
        "phone": "+886912345678",
        "email": "user@example.com"
      },
      "shippingInfo": {
        "address": "台北市中山區...",
        "method": "宅配"
      },
      "paidAt": "2026-02-25T09:30:00Z",
      "channelCreatedAt": "2026-02-25T09:00:00Z"
    }
  }
}
    ↓ (Kafka order.process topic)

// 3️⃣ Order Job 儲存
INSERT INTO orders (
  id, merchant_id, channel_id, channel_order_id,
  order_status, total_amount, buyer_info, shipping_info,
  paid_at, created_at, updated_at
) VALUES (...)
```

---

## 時間戳傳遞機制

### ⚠️ 最常見的錯誤：使用當前時間而不是消息時間戳

❌ **錯誤方式**:
```java
// Channel Job 中
long currentTime = Instant.now().getEpochSecond();
List<Order> orders = adapter.fetchOrdersByTimestamp(
    channelId,
    currentTime  // ❌ 使用當前時間（錯誤！）
);
```

**問題**:
- 訂單發送時間和實際抓取時間不一致
- 時間窗口計算錯誤，可能遺漏舊訂單

✅ **正確方式**:
```java
// Kafka 消費者中
KafkaMessage message = receive();
long baseTimestamp = parseTimestamp(message.header.timestamp);
// baseTimestamp = 2026-02-25T10:00:00Z 轉成秒

// 傳給 Channel Job
adapter.fetchOrdersByTimestamp(channelId, baseTimestamp);

// Channel Job 內部計算時間窗口
// 例如 Cyberbiz:
long createdFrom = baseTimestamp - (7 * 86400);   // 7 天前
long createdTo = baseTimestamp;
cyberbizApi.getOrders(createdFrom, createdTo);
```

### 時間戳轉換參考

```java
// ISO-8601 → Unix 秒
String isoTime = "2026-02-25T10:00:00Z";
Instant instant = Instant.parse(isoTime);
long unixSeconds = instant.getEpochSecond();  // 1771997600

// Unix 秒 → ISO-8601
long unixSeconds = 1771997600;
Instant instant = Instant.ofEpochSecond(unixSeconds);
String iso = instant.toString();  // "2026-02-25T10:00:00Z"
```

---

## 狀態轉換

### 訂單狀態枚舉 (OrderStatusEnum)

**7 個統一狀態** (所有平台都映射到這些狀態):

```java
enum OrderStatusEnum {
    PENDING("PENDING", "待支付", "訂單已創建，等待支付"),
    CONFIRMED("CONFIRMED", "已確認", "支付確認，準備出貨"),
    READY_TO_SHIP("READY_TO_SHIP", "待出貨", "訂單準備出貨"),
    SHIPPING("SHIPPING", "出貨中", "訂單已出貨，運輸中"),
    SHIPPED("SHIPPED", "已出貨", "訂單已送達買家"),
    COMPLETED("COMPLETED", "已完成", "訂單完成"),
    CANCELLED("CANCELLED", "已取消", "訂單已取消");

    private final String code;
    private final String label;  // 中文標籤
    private final String description;
}
```

### 平台狀態映射 (OrderStatusMapper)

**職責**: 將平台特定的狀態轉換為統一的 OMS 狀態

```java
public class OrderStatusMapper {
    // 15+ 平台的映射方法

    public static String mapToOmsStatus(String platform, String platformStatus) {
        return switch (platform.toLowerCase()) {
            case "cyberbiz" -> mapCyberbizStatus(platformStatus);
            case "shopee" -> mapShopeeStatus(platformStatus);
            case "momo" -> mapMomoStatus(platformStatus);
            // ... 其他平台
            default -> "PENDING";
        };
    }

    // 每個平台的詳細映射邏輯
    private static String mapCyberbizStatus(String status) {
        return switch (status.toLowerCase()) {
            case "pending_payment" -> "PENDING";
            case "confirmed" -> "CONFIRMED";
            case "ready_to_ship" -> "READY_TO_SHIP";
            case "shipped" -> "SHIPPED";
            case "completed" -> "COMPLETED";
            case "cancelled" -> "CANCELLED";
            default -> "PENDING";
        };
    }
}
```

### 狀態轉移規則

✅ **允許的轉移**:
```
PENDING → CONFIRMED → READY_TO_SHIP → SHIPPING → SHIPPED → COMPLETED
              ↓
            CANCELLED (隨時可取消)
```

❌ **不允許的轉移** (但系統要容錯):
```
COMPLETED → PENDING     (應該忽略)
CANCELLED → SHIPPED     (應該忽略)
PENDING → COMPLETED     (應該允許，可能平台狀態跳躍)
```

**設計原則**: 我們是被動同步方
- 接受任何狀態跳轉（平台可能有奇怪的狀態流）
- 記錄不尋常的轉移，但不拒絕

---

## 去重機制

### Redis 去重 (第一層)

```java
// 在 Channel Job 中計算 Order Hash
TreeMap<String, Object> sortedData = new TreeMap<>();
sortedData.put("status", orderData.getStatus());
sortedData.put("totalAmount", orderData.getTotalAmount());
sortedData.put("items", serializeItems(orderData.getItems()));
sortedData.put("buyerInfo", serializeByuerInfo(orderData.getBuyerInfo()));

String json = objectMapper.writeValueAsString(sortedData);
String orderHash = DigestUtils.sha256Hex(json);

// 檢查 Redis
String redisKey = RedisKeyUtil.orderHashKey(merchantId, channelId, channelOrderId);
String existingHash = redisTemplate.opsForValue().get(redisKey);

if (orderHash.equals(existingHash)) {
    // 訂單無變化，跳過
    return;
}

// 否則發送 ORDER_UPSERT，並更新 Redis
kafkaTemplate.send(ORDER_PROCESS_TOPIC, message);
redisTemplate.opsForValue().set(redisKey, orderHash);
```

### 容錯模式

Redis 連接失敗時，不應該阻止訂單流：

```java
try {
    String existingHash = redisTemplate.opsForValue().get(redisKey);
    if (orderHash.equals(existingHash)) {
        return;  // 去重成功
    }
} catch (Exception e) {
    // ⚠️ Redis 失敗，繼續發送（可能有重複，但確保訂單不遺漏）
    log.warn("Redis dedup failed, proceeding with ORDER_UPSERT", e);
}

// 繼續發送
kafkaTemplate.send(ORDER_PROCESS_TOPIC, message);
```

---

## 錯誤處理

### Kafka 發送回調

```java
kafkaTemplate.send(topic, key, message)
    .whenComplete((result, ex) -> {
        if (ex != null) {
            // ❌ 發送失敗
            log.error("Failed to send ORDER_UPSERT for {}", orderId, ex);
            // 可以轉送到 task.failed topic 讓 retry-job 處理
        } else {
            // ✅ 發送成功
            log.info("Successfully sent ORDER_UPSERT for {}: partition={}, offset={}",
                orderId,
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
        }
    });
```

### 失敗重試機制

```
Order Fetch 失敗
    ↓
Retry 邏輯 (指數退避: 1s, 2s, 4s, 8s, 16s, ...)
    ↓
如果仍失敗 → 記錄到 task.failed topic
    ↓
Retry Job 每分鐘掃描 task.failed，重新發送
    ↓
如果超過 max retries → 移到 task.dlt (Dead Letter Topic) 供人工檢查
```

---

## 最佳實踐

### 1. 時間窗口設計

**原則**: 每個平台有不同的 API 特性，時間窗口由 Channel Job 自主決策

| 平台 | API 能力 | 推薦窗口 | 備註 |
|------|---------|---------|------|
| **Cyberbiz** | 支持時間範圍查詢 | 7 天 | 可靠 API，支持多狀態 |
| **Shopee** | 狀態分類明確 | 1h(新) + 3d(待出) + 5d(出貨) + 7d(完成) | 需要按狀態分批 |
| **Momo** | item-level 記錄 | 1h + 3d | 需自己聚合訂單 |
| **Yahoo** | 只有更新時間 | 1 天 | 無狀態分類，評估訂單狀態 |
| **Easystore** | 完整訂單，嚴格 rate limit | 7 天 | 可一次取 50 筆 |

### 2. 訂單資料結構

**必須字段** (所有平台都要提供):
```json
{
  "orderId": "OMS 內部 ID (NanoID)",
  "channelOrderId": "通路訂單號",
  "orderStatus": "PENDING (大寫)",
  "totalAmount": 1000,
  "items": [
    {
      "channelItemId": "通路商品 ID",
      "sku": "商品 SKU",
      "quantity": 2,
      "price": 500
    }
  ],
  "buyerInfo": {
    "name": "買家名稱",
    "phone": "聯絡電話",
    "email": "電子郵件"
  },
  "shippingInfo": {
    "address": "配送地址"
  }
}
```

**選填字段** (根據平台提供):
```json
{
  "paymentMethod": "信用卡",
  "shippingMethod": "宅配",
  "discountAmount": 100,
  "shippingFee": 50,
  "paidAt": "2026-02-25T09:30:00Z",
  "shippedAt": "2026-02-25T14:00:00Z"
}
```

### 3. 日誌和監控

```java
// Channel Job 中的日誌
log.info("Processing Mode A orders for {} ({}) using baseTimestamp",
    adapter.getPlatformCode(), channelId);
log.info("Fetched {} orders from {} API", orders.size(), adapter.getPlatformCode());
log.debug("Order unchanged (hash match): {}", channelOrderId);
log.info("Sent ORDER_UPSERT for {} from {}", channelOrderId, adapter.getPlatformCode());

// 監控指標
metrics.increment("orders.fetched", tags("platform", "cyberbiz"));
metrics.increment("orders.deduplicated", tags("platform", "cyberbiz"));
metrics.increment("orders.sent_to_kafka", tags("platform", "cyberbiz"));
```

### 4. 版本控制

訊息結構變更時，使用版本號：

```java
{
  "header": {
    "version": 1,  // 當前版本
    "isRollback": false  // 回補訂單標籤
  },
  "body": { ... }
}

// 消費者端
if (message.header.version > SUPPORTED_VERSION) {
    // 不支援的版本，轉送到 task.dlt
    sendToDLT(message);
}
```

---

## 常見問題 (FAQ)

**Q: 為什麼要用 Hash 去重而不是直接檢查資料庫？**
A: 性能。Redis 查詢 O(1)，而數據庫查詢 O(log n)。避免不必要的 DB 操作。

**Q: 如果 Hash 相同但實際資料不同怎麼辦？**
A: 不會發生。Hash 計算的字段都是會變動的業務字段（狀態、金額、items、買家等），不包括時間戳。

**Q: 支持批量 upsert 嗎？**
A: 目前是單條發送（一條訂單一條 Kafka 消息）。可以優化為批量，但要確保去重邏輯仍然有效。

**Q: 訂單來自多個通路怎麼去重？**
A: Redis Key 已經包含 `merchantId` + `channelId` + `channelOrderId`，所以不同通路的訂單自動隔離。

---

**下一篇**: [新平台集成指南](./NEW_PLATFORM_INTEGRATION_GUIDE.md)
**相關**: [退貨流程設計](./RETURN_FLOW_DESIGN.md)
