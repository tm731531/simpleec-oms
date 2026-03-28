# PII 欄位加密（AES-256-GCM）

`orders` 資料表中的客戶個人資料以 AES-256-GCM 加密後靜態儲存。加解密在 JPA 持久化層透明處理——應用程式程式碼以一般字串讀寫，無需感知加密細節。

---

## 1. 加密欄位

| 欄位 | Entity 屬性 | 資料表 | 說明 |
|--------|-------------|-------|-------------|
| `buyer_name` | `buyerName` | `orders` | 客戶全名 |
| `buyer_phone` | `buyerPhone` | `orders` | 電話號碼 |
| `buyer_email` | `buyerEmail` | `orders` | Email 地址 |
| `shipping_address` | `shippingAddress` | `orders` | 完整送貨地址（純字串） |

這四個欄位在 entity 上標註 `@Convert(converter = EncryptedAttributeConverter.class)`。目前沒有其他欄位被加密。

---

## 2. 加密演算法

**AES-256-GCM（Galois/Counter Mode）**

| 屬性 | 值 |
|----------|-------|
| 金鑰長度 | 256 bits |
| IV 長度 | 96 bits（12 bytes）——每次加密呼叫隨機產生 |
| 認證標籤 | 128 bits（16 bytes）——由 Java Cipher 附加 |
| 儲存格式 | `Base64( IV[12] ‖ ciphertext ‖ auth_tag[16] )` |

GCM 同時提供機密性和完整性——認證標籤在解密前偵測對密文的任何篡改。IV 從不重用，因為每次加密呼叫都由 `SecureRandom` 產生全新的 IV。

---

## 3. 金鑰衍生（每商家獨立金鑰）

每個商家有一個從資料庫中儲存的共用主金鑰衍生的唯一 AES-256 金鑰：

```
global_config 資料表
  id = 'encryption_master_key'
  data = Triple-Base64 編碼的主金鑰 bytes（原始 32 bytes）
         （三重編碼以安全傳遞至 YAML/env vars）
```

衍生流程（`MerchantKeyProvider`）：
1. 應用程式啟動時從 `global_config` 讀取 `encryption_master_key`
2. 三重 Base64 解碼取得原始 32 bytes
3. 對每個商家：`PBKDF2WithHmacSHA256(password=Base64(masterKey), salt=merchantId, iterations=210_000, keyLength=256 bits)`
4. 將衍生的金鑰快取至 `ConcurrentHashMap`（金鑰衍生代價高昂——21 萬次迭代）

**重要警告：** 修改 `global_config` 中的 `encryption_master_key` 會使所有既有加密資料永久無法讀取。系統不提供自動重新加密功能。一旦有資料被加密，主金鑰應視為不可變動。

---

## 4. 程式碼運作方式

JPA `AttributeConverter`（`EncryptedAttributeConverter`）攔截所有標註欄位的每次讀寫。應用程式程式碼從不直接呼叫 `PiiEncryptor`。

### Entity 標註
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

### 寫入時的流程
```
orderRepo.save(order)
  → EncryptedAttributeConverter.convertToDatabaseColumn(plaintext)
    → EncryptionContext.getMerchantId()          // 讀取 ThreadLocal
    → PiiEncryptor.encrypt(plaintext, merchantId)
    → 將 Base64(IV + ciphertext + tag) 儲存至 DB
```

### 讀取時的流程
```
orderRepo.findById(id)
  → EncryptedAttributeConverter.convertToEntityAttribute(ciphertext)
    → EncryptionContext.getMerchantIdOrNull()    // 讀取 ThreadLocal
    → 若為 null：記錄警告，回傳原始值（優雅降級）
    → PiiEncryptor.decrypt(ciphertext, merchantId)
    → 回傳明文給呼叫者
```

---

## 5. EncryptionContext — ThreadLocal 模式

`EncryptionContext` 將目前的商家 ID 儲存在 `ThreadLocal<String>` 中。Converter 在 JPA 執行 SQL 時讀取其值。

**必須始終使用 try/finally：**

```java
// ✅ 正確——即使發生例外，clear() 也一定執行
EncryptionContext.setMerchantId(merchantId);
try {
    Order order = orderRepository.findById(orderId).orElseThrow();
    // order.getBuyerName() 在此回傳明文
    return OrderVO.from(order);
} finally {
    EncryptionContext.clear();  // 重要：防止 context 洩漏至下一個請求
}
```

```java
// ❌ 錯誤——若 DB 呼叫拋出例外，clear() 永遠不會執行
// 重用此執行緒的下一個請求將使用錯誤的 merchantId 解密
EncryptionContext.setMerchantId(merchantId);
Order order = orderRepository.findById(orderId).orElseThrow();
EncryptionContext.clear();  // 若上方拋出例外則太遲
```

**為何在執行緒池中至關重要：** Spring Web 伺服器重用執行緒。若跳過 `clear()`，merchant ID 會留在 ThreadLocal 中，同一執行緒上的下一個不相關請求將使用錯誤商家的金鑰解密資料——導致亂碼明文或無聲的跨租戶資料外洩。

---

## 6. 需要呼叫 setMerchantId 的位置

### Kafka consumers（OrderUpsertConsumer、ReturnUpsertConsumer）
```java
// 在呼叫任何讀寫加密欄位的 handler 之前：
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

### 批次作業／排程任務
任何讀取加密 Order 欄位的程式碼，必須在第一次 JPA 呼叫之前設定 context，即使在背景執行緒中也是如此。背景執行緒**不**繼承父執行緒的 ThreadLocal。

---

## 7. 舊版明文處理

`PiiEncryptor.decrypt()` 包含對加密前資料的優雅降級處理：

- 若儲存的值不是有效的 Base64 → 原樣回傳（舊版明文），並記錄警告
- 若解碼後的 bytes 短於 `IV_LENGTH + 16` → 原樣回傳（可能是舊版明文），並記錄警告

這意味著從明文遷移至加密儲存可以逐筆進行，無需硬性切換。但以原樣回傳的資料仍會將明文暴露給呼叫者——需要另外撰寫 migration 腳本，透過 `EncryptedAttributeConverter` 重新儲存這些資料列以完全加密。

---

## 8. 加密內部參考

| 類別 | 位置 | 職責 |
|-------|----------|---------------|
| `PiiEncryptor` | `simpleec-core/.../crypto/` | 低階 AES-GCM 加解密 |
| `MerchantKeyProvider` | `simpleec-core/.../crypto/` | PBKDF2 金鑰衍生；每商家金鑰快取 |
| `EncryptionContext` | `simpleec-core/.../crypto/` | ThreadLocal merchantId 持有者 |
| `EncryptedAttributeConverter` | `simpleec-core/.../crypto/` | JPA `@Convert` 整合 |
