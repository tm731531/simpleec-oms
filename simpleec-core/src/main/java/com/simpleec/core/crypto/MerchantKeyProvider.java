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
