package com.simpleec.core.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter that encrypts/decrypts String fields using AES-256-GCM.
 *
 * Apply to entity fields:
 *   @Convert(converter = EncryptedAttributeConverter.class)
 *   @Column(name = "buyer_name")
 *   private String buyerName;
 *
 * Requires EncryptionContext.setMerchantId() to be called before any JPA read/write.
 *
 * NOTE: Existing plaintext data in the DB will NOT be automatically encrypted.
 * The decrypt method gracefully returns input as-is if it looks like plaintext.
 * A separate data migration must be performed to encrypt existing rows.
 */
@Slf4j
@Converter
@Component
public class EncryptedAttributeConverter
        implements AttributeConverter<String, String>, ApplicationContextAware {

    // Static reference allows use in JPA-managed converter instances (not Spring-managed)
    private static PiiEncryptor encryptor;

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        EncryptedAttributeConverter.encryptor = ctx.getBean(PiiEncryptor.class);
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        String merchantId = EncryptionContext.getMerchantId();
        return encryptor.encrypt(plaintext, merchantId);
    }

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
