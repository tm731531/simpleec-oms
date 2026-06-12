package com.simpleec.core.crypto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Encrypts order PII fields at the SOURCE (channel-job order handlers, Excel
 * import) before the message is published to Kafka, so buyer PII is ciphertext
 * in transit on order.process / return.process and stored as-is at rest.
 *
 * Pairs with {@link PreEncryptedPassthroughConverter} on the Order entity:
 * source encrypts here, the DB column stores the ciphertext verbatim, reads
 * decrypt via the passthrough converter. Order processing never touches crypto.
 *
 * See docs/cycles/pii-encrypt-at-source-migration.md.
 */
@Component
public class OrderPiiEncryptor {

    /** Scalar PII keys carried in the normalized OMS orderData node. */
    private static final String[] SCALAR_PII_KEYS = {
            "buyerName", "buyerPhone", "buyerEmail", "shippingAddress"
    };

    private final PiiEncryptor encryptor;

    public OrderPiiEncryptor(PiiEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    /**
     * Encrypt the scalar PII fields in {@code orderData} in place. No-op for
     * keys that are absent / null / empty. The JSON structure is preserved —
     * only the field VALUES become ciphertext.
     *
     * @param orderData  normalized OMS order node (mutated in place)
     * @param merchantId merchant key scope for AES-256-GCM
     */
    public void encryptScalars(ObjectNode orderData, String merchantId) {
        if (orderData == null) return;
        for (String key : SCALAR_PII_KEYS) {
            if (orderData.hasNonNull(key)) {
                String plain = orderData.get(key).asText();
                if (plain != null && !plain.isEmpty()) {
                    orderData.put(key, encryptor.encrypt(plain, merchantId));
                }
            }
        }
    }
}
