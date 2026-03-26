package com.simpleec.core.crypto;

/**
 * Thread-local holder for the current merchantId during a DB operation.
 *
 * Usage pattern (ALWAYS use try/finally):
 *   EncryptionContext.setMerchantId(merchantId);
 *   try {
 *       // JPA read/write operations involving encrypted fields
 *   } finally {
 *       EncryptionContext.clear();
 *   }
 *
 * The EncryptedAttributeConverter reads from this context to determine which key to use.
 * If no merchantId is set when encryption/decryption is needed, an exception is thrown.
 */
public final class EncryptionContext {

    private static final ThreadLocal<String> MERCHANT_ID = new ThreadLocal<>();

    private EncryptionContext() {}

    public static void setMerchantId(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("merchantId must not be blank");
        }
        MERCHANT_ID.set(merchantId);
    }

    public static String getMerchantId() {
        String id = MERCHANT_ID.get();
        if (id == null) {
            throw new IllegalStateException(
                "EncryptionContext has no merchantId set. " +
                "Call EncryptionContext.setMerchantId() before accessing encrypted fields.");
        }
        return id;
    }

    /** Returns null if no merchantId is set (use for optional encryption scenarios). */
    public static String getMerchantIdOrNull() {
        return MERCHANT_ID.get();
    }

    public static void clear() {
        MERCHANT_ID.remove();
    }
}
