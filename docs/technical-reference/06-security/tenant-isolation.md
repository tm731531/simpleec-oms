# Multi-Tenant Data Isolation

SimpleEC OMS is a multi-tenant SaaS system. A single deployment serves multiple merchant companies. This document explains how tenant boundaries are enforced so that one merchant can never access another merchant's data.

---

## 1. Tenant Hierarchy

```
Platform Admin  (SimpleEC operator — manages the entire platform)
    │
    └── Merchant  (client company, e.g. "台灣好物有限公司")
            │
            ├── Account  (operator login, role = "main" or "sub")
            │
            ├── Channel  (platform integration instance, e.g. SHOPEE_001, MOMO_001)
            │
            └── Data: Orders, Returns, Products, DailyStatistics, ChannelSyncLogs
```

Every data record is owned by exactly one merchant. Accounts log in and operate within the scope of their merchant. Platform admins have a separate login and a different authority class — they can access `/api/admin/**` but not individual merchant data APIs.

---

## 2. Isolation Mechanisms

### 2a. Database — `merchant_id` column on every table

All data tables include a `merchant_id VARCHAR(20) NOT NULL` column. There are no shared tables without tenant scoping. Sample tables and their tenant column:

| Table | Tenant column |
|-------|--------------|
| `orders` | `merchant_id` |
| `return_orders` | `merchant_id` |
| `channels` | `merchant_id` |
| `products` | `merchant_id` |
| `daily_statistics` | `merchant_id` |
| `channel_sync_logs` | `merchant_id` |
| `accounts` | `merchant_id` |

### 2b. JWT — `merchantId` claim in every token

At login, the server embeds `merchantId` inside the JWT payload:
```json
{ "sub": "acc_...", "merchantId": "mrc_...", "role": "main", ... }
```

The client cannot modify JWT claims — the HMAC signature would break. `JwtAuthFilter` validates the signature and loads `merchantId` into `UserPrincipal`. API controllers read it from there.

### 2c. Repository queries — WHERE merchant_id = ?

Every repository method that lists or searches records includes `merchant_id` in the WHERE clause. Example with MyBatis-Plus:
```java
orderMapper.selectList(
    new LambdaQueryWrapper<Order>()
        .eq(Order::getMerchantId, merchantId)
        .eq(Order::getStatus, status)
        .orderByDesc(Order::getCreatedAt)
);
```

---

## 3. Controller Pattern — merchantId Always from JWT

The golden rule: **never accept `merchantId` as a user-supplied parameter.** Always read it from the authenticated `UserPrincipal`.

```java
// ❌ WRONG — caller can supply any merchantId, bypassing tenant boundary
@GetMapping("/orders")
public Page<Order> getOrders(@RequestParam String merchantId,
                              @RequestParam int page) {
    return orderRepo.findByMerchantId(merchantId, page);
}

// ✅ CORRECT — merchantId comes from the verified JWT
@GetMapping("/orders")
public Page<OrderVO> getOrders(@AuthenticationPrincipal UserPrincipal principal,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
    String merchantId = principal.getMerchantId();  // from JWT, not request
    return orderService.findByMerchantId(merchantId, page, size);
}
```

This applies to all HTTP verbs (GET, POST, PUT, DELETE, PATCH).

---

## 4. Cross-Resource Ownership Check (Single-Resource Endpoints)

For endpoints that accept a specific resource ID (e.g., `/orders/{orderId}`), a second ownership check is required after loading the record. The `merchant_id` in the DB row must match the JWT's `merchantId`.

```java
@GetMapping("/orders/{orderId}")
public ResponseEntity<OrderVO> getOrder(
        @PathVariable String orderId,
        @AuthenticationPrincipal UserPrincipal principal) {

    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    // Ownership check — prevents horizontal privilege escalation
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

Without this check, a logged-in merchant could guess another merchant's NanoID and retrieve their order.

---

## 5. Kafka Message Tenant Scoping

Kafka messages carry `merchantId` in the header. Channel Jobs read `channelId` from the header, then look up `merchantId` from the `channels` table rather than trusting the value in the Kafka message header. This prevents a compromised Scheduler from routing messages to the wrong merchant:

```java
// ChannelJobConsumer — merchantId is verified from DB, not blindly trusted from message
String channelId = header.path("channelId").asText("");
Channel channel = channelService.getChannel(channelId);
String merchantId = channel.getMerchantId();  // authoritative source: DB
```

---

## 6. Platform Admin Separation

Platform admins use a completely separate login path (`/api/admin/auth/login`) and their JWT contains `role: "platform_admin"`. Spring Security enforces:

```java
.requestMatchers("/api/admin/**").hasAuthority("ROLE_PLATFORM_ADMIN")
```

Platform admins can manage merchants, accounts, and platform-wide configuration, but they should not call merchant data APIs (orders, returns, products) directly. Those APIs are gated by tenant-scoped `merchantId` from the JWT, which for a platform admin token would be empty.

---

## 7. Summary of Checks at Each Layer

| Layer | Check |
|-------|-------|
| HTTP filter | JWT signature valid + not expired |
| Spring Security | Role authority for `/api/admin/**` |
| Controller | `merchantId` read from `UserPrincipal` (never from request) |
| Single-resource endpoints | DB row `merchant_id` == `principal.getMerchantId()` |
| Repository | All list queries include `WHERE merchant_id = ?` |
| Kafka consumer | `merchantId` resolved from DB `channels` table, not from message header |
| PII encryption | `EncryptionContext` set to `merchantId` before decrypting — wrong key = garbled data |
