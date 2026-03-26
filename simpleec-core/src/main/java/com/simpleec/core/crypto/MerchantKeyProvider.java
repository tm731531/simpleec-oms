package com.simpleec.core.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives per-merchant AES-256 keys from a master key using HKDF (RFC 5869).
 *
 * Key derivation: HKDF(masterKey, salt=merchantId, info="simpleec-pii-v1", length=32)
 * This ensures each merchant's data is encrypted with a unique key.
 *
 * Configure via: ENCRYPTION_MASTER_KEY env var (32 bytes, base64-encoded).
 * Generate a key: openssl rand -base64 32
 *
 * WARNING: If ENCRYPTION_MASTER_KEY changes, all existing encrypted data becomes unreadable.
 * A key rotation strategy must be implemented before changing the master key in production.
 */
@Component
public class MerchantKeyProvider {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final byte[] HKDF_INFO = "simpleec-pii-v1".getBytes(StandardCharsets.UTF_8);
    private static final int KEY_LENGTH = 32; // 256 bits for AES-256

    private final byte[] masterKey;
    // Cache derived keys to avoid repeated HKDF computation (bounded by merchant count)
    private final ConcurrentHashMap<String, byte[]> keyCache = new ConcurrentHashMap<>(64);

    public MerchantKeyProvider(@Value("${encryption.master-key:}") String masterKeyBase64) {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            throw new IllegalStateException(
                "ENCRYPTION_MASTER_KEY is not configured. " +
                "Set 'encryption.master-key' or the ENCRYPTION_MASTER_KEY environment variable. " +
                "Generate with: openssl rand -base64 32");
        }
        byte[] decoded = Base64.getDecoder().decode(masterKeyBase64.trim());
        if (decoded.length < 32) {
            throw new IllegalStateException(
                "ENCRYPTION_MASTER_KEY must be at least 32 bytes (256 bits). Got: " + decoded.length);
        }
        this.masterKey = Arrays.copyOf(decoded, KEY_LENGTH);
    }

    /**
     * Returns the AES-256 key for the given merchantId.
     * Result is cached. Cache is never evicted (merchant count is bounded).
     */
    public byte[] getKeyForMerchant(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("merchantId must not be blank");
        }
        return keyCache.computeIfAbsent(merchantId, this::deriveKey);
    }

    /** HKDF (RFC 5869) using HmacSHA256. Java 17 compatible (no BouncyCastle needed). */
    private byte[] deriveKey(String merchantId) {
        try {
            byte[] salt = merchantId.getBytes(StandardCharsets.UTF_8);
            // Step 1: HKDF-Extract — PRK = HMAC-SHA256(salt, IKM)
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(salt, HMAC_ALGO));
            byte[] prk = mac.doFinal(masterKey);

            // Step 2: HKDF-Expand — OKM = HMAC-SHA256(PRK, info || 0x01)
            mac.init(new SecretKeySpec(prk, HMAC_ALGO));
            mac.update(HKDF_INFO);
            mac.update((byte) 0x01); // counter = 1 (sufficient for 32 bytes)
            byte[] okm = mac.doFinal();

            return Arrays.copyOf(okm, KEY_LENGTH);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HKDF key derivation failed for merchant: " + merchantId, e);
        }
    }
}
