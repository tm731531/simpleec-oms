package com.simpleec.core.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * AES-256-GCM encryption/decryption for PII fields.
 *
 * Storage format: Base64(12-byte-IV || ciphertext || 16-byte-GCM-auth-tag)
 * The IV is randomly generated per encryption call (never reused).
 * The GCM auth tag is automatically appended by Java's Cipher implementation.
 */
@Component
public class PiiEncryptor {

    private static final Logger log = LoggerFactory.getLogger(PiiEncryptor.class);
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;   // 96-bit IV, recommended for GCM
    private static final int TAG_LENGTH = 128; // 128-bit GCM auth tag
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MerchantKeyProvider keyProvider;

    public PiiEncryptor(MerchantKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /**
     * Encrypts plaintext for the given merchant.
     * Returns Base64-encoded ciphertext (IV + encrypted bytes + auth tag).
     */
    public String encrypt(String plaintext, String merchantId) {
        if (plaintext == null) return null;
        try {
            byte[] key = keyProvider.getKeyForMerchant(merchantId);
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Concatenate IV + ciphertext (ciphertext already includes auth tag)
            byte[] combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed for merchant: " + merchantId, e);
        }
    }

    /**
     * Decrypts a Base64-encoded ciphertext for the given merchant.
     * Returns null if input is null.
     */
    public String decrypt(String ciphertext, String merchantId) {
        if (ciphertext == null) return null;
        try {
            byte[] key = keyProvider.getKeyForMerchant(merchantId);
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            if (combined.length < IV_LENGTH + 16) { // IV + minimum auth tag
                log.warn("Ciphertext too short for merchant {}, returning as-is (may be legacy plaintext)",
                         merchantId);
                return ciphertext; // graceful degradation for unencrypted legacy data
            }

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encryptedBytes = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encryptedBytes, 0, encryptedBytes.length);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed for merchant: " + merchantId, e);
        }
    }
}
