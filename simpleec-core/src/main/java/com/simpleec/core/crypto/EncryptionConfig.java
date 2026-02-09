package com.simpleec.core.crypto;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 將 Spring 管理的 AesGcmEncryptor 注入 MyBatis TypeHandler 的靜態 holder。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EncryptionConfig {

    private final AesGcmEncryptor encryptor;

    @PostConstruct
    public void init() {
        EncryptedFieldTypeHandler.setEncryptor(encryptor);
        log.info("EncryptedFieldTypeHandler wired with AesGcmEncryptor");
    }
}
