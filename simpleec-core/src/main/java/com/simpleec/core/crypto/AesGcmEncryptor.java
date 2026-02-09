package com.simpleec.core.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AES-256-GCM 加解密器。
 * 每個 merchant 透過 PBKDF2(masterKey, merchantId) 衍生獨立的 AES key。
 * 加密格式：Base64(nonce[12] || ciphertext || authTag[16])
 */
@Slf4j
@Component
public class AesGcmEncryptor {

    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final String KDF_ALGO = "PBKDF2WithHmacSHA256";
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 210_000;

    private final MasterKeyProvider masterKeyProvider;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, SecretKey> keyCache = new ConcurrentHashMap<>();

    public AesGcmEncryptor(MasterKeyProvider masterKeyProvider) {
        this.masterKeyProvider = masterKeyProvider;
    }

    /**
     * 加密明文，回傳 Base64 字串。
     */
    public String encrypt(String plaintext, String merchantId) {
        try {
            SecretKey key = deriveKey(merchantId);
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));

            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            // nonce || ciphertext (includes auth tag appended by GCM)
            ByteBuffer buffer = ByteBuffer.allocate(nonce.length + ciphertext.length);
            buffer.put(nonce);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * 解密 Base64 字串，回傳明文。
     */
    public String decrypt(String base64Ciphertext, String merchantId) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64Ciphertext);

            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            SecretKey key = deriveKey(merchantId);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /**
     * PBKDF2 衍生 per-merchant AES key，結果快取。
     */
    private SecretKey deriveKey(String merchantId) {
        return keyCache.computeIfAbsent(merchantId, mid -> {
            try {
                byte[] masterKey = masterKeyProvider.getMasterKey();
                // 用 master key 作為密碼，merchantId 作為鹽
                char[] password = Base64.getEncoder().encodeToString(masterKey).toCharArray();
                byte[] salt = mid.getBytes(StandardCharsets.UTF_8);

                KeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
                SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGO);
                byte[] keyBytes = factory.generateSecret(spec).getEncoded();

                return new SecretKeySpec(keyBytes, "AES");
            } catch (Exception e) {
                throw new RuntimeException("Key derivation failed for merchant: " + merchantId, e);
            }
        });
    }

    /**
     * 清除 per-merchant key 快取（master key 更換時使用）。
     */
    public void clearKeyCache() {
        keyCache.clear();
    }
}
