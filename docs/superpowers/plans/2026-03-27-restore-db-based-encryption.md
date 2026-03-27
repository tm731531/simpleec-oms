# Restore DB-Based PII Encryption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore original encryption design — master key read from `global_config` DB table (triple Base64 decode) with PBKDF2WithHmacSHA256 key derivation per merchant, replacing the broken env-var + HKDF approach.

**Architecture:** `MerchantKeyProvider` reads `encryption_master_key` from `global_config` at startup via `GlobalConfigRepository`, decodes it 3 times, then derives per-merchant AES-256 keys using PBKDF2WithHmacSHA256(iterations=210,000, salt=merchantId). Keys are cached in ConcurrentHashMap.

**Tech Stack:** Java 17, Spring Boot 3.5, JPA (JpaRepository), AES-256-GCM, PBKDF2WithHmacSHA256

---

## Files

| File | Action | Responsibility |
|------|--------|----------------|
| `simpleec-core/src/main/java/com/simpleec/core/entity/GlobalConfig.java` | CREATE | JPA entity for `global_config` table |
| `simpleec-core/src/main/java/com/simpleec/core/repository/GlobalConfigRepository.java` | CREATE | JPA repository to read `global_config` |
| `simpleec-core/src/main/java/com/simpleec/core/crypto/MerchantKeyProvider.java` | MODIFY | Replace HKDF+env-var with PBKDF2+DB |
| `simpleec-core/src/main/resources/application.yml` | MODIFY | Remove `encryption.master-key` line |
| `docker-compose.yml` | MODIFY | Remove `ENCRYPTION_MASTER_KEY` from 2 services |

---

## Task 1: Create GlobalConfig Entity

**Files:**
- Create: `simpleec-core/src/main/java/com/simpleec/core/entity/GlobalConfig.java`

- [ ] **Step 1: Create the entity**

```java
package com.simpleec.core.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "global_config")
public class GlobalConfig {

    @Id
    @Column(name = "id", length = 128, nullable = false)
    private String id;

    @Column(name = "data", length = 2048, nullable = false)
    private String data;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public String getData() { return data; }
    public String getDescription() { return description; }
}
```

- [ ] **Step 2: Commit**

```bash
git add simpleec-core/src/main/java/com/simpleec/core/entity/GlobalConfig.java
git commit -m "feat: add GlobalConfig JPA entity for global_config table"
```

---

## Task 2: Create GlobalConfigRepository

**Files:**
- Create: `simpleec-core/src/main/java/com/simpleec/core/repository/GlobalConfigRepository.java`

- [ ] **Step 1: Create the repository**

```java
package com.simpleec.core.repository;

import com.simpleec.core.entity.GlobalConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GlobalConfigRepository extends JpaRepository<GlobalConfig, String> {
    // findById(id) is inherited — use findById("encryption_master_key")
}
```

- [ ] **Step 2: Commit**

```bash
git add simpleec-core/src/main/java/com/simpleec/core/repository/GlobalConfigRepository.java
git commit -m "feat: add GlobalConfigRepository for reading system settings"
```

---

## Task 3: Rewrite MerchantKeyProvider

**Files:**
- Modify: `simpleec-core/src/main/java/com/simpleec/core/crypto/MerchantKeyProvider.java`

**Logic:**
1. At construction, call `globalConfigRepository.findById("encryption_master_key")`
2. Get `data` field value — this is the triple-Base64 encoded master key
3. Decode Base64 three times → raw byte array
4. Store as `masterKey`
5. `getKeyForMerchant(merchantId)` → PBKDF2WithHmacSHA256(password=masterKey, salt=merchantId.getBytes(UTF-8), iterations=210_000, keyLength=256 bits)
6. Cache result in ConcurrentHashMap

- [ ] **Step 1: Rewrite MerchantKeyProvider**

```java
package com.simpleec.core.crypto;

import com.simpleec.core.repository.GlobalConfigRepository;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives per-merchant AES-256 keys from a master key stored in global_config.
 *
 * Key derivation:
 *   1. Read global_config.data where id='encryption_master_key'
 *   2. Triple Base64 decode → raw masterKey bytes
 *   3. PBKDF2WithHmacSHA256(password=masterKey, salt=merchantId, iterations=210_000, keyLen=256)
 *
 * The master key is stored triple-encoded for transport safety.
 * Per-merchant keys are cached after first derivation.
 *
 * WARNING: Changing the master key in global_config makes all existing encrypted data unreadable.
 */
@Component
public class MerchantKeyProvider {

    private static final String KDF_ALGO = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int AES_KEY_BITS = 256;
    private static final String GLOBAL_CONFIG_KEY_ID = "encryption_master_key";

    private final byte[] masterKey;
    private final ConcurrentHashMap<String, byte[]> keyCache = new ConcurrentHashMap<>(64);

    public MerchantKeyProvider(GlobalConfigRepository globalConfigRepository) {
        String tripleEncoded = globalConfigRepository
                .findById(GLOBAL_CONFIG_KEY_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "encryption_master_key not found in global_config table. " +
                        "Ensure the DB seed data (02-seed-data.sql) has been applied."))
                .getData();

        // Triple Base64 decode
        byte[] decoded = Base64.getDecoder().decode(tripleEncoded.trim());
        decoded = Base64.getDecoder().decode(decoded);
        decoded = Base64.getDecoder().decode(decoded);

        if (decoded.length < 32) {
            throw new IllegalStateException(
                "encryption_master_key decoded to " + decoded.length + " bytes; expected >= 32.");
        }
        this.masterKey = Arrays.copyOf(decoded, 32);
    }

    /**
     * Returns the AES-256 key for the given merchantId.
     * Result is cached. Safe for concurrent use.
     */
    public byte[] getKeyForMerchant(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("merchantId must not be blank");
        }
        return keyCache.computeIfAbsent(merchantId, this::deriveKey);
    }

    private byte[] deriveKey(String merchantId) {
        try {
            // Use masterKey as password (char[] form via Base64), merchantId as salt
            char[] password = Base64.getEncoder().encodeToString(masterKey).toCharArray();
            byte[] salt = merchantId.getBytes(StandardCharsets.UTF_8);
            PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(KDF_ALGO);
            byte[] derived = skf.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return derived;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 key derivation failed for merchant: " + merchantId, e);
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add simpleec-core/src/main/java/com/simpleec/core/crypto/MerchantKeyProvider.java
git commit -m "fix: restore DB-based master key loading with triple Base64 decode and PBKDF2 key derivation"
```

---

## Task 4: Clean Up application.yml

**Files:**
- Modify: `simpleec-core/src/main/resources/application.yml`

- [ ] **Step 1: Remove the encryption.master-key property**

The file currently contains:
```yaml
encryption:
  master-key: ${ENCRYPTION_MASTER_KEY:}
```

Replace the entire file with:
```yaml
```
(empty file — no env var config needed anymore)

- [ ] **Step 2: Commit**

```bash
git add simpleec-core/src/main/resources/application.yml
git commit -m "fix: remove ENCRYPTION_MASTER_KEY env var dependency from application.yml"
```

---

## Task 5: Clean Up docker-compose.yml

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Remove ENCRYPTION_MASTER_KEY from simpleec-order-job (line 533)**

Find and remove this line from the `simpleec-order-job` environment section:
```yaml
      ENCRYPTION_MASTER_KEY: ${ENCRYPTION_MASTER_KEY:}
```

- [ ] **Step 2: Remove ENCRYPTION_MASTER_KEY from simpleec-api (line 657)**

Find and remove this line from the `simpleec-api` environment section:
```yaml
      ENCRYPTION_MASTER_KEY: ${ENCRYPTION_MASTER_KEY:}
```

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "fix: remove ENCRYPTION_MASTER_KEY env var from docker-compose — key now loaded from DB"
```

---

## Task 6: Build and Verify

- [ ] **Step 1: Build**

```bash
cd /home/tom/ONEEC/simpleec-oms
./gradlew clean build -x test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Bring up the system**

```bash
docker compose up -d --build
```
Expected: All containers start without `invalid interpolation format` error.

- [ ] **Step 3: Verify encryption_master_key loads correctly**

```bash
docker logs simpleec-order-job 2>&1 | grep -i "encrypt\|master\|key\|error\|IllegalState" | head -20
docker logs simpleec-api 2>&1 | grep -i "encrypt\|master\|key\|error\|IllegalState" | head -20
```
Expected: No `IllegalStateException` about missing master key. Services start successfully.

- [ ] **Step 4: Verify DB key is readable**

```bash
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT id, left(data, 20) as data_preview, description FROM global_config WHERE id = 'encryption_master_key';"
```
Expected: Row returned with the triple-encoded key.
