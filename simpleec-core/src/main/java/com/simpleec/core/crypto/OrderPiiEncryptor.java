package com.simpleec.core.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Encrypts order PII at the SOURCE (channel-job order handlers, Excel import)
 * before the message is published to Kafka, so buyer PII is ciphertext in
 * transit on order.process / return.process and stored as-is at rest.
 *
 * Pairs with {@link PreEncryptedPassthroughConverter} on the Order entity:
 * source encrypts here, the DB column stores the ciphertext verbatim, reads
 * decrypt via the passthrough converter. Order processing never touches crypto.
 *
 * Two layers (see docs/cycles/pii-encrypt-at-source-migration.md):
 *  1. Scalar OMS fields: buyerName / buyerPhone / buyerEmail / shippingAddress.
 *  2. In-JSON values inside the buyerInfo / shippingInfo nested objects — only
 *     the VALUES of the known PII keys are encrypted; the JSON structure is
 *     preserved (decision "B"). The PII key set mirrors the keys the handlers
 *     read when extracting the scalar fields, so coverage stays consistent.
 */
@Component
public class OrderPiiEncryptor {

    /** Scalar PII keys carried in the normalized OMS orderData node. */
    private static final String[] SCALAR_PII_KEYS = {
            "buyerName", "buyerPhone", "buyerEmail", "shippingAddress"
    };

    /** PII value keys inside the nested buyerInfo object. */
    private static final String[] BUYER_INFO_PII_KEYS = {"name", "phone", "mobile", "email"};

    /** PII value keys inside the nested shippingInfo object. */
    private static final String[] SHIPPING_INFO_PII_KEYS = {"address"};

    private final PiiEncryptor encryptor;

    public OrderPiiEncryptor(PiiEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    /**
     * Encrypt all order PII in {@code orderData} in place — scalar fields plus
     * the PII values inside the buyerInfo / shippingInfo nested objects. JSON
     * structure is preserved; only field VALUES become ciphertext. No-op for
     * keys that are absent / null / empty.
     *
     * @param orderData  normalized OMS order node (mutated in place)
     * @param merchantId merchant key scope for AES-256-GCM
     */
    public void encryptInPlace(ObjectNode orderData, String merchantId) {
        if (orderData == null) return;
        encryptKeys(orderData, SCALAR_PII_KEYS, merchantId);
        encryptNested(orderData, "buyerInfo", BUYER_INFO_PII_KEYS, merchantId);
        encryptNested(orderData, "shippingInfo", SHIPPING_INFO_PII_KEYS, merchantId);
    }

    /** Encrypt the values of {@code keys} directly on {@code node}. */
    private void encryptKeys(ObjectNode node, String[] keys, String merchantId) {
        for (String key : keys) {
            if (node.hasNonNull(key)) {
                String plain = node.get(key).asText();
                if (plain != null && !plain.isEmpty()) {
                    node.put(key, encryptor.encrypt(plain, merchantId));
                }
            }
        }
    }

    /** Encrypt PII keys inside the {@code childKey} object, if present. */
    private void encryptNested(ObjectNode parent, String childKey, String[] piiKeys, String merchantId) {
        JsonNode child = parent.get(childKey);
        if (child != null && child.isObject()) {
            encryptKeys((ObjectNode) child, piiKeys, merchantId);
        }
    }
}
