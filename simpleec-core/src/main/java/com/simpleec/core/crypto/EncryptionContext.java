package com.simpleec.core.crypto;

/**
 * ThreadLocal 持有目前操作的 merchantId，供 EncryptedFieldTypeHandler 取用。
 * 使用方式：在 Service 層呼叫 mapper 前 setMerchantId，finally 裡 clear。
 */
public final class EncryptionContext {

    private static final ThreadLocal<String> MERCHANT_ID = new ThreadLocal<>();

    private EncryptionContext() {}

    public static void setMerchantId(String merchantId) {
        MERCHANT_ID.set(merchantId);
    }

    public static String getMerchantId() {
        return MERCHANT_ID.get();
    }

    public static void clear() {
        MERCHANT_ID.remove();
    }
}
