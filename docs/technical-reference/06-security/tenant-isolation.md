# 多租戶資料隔離

SimpleEC OMS 是多租戶 SaaS 系統，單一部署服務多個商家公司。本文件說明如何強制執行租戶邊界，確保一個商家永遠無法存取另一個商家的資料。

---

## 1. 租戶層級結構

```
Platform Admin（SimpleEC 操作員——管理整個平台）
    │
    └── Merchant（客戶公司，如「台灣好物有限公司」）
            │
            ├── Account（操作員登入，role = "main" 或 "sub"）
            │
            ├── Channel（平台整合實例，如 SHOPEE_001、MOMO_001）
            │
            └── Data：Orders、Returns、Products、DailyStatistics、ChannelSyncLogs
```

每筆資料記錄由且僅由一個商家擁有。帳號登入後在其商家範圍內操作。平台管理員使用獨立登入路徑和不同的 authority 類別——可存取 `/api/admin/**`，但無法存取個別商家的資料 API。

---

## 2. 隔離機制

### 2a. 資料庫——每張資料表的 `merchant_id` 欄位

所有資料表均包含 `merchant_id VARCHAR(20) NOT NULL` 欄位。沒有任何共用資料表缺少租戶範圍。各資料表及其租戶欄位：

| 資料表 | 租戶欄位 |
|-------|--------------|
| `orders` | `merchant_id` |
| `return_orders` | `merchant_id` |
| `channels` | `merchant_id` |
| `products` | `merchant_id` |
| `daily_statistics` | `merchant_id` |
| `channel_sync_logs` | `merchant_id` |
| `accounts` | `merchant_id` |

### 2b. JWT——每個 token 中的 `merchantId` claim

登入時，伺服器將 `merchantId` 嵌入 JWT payload：
```json
{ "sub": "acc_...", "merchantId": "mrc_...", "role": "main", ... }
```

客戶端無法修改 JWT claims——HMAC 簽章會失效。`JwtAuthFilter` 驗證簽章並將 `merchantId` 載入 `UserPrincipal`。API controllers 從中讀取。

### 2c. Repository 查詢——WHERE merchant_id = ?

每個列出或搜尋記錄的 repository 方法都在 WHERE 子句中包含 `merchant_id`。以 MyBatis-Plus 為例：
```java
orderMapper.selectList(
    new LambdaQueryWrapper<Order>()
        .eq(Order::getMerchantId, merchantId)
        .eq(Order::getStatus, status)
        .orderByDesc(Order::getCreatedAt)
);
```

---

## 3. Controller 模式——merchantId 始終來自 JWT

黃金法則：**絕不接受使用者提供的 `merchantId` 參數。** 始終從已驗證的 `UserPrincipal` 中讀取。

```java
// ❌ 錯誤——呼叫者可提供任意 merchantId，繞過租戶邊界
@GetMapping("/orders")
public Page<Order> getOrders(@RequestParam String merchantId,
                              @RequestParam int page) {
    return orderRepo.findByMerchantId(merchantId, page);
}

// ✅ 正確——merchantId 來自已驗證的 JWT
@GetMapping("/orders")
public Page<OrderVO> getOrders(@AuthenticationPrincipal UserPrincipal principal,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
    String merchantId = principal.getMerchantId();  // 來自 JWT，非 request
    return orderService.findByMerchantId(merchantId, page, size);
}
```

這適用於所有 HTTP 動詞（GET、POST、PUT、DELETE、PATCH）。

---

## 4. 跨資源擁有者驗證（單一資源端點）

對於接受特定資源 ID 的端點（如 `/orders/{orderId}`），在載入記錄後需要進行第二次擁有者驗證。DB 資料列中的 `merchant_id` 必須與 JWT 的 `merchantId` 相符。

```java
@GetMapping("/orders/{orderId}")
public ResponseEntity<OrderVO> getOrder(
        @PathVariable String orderId,
        @AuthenticationPrincipal UserPrincipal principal) {

    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    // 擁有者驗證——防止水平權限提升
    if (!principal.getMerchantId().equals(order.getMerchantId())) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    EncryptionContext.setMerchantId(principal.getMerchantId());
    try {
        return ResponseEntity.ok(OrderVO.from(order));
    } finally {
        EncryptionContext.clear();
    }
}
```

若缺少此驗證，已登入的商家可猜測另一商家的 NanoID 並取得其訂單。

---

## 5. Kafka 訊息的租戶範圍

Kafka 訊息在 header 中攜帶 `merchantId`。Channel Job 從 header 讀取 `channelId`，然後從 `channels` 資料表查詢 `merchantId`，而非直接信任 Kafka 訊息 header 中的值。這防止被入侵的 Scheduler 將訊息路由至錯誤的商家：

```java
// ChannelJobConsumer——merchantId 從 DB 驗證，不盲目信任訊息內容
String channelId = header.path("channelId").asText("");
Channel channel = channelService.getChannel(channelId);
String merchantId = channel.getMerchantId();  // 權威來源：DB
```

---

## 6. 平台管理員的分離

平台管理員使用完全獨立的登入路徑（`/api/admin/auth/login`），其 JWT 包含 `role: "platform_admin"`。Spring Security 強制執行：

```java
.requestMatchers("/api/admin/**").hasAuthority("ROLE_PLATFORM_ADMIN")
```

平台管理員可管理商家、帳號和全平台設定，但不應直接呼叫商家資料 API（訂單、退款、商品）。這些 API 由 JWT 中租戶範圍的 `merchantId` 守護，而平台管理員 token 的 `merchantId` 為空。

---

## 7. 各層驗證摘要

| 層級 | 驗證內容 |
|-------|-------|
| HTTP filter | JWT 簽章有效且未過期 |
| Spring Security | `/api/admin/**` 的角色 authority |
| Controller | `merchantId` 從 `UserPrincipal` 讀取（絕不從 request 讀取） |
| 單一資源端點 | DB 資料列 `merchant_id` == `principal.getMerchantId()` |
| Repository | 所有列表查詢包含 `WHERE merchant_id = ?` |
| Kafka consumer | `merchantId` 從 DB `channels` 資料表解析，非從訊息 header 取得 |
| PII 加密 | 解密前將 `EncryptionContext` 設為 `merchantId`——金鑰錯誤 = 亂碼資料 |
