# PII Field Encryption (AES-256-GCM)

Customer personal data stored in the `orders` table is encrypted at rest using AES-256-GCM. Encryption and decryption happen transparently at the JPA persistence layer — application code reads and writes plaintext strings normally.

---

## 1. Encrypted Fields

| Column | Entity Field | Table | Description |
|--------|-------------|-------|-------------|
| `buyer_name` | `buyerName` | `orders` | Customer full name |
| `buyer_phone` | `buyerPhone` | `orders` | Phone number |
| `buyer_email` | `buyerEmail` | `orders` | Email address |
| `shipping_address` | `shippingAddress` | `orders` | Full delivery address (plain string) |

These four fields are annotated with `@Convert(converter = EncryptedAttributeConverter.class)` on the entity. No other fields are currently encrypted.

---

## 2. Encryption Algorithm

**AES-256-GCM (Galois/Counter Mode)**

| Property | Value |
|----------|-------|
| Key length | 256 bits |
| IV length | 96 bits (12 bytes) — randomly generated per encryption call |
| Auth tag | 128 bits (16 bytes) — appended by Java Cipher |
| Storage format | `Base64( IV[12] ‖ ciphertext ‖ auth_tag[16] )` |

GCM provides both confidentiality and integrity — the auth tag detects any tampering with the ciphertext before decryption succeeds. The IV is never reused because it is generated fresh by `SecureRandom` for every encryption call.

---

## 3. Key Derivation (Per-Merchant Keys)

Each merchant has a unique AES-256 key derived from a shared master key stored in the database:

```
global_config table
  id = 'encryption_master_key'
  data = Triple-Base64-encoded master key bytes (32 bytes raw)
         (triple encoding for safe transport through YAML/env vars)
```

Derivation process (`MerchantKeyProvider`):
1. Read `encryption_master_key` from `global_config` at application startup
2. Triple Base64-decode to get 32 raw bytes
3. For each merchant: `PBKDF2WithHmacSHA256(password=Base64(masterKey), salt=merchantId, iterations=210_000, keyLength=256 bits)`
4. Cache derived key in a `ConcurrentHashMap` (key derivation is expensive — 210k iterations)

**Critical warning:** Changing `encryption_master_key` in `global_config` makes all existing encrypted data permanently unreadable. There is no automatic re-encryption. Treat the master key as immutable once any data has been encrypted.

---

## 4. How It Works in Code

The JPA `AttributeConverter` (`EncryptedAttributeConverter`) intercepts every read and write for annotated fields. Application code never calls `PiiEncryptor` directly.

### Entity annotation
```java
// simpleec-core/.../entity/Order.java
@Convert(converter = EncryptedAttributeConverter.class)
@Column(name = "buyer_name")
private String buyerName;

@Convert(converter = EncryptedAttributeConverter.class)
@Column(name = "buyer_phone")
private String buyerPhone;

@Convert(converter = EncryptedAttributeConverter.class)
@Column(name = "buyer_email")
private String buyerEmail;

@Convert(converter = EncryptedAttributeConverter.class)
@Column(name = "shipping_address")
private String shippingAddress;
```

### What happens on write
```
orderRepo.save(order)
  → EncryptedAttributeConverter.convertToDatabaseColumn(plaintext)
    → EncryptionContext.getMerchantId()          // reads ThreadLocal
    → PiiEncryptor.encrypt(plaintext, merchantId)
    → stores Base64(IV + ciphertext + tag) in DB
```

### What happens on read
```
orderRepo.findById(id)
  → EncryptedAttributeConverter.convertToEntityAttribute(ciphertext)
    → EncryptionContext.getMerchantIdOrNull()    // reads ThreadLocal
    → if null: logs warning, returns raw value (graceful degradation)
    → PiiEncryptor.decrypt(ciphertext, merchantId)
    → returns plaintext to caller
```

---

## 5. EncryptionContext — Thread-Local Pattern

`EncryptionContext` stores the current merchant ID in a `ThreadLocal<String>`. The converter reads from it at the moment JPA executes the SQL.

**You must always use try/finally:**

```java
// ✅ Correct — clear() always runs, even on exception
EncryptionContext.setMerchantId(merchantId);
try {
    Order order = orderRepository.findById(orderId).orElseThrow();
    // order.getBuyerName() returns plaintext here
    return OrderVO.from(order);
} finally {
    EncryptionContext.clear();  // CRITICAL: prevents context leak to next request
}
```

```java
// ❌ WRONG — if the DB call throws, clear() never runs
// The next request reusing this thread will decrypt with the wrong merchantId
EncryptionContext.setMerchantId(merchantId);
Order order = orderRepository.findById(orderId).orElseThrow();
EncryptionContext.clear();  // too late if exception thrown above
```

**Why this matters on a thread pool:** Spring web servers reuse threads. If `clear()` is skipped, the merchant ID stays in the ThreadLocal and the next unrelated request on the same thread will decrypt data using the wrong merchant's key — causing garbled plaintext or silent cross-tenant data exposure.

---

## 6. Where setMerchantId Must Be Called

### Kafka consumers (OrderUpsertConsumer, ReturnUpsertConsumer)
```java
// Before calling any handler that writes/reads encrypted fields:
EncryptionContext.setMerchantId(merchantId);
try {
    orderUpsertHandler.handle(message);
} finally {
    EncryptionContext.clear();
}
```

### API controllers
```java
@GetMapping("/orders/{orderId}")
public ResponseEntity<OrderVO> getOrder(
        @PathVariable String orderId,
        @AuthenticationPrincipal UserPrincipal principal) {

    EncryptionContext.setMerchantId(principal.getMerchantId());
    try {
        Order order = orderRepository.findById(orderId).orElseThrow();
        return ResponseEntity.ok(OrderVO.from(order));
    } finally {
        EncryptionContext.clear();
    }
}
```

### Batch jobs / scheduled tasks
Any code that reads encrypted Order fields must set the context before the first JPA call, even in background threads. Background threads do NOT inherit the ThreadLocal from the parent thread.

---

## 7. Legacy Plaintext Handling

`PiiEncryptor.decrypt()` includes graceful degradation for pre-encryption data:

- If the stored value is not valid Base64 → returns the value as-is (legacy plaintext), logs a warning
- If the decoded bytes are shorter than `IV_LENGTH + 16` → returns as-is (may be legacy plaintext), logs a warning

This means a migration from plaintext to encrypted storage can be done gradually (row by row) without a hard cutover. However, rows that are returned as-is will still show plaintext to the caller — a separate migration script must re-save those rows through the `EncryptedAttributeConverter` to fully encrypt them.

---

## 8. Encryption Internals Reference

| Class | Location | Responsibility |
|-------|----------|---------------|
| `PiiEncryptor` | `simpleec-core/.../crypto/` | Low-level AES-GCM encrypt/decrypt |
| `MerchantKeyProvider` | `simpleec-core/.../crypto/` | PBKDF2 key derivation; per-merchant key cache |
| `EncryptionContext` | `simpleec-core/.../crypto/` | ThreadLocal merchantId holder |
| `EncryptedAttributeConverter` | `simpleec-core/.../crypto/` | JPA `@Convert` integration |
