package com.simpleec.core.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter for PII fields that are ALREADY encrypted upstream
 * (at the producer / source — e.g. channel-job order handlers, Excel import).
 *
 * Difference from {@link EncryptedAttributeConverter}:
 *   - WRITE (convertToDatabaseColumn): PASS-THROUGH. The value arrives already
 *     encrypted from the source, so we store it as-is. Encrypting again here
 *     would double-encrypt.
 *   - READ (convertToEntityAttribute): DECRYPT — identical to
 *     EncryptedAttributeConverter, so rows written by the old converter (same
 *     key) and rows written pre-encrypted from source both read correctly.
 *
 * Apply to entity fields whose PII is encrypted at source:
 *   @Convert(converter = PreEncryptedPassthroughConverter.class)
 *   @Column(name = "buyer_name")
 *   private String buyerName;
 *
 * Requires EncryptionContext.setMerchantId() to be set before any JPA READ
 * (write needs no context — it stores the ciphertext verbatim).
 *
 * Rationale: see docs/cycles/pii-encrypt-at-source-migration.md (decision "c" —
 * order processing must not touch crypto; the shared EncryptedAttributeConverter
 * stays on Channel tokens which are still entered as plaintext via the admin API).
 */
@Slf4j
@Converter
@Component
public class PreEncryptedPassthroughConverter
        implements AttributeConverter<String, String>, ApplicationContextAware {

    // Static reference allows use in JPA-managed converter instances (not Spring-managed)
    private static PiiEncryptor encryptor;

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        PreEncryptedPassthroughConverter.encryptor = ctx.getBean(PiiEncryptor.class);
    }

    /**
     * Pass-through: the value is already encrypted at the source. Store as-is.
     */
    @Override
    public String convertToDatabaseColumn(String alreadyEncrypted) {
        return alreadyEncrypted;
    }

    /**
     * Decrypt on read — identical semantics to EncryptedAttributeConverter so
     * both pre-encrypted (source) and legacy (old-converter) rows read correctly.
     */
    @Override
    public String convertToEntityAttribute(String ciphertext) {
        if (ciphertext == null) return null;
        String merchantId = EncryptionContext.getMerchantIdOrNull();
        if (merchantId == null) {
            log.warn("EncryptionContext not set during read — returning raw value. " +
                     "Wrap the query in EncryptionContext.setMerchantId() / clear().");
            return ciphertext;
        }
        return encryptor.decrypt(ciphertext, merchantId);
    }
}
